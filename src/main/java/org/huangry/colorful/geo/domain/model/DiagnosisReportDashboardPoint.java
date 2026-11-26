package org.huangry.colorful.geo.domain.model;

import com.alibaba.fastjson.JSONObject;

import java.time.LocalDateTime;

/**
 * <pre>
 * 诊断大盘趋势上的一个点：同一方案的一次诊断任务，以及这次任务最新一份成功报告里冻结的大盘数字
 *
 * 负责：
 * - 由 GeoDiagnosisReportService.listEarlierDashboardPoints 返回，DiagnosisReportBusiness 拼进报告详情的 dashboardTrend
 *
 * 约束：
 * - dashboard 原样取自报告 summary.dashboard，结构由 ReportDashboardSummarizer 决定，这里不改写
 * </pre>
 * @param taskId 诊断任务 ID（geo_diagnosis_task.id）
 * @param reportId 提供大盘数字的诊断报告 ID（geo_diagnosis_report.id）
 * @param time 这个点在趋势横轴上的时间：任务结束时间，任务没有结束时间时用任务创建时间
 * @param dashboard 该报告冻结的大盘数字
 */
public record DiagnosisReportDashboardPoint(Long taskId, Long reportId, LocalDateTime time, JSONObject dashboard) {
}
