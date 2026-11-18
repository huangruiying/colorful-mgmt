package org.huangry.colorful.geo.presentation.task;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.report.tactic.AdvicePrompt;
import org.huangry.colorful.geo.domain.service.diagnosis.report.dimension.DimensionPrompt;
import org.huangry.colorful.geo.domain.service.diagnosis.report.tactic.RegionStrategyPrompt;
import org.huangry.colorful.geo.domain.service.diagnosis.report.dimension.DimensionNormalizer;
import org.huangry.colorful.geo.domain.service.diagnosis.report.tactic.TacticGenerator;
import org.huangry.colorful.geo.domain.service.diagnosis.report.analyze.ResultAnalyzer;
import org.huangry.colorful.geo.domain.service.diagnosis.report.analyze.ResultJudgePrompt;
import org.huangry.colorful.geo.domain.service.diagnosis.report.analyze.ResultRuleAnalyzer;
import org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportSummarizer;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisReportService;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisReportService.ReportAnswer;
import org.huangry.colorful.geo.domain.service.llm.GeoLlmChannelService;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMStrategyRouter;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportResultAnalysisEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 报告两段编排：冻结默认判定渠道；判定段逐条判定，有失败就暂停、全部成功才进入第 2 段；
 * 第 2 段四步跑完再看结果，全部成功则完成，有失败保存内容并暂停；不连接数据库与模型。
 */
class DiagnosisReportWorkerTest {
    private final DiagnosisReportWorker worker = new DiagnosisReportWorker();
    private final GeoDiagnosisReportService service = mock(GeoDiagnosisReportService.class);
    private final ResultJudgePrompt analysisPrompt = mock(ResultJudgePrompt.class);
    private final ReportSummarizer summarizer = mock(ReportSummarizer.class);
    private final LLMClient llmClient = mock(LLMClient.class);
    private final LLMStrategyRouter router = mock(LLMStrategyRouter.class);
    private final DiagnosisTaskSnapshots snapshots = mock(DiagnosisTaskSnapshots.class);
    private final GeoLlmChannelEntity channel = new GeoLlmChannelEntity();

    /** 首次生成冻结默认渠道；一条判定成功、一条模型失败时继续判完，再按失败原因暂停在判定段，不生成报告内容。 */
    @Test
    void shouldPauseAtJudgeStageWhenAnyAnswerFails() {
        when(service.findNextPendingAnalysis(9L)).thenReturn(analysis(1L), analysis(2L), null);
        when(llmClient.query(any(), same(channel))).thenReturn(new LLMResponse("{}", List.of()))
                .thenThrow(new LLMClientException("调用失败"));
        when(analysisPrompt.parseAnalysisResult(any(), any(), any(), any())).thenReturn(new ResultJudgePrompt.AnalysisResult(new JSONArray(), new JSONArray(), null, null));
        when(service.countAnalysisFailureReasons(9L)).thenReturn(Map.of("判定模型不可用或未返回有效回答", 1L));

        ReflectionTestUtils.invokeMethod(worker, "generate", report("JUDGE"));

        verify(service).freezeJudgeChannel(eq(9L), any(), eq(ResultJudgePrompt.PROMPT_VERSION));
        verify(service).updateAnalysisSucceeded(argThat(saved -> saved.getId() == 1L));
        verify(service).updateAnalysisFailed(eq(2L), eq("判定模型不可用或未返回有效回答"), any());
        verify(service).updateReportPaused(9L, "判定失败 1 条：判定模型不可用或未返回有效回答 1 条");
        verify(service, never()).updateReportStageContent(anyLong());
        verify(service, never()).loadAnswers(anyLong());
        // 逐条判定按报告语言组装提示词，判定只看回答原文，不联网
        verify(analysisPrompt, times(2)).buildPrompt(any(), any(), any(), eq("英语"));
        verify(llmClient, never()).query(argThat(LLMQueryRequest::enableWebSearch), any());
    }

