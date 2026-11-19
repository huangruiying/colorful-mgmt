package org.huangry.colorful.geo.domain.service.diagnosis;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.huangry.colorful.geo.domain.service.llm.GeoLlmChannelService;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisReportResultAnalysisDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisReportDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 创建诊断报告的前置校验与有效回答口径，以及两段执行中段与状态的切换和拒绝条件；事务回滚与 SQL 实际执行另用真实 MySQL 验证。 */
class GeoDiagnosisReportServiceTest {
    private final GeoDiagnosisReportService service = new GeoDiagnosisReportService();
    private final GeoDiagnosisReportDao reports = mock(GeoDiagnosisReportDao.class);
    private final GeoDiagnosisReportResultAnalysisDao analyses = mock(GeoDiagnosisReportResultAnalysisDao.class);
    private final GeoDiagnosisTaskDao tasks = mock(GeoDiagnosisTaskDao.class);
    private final GeoLlmChannelService channels = mock(GeoLlmChannelService.class);

    /** 已结束且有成功结果的任务创建待生成报告，只纳入成功且未截断的回答。 */
    @Test
    void shouldCreatePendingReportWithValidAnswersOnly() {
        for (String status : List.of("SUCCEEDED", "PARTIAL_FAILED")) {
            reset(reports, analyses);
            when(tasks.selectOne(any())).thenReturn(task(status));
            when(reports.insert(any(GeoDiagnosisReportEntity.class))).thenAnswer(invocation -> {
                invocation.<GeoDiagnosisReportEntity>getArgument(0).setId(9L);
                return 1;
            });
            when(analyses.insertPendingFromResults(9L, 30L, "SUCCEEDED", "length", "PENDING")).thenReturn(5);
            var saved = new GeoDiagnosisReportEntity();
            saved.setId(9L);
            when(reports.selectById(9L)).thenReturn(saved);

            assertSame(saved, service.createReport(30L, " 英语 "));
            var inserted = org.mockito.ArgumentCaptor.forClass(GeoDiagnosisReportEntity.class);
            verify(reports).insert(inserted.capture());
            assertEquals(30L, inserted.getValue().getTaskId());
            assertEquals("PENDING", inserted.getValue().getStatus());
            assertEquals("JUDGE", inserted.getValue().getStage());
            assertEquals("英语", inserted.getValue().getReportLanguage());
        }
    }

    /** 报告语言留空按中文；超过 50 字符在锁任务、写库之前拒绝。 */
    @Test
    void shouldDefaultReportLanguageToChineseAndRejectTooLong() {
        when(tasks.selectOne(any())).thenReturn(task("SUCCEEDED"));
        when(reports.insert(any(GeoDiagnosisReportEntity.class))).thenAnswer(invocation -> {
            invocation.<GeoDiagnosisReportEntity>getArgument(0).setId(9L);
            return 1;
        });
        when(analyses.insertPendingFromResults(9L, 30L, "SUCCEEDED", "length", "PENDING")).thenReturn(5);

        service.createReport(30L, "  ");
        var inserted = org.mockito.ArgumentCaptor.forClass(GeoDiagnosisReportEntity.class);
        verify(reports).insert(inserted.capture());
        assertEquals("中文", inserted.getValue().getReportLanguage());

        reset(tasks, reports);
        assertThrows(IllegalArgumentException.class, () -> service.createReport(30L, "语".repeat(51)));
        verifyNoInteractions(tasks, reports);
    }

    /** 不存在、执行中或全部失败的任务不能生成报告，不创建任何记录。 */
    @Test
    void shouldRejectMissingUnfinishedOrAllFailedTask() {
        when(tasks.selectOne(any())).thenReturn(null);
        assertThrows(NoSuchElementException.class, () -> service.createReport(30L, null));
        for (String status : List.of("PENDING", "RUNNING", "FAILED")) {
            when(tasks.selectOne(any())).thenReturn(task(status));
            assertThrows(IllegalArgumentException.class, () -> service.createReport(30L, null));
        }
        verify(reports, never()).insert(any(GeoDiagnosisReportEntity.class));
    }

