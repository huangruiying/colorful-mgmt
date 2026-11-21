package org.huangry.colorful.geo.presentation.task;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.report.content.advice.AdviceGenerator;
import org.huangry.colorful.geo.domain.service.diagnosis.report.content.advice.AdvicePrompt;
import org.huangry.colorful.geo.domain.service.diagnosis.report.content.dimension.DimensionPrompt;
import org.huangry.colorful.geo.domain.service.diagnosis.report.content.strategy.RegionStrategyGenerator;
import org.huangry.colorful.geo.domain.service.diagnosis.report.content.strategy.RegionStrategyPrompt;
import org.huangry.colorful.geo.domain.service.diagnosis.report.content.dimension.DimensionNormalizer;
import org.huangry.colorful.geo.domain.service.diagnosis.report.judge.ResultAnalyzer;
import org.huangry.colorful.geo.domain.service.diagnosis.report.judge.ResultJudgePrompt;
import org.huangry.colorful.geo.domain.service.diagnosis.report.judge.ResultRuleAnalyzer;
import org.huangry.colorful.geo.domain.service.diagnosis.report.content.summary.ReportSummarizer;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisReportService;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisReportService.ReportAnswer;
import org.huangry.colorful.geo.domain.service.llm.GeoLlmChannelService;
import org.huangry.colorful.geo.domain.service.prompt.GeoPromptTemplateService;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMStrategyRouter;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.enums.PromptTemplateKey;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportResultAnalysisEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoPromptTemplateEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 报告两段编排：冻结默认判定渠道；判定段逐条判定，有失败就暂停、全部成功才进入第 2 段；
 * 第 2 段四步跑完再看结果，全部成功则完成，有失败保存内容并暂停，每步的进度随时写出；不连接数据库与模型。
 */
class DiagnosisReportWorkerTest {
    private final DiagnosisReportWorker worker = new DiagnosisReportWorker();
    private final GeoDiagnosisReportService service = mock(GeoDiagnosisReportService.class);
    private final ResultJudgePrompt analysisPrompt = mock(ResultJudgePrompt.class);
    private final ReportSummarizer summarizer = mock(ReportSummarizer.class);
    private final LLMClient llmClient = mock(LLMClient.class);
    private final LLMStrategyRouter router = mock(LLMStrategyRouter.class);
    private final DiagnosisTaskSnapshots snapshots = mock(DiagnosisTaskSnapshots.class);
    private final GeoPromptTemplateService promptTemplates = mock(GeoPromptTemplateService.class);
    /** 判定提示词的当前版本：第 2 版，内容只用于确认传给了判定。 */
    private final GeoPromptTemplateEntity judgeTemplate = template(2, "判定模板");
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