    /** 全部判定成功后先切到第 2 段；第 2 段调用一次模型写建议，只保留引用了已知问题编号的条目，随内容一起冻结，报告完成。 */
    @Test
    void shouldMoveToContentStageAndFinishWithAdvice() {
        when(service.findNextPendingAnalysis(9L)).thenReturn(null);
        when(service.loadAnswers(9L)).thenReturn(List.of(new ReportAnswer(succeeded(), new GeoDiagnosisTaskResultEntity())));
        when(summarizer.buildSummary(any(), any(), any(), any(), any())).thenReturn(summaryWithOneIssue());
        when(llmClient.query(any(), same(channel))).thenReturn(new LLMResponse("""
                {"findings":[{"content":"千问很少提到目标","issueIds":["P1"]},{"content":"编造的结论","issueIds":["P9"]}],
                 "actions":[{"title":"补充场景内容","category":"FILL_CONTENT_GAP","content":"写一篇","evidenceStatus":"INFERRED","issueIds":["P1"]}]}
                """, List.of()));

        ReflectionTestUtils.invokeMethod(worker, "generate", report("JUDGE"));

        InOrder order = inOrder(service);
        order.verify(service).updateReportStageContent(9L);
        order.verify(service).finishReport(eq(9L), argThat(summary -> {
            JSONObject advice = JSON.parseObject(summary).getJSONObject("advice");
            return advice.getString("status").equals("SUCCEEDED") && advice.getJSONArray("findings").size() == 1
                    && advice.getJSONArray("actions").getJSONObject(0).getString("title").equals("补充场景内容");
        }));
        verify(service, never()).updateReportPaused(anyLong(), any());
        verify(service, never()).updateReportContentPaused(anyLong(), any(), any());
    }

    /** 第 2 段每个有机会的地域调用一次模型写提升策略，随内容一起冻结；建议与策略都按报告语言书写，且不联网。 */
    @Test
    void shouldFreezeRegionStrategiesWithContent() {
        when(service.findNextPendingAnalysis(9L)).thenReturn(null);
        when(service.loadAnswers(9L)).thenReturn(List.of(new ReportAnswer(succeeded(), new GeoDiagnosisTaskResultEntity())));
        when(summarizer.buildSummary(any(), any(), any(), any(), any())).thenReturn(summaryWithRegion());
        when(llmClient.query(any(), same(channel))).thenReturn(new LLMResponse("{\"findings\":[],\"actions\":[]}", List.of()),
                new LLMResponse("{\"mentionPlays\":[{\"title\":\"进入推荐清单\",\"steps\":[\"发测评\"],\"evidenceStatus\":\"INFERRED\",\"opportunityIds\":[\"R1-O1\"]}]}", List.of()));

        ReflectionTestUtils.invokeMethod(worker, "generate", report("JUDGE"));

        verify(service).finishReport(eq(9L), argThat(saved -> JSON.parseObject(saved).getJSONObject("region").getJSONArray("regions")
                .getJSONObject(0).getJSONObject("strategy").getString("status").equals("SUCCEEDED")));
        var requests = ArgumentCaptor.forClass(LLMQueryRequest.class);
        verify(llmClient, times(2)).query(requests.capture(), same(channel));
        assertTrue(requests.getAllValues().stream().allMatch(request -> request.prompt().contains("用英语书写") && !request.enableWebSearch()));
    }

    /** 建议与某个地域的策略生成失败：内容照常生成并保存，失败部分标记为生成失败，报告暂停在第 2 段并写明哪几步失败。 */
    @Test
    void shouldSaveContentAndPauseWhenAdviceOrRegionStrategyFails() {
        when(service.loadAnswers(9L)).thenReturn(List.of(new ReportAnswer(succeeded(), new GeoDiagnosisTaskResultEntity())));
        when(summarizer.buildSummary(any(), any(), any(), any(), any())).thenReturn(summaryWithRegion());
        when(llmClient.query(any(), same(channel))).thenThrow(new LLMClientException("调用失败"));

        ReflectionTestUtils.invokeMethod(worker, "generate", report("CONTENT"));

        verify(service).updateReportContentPaused(eq(9L), argThat(saved -> {
            JSONObject content = JSON.parseObject(saved);
            return content.getJSONObject("advice").getString("status").equals("FAILED") && content.getJSONObject("region")
                    .getJSONArray("regions").getJSONObject(0).getJSONObject("strategy").getString("status").equals("FAILED");
        }), eq("写优化建议失败；写地域策略失败：美国"));
        verify(service, never()).finishReport(anyLong(), any());
    }

    /** 维度归一失败：维度保持原名继续统计和写建议，内容保存后暂停在第 2 段，原因写明合并同义维度失败。 */
    @Test
    void shouldSaveContentAndPauseWhenDimensionNormalizationFails() {
        when(service.loadAnswers(9L)).thenReturn(List.of(new ReportAnswer(succeededWithDimension(), new GeoDiagnosisTaskResultEntity())));
        when(summarizer.buildSummary(any(), any(), any(), any(), any())).thenReturn(summaryWithOneIssue());
        when(llmClient.query(any(), same(channel))).thenThrow(new LLMClientException("调用失败"))
                .thenReturn(new LLMResponse("{\"findings\":[],\"actions\":[]}", List.of()));

        ReflectionTestUtils.invokeMethod(worker, "generate", report("CONTENT"));

        verify(summarizer).buildSummary(any(), any(), eq(Map.of("价格偏高", "价格偏高")), any(), any());
        verify(service).updateReportContentPaused(eq(9L),
                argThat(saved -> JSON.parseObject(saved).getJSONObject("advice").getString("status").equals("SUCCEEDED")), eq("合并同义维度失败"));
        verify(service, never()).finishReport(anyLong(), any());
    }

