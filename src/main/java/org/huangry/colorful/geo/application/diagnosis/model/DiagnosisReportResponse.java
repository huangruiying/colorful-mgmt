package org.huangry.colorful.geo.application.diagnosis.model;

import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <pre>
 * 诊断报告创建、列表、详情及重跑类接口返回给页面的一份报告
 *
 * 负责：
 * - 报告的所属任务、状态与两段进度，方案与项目名称取自诊断任务快照
 * - 详情另带报告内容（summary）和诊断大盘的趋势点（dashboardTrend）
 *
 * 约束：
 * - 判定渠道只返回非敏感快照
 * - 报告内容与大盘趋势只在详情返回；顶层空字段不输出到 JSON
 * </pre>
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
        /**
         * 第 2 段（生成报告内容）的子步骤进度：steps 按执行顺序，每项 key 为 DIMENSION 合并同义维度 / SUMMARY 统计 / ADVICE 写优化建议 /
         * REGION_STRATEGY 写地域策略，status 为 PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED（无需执行），detail 为说明；
         * 还没运行过第 2 段或重新排队后为空。只用于显示。
         */
        JSONObject contentProgress,
        /** 判定渠道的非敏感快照，开始判定后才有值。 */
        JSONObject judgeChannel,
        /** 判定提示词版本。 */
        String promptVersion,
        /** 报告语言：解析出来的观点概括、维度名、建议与地域策略用此语言书写，问题、回答、引用等原文不翻译。 */
        String reportLanguage,
        /** 报告内容，只在详情返回：已完成时为冻结的内容；停在第 2 段暂停时为已生成的内容，失败部分标记为生成失败；其他状态不返回。 */
        JSONObject summary,
        /**
         * ⓪ 诊断大盘的趋势点，只在详情且报告内容里有大盘数字时返回：按任务从早到晚，前面是同方案更早的任务，最后一个是本报告；
         * 页面用它画趋势，并拿最后两个点算“较上次”。只有本报告一个点时表示没有可比的更早任务。
         */
        List<DashboardPointResponse> dashboardTrend,
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

    /**
     * 诊断大盘趋势上的一个点：一次诊断任务及其报告冻结的大盘数字。
     * @param taskId 诊断任务 ID（geo_diagnosis_task.id）
     * @param reportId 提供数字的诊断报告 ID（geo_diagnosis_report.id）
     * @param time 横轴时间：任务结束时间，没有时为任务创建时间
     * @param current 是否本报告自己
     * @param dashboard 大盘数字，结构与 summary.dashboard 相同
     */
    public record DashboardPointResponse(Long taskId, Long reportId, LocalDateTime time, boolean current, JSONObject dashboard) {
    }
}
