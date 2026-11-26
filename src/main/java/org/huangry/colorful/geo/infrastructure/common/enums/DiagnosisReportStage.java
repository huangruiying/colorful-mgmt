package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 诊断报告执行到哪一段：先判定每条回答，再生成报告内容；数据库 geo_diagnosis_report.stage 保存枚举名称。
 * <p>后台执行器按这一列决定从哪一段开始跑。与 status 一起看：stage 之前的段已完成，stage 这一段的进度看 status，之后的段还没开始。
 * 正常只从判定段前进到生成报告内容；用户重判回答时回到判定段，重新生成报告内容时回到第 2 段。</p>
 */
public enum DiagnosisReportStage {
	/**
	 * 第 1 段 判定每条回答：逐条调用判定模型，结果写入回答分析表；新建的报告从这一段开始。
	 * 判完有失败时报告暂停在这一段；全部成功，或用户选择忽略失败继续后，进入生成报告内容。
	 */
	JUDGE,
	/**
	 * 第 2 段 生成报告内容：维度归一 → 统计 ①–⑦ → 优化建议 → 各地域提升策略，结果写入 summary。
	 * 中断或重试时整段重做；全部成功则报告完成，有失败时保存已生成的内容并暂停在这一段。
	 */
	CONTENT
}
