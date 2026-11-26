package org.huangry.colorful.geo.domain.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.fastjson.serializer.SimplePropertyPreFilter;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisPlanService;
import org.huangry.colorful.geo.domain.service.prompt.GeoPromptTemplateService;
import org.huangry.colorful.geo.domain.service.prompt.PromptTemplateRenderer;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.common.enums.PromptTemplateKey;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * <pre>
 * 诊断方案的制题服务：调用模型按项目资料生成一套母题，再把母题按地域改写成当地用户会问的候选问题
 *
 * 负责：
 * - 生成母题：把本次地域写进提示词，要求母题在这些地域都成立
 * - 按地域改写：意图、品牌范围逐题沿用母题，模型只改写正文并给出语言
 * - 严格解析模型输出，并用方案的问题校验规则把关
 *
 * 核心入口：
 * - generate(...)：DiagnosisPlanBusiness 在方案页点「生成候选」时调用，每次一套母题
 * - localizeQuestions(...)：DiagnosisPlanBusiness 逐个地域调用，把母题改写成该地域的候选
 *
 * 约束：
 * - 只返回经方案规则校验的结果，不保存、不执行诊断；任何一条不合规则整批失败，不返回部分结果
 * - 生成与改写的结果都要通过 GeoDiagnosisPlanService.validateQuestions：非品牌题不能出现项目名称或别名，
 *   品牌认知题与竞品比较题必须出现
 * - 制题渠道与方案的被测渠道相互独立，调用不联网搜索
 * - 问题面向哪些地域、用什么语言只看本次输入，项目资料里的目标市场、目标语言只在页面展示，不发给模型
 * - 两段提示词在「提示词管理」维护（诊断方案-生成母题提示词、诊断方案-按地域改写问题提示词），每次调用取当前版本，
 *   本类按变量名填入数据；未配置时明确报错，不调用模型
 *
 * 不负责：
 * - 问题校验规则本身（GeoDiagnosisPlanService.validateQuestions）
 * - 保存方案与问题（DiagnosisPlanBusiness、GeoDiagnosisPlanService.savePlan）
 * </pre>
 */
@Service
public class QuestionGenerateService {
	/** 每次生成或改写的问题数量上限，限制单次模型输出规模。 */
	private static final int MAX_QUESTIONS_PER_CALL = 100;
	/** 发给制题模型前从项目资料中去掉的字段：目标市场、目标语言只供页面参考，地域和语言以本次输入为准。 */
	private static final Set<String> PROMPT_EXCLUDED_PROJECT_FIELDS = Set.of("targetMarket", "targetLanguage");

	@Resource
	private LLMClient llmClient;
	@Resource
	private GeoDiagnosisPlanService planService;
	@Resource
	private GeoPromptTemplateService geoPromptTemplateService;

	/**
	 * 按项目资料生成一套母题，之后由页面逐个地域改写成候选；默认渠道按数据库优先级选择，与被测渠道独立。
	 * <p>本次地域写进提示词，要求母题在这些地域都成立、不写死某一个国家，地域说法留到改写时加入。</p>
	 * @param project 提供制题背景的项目资料
	 * @param channelCode 可选制题渠道编码
	 * @param count 母题数量，范围为 1 到 100
	 * @param language 母题语言
	 * @param regions 本次要改写到的地域，至少一个，每个最多 50 字符；重复的地域只算一次
	 * @return 经方案规则校验的母题，不保存，地域为空
	 * @throws IllegalArgumentException 数量、语言、渠道编码或地域不合法，此时不调用模型
	 * @throws NoSuchElementException 生成母题提示词未配置，此时不调用模型
	 * @throws LLMClientException 模型调用失败，或返回的数量、字段、品牌标记不符合要求
	 */
	public List<GeoDiagnosisPlanQuestionEntity> generate(GeoProjectInfoEntity project, String channelCode,
	                                                     Integer count, String language, List<String> regions) {
		// 1. 限制生成规模和输入长度，地域至少一个，避免一次请求失控。
		if (count == null || count < 1 || count > MAX_QUESTIONS_PER_CALL) {
			throw new IllegalArgumentException("每次生成 1 到 100 个问题");
		}
		if (language == null || language.isBlank() || language.length() > 100) {
			throw new IllegalArgumentException("母题语言必填，最多 100 字符");
		}
		if (channelCode != null && channelCode.length() > 100) {
			throw new IllegalArgumentException("生成渠道编码过长");
		}
		List<String> targetRegions = confirmTargetRegions(regions);
		// 2. 取生成母题提示词的当前版本，填入项目资料后调用制题模型；项目资料只是制题数据，不能成为后续诊断回答的隐藏上下文。
		String template = geoPromptTemplateService.getCurrentTemplate(PromptTemplateKey.DIAGNOSIS_PLAN_QUESTION_GENERATE).getContent();
		String prompt = buildPrompt(template, project, count, language.strip(), targetRegions);
		String content = llmClient.query(new LLMQueryRequest(prompt, channelCode, null)).getContent();
		// 3. 严格解析并复用保存校验；失败不降级为空问题集合。
		try {
			List<GeoDiagnosisPlanQuestionEntity> candidates = parseCandidates(content, language.strip(), count);
			planService.validateQuestions(project, candidates);
			return candidates;
		} catch (Exception exception) {
			throw new LLMClientException("生成候选格式或内容不符合要求", exception);
		}
	}

