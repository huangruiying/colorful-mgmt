package org.huangry.colorful.geo.domain.service.diagnosis.report.llm;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisReportService.ReportAnswer;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultAnalysisEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 报告中需要模型的步骤：没有输入时不调用；调用失败时维度保持原名、建议或该地域策略记为失败，不抛出。 */
class ReportLlmAnalyzerTest {
    private final LLMClient llmClient = mock(LLMClient.class);
    private final GeoLlmChannelEntity channel = new GeoLlmChannelEntity();
    private final ReportLlmAnalyzer llmAnalyzer = llmAnalyzer();

    /** 没有维度、没有问题时不调用模型。 */
    @Test
    void shouldSkipModelWhenNothingToProcess() {
        assertEquals(Map.of(), llmAnalyzer.normalizeDimensions(List.of(), channel, 9L, "中文"));
        assertEquals("NO_ISSUES", llmAnalyzer.generateAdvice(new JSONArray(), new JSONObject(), channel, 9L, "中文").getString("status"));
        verifyNoInteractions(llmClient);
    }

    /** 模型调用失败：维度原样返回，建议标为失败并给出提示。 */
    @Test
    void shouldKeepNamesAndMarkAdviceFailedWhenModelFails() {
        when(llmClient.query(any(), same(channel))).thenThrow(new LLMClientException("调用失败"));

        assertEquals(Map.of("价格偏高", "价格偏高"), llmAnalyzer.normalizeDimensions(answersWithDimension(), channel, 9L, "中文"));
        JSONObject advice = llmAnalyzer.generateAdvice(JSON.parseArray("[{\"id\":\"P1\"}]"), new JSONObject().fluentPut("name", "团油"), channel, 9L, "中文");
        assertEquals("FAILED", advice.getString("status"));
        assertEquals("优化建议生成失败，可先查看规则发现的问题", advice.getString("errorMessage"));
    }

    /** 模型返回归一结果时按映射合并同义维度；归一名称按报告语言书写，调用不联网。 */
    @Test
    void shouldNormalizeDimensionsFromModelOutput() {
        when(llmClient.query(any(), same(channel))).thenReturn(new LLMResponse("{\"价格偏高\":\"Price\"}", List.of()));

        assertEquals(Map.of("价格偏高", "Price"), llmAnalyzer.normalizeDimensions(answersWithDimension(), channel, 9L, "英语"));
        verify(llmClient).query(argThat(request -> request.prompt().contains("用英语书写") && !request.enableWebSearch()), same(channel));
    }

    /** 每个有机会的地域调用一次模型；某个地域失败只影响该地域，没有机会的地域不调用模型。 */
    @Test
    void shouldGenerateStrategyPerRegionAndIsolateFailures() {
        when(llmClient.query(any(), same(channel)))
                .thenReturn(new LLMResponse("{\"mentionPlays\":[{\"title\":\"进入推荐清单\",\"steps\":[\"发测评\"],\"evidenceStatus\":\"INFERRED\",\"opportunityIds\":[\"R1-O1\"]}]}", List.of()))
                .thenThrow(new LLMClientException("调用失败"));
        JSONObject regionReport = JSON.parseObject("""
                {"regions":[{"name":"美国","portrayal":{},"opportunities":[{"id":"R1-O1"}]},
                            {"name":"德国","portrayal":{},"opportunities":[{"id":"R2-O1"}]},
                            {"name":"日本","portrayal":{},"opportunities":[]}]}""");

        llmAnalyzer.generateRegionStrategies(regionReport, new JSONObject().fluentPut("name", "Olight"), channel, 9L, "中文");

        JSONArray regions = regionReport.getJSONArray("regions");
        JSONObject usa = regions.getJSONObject(0).getJSONObject("strategy");
        assertEquals("SUCCEEDED", usa.getString("status"));
        assertEquals("进入推荐清单", usa.getJSONArray("mentionPlays").getJSONObject(0).getString("title"));
        assertEquals("FAILED", regions.getJSONObject(1).getJSONObject("strategy").getString("status"));
        assertEquals("NO_OPPORTUNITIES", regions.getJSONObject(2).getJSONObject("strategy").getString("status"));
        verify(llmClient, times(2)).query(any(), same(channel));
    }

    /** @return 一条判定成功、带“价格偏高”保留意见的回答 */
    private List<ReportAnswer> answersWithDimension() {
        var analysis = new GeoDiagnosisTaskResultAnalysisEntity();
        analysis.setStatus("SUCCEEDED");
        analysis.setPoints("[{\"type\":\"RESERVATION\",\"dimension\":\"价格偏高\",\"quote\":\"贵\"}]");
        return List.of(new ReportAnswer(analysis, new GeoDiagnosisTaskResultEntity()));
    }

    /** @return 使用真实提示词组件与模型替身的分析器 */
    private ReportLlmAnalyzer llmAnalyzer() {
        var analyzer = new ReportLlmAnalyzer();
        ReflectionTestUtils.setField(analyzer, "llmClient", llmClient);
        ReflectionTestUtils.setField(analyzer, "dimensionPrompt", new DimensionPrompt());
        ReflectionTestUtils.setField(analyzer, "advicePrompt", new AdvicePrompt());
        ReflectionTestUtils.setField(analyzer, "regionStrategyPrompt", new RegionStrategyPrompt());
        return analyzer;
    }
}