    /** 第 2 段出现程序异常（统计出错）：不保存内容、不再调用模型，暂停等修复后重新生成，避免轮询反复重跑。 */
    @Test
    void shouldPauseWithoutContentWhenContentGenerationThrows() {
        when(service.loadAnswers(9L)).thenReturn(List.of(new ReportAnswer(succeeded(), new GeoDiagnosisTaskResultEntity())));
        when(summarizer.buildSummary(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("统计出错"));

        ReflectionTestUtils.invokeMethod(worker, "generate", report("CONTENT"));

        verify(service).updateReportContentPaused(9L, null, "报告内容生成出错，请联系管理员修复后重新生成报告内容");
        verify(service, never()).finishReport(anyLong(), any());
        verify(llmClient, never()).query(any(), any());
    }

    /** 停在第 2 段的报告（重新生成内容，或在判定段忽略失败后）直接生成报告内容，不再逐条判定。 */
    @Test
    void shouldStartFromContentStageWithoutJudging() {
        when(service.loadAnswers(9L)).thenReturn(List.of(new ReportAnswer(succeeded(), new GeoDiagnosisTaskResultEntity())));
        when(summarizer.buildSummary(any(), any(), any(), any(), any())).thenReturn(summaryWithOneIssue());
        when(llmClient.query(any(), same(channel))).thenReturn(new LLMResponse("{\"findings\":[],\"actions\":[]}", List.of()));

        ReflectionTestUtils.invokeMethod(worker, "generate", report("CONTENT"));

        verify(service, never()).findNextPendingAnalysis(anyLong());
        verify(service, never()).updateReportStageContent(anyLong());
        verify(service).finishReport(eq(9L), any());
    }

    /** 没有已启用渠道时暂停在判定段，配好渠道后由用户重试；不进入判定，也不把报告标为失败。 */
    @Test
    void shouldPauseReportWithoutEnabledChannel() {
        when(router.selectDefaultChannel()).thenThrow(new LLMClientException("没有符合渠道及模型条件的已启用模型配置"));

        ReflectionTestUtils.invokeMethod(worker, "generate", report("JUDGE"));

        verify(service).updateReportPaused(9L, "没有已启用的模型渠道，配置后请重试");
        verify(service, never()).failReport(anyLong(), any());
        verify(service, never()).findNextPendingAnalysis(anyLong());
    }

    /** 冻结的判定渠道已被删除时报告失败：重跑不换渠道，只能重新生成一份。 */
    @Test
    void shouldFailReportWhenFrozenJudgeChannelDeleted() {
        var report = report("CONTENT");
        report.setJudgeChannelSnapshot("{\"id\":5}");

        ReflectionTestUtils.invokeMethod(worker, "generate", report);

        verify(service).failReport(9L, "判定渠道已被删除，请重新生成报告");
        verify(service, never()).loadAnswers(anyLong());
    }

    @BeforeEach
    void setup() throws Exception {
        Connection lease = mock(Connection.class);
        when(lease.isValid(anyInt())).thenReturn(true);
        ReflectionTestUtils.setField(worker, "lease", lease);
        ReflectionTestUtils.setField(worker, "geoDiagnosisReportService", service);
        var resultAnalyzer = new ResultAnalyzer();
        ReflectionTestUtils.setField(resultAnalyzer, "geoDiagnosisReportService", service);
        ReflectionTestUtils.setField(resultAnalyzer, "resultRuleAnalyzer", new ResultRuleAnalyzer());
        ReflectionTestUtils.setField(resultAnalyzer, "resultJudgePrompt", analysisPrompt);
        ReflectionTestUtils.setField(resultAnalyzer, "diagnosisTaskSnapshots", snapshots);
        ReflectionTestUtils.setField(resultAnalyzer, "llmClient", llmClient);
        ReflectionTestUtils.setField(worker, "resultAnalyzer", resultAnalyzer);
        ReflectionTestUtils.setField(worker, "reportSummarizer", summarizer);
        var dimensionNormalizer = new DimensionNormalizer();
        ReflectionTestUtils.setField(dimensionNormalizer, "llmClient", llmClient);
        ReflectionTestUtils.setField(dimensionNormalizer, "dimensionPrompt", new DimensionPrompt());
        ReflectionTestUtils.setField(worker, "dimensionNormalizer", dimensionNormalizer);
        var tacticGenerator = new TacticGenerator();
        ReflectionTestUtils.setField(tacticGenerator, "llmClient", llmClient);
        ReflectionTestUtils.setField(tacticGenerator, "advicePrompt", new AdvicePrompt());
        ReflectionTestUtils.setField(tacticGenerator, "regionStrategyPrompt", new RegionStrategyPrompt());
        ReflectionTestUtils.setField(worker, "tacticGenerator", tacticGenerator);
        ReflectionTestUtils.setField(worker, "diagnosisTaskSnapshots", snapshots);
        ReflectionTestUtils.setField(worker, "router", router);
        ReflectionTestUtils.setField(worker, "channelService", mock(GeoLlmChannelService.class));
        channel.setChannelCode("analysisPrompt");
        when(router.selectDefaultChannel()).thenReturn(channel);
        when(snapshots.buildChannelSnapshot(channel)).thenReturn(new JSONObject().fluentPut("id", 1));
        when(snapshots.parseSnapshot(any())).thenAnswer(invocation -> JSON.parse(invocation.<String>getArgument(0)));
        when(service.getTaskSnapshot(30L)).thenReturn(new JSONObject().fluentPut("project", new JSONObject().fluentPut("name", "团油")));
        when(service.listPublishedUrls()).thenReturn(Set.of());
        var result = new GeoDiagnosisTaskResultEntity();
        result.setAnswerText("回答");
        result.setCitations("[]");
        result.setQuestionSnapshot("{\"text\":\"问题\",\"brandScope\":\"NON_BRAND\"}");
        when(service.getTaskResult(anyLong())).thenReturn(result);
        when(analysisPrompt.buildPrompt(any(), any(), any(), any())).thenReturn("判定提示词");
    }

    /** @return 只含一个规则问题 P1 的内容，⑦ 没有地域 */
    private JSONObject summaryWithOneIssue() {
        JSONArray issues = new JSONArray();
        issues.add(new JSONObject().fluentPut("id", "P1").fluentPut("title", "千问很少提到目标"));
        return new JSONObject().fluentPut("sample", new JSONObject()).fluentPut("issues", issues)
                .fluentPut("region", new JSONObject().fluentPut("unassignedAnswers", 0).fluentPut("regions", new JSONArray()));
    }

    /** @return 含规则问题 P1，且 ⑦ 有一个带机会的地域“美国”的内容 */
    private JSONObject summaryWithRegion() {
        JSONObject summary = summaryWithOneIssue();
        summary.getJSONObject("region").getJSONArray("regions").add(JSON.parseObject(
                "{\"name\":\"美国\",\"portrayal\":{},\"opportunities\":[{\"id\":\"R1-O1\",\"title\":\"竞品占位\"}]}"));
        return summary;
    }

    /**
     * @param stage 报告停在的段：JUDGE 判定每条回答，CONTENT 生成报告内容
     * @return 生成中的报告，报告语言为英语，尚未冻结判定渠道
     */
    private GeoDiagnosisReportEntity report(String stage) {
        var report = new GeoDiagnosisReportEntity();
        report.setId(9L);
        report.setTaskId(30L);
        report.setStatus("RUNNING");
        report.setStage(stage);
        report.setReportLanguage("英语");
        return report;
    }

    /**
     * @param id 分析记录 ID
     * @return 待判定记录
     */
    private GeoDiagnosisReportResultAnalysisEntity analysis(long id) {
        var analysis = new GeoDiagnosisReportResultAnalysisEntity();
        analysis.setId(id);
        analysis.setTaskResultId(id);
        analysis.setStatus("PENDING");
        return analysis;
    }

    /** @return 判定成功的分析记录 */
    private GeoDiagnosisReportResultAnalysisEntity succeeded() {
        var analysis = new GeoDiagnosisReportResultAnalysisEntity();
        analysis.setStatus("SUCCEEDED");
        return analysis;
    }

    /** @return 判定成功、带“价格偏高”保留意见（需要维度归一）的分析记录 */
    private GeoDiagnosisReportResultAnalysisEntity succeededWithDimension() {
        var analysis = succeeded();
        analysis.setPoints("[{\"type\":\"RESERVATION\",\"dimension\":\"价格偏高\",\"quote\":\"贵\"}]");
        return analysis;
    }
}
