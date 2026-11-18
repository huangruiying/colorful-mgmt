package org.huangry.colorful.geo.application.diagnosis.model;

import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 诊断报告创建、列表、详情及重跑类接口的响应；名称取自诊断任务快照，判定渠道只返回非敏感快照。
 * 报告内容只在详情返回；顶层空字段不输出到 JSON。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagnosisReportResponse(
        /** 诊断报告 ID（geo_diagnosis_report.id）。 */
        Long id,
        /** 所属诊断任务 ID（geo_diagnosis_task.id）。 */
        Long diagnosisTaskId,
        /** 诊断任务创建时冻结的方案名称。 */
        String planName,
        /** 诊断任务创建时冻结的项目名称。 */
        String projectName,
        /** 报告状态：PENDING 待生成、RUNNING 生成中、PAUSED 已暂停、SUCCEEDED 已完成、FAILED 失败。 */
        String status,
        /** 报告执行到或停在哪一段：JUDGE 判定每条回答、CONTENT 生成报告内容；与 status 一起表示进度。 */
        String stage,
        /** 逐条判定进度，由分析记录实时统计。 */
        ProgressResponse progress,
        /** 判定渠道的非敏感快照，开始判定后才有值。 */
        JSONObject judgeChannel,
        /** 判定提示词版本。 */
        String promptVersion,
        /** 报告语言：解析出来的观点概括、维度名、建议与地域策略用此语言书写，问题、回答、引用等原文不翻译。 */
        String reportLanguage,
        /** 报告内容，只在详情返回：已完成时为冻结的内容；停在第 2 段暂停时为已生成的内容，失败部分标记为生成失败；其他状态不返回。 */
        JSONObject summary,
        /** 暂停或失败原因，写明哪一步失败。 */
        String errorMessage,
        /** 报告创建时间。 */
        LocalDateTime createdAt,
        /** 后台最近一次领取执行的时间。 */
        LocalDateTime startedAt,
        /** 报告完成或失败的时间。 */
        LocalDateTime finishedAt
) {
    /**
     * 本报告有效回答的判定进度。
     * @param total 纳入分析的有效回答数
     * @param pending 尚未判定的数量
     * @param succeeded 判定成功的数量
     * @param failed 判定失败的数量
     */
    public record ProgressResponse(long total, long pending, long succeeded, long failed) {
    }
}
