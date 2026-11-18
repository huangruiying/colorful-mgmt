package org.huangry.colorful.geo.domain.service.diagnosis.report.dimension;

import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisReportService.ReportAnswer;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportResultAnalysisEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 维度归一：没有维度时不调用模型；调用失败时维度保持原名、不抛出；模型返回映射时按映射合并。 */
class DimensionNormalizerTest {
    private final LLMClient llmClient = mock(LLMClient.class);
    private final GeoLlmChannelEntity channel = new GeoLlmChannelEntity();
    private final DimensionNormalizer normalizer = normalizer();

    /** 没有维度时不调用模型。 */
    @Test
    void shouldSkipModelWhenNoDimensions() {
        assertEquals(Map.of(), normalizer.normalizeDimensions(List.of(), channel, 9L, "中文"));
        verifyNoInteractions(llmClient);
    }

    /** 模型调用失败：维度原样返回。 */
    @Test
    void shouldKeepOriginalNamesWhenModelFails() {
        when(llmClient.query(any(), same(channel))).thenThrow(new LLMClientException("调用失败"));

        assertEquals(Map.of("价格偏高", "价格偏高"), normalizer.normalizeDimensions(answersWithDimension(), channel, 9L, "中文"));
    }

    /** 模型返回归一结果时按映射合并同义维度；归一名称按报告语言书写，调用不联网。 */
    @Test
    void shouldNormalizeDimensionsFromModelOutput() {
        when(llmClient.query(any(), same(channel))).thenReturn(new LLMResponse("{\"价格偏高\":\"Price\"}", List.of()));

        assertEquals(Map.of("价格偏高", "Price"), normalizer.normalizeDimensions(answersWithDimension(), channel, 9L, "英语"));
        verify(llmClient).query(argThat(request -> request.prompt().contains("用英语书写") && !request.enableWebSearch()), same(channel));
    }

    /** @return 一条判定成功、带“价格偏高”保留意见的回答 */
    private List<ReportAnswer> answersWithDimension() {
        var analysis = new GeoDiagnosisReportResultAnalysisEntity();
        analysis.setStatus("SUCCEEDED");
        analysis.setPoints("[{\"type\":\"RESERVATION\",\"dimension\":\"价格偏高\",\"quote\":\"贵\"}]");
        return List.of(new ReportAnswer(analysis, new GeoDiagnosisTaskResultEntity()));
    }

    /** @return 使用真实提示词组件与模型替身的维度归一 */
    private DimensionNormalizer normalizer() {
        var normalizer = new DimensionNormalizer();
        ReflectionTestUtils.setField(normalizer, "llmClient", llmClient);
        ReflectionTestUtils.setField(normalizer, "dimensionPrompt", new DimensionPrompt());
        return normalizer;
    }
}
