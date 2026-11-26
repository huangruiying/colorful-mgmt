package org.huangry.colorful.geo.domain.service.diagnosis;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;

/**
 * <pre>
 * 诊断问题原文的比较规则：两道题算不算同一道，以及一道题有没有问到本项目
 *
 * 负责：
 * - 把问题原文整理成比较用的形式，忽略大小写、全半角与空白差异
 * - 判断问题原文里是否出现项目名称或任一别名
 *
 * 核心入口：
 * - normalize(...)：GeoDiagnosisPlanService.validateQuestions 判断问题是否重复时调用
 * - isTargetNamed(...)：GeoDiagnosisPlanService.validateQuestions 在保存方案、生成与改写问题、发起诊断任务时调用，
 *   核对品牌范围与原文是否相符；ReportSummarizer 统计报告时调用，认定一道题算不算点名题、对比题
 *
 * 约束：
 * - 纯计算，无状态
 * - 校验和统计共用同一个判断，保存时算“问到了”的题，统计时也一定算
 * - 只做字面匹配：项目名称被翻译、音译或写成“该品牌”这类代称时，判为没有问到
 *
 * 不负责：
 * - 品牌范围的取值是否合法、各范围允许或要求什么（GeoDiagnosisPlanService.validateQuestions）
 * - 回答里有没有提到目标（ResultRuleAnalyzer.containsAnyName，对象是回答原文）
 * </pre>
 */
public final class DiagnosisQuestionTextRule {
	private DiagnosisQuestionTextRule() {
	}

	/**
	 * 把文本整理成比较用的形式：全半角统一、转小写、去掉所有空白。
	 * @param text 问题原文或名称
	 * @return 比较用的文本
	 */
	public static String normalize(String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
	}

	/**
	 * 判断问题原文里是否出现项目名称或任一别名，即这道题有没有问到本项目。
	 * @param questionText 问题原文，可为 null
	 * @param targetNames 项目名称与别名，空白项忽略，可为 null
	 * @return 出现任一名称时为 true；问题原文或名称为空时为 false
	 */
	public static boolean isTargetNamed(String questionText, Collection<String> targetNames) {
		if (questionText == null || targetNames == null) {
			return false;
		}
		String normalized = normalize(questionText);
		return targetNames.stream().filter(name -> name != null && !name.isBlank())
				.anyMatch(name -> normalized.contains(normalize(name)));
	}
}