	/**
	 * 把一批母题按一个地域改写成当地用户会问的问题，只返回候选，不保存。
	 * <p>意图、品牌范围逐题取自母题，模型只改写正文并给出语言；同一地域可以分别用不同语言各改写一批。
	 * 地域和语言只看本次输入，项目资料里的目标市场、目标语言不发给模型。</p>
	 * @param project 项目资料，作为改写背景
	 * @param channelCode 可选制题渠道编码，空值按数据库优先级选择
	 * @param region 目标地域，如美国、东南亚，1 到 50 个字符
	 * @param language 问题语言，可为空；为空时用该地域的常用语言，由模型给出
	 * @param questions 母题，1 到 100 道，按方案保存规则校验
	 * @return 与母题一一对应、顺序一致的候选，地域为本次地域，来源为模型生成
	 * @throws IllegalArgumentException 地域、语言、渠道编码或母题不合法，此时不调用模型
	 * @throws NoSuchElementException 按地域改写问题提示词未配置，此时不调用模型
	 * @throws LLMClientException 模型调用失败，或返回的数量、字段、品牌标记不符合要求（整批失败）
	 */
	public List<GeoDiagnosisPlanQuestionEntity> localizeQuestions(GeoProjectInfoEntity project, String channelCode, String region,
	                                                              String language, List<GeoDiagnosisPlanQuestionEntity> questions) {
		// 1. 地域必填、语言可空，母题按保存规则校验；不合法时不调用模型
		String targetRegion = planService.confirmQuestionRegion(region);
		if (targetRegion == null) {
			throw new IllegalArgumentException("请填写改写的目标地域");
		}
		String targetLanguage = language == null || language.isBlank() ? null : language.strip();
		validateLocalizeInput(project, channelCode, targetLanguage, questions);
		// 2. 取按地域改写问题提示词的当前版本，填入母题与项目资料后调用制题模型，按地域和语言改写
		String template = geoPromptTemplateService.getCurrentTemplate(PromptTemplateKey.DIAGNOSIS_PLAN_QUESTION_LOCALIZE).getContent();
		String prompt = buildLocalizePrompt(template, project, targetRegion, targetLanguage, questions);
		String content = llmClient.query(new LLMQueryRequest(prompt, channelCode, null)).getContent();
		// 3. 严格解析并复用保存校验；任何一条不合规则则整批失败，不降级为部分结果
		try {
			List<GeoDiagnosisPlanQuestionEntity> localized = parseLocalizedQuestions(content, questions, targetRegion, targetLanguage);
			planService.validateQuestions(project, localized);
			return localized;
		} catch (IllegalArgumentException | JSONException exception) {
			throw new LLMClientException("改写结果格式或内容不符合要求", exception);
		}
	}

	/**
	 * 校验按地域改写的其余输入，全部在调用模型前完成，避免为无效请求产生费用。
	 * @param project 项目资料，提供项目名称与别名，用于核对母题的品牌范围与原文是否相符
	 * @param channelCode 可选制题渠道编码
	 * @param language 已去除首尾空白的指定语言，可为空
	 * @param questions 母题
	 * @throws IllegalArgumentException 语言或渠道编码超长、母题数量越界或母题不符合保存规则
	 */
	private void validateLocalizeInput(GeoProjectInfoEntity project, String channelCode, String language,
	                                   List<GeoDiagnosisPlanQuestionEntity> questions) {
		if (language != null && language.length() > 100) {
			throw new IllegalArgumentException("改写语言最多 100 字符");
		}
		if (channelCode != null && channelCode.length() > 100) {
			throw new IllegalArgumentException("生成渠道编码过长");
		}
		if (questions == null || questions.isEmpty() || questions.size() > MAX_QUESTIONS_PER_CALL) {
			throw new IllegalArgumentException("每次改写 1 到 100 个问题");
		}
		// 母题与保存方案共用同一套规则，例如非品牌题不能含项目名称或别名，品牌认知题与竞品比较题必须含
		planService.validateQuestions(project, questions);
	}

