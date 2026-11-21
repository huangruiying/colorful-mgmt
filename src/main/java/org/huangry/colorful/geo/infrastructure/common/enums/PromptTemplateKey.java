package org.huangry.colorful.geo.infrastructure.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 可在「基础配置 → 提示词管理」维护的提示词：每个枚举值是一段提示词，枚举名是库里的 key，带页面显示的描述和可用变量。
 * <p>执行时按枚举到 geo_prompt_template 取模板（当前版本，或报告冻结的版本），再按变量名填入数据；模板只能使用这里声明的变量，
 * 保存时拒绝拼错的变量和缺少的必填变量。声明顺序即页面下拉顺序，按业务流程排列：诊断方案制题、诊断报告、内容优化。
 * 新增提示词：加枚举值并声明变量，再在建表脚本与迁移脚本里写入初始模板。</p>
 */
@Getter
@RequiredArgsConstructor
public enum PromptTemplateKey {
	/** 诊断方案制题：按项目资料和本次地域生成一套母题，母题要在这些地域都成立，之后再逐个地域改写。 */
	DIAGNOSIS_PLAN_QUESTION_GENERATE("诊断方案-生成母题提示词", List.of(
			new PromptVariable("count", "母题数量，模型须恰好返回这么多题", true),
			new PromptVariable("language", "母题语言（JSON 字符串，带引号）", false),
			new PromptVariable("regions", "本次要改写到的地域（JSON 数组），母题要在这些地域都成立", false),
			new PromptVariable("project", "项目资料（JSON，不含目标市场、目标语言）", true))),
	/** 诊断方案制题：把一批母题按一个地域改写成当地用户会问的问题，意图与品牌范围沿用母题。 */
	DIAGNOSIS_PLAN_QUESTION_LOCALIZE("诊断方案-按地域改写问题提示词", List.of(
			new PromptVariable("languageRule", "语言要求：指定了语言时为“全部使用指定语言 \"德语\"”这样的文字，没指定时为“使用该地域用户向 AI 提问时最常用的语言”", false),
			new PromptVariable("region", "目标地域（JSON 字符串，带引号）", true),
			new PromptVariable("project", "项目资料（JSON，不含目标市场、目标语言）", false),
			new PromptVariable("questions", "母题（JSON 数组，每项有序号、正文、意图、品牌范围），模型须按原顺序逐题改写", true))),
	/**
	 * 诊断报告第 1 段：判定一条回答提到的品牌、推荐、观点与情感。初始模板沿用代码时期的 v2 编号，从第 2 版开始，报告上记作 v2。
	 */
	DIAGNOSIS_REPORT_RESULT_JUDGE("诊断报告-逐条判定提示词", List.of(
			new PromptVariable("target", "目标名称（项目名）", false),
			new PromptVariable("aliases", "目标别名，顿号分隔，没有时为空", false),
			new PromptVariable("competitors", "预设竞品，顿号分隔，没有时为空", false),
			new PromptVariable("brandScope", "问题分组：NON_BRAND 未点名、BRAND 点名、COMPARISON 对比", false),
			new PromptVariable("question", "用户问题原文", false),
			new PromptVariable("projectMaterials", "点名问题附带的项目资料段（核心功能、卖点、价格、目标用户），末尾带换行；其他问题为空", false),
			new PromptVariable("answer", "被判定的回答原文", true),
			new PromptVariable("reportLanguage", "报告语言，观点概括与维度名按此语言书写", false))),
	/** 诊断报告第 2 段：把写法不同、意思相同的维度名合并成统一名称。 */
	DIAGNOSIS_REPORT_DIMENSION("诊断报告-合并同义维度提示词", List.of(
			new PromptVariable("reportLanguage", "报告语言，合并后的名称按此语言书写", false),
			new PromptVariable("dimensions", "待合并的维度名，每行一个", true))),
	/** 诊断报告第 2 段：基于规则发现的问题写主要发现与优化建议。 */
	DIAGNOSIS_REPORT_ADVICE("诊断报告-优化建议提示词", List.of(
			new PromptVariable("target", "目标名称（项目名）", false),
			new PromptVariable("sellingPoints", "核心卖点，没有时为空", false),
			new PromptVariable("issues", "规则发现的问题列表（JSON 数组，每项有编号、数字与回答原文证据）", true),
			new PromptVariable("reportLanguage", "报告语言，发现与建议按此语言书写", false))),
	/** 诊断报告第 2 段：为 ⑦ 的一个地域写品牌优化方向与两类打法，每个有机会的地域调用一次。 */
	DIAGNOSIS_REPORT_REGION_STRATEGY("诊断报告-地域策略提示词", List.of(
			new PromptVariable("reportLanguage", "报告语言，策略按此语言书写", false),
			new PromptVariable("target", "目标名称（项目名）", false),
			new PromptVariable("sellingPoints", "核心卖点，没有时为空", false),
			new PromptVariable("competitors", "预设竞品（JSON 数组）", false),
			new PromptVariable("region", "地域名称", false),
			new PromptVariable("sample", "该地域的样本数（JSON）", false),
			new PromptVariable("portrayal", "该地域的宣传状态（JSON，结论、计数与各维度排行前几项）", false),
			new PromptVariable("visibility", "各模型在该地域的可见度（JSON）", false),
			new PromptVariable("opportunities", "该地域的机会清单（JSON 数组，每项有编号），策略只能引用这些编号", true))),
	/** 内容优化：按选定的 GEO 策略改写待发布正文，只能用调用方提供的参考资料补充事实。 */
	CONTENT_OPTIMIZATION("内容优化-正文优化提示词", List.of(
			new PromptVariable("optimizationPreference", "优化偏好，未填写时为“未指定”", false),
			new PromptVariable("strategies", "本次策略，每行一条“- 策略名：做法”", true),
			new PromptVariable("references", "参考资料，每条含标题、链接、摘录（尖括号已转义）；没有时为“未提供参考资料；不得新增统计、来源、引文或其他原文不存在的事实。”", true),
			new PromptVariable("content", "待优化正文（尖括号已转义，防止提前结束提示词里的标签）", true)));

	/** 页面下拉与提示中显示的描述，如“诊断报告-优化建议提示词”。 */
	private final String title;
	/** 模板可用的变量，按页面显示顺序。 */
	private final List<PromptVariable> variables;

	/**
	 * 提示词模板里的一个变量：模板中写作 {{name}}，执行时由程序填入对应数据。
	 *
	 * @param name        变量名，只用英文字母
	 * @param description 页面显示的说明：填的是什么、什么格式
	 * @param required    是否必须出现在模板里；缺了提示词就失去作用（如判定的回答原文），保存时拒绝
	 */
	public record PromptVariable(String name, String description, boolean required) {
	}
}
