package org.huangry.colorful.geo.domain.service.diagnosis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.repository.*;
import org.huangry.colorful.geo.infrastructure.repository.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <pre>
 * 诊断方案的领域服务：整体保存方案及其问题、渠道，并规定一组问题怎样才算合格
 *
 * 负责：
 * - 保存方案：方案、问题、渠道三张表在同一事务内更新，避免半个方案
 * - 问题校验：数量、重复、来源、意图、品牌范围、语言、地域，以及品牌范围与问题原文是否相符
 * - 按方案顺序读取问题与渠道
 *
 * 核心入口：
 * - savePlan(...)：DiagnosisPlanBusiness 创建或修改方案时调用
 * - validateQuestions(...)：保存方案时本类调用；QuestionGenerateService 生成母题、按地域改写时调用；
 *   GeoDiagnosisTaskService 发起诊断任务时调用。四处共用同一套规则
 * - confirmQuestionRegion(...)：本类与 QuestionGenerateService 确认地域时调用
 * - listQuestions(...)、listChannels(...)：读取方案详情与发起任务时调用
 *
 * 约束：
 * - 品牌范围必须与问题原文相符：非品牌题不能出现项目名称或别名，品牌认知题与竞品比较题必须出现；
 *   有没有出现由 DiagnosisQuestionTextRule 判断，报告统计用的是同一个判断
 * - 校验不通过时整组拒绝，不静默过滤
 *
 * 不负责：
 * - 调用模型生成或改写问题（QuestionGenerateService）
 * - 冻结方案、执行诊断（GeoDiagnosisTaskService）
 * </pre>
 */
@Service
public class GeoDiagnosisPlanService extends ServiceImpl<GeoDiagnosisPlanDao, GeoDiagnosisPlanEntity> {
	/** 问题地域的最大字符数，与 geo_diagnosis_plan_question.region 的列长度一致。 */
	private static final int REGION_MAX_LENGTH = 50;
	/** 提示“哪些问题没有问到本项目”时最多列出的序号个数，超出的只给总数，避免提示过长。 */
	private static final int OFF_TARGET_NUMBER_LIMIT = 30;

	@Resource
	private GeoDiagnosisPlanQuestionDao questionDao;
	@Resource
	private GeoDiagnosisPlanChannelDao channelDao;
	@Resource
	private GeoProjectInfoDao projectDao;
	@Resource
	private GeoLlmChannelDao llmChannelDao;

	/**
	 * 创建或修改诊断方案，连同它的问题和渠道整体保存；修改时锁定方案后替换子集合，子项失败使方案及旧集合一并回滚。
	 * <p>写入（同一事务）：geo_diagnosis_plan 新增或更新 1 条；geo_diagnosis_plan_question、geo_diagnosis_plan_channel 修改时先删光该方案的旧行，
	 * 再按传入的集合逐条新增。</p>
	 * @param plan 方案的公共配置；ID 为空表示新建，否则修改该方案
	 * @param questions 方案的全部问题，按顺序保存；须通过 validateQuestions
	 * @param channels 方案选择的被测渠道，1 到 20 个，都须已启用
	 * @return 回读后的方案
	 * @throws IllegalArgumentException 名称、重复次数、项目、问题或渠道不合规则，此时不写库；问题的规则见 validateQuestions
	 * @throws NoSuchElementException 要修改的方案不存在
	 */
	@Transactional
	public GeoDiagnosisPlanEntity savePlan(GeoDiagnosisPlanEntity plan,
	                                       List<GeoDiagnosisPlanQuestionEntity> questions, List<GeoDiagnosisPlanChannelEntity> channels) {
		// 1. 校验关联及问题，拒绝失效渠道，以及品牌范围与原文不符的问题（非品牌题问到了本项目，或品牌认知题、竞品比较题没问到本项目）。
		validatePlan(plan, questions, channels);
		// 2. 锁定已有方案后保存公共配置，修改不覆盖创建时间。
		if (plan.getId() == null) {
			if (! save(plan)) throw new IllegalStateException("诊断方案创建失败");
		} else {
			GeoDiagnosisPlanEntity existing = getOne(new LambdaQueryWrapper<GeoDiagnosisPlanEntity>()
					.eq(GeoDiagnosisPlanEntity::getId, plan.getId()).last("FOR UPDATE"));
			if (existing == null) throw new NoSuchElementException("诊断方案不存在");
			plan.setUpdatedAt(LocalDateTime.now());
			if (! updateById(plan)) throw new IllegalStateException("诊断方案修改失败");
			questionDao.delete(new LambdaQueryWrapper<GeoDiagnosisPlanQuestionEntity>()
					.eq(GeoDiagnosisPlanQuestionEntity::getPlanId, plan.getId()));
			channelDao.delete(new LambdaQueryWrapper<GeoDiagnosisPlanChannelEntity>()
					.eq(GeoDiagnosisPlanChannelEntity::getPlanId, plan.getId()));
		}
		// 3. 在同一事务中写入完整的问题和渠道集合。
		for (GeoDiagnosisPlanQuestionEntity question : questions) {
			question.setPlanId(plan.getId());
			questionDao.insert(question);
		}
		for (GeoDiagnosisPlanChannelEntity channel : channels) {
			channel.setPlanId(plan.getId());
			channelDao.insert(channel);
		}
		return getById(plan.getId());
	}