	/**
	 * 确认本次要改写到的地域：逐个去首尾空白并校验长度，去掉空白项和重复项，保持页面顺序。
	 * @param regions 页面各地域行的地域；同一地域测多种语言时会重复出现
	 * @return 去重后的地域，至少一个
	 * @throws IllegalArgumentException 没有地域，或某个地域超过 50 字符
	 */
	private List<String> confirmTargetRegions(List<String> regions) {
		// 母题只需知道要覆盖哪些地域，同一地域的多种语言在改写时分别处理
		Set<String> targetRegions = new LinkedHashSet<>();
		if (regions != null) {
			for (String region : regions) {
				String confirmed = planService.confirmQuestionRegion(region);
				if (confirmed != null) {
					targetRegions.add(confirmed);
				}
			}
		}
		if (targetRegions.isEmpty()) {
			throw new IllegalArgumentException("请至少添加一个地域");
		}
		return List.copyOf(targetRegions);
	}

	/**
	 * 组装母题提示词：把数量、母题语言、本次地域和项目资料填进模板；项目资料以 JSON 数据块提供，减少意图和侧面问题脱离产品定位，
	 * 本次地域只用来要求母题在这些地域都成立。
	 * @param template 生成母题提示词模板，取自提示词管理的当前版本
	 * @param project 项目资料
	 * @param count 期望问题数量
	 * @param language 母题语言
	 * @param regions 本次要改写到的地域
	 * @return 含 JSON 数据块的制题提示词
	 */
	private String buildPrompt(String template, GeoProjectInfoEntity project, int count, String language, List<String> regions) {
		// 取值与模板变量一一对应（见 PromptTemplateKey.DIAGNOSIS_PLAN_QUESTION_GENERATE），语言与地域以 JSON 写出，与项目资料同为数据
		return PromptTemplateRenderer.render(template, Map.of(
				"count", String.valueOf(count),
				"language", JSON.toJSONString(language),
				"regions", JSON.toJSONString(regions),
				"project", buildPromptProjectJson(project)));
	}

	/**
	 * 组装按地域改写的提示词：把语言要求、目标地域、项目资料和母题填进模板，母题与项目资料都作为数据，地域和语言只看本次输入。
	 * @param template 按地域改写问题提示词模板，取自提示词管理的当前版本
	 * @param project 项目资料
	 * @param region 目标地域
	 * @param language 指定语言，为空时让模型用该地域的常用语言
	 * @param questions 母题
	 * @return 含母题 JSON 数组的改写提示词
	 */
	private String buildLocalizePrompt(String template, GeoProjectInfoEntity project, String region, String language,
	                                   List<GeoDiagnosisPlanQuestionEntity> questions) {
		// 1. 母题只交出序号、正文、意图和品牌范围，序号让模型按原顺序一一对应
		JSONArray baseQuestions = new JSONArray();
		for (int index = 0; index < questions.size(); index++) {
			GeoDiagnosisPlanQuestionEntity question = questions.get(index);
			baseQuestions.add(new JSONObject(true)
					.fluentPut("index", index + 1)
					.fluentPut("text", question.getQuestionText())
					.fluentPut("intent", question.getIntent())
					.fluentPut("brandScope", question.getBrandScope()));
		}
		// 2. 语言未指定时由模型按地域选择常用语言；项目资料不含目标市场、目标语言，地域和语言只看本次输入
		String languageRule = language == null ? "使用该地域用户向 AI 提问时最常用的语言" : "全部使用指定语言 " + JSON.toJSONString(language);
		// 3. 取值与模板变量一一对应（见 PromptTemplateKey.DIAGNOSIS_PLAN_QUESTION_LOCALIZE）
		return PromptTemplateRenderer.render(template, Map.of(
				"languageRule", languageRule,
				"region", JSON.toJSONString(region),
				"project", buildPromptProjectJson(project),
				"questions", baseQuestions.toJSONString()));
	}

	/**
	 * 把项目资料转成提示词里的 JSON 数据块，去掉目标市场和目标语言：问题面向哪些地域、用什么语言以本次输入为准，这两项只在页面上展示。
	 * @param project 项目资料
	 * @return 项目资料 JSON，空字段保留为 null
	 * @throws IllegalStateException 项目资料无法序列化
	 */
	private String buildPromptProjectJson(GeoProjectInfoEntity project) {
		SimplePropertyPreFilter filter = new SimplePropertyPreFilter();
		filter.getExcludes().addAll(PROMPT_EXCLUDED_PROJECT_FIELDS);
		try {
			return JSON.toJSONString(project, filter, SerializerFeature.WriteMapNullValue);
		} catch (JSONException exception) {
			throw new IllegalStateException("项目资料序列化失败", exception);
		}
	}

