package org.huangry.colorful.geo.application.diagnosis;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.application.diagnosis.model.DiagnosisReportResponse;
import org.huangry.colorful.geo.application.diagnosis.model.DiagnosisReportResponse.DashboardPointResponse;
import org.huangry.colorful.geo.domain.model.DiagnosisReportDashboardPoint;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisReportService;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisReportStage;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisReportStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisReportResultAnalysisStatus;
import org.huangry.colorful.geo.infrastructure.common.model.PageQuery;
import org.huangry.colorful.geo.infrastructure.common.model.PageResponse;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisReportResultAnalysisDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisReportDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * 诊断报告页面背后的用例入口：生成报告、看列表和详情，以及对暂停或已完成的报告重判、重新生成内容、忽略失败继续
 *
 * 负责：
 * - 生成：创建报告与待判定记录后立即返回，不等待判定
 * - 列表与详情：组装报告的所属任务名称、段、判定进度与第 2 段子步骤进度；详情另带报告内容和 ⓪ 诊断大盘的趋势点
 * - 重跑类操作：交给报告服务校验并改状态，再返回最新详情
 *
 * 核心入口：
 * - createReport(...)、listReports(...)、getReport(...)：MgmtDiagnosisReportController 对应接口调用
 * - rejudgeReport(...)、regenerateReportContent(...)、skipReportFailures(...)：同上，用户操作暂停或已完成的报告时调用
 *
 * 约束：
 * - 方案与项目名称取自诊断任务快照
 * - 报告内容与大盘趋势只在详情返回，避免列表传输大字段
 * - 大盘趋势里更早的点每次打开详情现读，不随报告冻结
 *
 * 不负责：
 * - 调用模型：判定与生成内容由后台执行器完成（DiagnosisReportWorker）
 * - 段与状态的规则和切换（GeoDiagnosisReportService）
 *
 * 说明：报告生成完整流程（一份报告对应一次诊断任务，报告表 geo_diagnosis_report，
 * 逐条分析表 geo_diagnosis_report_result_analysis），分两段执行，stage 记录执行到或停在哪一段：
 * 1. 用户点击生成：本类 createReport 调用 GeoDiagnosisReportService.createReport，
 *    创建停在判定段的 PENDING 报告，并为每条有效诊断结果建一条 PENDING 分析记录，随即返回，不等待判定。
 * 2. 后台领取：DiagnosisReportWorker 每秒轮询，取得数据库锁后领取报告（优先续跑中断的 RUNNING），
 *    首次冻结判定渠道（分数最高的已启用渠道），续跑和重跑都沿用同一渠道。
 * 3. 第 1 段 判定每条回答（stage = JUDGE）：规则归类引用来源（ResultRuleAnalyzer.classifySources），
 *    模型判定品牌、观点、情感（ResultAnalyzer），单条失败只记录原因并继续；判完有失败则报告暂停，全部成功进入第 2 段。
 * 4. 第 2 段 生成报告内容（stage = CONTENT）：维度归一（DimensionNormalizer）→ 统计 ⓪–⑦（ReportSummarizer.buildSummary）
 *    → 优化建议（AdviceGenerator）→ 各地域提升策略（RegionStrategyGenerator）；每步开始、结束时更新子步骤进度（content_progress）供页面显示；
 *    全部成功则 finishReport 冻结内容，有失败则保存内容并暂停。
 * 5. 暂停后由用户选择：本类 rejudgeReport 重判失败的回答，regenerateReportContent 整段重新生成报告内容，
 *    skipReportFailures 忽略失败继续；已完成的报告也可重判失败的回答或重新生成报告内容。规则与状态切换都在报告服务中。
 * 6. 查看：本类 listReports 返回报告列表、段、判定进度与第 2 段子步骤进度，getReport 另外返回报告内容，
 *    以及 ⓪ 诊断大盘的趋势点（同方案更早任务的大盘数字加本报告）。
 * </pre>
 */
