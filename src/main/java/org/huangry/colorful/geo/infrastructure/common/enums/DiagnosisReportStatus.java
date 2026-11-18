package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 诊断报告状态，数据库保存枚举名称；与 stage（执行到哪一段，见 {@link DiagnosisReportStage}）一起表示报告进度。
 * <p>流转：PENDING →（后台领取）RUNNING →（两段都成功）SUCCEEDED；任一段有失败时 RUNNING → PAUSED，
 * 由用户重试该段回到 PENDING，或忽略失败继续；已完成的报告也可由用户重跑某一段回到 PENDING。
 * 只有冻结的判定渠道被删除时进入 FAILED，之后不再改写。</p>
 */
public enum DiagnosisReportStatus {
	/**
	 * 待生成：用户点击生成时写入，同时为每条有效回答建立待判定记录（createReport）；
	 * 用户重试、重跑，或在判定段忽略失败继续后也回到这里，等后台领取。
	 */
	PENDING,
	/**
	 * 生成中：后台领取后写入（claimNextReport），执行 stage 所在的一段。
	 * 服务重启或数据库异常中断时保持此状态，下次轮询续跑：判定段接着判剩余记录，第 2 段整段重做。
	 */
	RUNNING,
	/**
	 * 已暂停：stage 所在的一段有失败，原因写入 errorMessage；后台不会自动领取，等用户重试该段或忽略失败继续。
	 * 停在第 2 段时 summary 保存已生成的内容，失败的部分标记为生成失败；内容生成出错时没有内容。
	 */
	PAUSED,
	/**
	 * 已完成：报告内容冻结到 summary（finishReport，或用户在第 2 段暂停后忽略失败继续）；用户仍可重跑某一段。
	 */
	SUCCEEDED,
	/**
	 * 失败，原因写入 errorMessage（failReport），已保存的回答分析保留，不能重试，只能重新生成一份报告。
	 * 现在只在冻结的判定渠道被删除时失败；早期报告的其他失败原因（如全部回答判定失败）保持原样。
	 */
	FAILED
}