	/**
	 * 解析生成结果，逐项提取正文、意图与品牌范围；来源和语言由系统固定。
	 * @param content 模型返回的完整正文
	 * @param language 候选问题语言
	 * @param count 期望数量
	 * @return 已提取字段的候选，业务有效性由方案服务校验
	 */
	private List<GeoDiagnosisPlanQuestionEntity> parseCandidates(String content, String language, int count) {
		// 1. 只接受完整 JSON 数组，数量必须与请求一致
		JSONArray array = parseJsonArray(content, count, "生成问题数量与请求不一致");
		// 2. 每个值必须是对象，禁止将空值或其他类型强制转成问题
		List<GeoDiagnosisPlanQuestionEntity> questions = new ArrayList<>();
		for (Object value : array) {
			if (!(value instanceof JSONObject item)) {
				throw new IllegalArgumentException("候选问题字段无效");
			}
			var question = new GeoDiagnosisPlanQuestionEntity();
			question.setQuestionText(requiredText(item, "text"));
			question.setIntent(requiredText(item, "intent"));
			question.setBrandScope(requiredText(item, "brandScope"));
			question.setSource("GENERATED");
			question.setLanguage(language);
			questions.add(question);
		}
		return questions;
	}

	/**
	 * 解析改写结果：数量必须与母题一致，逐题只取模型改写的正文和语言，意图与品牌范围沿用母题。
	 * @param content 模型返回的完整正文
	 * @param questions 母题，顺序与改写结果一一对应
	 * @param region 本次地域
	 * @param language 本次指定语言；为空时每题必须带模型给出的语言
	 * @return 改写后的候选，业务有效性由方案服务校验
	 * @throws IllegalArgumentException 数量不一致或字段无效
	 */
	private List<GeoDiagnosisPlanQuestionEntity> parseLocalizedQuestions(String content, List<GeoDiagnosisPlanQuestionEntity> questions,
	                                                                     String region, String language) {
		// 1. 只接受完整 JSON 数组，数量必须与母题一致
		JSONArray array = parseJsonArray(content, questions.size(), "改写问题数量与母题不一致");
		// 2. 逐题对应母题：正文与语言取改写结果，意图和品牌范围决定报告的统计口径，只取母题，不采用模型可能做的改动
		List<GeoDiagnosisPlanQuestionEntity> localized = new ArrayList<>();
		for (int index = 0; index < array.size(); index++) {
			if (!(array.get(index) instanceof JSONObject item)) {
				throw new IllegalArgumentException("改写问题字段无效");
			}
			GeoDiagnosisPlanQuestionEntity base = questions.get(index);
			var question = new GeoDiagnosisPlanQuestionEntity();
			question.setQuestionText(requiredText(item, "text"));
			question.setIntent(base.getIntent());
			question.setBrandScope(base.getBrandScope());
			question.setSource("GENERATED");
			question.setLanguage(language != null ? language : requiredText(item, "language"));
			question.setRegion(region);
			localized.add(question);
		}
		return localized;
	}

	/**
	 * 只接受完整 JSON 数组，允许包住整个响应的代码围栏，但不从任意正文猜测或截取。
	 * @param content 模型返回的完整正文
	 * @param count 期望的数组长度
	 * @param countMessage 不是数组或数量不一致时的失败原因
	 * @return 解析后的数组
	 * @throws IllegalArgumentException 不是数组或数量不一致；尾随内容等语法错误由 Fastjson 以 JSONException 拒绝
	 */
	private JSONArray parseJsonArray(String content, int count, String countMessage) {
		// 1. 围栏只在包住整个响应时去除，避免掩盖格式错误
		String json = content.strip();
		if (json.startsWith("```json") && json.endsWith("```")) {
			json = json.substring(7, json.length() - 3).strip();
		} else if (json.startsWith("```") && json.endsWith("```")) {
			json = json.substring(3, json.length() - 3).strip();
		}
		// 2. 解析完整 JSON 并核对数组数量，尾随内容由 Fastjson 解析器拒绝
		Object parsed = JSON.parse(json);
		if (!(parsed instanceof JSONArray array) || array.size() != count) {
			throw new IllegalArgumentException(countMessage);
		}
		return array;
	}

	/**
	 * 拒绝缺字段与非文本值，避免模型输出被错误地当成已确认问题。
	 * @param item 单个候选问题对象
	 * @param key 必需的文本字段
	 * @return 原始文本，不进行类型强制转换
	 */
	private String requiredText(JSONObject item, String key) {
		// 非文本值不可作为有效问题字段，即使 Fastjson 可以将其转成字符串
		if (!(item.get(key) instanceof String value)) {
			throw new IllegalArgumentException("候选问题字段无效");
		}
		return value;
	}
}