@Service
public class DiagnosisReportBusiness {
    @Resource
    private GeoDiagnosisReportService reportService;
    @Resource
    private GeoDiagnosisReportDao reportDao;
    @Resource
    private GeoDiagnosisReportResultAnalysisDao analysisDao;
    @Resource
    private GeoDiagnosisTaskDao taskDao;
    @Resource
    private DiagnosisTaskSnapshots snapshots;

    /**
     * 为一次诊断任务生成报告：创建报告与待判定记录后立即返回，后台执行器领取并判定。
     * @param diagnosisTaskId 要生成报告的诊断任务 ID（geo_diagnosis_task.id）
     * @param reportLanguage 报告语言，可为空；为空或空白时为中文
     * @return 新建的报告，状态为 PENDING
     * @throws IllegalArgumentException 未选择任务、报告语言超长、任务未结束或全部失败、已有报告在生成、没有有效回答
     * @throws NoSuchElementException 诊断任务不存在
     */
    public DiagnosisReportResponse createReport(long diagnosisTaskId, String reportLanguage) {
        // 页面未选择任务时传 0，按输入错误提示而不是“任务不存在”
        if (diagnosisTaskId <= 0) {
            throw new IllegalArgumentException("请选择诊断任务");
        }
        GeoDiagnosisReportEntity report = reportService.createReport(diagnosisTaskId, reportLanguage);
        return responses(List.of(report), false).get(0);
    }

    /**
     * 诊断报告列表分页查询，可按诊断任务和状态筛选，最新的在前。
     * @param diagnosisTaskId 可选，按诊断任务 ID（geo_diagnosis_task.id）筛选
     * @param status 可选报告状态
     * @param page 从零开始的页码
     * @param size 每页数量，1 到 100
     * @return 当前页报告及总数，不含汇总
     * @throws IllegalArgumentException 分页参数或状态不合法
     */
    public PageResponse<DiagnosisReportResponse> listReports(Long diagnosisTaskId, String status, int page, int size) {
        // 1. 校验分页与状态，不接受未知筛选条件
        var pageQuery = new PageQuery(page, size);
        boolean filterStatus = status != null && !status.isBlank();
        if (filterStatus && Arrays.stream(DiagnosisReportStatus.values()).noneMatch(value -> value.name().equals(status))) {
            throw new IllegalArgumentException("报告状态不合法");
        }
        // 2. 统计总数后按 ID 倒序取当前页
        LambdaQueryWrapper<GeoDiagnosisReportEntity> query = new LambdaQueryWrapper<GeoDiagnosisReportEntity>()
                .eq(diagnosisTaskId != null, GeoDiagnosisReportEntity::getTaskId, diagnosisTaskId)
                .eq(filterStatus, GeoDiagnosisReportEntity::getStatus, status);
        long total = reportDao.selectCount(query);
        query.select(GeoDiagnosisReportEntity::getId, GeoDiagnosisReportEntity::getTaskId, GeoDiagnosisReportEntity::getStatus,
                        GeoDiagnosisReportEntity::getStage, GeoDiagnosisReportEntity::getContentProgress,
                        GeoDiagnosisReportEntity::getJudgeChannelSnapshot, GeoDiagnosisReportEntity::getPromptVersion,
                        GeoDiagnosisReportEntity::getErrorMessage, GeoDiagnosisReportEntity::getCreatedAt,
                        GeoDiagnosisReportEntity::getStartedAt, GeoDiagnosisReportEntity::getFinishedAt)
                .orderByDesc(GeoDiagnosisReportEntity::getId).last(pageQuery.limitClause());
        return pageQuery.toResponse(responses(reportDao.selectList(query), false), total);
    }

    /**
     * 查看报告详情：已完成时包含冻结的报告内容，停在第 2 段暂停时包含已生成的内容；有大盘数字时另带 ⓪ 诊断大盘的趋势点。
     * @param reportId 报告 ID
     * @return 报告详情
     * @throws NoSuchElementException 报告不存在
     */
    public DiagnosisReportResponse getReport(long reportId) {
        GeoDiagnosisReportEntity report = reportId > 0 ? reportDao.selectById(reportId) : null;
        if (report == null) {
            throw new NoSuchElementException("诊断报告不存在");
        }
        return responses(List.of(report), true).get(0);
    }

