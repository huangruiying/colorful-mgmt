package org.huangry.colorful.geo.application.diagnosis;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisReportService;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisReportResultAnalysisDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisReportDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 报告列表与详情的组装：名称取自任务快照、判定进度按状态汇总、报告内容只在详情且可查看时返回、重跑类操作交给报告服务、输入校验。 */
class DiagnosisReportBusinessTest {
    private final DiagnosisReportBusiness business = new DiagnosisReportBusiness();
    private final GeoDiagnosisReportService service = mock(GeoDiagnosisReportService.class);
    private final GeoDiagnosisReportDao reports = mock(GeoDiagnosisReportDao.class);
    private final GeoDiagnosisReportResultAnalysisDao analyses = mock(GeoDiagnosisReportResultAnalysisDao.class);
    private final GeoDiagnosisTaskDao tasks = mock(GeoDiagnosisTaskDao.class);

    /** 列表按报告汇总判定进度并带上第 2 段子步骤进度，名称来自任务快照，不返回报告内容。 */
    @Test
    void shouldListReportsWithProgressAndWithoutSummary() {
        when(reports.selectCount(any())).thenReturn(1L);
        var running = report("RUNNING", "CONTENT", "{\"models\":[]}");
        running.setContentProgress("{\"steps\":[{\"key\":\"DIMENSION\",\"status\":\"RUNNING\"}]}");
        when(reports.selectList(any())).thenReturn(List.of(running));
        when(analyses.countStatuses(List.of(9L))).thenReturn(List.of(
                Map.of("report_id", 9L, "status", "PENDING", "analysis_count", 2L),
                Map.of("report_id", 9L, "status", "SUCCEEDED", "analysis_count", 5L),
                Map.of("report_id", 9L, "status", "FAILED", "analysis_count", 1L)));

        var page = business.listReports(null, "RUNNING", 0, 10);

        var item = page.items().get(0);
        assertEquals(1L, page.total());
        assertEquals("加油诊断", item.planName());
        assertEquals("团油", item.projectName());
        assertEquals(8, item.progress().total());
        assertEquals(2, item.progress().pending());
        assertEquals(5, item.progress().succeeded());
        assertEquals(1, item.progress().failed());
        assertNull(item.summary());
        assertEquals("CONTENT", item.stage());
        assertEquals("RUNNING", item.contentProgress().getJSONArray("steps").getJSONObject(0).getString("status"));
        assertEquals("qwen-plus", item.judgeChannel().getString("modelName"));
    }

    /** 已完成报告的详情返回冻结内容；不存在的报告明确 404。 */
    @Test
    void shouldReturnSummaryOnlyInDetail() {
        when(reports.selectById(9L)).thenReturn(report("SUCCEEDED", "CONTENT", "{\"models\":[{\"mentionRate\":0.5}]}"));
        when(analyses.countStatuses(List.of(9L))).thenReturn(List.of());

        var detail = business.getReport(9L);

        assertEquals(0.5, detail.summary().getJSONArray("models").getJSONObject(0).getDouble("mentionRate"));
        assertEquals(0, detail.progress().total());
        assertThrows(NoSuchElementException.class, () -> business.getReport(99L));
    }

    /** 详情里的大盘趋势：同方案更早任务的点在前，本报告排最后并标为当前；时间取所属任务的结束时间。 */
    @Test
    void shouldAppendCurrentReportAfterEarlierDashboardPoints() {
        when(reports.selectById(9L)).thenReturn(report("SUCCEEDED", "CONTENT", "{\"dashboard\":{\"questions\":5}}"));
        when(analyses.countStatuses(List.of(9L))).thenReturn(List.of());
        var earlierDashboard = com.alibaba.fastjson.JSON.parseObject("{\"questions\":4}");
        when(service.listEarlierDashboardPoints(30L)).thenReturn(List.of(new org.huangry.colorful.geo.domain.model.DiagnosisReportDashboardPoint(
                20L, 7L, java.time.LocalDateTime.of(2026, 11, 2, 9, 30), earlierDashboard)));

        var trend = business.getReport(9L).dashboardTrend();

        assertEquals(List.of(7L, 9L), trend.stream().map(point -> point.reportId()).toList());
        assertEquals(List.of(false, true), trend.stream().map(point -> point.current()).toList());
        assertEquals(4, trend.get(0).dashboard().getIntValue("questions"));
        assertEquals(List.of(30L, java.time.LocalDateTime.of(2026, 11, 20, 10, 0), 5),
                List.of(trend.get(1).taskId(), trend.get(1).time(), trend.get(1).dashboard().getIntValue("questions")));
    }