        verify(service).freezeJudgeChannel(eq(9L), any());
        verify(service).freezeJudgePromptVersion(9L, "v2");
        verify(service).updateAnalysisSucceeded(argThat(saved -> saved.getId() == 1L));
        verify(service).updateAnalysisFailed(eq(2L), eq("判定模型不可用或未返回有效回答"), any());
        verify(service).updateReportPaused(9L, "判定失败 1 条：判定模型不可用或未返回有效回答 1 条");
        verify(service, never()).updateReportStageContent(anyLong());
        verify(service, never()).loadAnswers(anyLong());
        // 逐条判定按报告语言组装提示词，判定只看回答原文，不联网
        verify(analysisPrompt, times(2)).buildPrompt(eq("判定模板"), any(), any(), any(), eq("英语"));
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
        }), anyString());
        verify(service, never()).updateReportPaused(anyLong(), any());
        verify(service, never()).updateReportContentPaused(anyLong(), any(), any(), any());
    }

    /** 第 2 段每个有机会的地域调用一次模型写提升策略，随内容一起冻结；写之前报出地域进度，结束时四步的结果随内容写入；建议与策略按报告语言书写且不联网。 */
    @Test
    void shouldFreezeRegionStrategiesWithContent() {
        when(service.findNextPendingAnalysis(9L)).thenReturn(null);
        when(service.loadAnswers(9L)).thenReturn(List.of(new ReportAnswer(succeeded(), new GeoDiagnosisTaskResultEntity())));
        when(summarizer.buildSummary(any(), any(), any(), any(), any())).thenReturn(summaryWithRegion());
        when(llmClient.query(any(), same(channel))).thenReturn(new LLMResponse("{\"findings\":[{\"content\":\"千问很少提到目标\",\"issueIds\":[\"P1\"]}],\"actions\":[]}", List.of()),
                new LLMResponse("{\"mentionPlays\":[{\"title\":\"进入推荐清单\",\"steps\":[\"发测评\"],\"evidenceStatus\":\"INFERRED\",\"opportunityIds\":[\"R1-O1\"]}]}", List.of()));

        ReflectionTestUtils.invokeMethod(worker, "generate", report("JUDGE"));

        verify(service).finishReport(eq(9L), argThat(saved -> JSON.parseObject(saved).getJSONObject("region").getJSONArray("regions")
                .getJSONObject(0).getJSONObject("strategy").getString("status").equals("SUCCEEDED")),
                argThat(progress -> describe(progress).equals(List.of("DIMENSION:SKIPPED:没有需要合并的维度", "SUMMARY:SUCCEEDED:规则问题 1 个，地域 1 个",
                        "ADVICE:SUCCEEDED", "REGION_STRATEGY:SUCCEEDED:共 1 个地域"))));
        var progressUpdates = ArgumentCaptor.forClass(String.class);
        verify(service, atLeastOnce()).updateReportContentProgress(eq(9L), progressUpdates.capture());
        assertTrue(progressUpdates.getAllValues().stream().anyMatch(progress -> describe(progress).get(3).equals("REGION_STRATEGY:RUNNING:0 / 1")));
        assertTrue(progressUpdates.getAllValues().stream().anyMatch(progress -> describe(progress).get(2).equals("ADVICE:RUNNING")));
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
        }), eq("写优化建议失败；写地域策略失败：美国（共 1 个地域）"),
                argThat(progress -> describe(progress).subList(2, 4).equals(List.of("ADVICE:FAILED", "REGION_STRATEGY:FAILED:美国（共 1 个地域）"))));
        verify(service, never()).finishReport(anyLong(), any(), any());
    }

    /** 维度归一失败：维度保持原名继续统计和写建议，内容保存后暂停在第 2 段，原因写明合并同义维度失败。 */
    @Test
    void shouldSaveContentAndPauseWhenDimensionNormalizationFails() {
        when(service.loadAnswers(9L)).thenReturn(List.of(new ReportAnswer(succeededWithDimension(), new GeoDiagnosisTaskResultEntity())));
        when(summarizer.buildSummary(any(), any(), any(), any(), any())).thenReturn(summaryWithOneIssue());
        when(llmClient.query(any(), same(channel))).thenThrow(new LLMClientException("调用失败"))
                .thenReturn(new LLMResponse("{\"findings\":[{\"content\":\"千问很少提到目标\",\"issueIds\":[\"P1\"]}],\"actions\":[]}", List.of()));

        ReflectionTestUtils.invokeMethod(worker, "generate", report("CONTENT"));

        verify(summarizer).buildSummary(any(), any(), eq(Map.of("价格偏高", "价格偏高")), any(), any());
        verify(service).updateReportContentPaused(eq(9L),
                argThat(saved -> JSON.parseObject(saved).getJSONObject("advice").getString("status").equals("SUCCEEDED")), eq("合并同义维度失败：维度保持原名"),
                argThat(progress -> describe(progress).get(0).equals("DIMENSION:FAILED:维度保持原名")));
        verify(service, never()).finishReport(anyLong(), any(), any());
    }

    /** 第 2 段出现程序异常（统计出错）：出错的那一步记为失败、后面的保持未开始，不保存内容、不再调用模型，暂停等修复后重新生成。 */
    @Test
    void shouldPauseWithoutContentWhenContentGenerationThrows() {
        when(service.loadAnswers(9L)).thenReturn(List.of(new ReportAnswer(succeeded(), new GeoDiagnosisTaskResultEntity())));
        when(summarizer.buildSummary(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("统计出错"));

        ReflectionTestUtils.invokeMethod(worker, "generate", report("CONTENT"));

        verify(service).updateReportContentPaused(eq(9L), isNull(), eq("报告内容生成出错，请联系管理员修复后重新生成报告内容"),
                argThat(progress -> describe(progress).equals(List.of("DIMENSION:SKIPPED:没有需要合并的维度", "SUMMARY:FAILED:程序出错",
                        "ADVICE:PENDING", "REGION_STRATEGY:PENDING"))));
        verify(service, never()).finishReport(anyLong(), any(), any());
        verify(llmClient, never()).query(any(), any());
    }

    /** 停在第 2 段的报告（重新生成内容，或在判定段忽略失败后）直接生成报告内容，不再逐条判定。 */
    @Test
    void shouldStartFromContentStageWithoutJudging() {
        when(service.loadAnswers(9L)).thenReturn(List.of(new ReportAnswer(succeeded(), new GeoDiagnosisTaskResultEntity())));
        when(summarizer.buildSummary(any(), any(), any(), any(), any())).thenReturn(summaryWithOneIssue());
        when(llmClient.query(any(), same(channel))).thenReturn(new LLMResponse("{\"findings\":[{\"content\":\"千问很少提到目标\",\"issueIds\":[\"P1\"]}],\"actions\":[]}", List.of()));

        ReflectionTestUtils.invokeMethod(worker, "generate", report("CONTENT"));

        verify(service, never()).findNextPendingAnalysis(anyLong());
        verify(service, never()).updateReportStageContent(anyLong());
        verify(service).finishReport(eq(9L), any(), any());
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

    /** 续跑时按报告冻结的版本取判定提示词，不换成当前版本，也不重新冻结，同一份报告判定口径一致。 */
    @Test
    void shouldResumeJudgingWithFrozenPromptVersion() {
        var report = report("JUDGE");
        report.setPromptVersion("v2");
        when(service.findNextPendingAnalysis(9L)).thenReturn(analysis(1L), (GeoDiagnosisReportResultAnalysisEntity) null);
        when(llmClient.query(any(), same(channel))).thenReturn(new LLMResponse("{}", List.of()));
        when(analysisPrompt.parseAnalysisResult(any(), any(), any(), any())).thenReturn(new ResultJudgePrompt.AnalysisResult(new JSONArray(), new JSONArray(), null, null));

        ReflectionTestUtils.invokeMethod(worker, "generate", report);

        verify(promptTemplates).getTemplate(PromptTemplateKey.DIAGNOSIS_REPORT_RESULT_JUDGE, 2);
        verify(promptTemplates, never()).getCurrentTemplate(PromptTemplateKey.DIAGNOSIS_REPORT_RESULT_JUDGE);
        verify(service, never()).freezeJudgePromptVersion(anyLong(), any());
        verify(analysisPrompt).buildPrompt(eq("判定模板"), any(), any(), any(), any());
    }

    /** 判定提示词还没配置时暂停在判定段，写明缺哪个提示词，不进入判定。 */
    @Test
    void shouldPauseWhenJudgePromptMissing() {
        when(promptTemplates.getCurrentTemplate(PromptTemplateKey.DIAGNOSIS_REPORT_RESULT_JUDGE))
                .thenThrow(new NoSuchElementException("提示词未配置：诊断报告-逐条判定提示词"));

        ReflectionTestUtils.invokeMethod(worker, "generate", report("JUDGE"));

        verify(service).updateReportPaused(9L, "提示词未配置：诊断报告-逐条判定提示词，请在提示词管理中检查后点重试（重试改用最新版）");
        verify(service, never()).findNextPendingAnalysis(anyLong());
        verify(service, never()).updateReportStageContent(anyLong());
    }

    /** 第 2 段的提示词缺失时暂停在第 2 段，不调用模型、不保存内容，补上后由用户重试。 */
    @Test
    void shouldPauseWhenContentPromptMissing() {
        when(service.loadAnswers(9L)).thenReturn(List.of(new ReportAnswer(succeeded(), new GeoDiagnosisTaskResultEntity())));
        when(promptTemplates.getCurrentTemplate(PromptTemplateKey.DIAGNOSIS_REPORT_ADVICE))
                .thenThrow(new NoSuchElementException("提示词未配置：诊断报告-优化建议提示词"));

        ReflectionTestUtils.invokeMethod(worker, "generate", report("CONTENT"));

        verify(service).updateReportContentPaused(9L, null, "提示词未配置：诊断报告-优化建议提示词，请在提示词管理中补上后重试", null);
        verify(service, never()).finishReport(anyLong(), any(), any());
        verify(llmClient, never()).query(any(), any());
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
        var adviceGenerator = new AdviceGenerator();
        ReflectionTestUtils.setField(adviceGenerator, "llmClient", llmClient);
        ReflectionTestUtils.setField(adviceGenerator, "advicePrompt", new AdvicePrompt());
        ReflectionTestUtils.setField(worker, "adviceGenerator", adviceGenerator);
        var regionStrategyGenerator = new RegionStrategyGenerator();
        ReflectionTestUtils.setField(regionStrategyGenerator, "llmClient", llmClient);
        ReflectionTestUtils.setField(regionStrategyGenerator, "regionStrategyPrompt", new RegionStrategyPrompt());
        ReflectionTestUtils.setField(worker, "regionStrategyGenerator", regionStrategyGenerator);
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
        when(analysisPrompt.buildPrompt(any(), any(), any(), any(), any())).thenReturn("判定提示词");
        ReflectionTestUtils.setField(worker, "geoPromptTemplateService", promptTemplates);
        when(promptTemplates.getCurrentTemplate(PromptTemplateKey.DIAGNOSIS_REPORT_RESULT_JUDGE)).thenReturn(judgeTemplate);
        when(promptTemplates.getTemplate(PromptTemplateKey.DIAGNOSIS_REPORT_RESULT_JUDGE, 2)).thenReturn(judgeTemplate);
        when(promptTemplates.getCurrentTemplate(PromptTemplateKey.DIAGNOSIS_REPORT_DIMENSION))
                .thenReturn(template(1, "合并维度，用{{reportLanguage}}书写：\n{{dimensions}}"));
        when(promptTemplates.getCurrentTemplate(PromptTemplateKey.DIAGNOSIS_REPORT_ADVICE))
                .thenReturn(template(1, "建议 {{target}} {{issues}} 用{{reportLanguage}}书写"));
        when(promptTemplates.getCurrentTemplate(PromptTemplateKey.DIAGNOSIS_REPORT_REGION_STRATEGY))
                .thenReturn(template(1, "策略 {{region}} {{opportunities}} 用{{reportLanguage}}书写"));
    }

    /**
     * @param progress 写入 content_progress 的 JSON
     * @return 按执行顺序，每步写成“key:status”或“key:status:detail”
     */
    private List<String> describe(String progress) {
        return JSON.parseObject(progress).getJSONArray("steps").stream().map(JSONObject.class::cast)
                .map(step -> step.getString("key") + ":" + step.getString("status") + (step.containsKey("detail") ? ":" + step.getString("detail") : ""))
                .toList();
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

    /**
     * @param version 版本号
     * @param content 模板内容
     * @return 一版提示词
     */
    private GeoPromptTemplateEntity template(int version, String content) {
        var template = new GeoPromptTemplateEntity();
        template.setVersion(version);
        template.setContent(content);
        return template;
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