    /**
     * 重判报告中没有判定成功的回答：已暂停或已完成的报告可用，失败的回答改回待判定，成功的保留，报告回到待生成，
     * 由后台从判定段重新执行并接着生成报告内容。
     * @param reportId 诊断报告 ID
     * @return 重新排队后的报告详情
     * @throws NoSuchElementException 报告不存在
     * @throws IllegalArgumentException 报告正在生成或已失败、没有需要重判的回答、同一任务已有报告在生成，或判定渠道已被删除
     */
    public DiagnosisReportResponse rejudgeReport(long reportId) {
        // 1. 报告服务校验前置条件并改状态；需要重新执行的由后台下次轮询领取
        reportService.rejudgeReport(reportId);
        // 2. 返回改状态后的详情，页面据此刷新两段进度与操作按钮
        return getReport(reportId);
    }

    /**
     * 重新生成报告内容：停在第 2 段的已暂停报告或已完成的报告可用，报告回到待生成，由后台整段重做第 2 段，判定结果不动，原有内容届时被替换。
     * @param reportId 诊断报告 ID
     * @return 重新排队后的报告详情
     * @throws NoSuchElementException 报告不存在
     * @throws IllegalArgumentException 报告停在判定段、正在生成或已失败，同一任务已有报告在生成，或判定渠道已被删除
     */
    public DiagnosisReportResponse regenerateReportContent(long reportId) {
        // 1. 报告服务校验前置条件并改状态；需要重新执行的由后台下次轮询领取
        reportService.regenerateReportContent(reportId);
        // 2. 返回改状态后的详情，页面据此刷新两段进度与操作按钮
        return getReport(reportId);
    }

    /**
     * 忽略失败继续：只对已暂停的报告可用。停在判定段时失败的回答不计入指标，报告进入第 2 段等后台生成内容；
     * 停在第 2 段时按已生成的内容直接完成，失败部分显示为生成失败。
     * @param reportId 诊断报告 ID
     * @return 处理后的报告详情
     * @throws NoSuchElementException 报告不存在
     * @throws IllegalArgumentException 报告未暂停、判定段没有判定成功的回答、第 2 段没有已生成的内容、同一任务已有报告在生成，或判定渠道已被删除
     */
    public DiagnosisReportResponse skipReportFailures(long reportId) {
        // 1. 报告服务校验前置条件并改状态；需要重新执行的由后台下次轮询领取
        reportService.skipReportFailures(reportId);
        // 2. 返回改状态后的详情，页面据此刷新两段进度与操作按钮
        return getReport(reportId);
    }