	/**
	 * 保存前确认基础档案存在、渠道均启用，避免创建不可执行的方案。
	 */
	private void validatePlan(GeoDiagnosisPlanEntity plan, List<GeoDiagnosisPlanQuestionEntity> questions,
	                          List<GeoDiagnosisPlanChannelEntity> channels) {
		// 公共配置范围与数据库容量保持一致。
		if (plan.getName() == null || plan.getName().isBlank() || plan.getName().strip().length() > 200)
			throw new IllegalArgumentException("方案名称必填，最多 200 字符");
		plan.setName(plan.getName().strip());
		if (plan.getRepeatCount() == null || plan.getRepeatCount() < 1 || plan.getRepeatCount() > 10)
			throw new IllegalArgumentException("每题重复执行次数必须为 1 到 10");
		GeoProjectInfoEntity project = plan.getProjectId() == null ? null : projectDao.selectById(plan.getProjectId());
		if (project == null) throw new IllegalArgumentException("请选择有效项目");
		validateQuestions(project, questions);
		// 只接受显式选择的渠道，不使用内部生成问题的默认渠道替代。
		if (channels == null || channels.isEmpty() || channels.size() > 20)
			throw new IllegalArgumentException("请选择 1 到 20 个测试渠道");
		Set<Long> ids = new HashSet<>();
		for (GeoDiagnosisPlanChannelEntity channel : channels) {
			if (channel == null || channel.getChannelId() == null || ! ids.add(channel.getChannelId()))
				throw new IllegalArgumentException("测试渠道不能为空或重复");
			GeoLlmChannelEntity model = llmChannelDao.selectById(channel.getChannelId());
			if (model == null || ! Boolean.TRUE.equals(model.getEnabled()))
				throw new IllegalArgumentException("测试渠道不存在或已停用，请重新选择");
		}
	}

	/**
	 * 生成、按地域改写、保存方案与发起诊断任务共用的题目校验。
	 * <p>品牌范围必须与问题原文相符：非品牌题（NON_BRAND）不能出现本项目名称或别名；品牌认知题（BRAND）与竞品比较题（COMPARISON）
	 * 必须出现，它们在报告里是点名题、对比题，没问到本项目就没有可统计的品牌评价和对比结果。</p>
	 * <p>单个方案 1 到 500 个问题；校验通过时就地去除问题、语言与地域的首尾空白，地域空白改为空（不分地域），并按输入顺序回填排序号。</p>
	 * @param project 诊断方案所属项目，提供项目名称与别名，用于核对品牌范围与原文是否相符
	 * @param questions 待保存、生成、改写或即将发起诊断的问题集合
	 * @throws IllegalArgumentException 数量越界、内容重复、字段取值无效、地域超长、非品牌题出现了项目名称，
	 *         或品牌认知题、竞品比较题没有出现项目名称或别名时拒绝；最后一种查完全部问题后按序号一次列出
	 */
	public void validateQuestions(GeoProjectInfoEntity project, List<GeoDiagnosisPlanQuestionEntity> questions) {
		// 1. 拒绝空集合和重复文本，不把非法候选静默过滤成成功。
		if (questions == null || questions.isEmpty() || questions.size() > 500)
			throw new IllegalArgumentException("问题集合必须包含 1 到 500 个问题");
		Set<String> texts = new HashSet<>();
		List<String> brands = new ArrayList<>();
		brands.add(project.getName());
		if (project.getAliases() != null) brands.addAll(project.getAliases());
		List<Integer> offTargetNumbers = new ArrayList<>();
		// 2. 独立校验来源、意图、品牌范围、语言和地域，顺序以输入数组为准。
		for (int index = 0; index < questions.size(); index++) {
			GeoDiagnosisPlanQuestionEntity question = questions.get(index);
			if (question == null || question.getQuestionText() == null || question.getQuestionText().isBlank()
					|| question.getQuestionText().length() > 2000)
				throw new IllegalArgumentException("问题内容必填，最多 2000 字符");
			question.setQuestionText(question.getQuestionText().strip());
			// 忽略大小写、全半角与空白差异后比较，阻止同一问题重复计入诊断执行量。
			if (! texts.add(DiagnosisQuestionTextRule.normalize(question.getQuestionText())))
				throw new IllegalArgumentException("问题内容重复，请删除重复问题");
			if (! Set.of("MANUAL", "GENERATED").contains(Objects.toString(question.getSource(), "")))
				throw new IllegalArgumentException("问题来源无效");
			if (! Set.of("RECOMMENDATION", "PROBLEM_SOLVING", "COMPARISON", "PURCHASE", "TECHNICAL", "AWARENESS")
					.contains(Objects.toString(question.getIntent(), "")))
				throw new IllegalArgumentException("问题意图无效");
			if (! Set.of("NON_BRAND", "BRAND", "COMPARISON").contains(Objects.toString(question.getBrandScope(), "")))
				throw new IllegalArgumentException("品牌范围无效");
			if (question.getLanguage() == null || question.getLanguage().isBlank() || question.getLanguage().length() > 100)
				throw new IllegalArgumentException("问题语言必填，最多 100 字符");
			question.setLanguage(question.getLanguage().strip());
			question.setRegion(confirmQuestionRegion(question.getRegion()));
			question.setSortOrder(index);
			// 品牌范围必须与问题原文相符。非品牌题问到了本项目会抬高自然发现率，当场拒绝；
			// 品牌认知题、竞品比较题没问到本项目时先记下序号，查完全部问题后一次报出，免得改一道报一道。
			boolean targetNamed = DiagnosisQuestionTextRule.isTargetNamed(question.getQuestionText(), brands);
			if ("NON_BRAND".equals(question.getBrandScope())) {
				if (targetNamed) throw new IllegalArgumentException("非品牌问题包含项目名称或别名，请调整内容或品牌范围");
			} else if (! targetNamed) {
				offTargetNumbers.add(index + 1);
			}
		}
		// 3. 品牌认知题、竞品比较题里没问到本项目的，按序号一次列出。
		if (! offTargetNumbers.isEmpty()) throw new IllegalArgumentException(describeOffTargetQuestions(offTargetNumbers));
	}