    /** 已有报告正在生成时拒绝，避免重复判定计费。 */
    @Test
    void shouldRejectWhenReportAlreadyGenerating() {
        when(tasks.selectOne(any())).thenReturn(task("SUCCEEDED"));
        when(reports.exists(any())).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.createReport(30L, null));
        verify(reports, never()).insert(any(GeoDiagnosisReportEntity.class));
    }

    /** 没有有效回答时抛出异常，由事务回滚已插入的报告。 */
    @Test
    void shouldRejectWhenNoValidAnswer() {
        when(tasks.selectOne(any())).thenReturn(task("PARTIAL_FAILED"));
        when(reports.insert(any(GeoDiagnosisReportEntity.class))).thenAnswer(invocation -> {
            invocation.<GeoDiagnosisReportEntity>getArgument(0).setId(9L);
            return 1;
        });
        when(analyses.insertPendingFromResults(anyLong(), anyLong(), any(), any(), any())).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> service.createReport(30L, null));
        verify(reports, never()).selectById(any());
    }

    /** 优先续跑中断的 RUNNING 报告，不重复改状态；没有时把最早的 PENDING 报告置为 RUNNING。 */
    @Test
    void shouldClaimRunningReportBeforePendingOne() {
        var running = new GeoDiagnosisReportEntity();
        running.setId(3L);
        running.setStatus("RUNNING");
        when(reports.selectOne(any())).thenReturn(running);
        assertSame(running, service.claimNextReport());
        verify(reports, never()).update(isNull(), any());

        var pending = new GeoDiagnosisReportEntity();
        pending.setId(4L);
        pending.setStatus("PENDING");
        when(reports.selectOne(any())).thenReturn(null, pending);
        when(reports.update(isNull(), any())).thenReturn(1);
        var claimed = service.claimNextReport();
        assertEquals("RUNNING", claimed.getStatus());
        assertNotNull(claimed.getStartedAt());
    }

    /** 只有待判定记录能保存结果，写入数量不符时明确失败，避免覆盖已有判定。 */
    @Test
    void shouldRejectSavingAnalysisThatIsNoLongerPending() {
        var analysis = new org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportResultAnalysisEntity();
        analysis.setId(8L);
        when(analyses.update(isNull(), any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.updateAnalysisSucceeded(analysis));
        assertThrows(IllegalStateException.class, () -> service.updateAnalysisFailed(8L, "原因", "[]"));
    }

    /** 重判：失败的回答改回待判定并清空旧原因与来源，报告回到待生成、停在判定段，暂停原因、结束时间与冻结的判定提示词版本清空（重判用最新版）；已暂停和已完成的报告都可以。 */
    @Test
    void shouldRequeueReportToJudgeStageWhenRejudging() {
        for (String status : List.of("PAUSED", "SUCCEEDED")) {
            reset(reports, analyses, tasks);
            arrangeRequeue(report(status, "CONTENT", "{}"));
            when(analyses.exists(any())).thenReturn(true);

            service.rejudgeReport(9L);

            var analysisUpdate = ArgumentCaptor.forClass(Wrapper.class);
            verify(analyses).update(isNull(), analysisUpdate.capture());
            assertSets(analysisUpdate.getValue(), "status", "PENDING", "error_message", null, "sources", null);
            assertSets(captureReportUpdate(), "stage", "JUDGE", "status", "PENDING", "error_message", null, "finished_at", null, "content_progress", null, "prompt_version", null);
            verify(tasks).selectOne(any());
        }
    }

    /** 重判前拒绝：正在生成或已失败、没有失败或待判定的回答、同一任务已有报告在生成、判定渠道被删，以及状态已被其他操作改走。 */
    @Test
    void shouldRejectRejudgeWhenNotAllowed() {
        for (String status : List.of("PENDING", "RUNNING", "FAILED")) {
            arrangeRequeue(report(status, "JUDGE", null));
            assertThrows(IllegalArgumentException.class, () -> service.rejudgeReport(9L));
        }
        arrangeRequeue(report("PAUSED", "JUDGE", null));
        assertThrows(IllegalArgumentException.class, () -> service.rejudgeReport(9L));
        when(analyses.exists(any())).thenReturn(true);
        when(reports.exists(any())).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.rejudgeReport(9L));
        when(reports.exists(any())).thenReturn(false);
        when(channels.getById(1L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.rejudgeReport(9L));
        verify(analyses, never()).update(isNull(), any());
        // 加锁前读到的状态已被其他操作改走：写报告时状态条件不成立，整次重判回滚
        when(channels.getById(1L)).thenReturn(new GeoLlmChannelEntity());
        when(reports.update(isNull(), any())).thenReturn(0);
        assertThrows(IllegalArgumentException.class, () -> service.rejudgeReport(9L));
    }

    /** 重新生成报告内容：停在第 2 段的已暂停报告、已完成的报告回到待生成并停在第 2 段；停在判定段的已暂停报告不加锁直接拒绝。 */
    @Test
    void shouldRequeueReportToContentStageWhenRegeneratingContent() {
        for (var report : List.of(report("PAUSED", "CONTENT", null), report("SUCCEEDED", "JUDGE", "{}"))) {
            reset(reports, tasks);
            arrangeRequeue(report);

            service.regenerateReportContent(9L);

            assertSets(captureReportUpdate(), "stage", "CONTENT", "status", "PENDING", "error_message", null, "finished_at", null, "content_progress", null);
        }
        reset(reports, tasks);
        arrangeRequeue(report("PAUSED", "JUDGE", null));
        assertThrows(IllegalArgumentException.class, () -> service.regenerateReportContent(9L));
        verifyNoInteractions(tasks);
        verify(reports, never()).update(isNull(), any());
    }

    /** 判定段忽略失败：至少有一条判定成功时报告回到待生成、进入第 2 段；一条都没有时拒绝，只能重判。 */
    @Test
    void shouldSkipJudgeFailuresIntoContentStage() {
        arrangeRequeue(report("PAUSED", "JUDGE", null));
        when(analyses.exists(any())).thenReturn(true);

        service.skipReportFailures(9L);

        assertSets(captureReportUpdate(), "stage", "CONTENT", "status", "PENDING", "error_message", null, "finished_at", null, "content_progress", null);
        reset(reports);
        arrangeRequeue(report("PAUSED", "JUDGE", null));
        when(analyses.exists(any())).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> service.skipReportFailures(9L));
        verify(reports, never()).update(isNull(), any());
    }

    /** 第 2 段忽略失败：按已保存的内容直接完成，不锁任务、不检查渠道；没有内容（内容生成出错）或报告未暂停时拒绝。 */
    @Test
    void shouldCompleteReportWhenSkippingContentFailures() {
        arrangeRequeue(report("PAUSED", "CONTENT", "{}"));

        service.skipReportFailures(9L);

        Map<String, Object> sets = setValues(captureReportUpdate());
        assertEquals("SUCCEEDED", sets.get("status"));
        assertTrue(sets.containsKey("error_message") && sets.get("error_message") == null);
        assertNotNull(sets.get("finished_at"));
        verifyNoInteractions(tasks, channels);
        for (var rejected : List.of(report("PAUSED", "CONTENT", null), report("SUCCEEDED", "CONTENT", "{}"))) {
            reset(reports);
            arrangeRequeue(rejected);
            assertThrows(IllegalArgumentException.class, () -> service.skipReportFailures(9L));
            verify(reports, never()).update(isNull(), any());
        }
    }

    /** 第 2 段进度只写生成中、停在第 2 段的报告；完成与暂停时进度随结果一起写入。 */
    @Test
    void shouldWriteContentProgressOnlyWithStageTwoState() {
        service.updateReportContentProgress(9L, "{\"steps\":[]}");
        var progressUpdate = (LambdaUpdateWrapper<?>) captureReportUpdate();
        assertSets(progressUpdate, "content_progress", "{\"steps\":[]}");
        // 条件里的取值在生成 WHERE 片段时才写入参数表，先生成再核对只改生成中、停在第 2 段的报告
        assertTrue(progressUpdate.getSqlSegment().contains("stage"));
        assertTrue(progressUpdate.getParamNameValuePairs().values().containsAll(List.of("RUNNING", "CONTENT")));

        reset(reports);
        service.finishReport(9L, "{}", "{\"steps\":[1]}");
        Map<String, Object> finished = setValues(captureReportUpdate());
        assertEquals(List.of("SUCCEEDED", "{}", "{\"steps\":[1]}"), List.of(finished.get("status"), finished.get("summary"), finished.get("content_progress")));
        assertNotNull(finished.get("finished_at"));

        reset(reports);
        service.updateReportContentPaused(9L, null, "写优化建议失败", "{\"steps\":[2]}");
        assertSets(captureReportUpdate(), "status", "PAUSED", "summary", null, "error_message", "写优化建议失败", "content_progress", "{\"steps\":[2]}");
    }

    /** 判定失败按原因计数并保持数量顺序，没有记录原因的计为“原因未记录”。 */
    @Test
    void shouldCountAnalysisFailureReasonsInOrder() {
        when(analyses.countFailureReasons(9L, "FAILED")).thenReturn(List.of(
                Map.of("error_message", "判定模型调用超时", "analysis_count", 4L), Map.of("analysis_count", 1L)));

        Map<String, Long> reasons = service.countAnalysisFailureReasons(9L);

        assertEquals(List.of("判定模型调用超时", "原因未记录"), List.copyOf(reasons.keySet()));
        assertEquals(List.of(4L, 1L), List.copyOf(reasons.values()));
    }

    /** 切到第 2 段只改生成中且停在判定段的报告，写入数量不符时明确失败，交由执行器下次轮询续跑。 */
    @Test
    void shouldRejectMovingToContentStageWhenReportChanged() {
        when(reports.update(isNull(), any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.updateReportStageContent(9L));
    }

    @BeforeEach
    void setup() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "diagnosis-report-test");
        for (Class<?> type : List.of(GeoDiagnosisTaskEntity.class, GeoDiagnosisReportEntity.class,
                org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportResultAnalysisEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, type);
        }
        ReflectionTestUtils.setField(service, "reportDao", reports);
        ReflectionTestUtils.setField(service, "analysisDao", analyses);
        ReflectionTestUtils.setField(service, "taskDao", tasks);
        ReflectionTestUtils.setField(service, "snapshots", new DiagnosisTaskSnapshots());
        ReflectionTestUtils.setField(service, "channelService", channels);
    }

    /**
     * 准备一次重新排队：报告可读、任务可锁、同一任务没有别的报告在生成、冻结的判定渠道仍在、写报告成功。
     * @param report 要操作的报告
     */
    private void arrangeRequeue(GeoDiagnosisReportEntity report) {
        when(reports.selectById(9L)).thenReturn(report);
        when(tasks.selectOne(any())).thenReturn(task("SUCCEEDED"));
        when(channels.getById(1L)).thenReturn(new GeoLlmChannelEntity());
        when(reports.update(isNull(), any())).thenReturn(1);
    }

    /** @return 服务写报告时使用的更新条件 */
    private Wrapper<?> captureReportUpdate() {
        var update = ArgumentCaptor.forClass(Wrapper.class);
        verify(reports).update(isNull(), update.capture());
        return update.getValue();
    }

    /**
     * 核对更新语句 SET 的列与取值，不多不少。
     * @param wrapper 更新条件
     * @param columnValues 交替给出的列名与取值，取值可为 null
     */
    private void assertSets(Wrapper<?> wrapper, Object... columnValues) {
        Map<String, Object> expected = new HashMap<>();
        for (int index = 0; index < columnValues.length; index += 2) {
            expected.put((String) columnValues[index], columnValues[index + 1]);
        }
        assertEquals(expected, setValues(wrapper));
    }

    /**
     * @param wrapper 更新条件
     * @return 更新语句 SET 的列名到取值，NULL 取值记为 null
     */
    private Map<String, Object> setValues(Wrapper<?> wrapper) {
        var update = (LambdaUpdateWrapper<?>) wrapper;
        Map<String, Object> values = new HashMap<>();
        for (String assignment : update.getSqlSet().split(",")) {
            String[] parts = assignment.split("=", 2);
            String param = parts[1].replaceAll(".*paramNameValuePairs\\.(\\w+).*", "$1");
            values.put(parts[0], update.getParamNameValuePairs().get(param));
        }
        return values;
    }

    /**
     * @param status 报告状态
     * @param stage 报告所在段
     * @param summary 报告内容 JSON，可为 null
     * @return 所属任务 30、已冻结判定渠道 1 的报告
     */
    private GeoDiagnosisReportEntity report(String status, String stage, String summary) {
        var report = new GeoDiagnosisReportEntity();
        report.setId(9L);
        report.setTaskId(30L);
        report.setStatus(status);
        report.setStage(stage);
        report.setSummary(summary);
        report.setJudgeChannelSnapshot("{\"id\":1,\"channelName\":\"千问\"}");
        return report;
    }

    /**
     * @param status 诊断任务状态
     * @return 指定状态的诊断任务
     */
    private GeoDiagnosisTaskEntity task(String status) {
        var task = new GeoDiagnosisTaskEntity();
        task.setId(30L);
        task.setStatus(status);
        return task;
    }
}