    /**
     * 批量组装响应：一次查询所属任务与判定进度，避免逐份报告往返；详情另按报告读取大盘趋势。
     * @param reports 报告
     * @param detail 是否返回报告内容
     * @return 与输入顺序一致的响应
     */
    private List<DiagnosisReportResponse> responses(List<GeoDiagnosisReportEntity> reports, boolean detail) {
        if (reports.isEmpty()) {
            return List.of();
        }
        // 1. 读取所属任务快照中的方案与项目名称
        Map<Long, GeoDiagnosisTaskEntity> tasks = taskDao.selectBatchIds(
                        reports.stream().map(GeoDiagnosisReportEntity::getTaskId).distinct().toList()).stream()
                .collect(Collectors.toMap(GeoDiagnosisTaskEntity::getId, Function.identity()));
        // 2. 按报告汇总判定状态数量
        Map<Long, long[]> progress = new HashMap<>();
        for (Map<String, Object> row : analysisDao.countStatuses(reports.stream().map(GeoDiagnosisReportEntity::getId).toList())) {
            long[] counts = progress.computeIfAbsent(((Number) row.get("report_id")).longValue(), key -> new long[3]);
            int index = switch (DiagnosisReportResultAnalysisStatus.valueOf((String) row.get("status"))) {
                case PENDING -> 0;
                case SUCCEEDED -> 1;
                case FAILED -> 2;
            };
            counts[index] += ((Number) row.get("analysis_count")).longValue();
        }
        // 3. 组装响应，判定渠道、第 2 段子步骤进度与报告内容以 JSON 对象返回；详情有大盘数字时带上趋势点
        return reports.stream().map(report -> {
            GeoDiagnosisTaskEntity task = tasks.get(report.getTaskId());
            JSONObject summary = detail && isContentVisible(report) ? (JSONObject) snapshots.parseSnapshot(report.getSummary()) : null;
            JSONObject snapshot = task == null ? new JSONObject() : (JSONObject) snapshots.parseSnapshot(task.getPlanSnapshot());
            long[] counts = progress.getOrDefault(report.getId(), new long[3]);
            List<DashboardPointResponse> dashboardTrend = summary == null ? null : listDashboardTrend(report, task, summary.getJSONObject("dashboard"));
            return new DiagnosisReportResponse(report.getId(), report.getTaskId(), snapshot.getString("planName"),
                    snapshot.getJSONObject("project") == null ? null : snapshot.getJSONObject("project").getString("name"),
                    report.getStatus(), report.getStage(),
                    new DiagnosisReportResponse.ProgressResponse(counts[0] + counts[1] + counts[2], counts[0], counts[1], counts[2]),
                    report.getContentProgress() == null ? null : (JSONObject) snapshots.parseSnapshot(report.getContentProgress()),
                    report.getJudgeChannelSnapshot() == null ? null : (JSONObject) snapshots.parseSnapshot(report.getJudgeChannelSnapshot()),
                    report.getPromptVersion(), report.getReportLanguage(), summary, dashboardTrend,
                    report.getErrorMessage(), report.getCreatedAt(), report.getStartedAt(), report.getFinishedAt());
        }).toList();
    }

    /**
     * 拼出 ⓪ 诊断大盘的趋势点：同方案更早任务的大盘数字在前，本报告自己排在最后。
     * <p>更早的点每次打开详情时现读，不随报告冻结：之后再跑的任务不会改动这份报告，但这份报告之前的任务补出报告后，趋势会多出点。</p>
     * @param report 正在查看的报告
     * @param task 报告所属的诊断任务，提供本报告这个点的时间；任务已不存在时用报告创建时间
     * @param dashboard 本报告内容里的大盘数字；大盘功能之前生成的报告没有，为 null
     * @return 按任务从早到晚的趋势点；本报告没有大盘数字时为 null，页面按缺内容提示重新生成报告内容
     */
    private List<DashboardPointResponse> listDashboardTrend(GeoDiagnosisReportEntity report, GeoDiagnosisTaskEntity task, JSONObject dashboard) {
        // 本报告自己没有大盘数字时页面不画大盘，不必读历史
        if (dashboard == null) {
            return null;
        }
        // 1. 同方案更早的任务，各取最新一份成功报告的大盘数字
        List<DashboardPointResponse> trend = new ArrayList<>();
        for (DiagnosisReportDashboardPoint point : reportService.listEarlierDashboardPoints(report.getTaskId())) {
            trend.add(new DashboardPointResponse(point.taskId(), point.reportId(), point.time(), false, point.dashboard()));
        }
        // 2. 本报告作为最后一个点
        LocalDateTime time = task == null ? report.getCreatedAt() : task.getFinishedAt() != null ? task.getFinishedAt() : task.getCreatedAt();
        trend.add(new DashboardPointResponse(report.getTaskId(), report.getId(), time, true, dashboard));
        return trend;
    }

    /**
     * 判断报告内容能否给页面查看：已完成的报告，或停在第 2 段暂停、保存了本次内容的报告。
     * 重跑排队或生成中时库里还是上一次的内容，停在判定段时内容还没重新生成，这些都不返回，避免页面把旧内容当成当前结果。
     * @param report 报告
     * @return 有内容且可以查看时为 true
     */
    private boolean isContentVisible(GeoDiagnosisReportEntity report) {
        boolean pausedAtContent = DiagnosisReportStatus.PAUSED.name().equals(report.getStatus())
                && DiagnosisReportStage.CONTENT.name().equals(report.getStage());
        return report.getSummary() != null && (DiagnosisReportStatus.SUCCEEDED.name().equals(report.getStatus()) || pausedAtContent);
    }
}