	/**
	 * 写出“哪些品牌认知题、竞品比较题没有问到本项目”的提示，序号与方案页问题列表的序号一致。
	 * <p>只写序号，不带问题原文：接口返回的错误会记入日志，问题原文不进日志。</p>
	 * @param numbers 不符的问题序号，从 1 开始，按问题顺序
	 * @return 给用户的提示；序号超过 OFF_TARGET_NUMBER_LIMIT 个时只列前面的，并给出总数
	 */
	private String describeOffTargetQuestions(List<Integer> numbers) {
		String listed = numbers.stream().limit(OFF_TARGET_NUMBER_LIMIT).map(String::valueOf).collect(Collectors.joining("、"));
		String total = numbers.size() > OFF_TARGET_NUMBER_LIMIT ? "等共 " + numbers.size() + " 个" : "";
		return "第 " + listed + " 个" + total + "问题的品牌范围是品牌认知或竞品比较，但问题里没有出现项目名称或别名，请调整内容或品牌范围";
	}

	/**
	 * 确认问题地域：去除首尾空白，空白视为不分地域。
	 * <p>地域与语言相互独立，例如东南亚可以用英语提问；方案保存与按地域改写共用这条规则。</p>
	 * @param region 页面填写或请求传入的地域，可为空
	 * @return 去除首尾空白后的地域；未填写或只有空白时返回 null，表示不分地域
	 * @throws IllegalArgumentException 地域超过 50 个字符
	 */
	public String confirmQuestionRegion(String region) {
		if (region == null || region.isBlank()) {
			return null;
		}
		String stripped = region.strip();
		if (stripped.length() > REGION_MAX_LENGTH) {
			throw new IllegalArgumentException("问题地域最多 50 字符");
		}
		return stripped;
	}

	/**
	 * 固定按方案顺序回读问题。
	 */
	public List<GeoDiagnosisPlanQuestionEntity> listQuestions(long planId) {
		return questionDao.selectList(new LambdaQueryWrapper<GeoDiagnosisPlanQuestionEntity>()
				.eq(GeoDiagnosisPlanQuestionEntity::getPlanId, planId)
				.orderByAsc(GeoDiagnosisPlanQuestionEntity::getSortOrder));
	}

	/**
	 * 渠道顺序只影响展示，不改变调用优先级。
	 */
	public List<GeoDiagnosisPlanChannelEntity> listChannels(long planId) {
		return channelDao.selectList(new LambdaQueryWrapper<GeoDiagnosisPlanChannelEntity>()
				.eq(GeoDiagnosisPlanChannelEntity::getPlanId, planId)
				.orderByAsc(GeoDiagnosisPlanChannelEntity::getId));
	}
}