    /** 大盘功能之前生成的报告没有大盘数字：详情不带趋势，也不去读历史；列表从不带趋势。 */
    @Test
    void shouldOmitDashboardTrendWithoutDashboard() {
        when(reports.selectById(9L)).thenReturn(report("SUCCEEDED", "CONTENT", "{\"models\":[]}"));
        when(analyses.countStatuses(List.of(9L))).thenReturn(List.of());
        when(reports.selectCount(any())).thenReturn(1L);
        when(reports.selectList(any())).thenReturn(List.of(report("SUCCEEDED", "CONTENT", "{\"dashboard\":{}}")));

        assertNull(business.getReport(9L).dashboardTrend());
        assertNull(business.listReports(null, null, 0, 10).items().get(0).dashboardTrend());
        verify(service, never()).listEarlierDashboardPoints(anyLong());
    }

    /** 停在第 2 段暂停时返回本次生成的内容；重跑排队、生成中或停在判定段时库里是上一次的内容，不返回。 */
    @Test
    void shouldReturnContentOnlyWhenItIsCurrent() {
        when(analyses.countStatuses(List.of(9L))).thenReturn(List.of());
        String content = "{\"models\":[]}";
        when(reports.selectById(9L)).thenReturn(report("PAUSED", "CONTENT", content));
        assertNotNull(business.getReport(9L).summary());
        for (var hidden : List.of(report("PENDING", "CONTENT", content), report("RUNNING", "CONTENT", content), report("PAUSED", "JUDGE", content))) {
            when(reports.selectById(9L)).thenReturn(hidden);
            assertNull(business.getReport(9L).summary(), hidden.getStatus() + "/" + hidden.getStage());
        }
    }

    /** 重判、重新生成内容与忽略失败继续都交给报告服务改状态，再返回最新详情。 */
    @Test
    void shouldDelegateRerunActionsToServiceAndReturnLatestDetail() {
        when(reports.selectById(9L)).thenReturn(report("PENDING", "JUDGE", null));
        when(analyses.countStatuses(List.of(9L))).thenReturn(List.of());

        assertEquals("JUDGE", business.rejudgeReport(9L).stage());
        business.regenerateReportContent(9L);
        business.skipReportFailures(9L);

        verify(service).rejudgeReport(9L);
        verify(service).regenerateReportContent(9L);
        verify(service).skipReportFailures(9L);
        verify(reports, times(3)).selectById(9L);
    }

    /** 未选择任务、未知状态与越界分页在调用服务和查询前拒绝。 */
    @Test
    void shouldRejectInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> business.createReport(0L, "中文"));
        assertThrows(IllegalArgumentException.class, () -> business.listReports(null, "DONE", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> business.listReports(null, null, 0, 101));
        verifyNoInteractions(service);
        verify(reports, never()).selectList(any());
    }

    @BeforeEach
    void setup() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "diagnosis-report-business-test");
        TableInfoHelper.initTableInfo(assistant, GeoDiagnosisReportEntity.class);
        ReflectionTestUtils.setField(business, "reportService", service);
        ReflectionTestUtils.setField(business, "reportDao", reports);
        ReflectionTestUtils.setField(business, "analysisDao", analyses);
        ReflectionTestUtils.setField(business, "taskDao", tasks);
        ReflectionTestUtils.setField(business, "snapshots", new DiagnosisTaskSnapshots());
        var task = new GeoDiagnosisTaskEntity();
        task.setId(30L);
        task.setPlanSnapshot("{\"planName\":\"加油诊断\",\"project\":{\"name\":\"团油\"}}");
        task.setFinishedAt(java.time.LocalDateTime.of(2026, 11, 20, 10, 0));
        when(tasks.selectBatchIds(any())).thenReturn(List.of(task));
    }

    /**
     * @param status 报告状态
     * @param stage 报告所在段
     * @param summary 报告内容 JSON，可为 null
     * @return 所属任务 30、已冻结判定渠道的报告
     */
    private GeoDiagnosisReportEntity report(String status, String stage, String summary) {
        var report = new GeoDiagnosisReportEntity();
        report.setId(9L);
        report.setTaskId(30L);
        report.setStatus(status);
        report.setStage(stage);
        report.setJudgeChannelSnapshot("{\"channelName\":\"千问\",\"modelName\":\"qwen-plus\"}");
        report.setSummary(summary);
        return report;
    }
}
