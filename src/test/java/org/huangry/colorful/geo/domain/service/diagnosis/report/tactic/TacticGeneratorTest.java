package org.huangry.colorful.geo.domain.service.diagnosis.report.tactic;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 优化建议与地域策略：没有问题或机会时不调用模型；调用失败时建议或该地域策略记为失败，不抛出。 */
class TacticGeneratorTest {
    private final LLMClient llmClient = mock(LLMClient.class);
    private final GeoLlmChannelEntity channel = new GeoLlmChannelEntity();
    private final TacticGenerator generator = generator();

    /** 没有规则问题时不调用模型，建议记为没有需要处理的问题。 */
    @Test
    void shouldSkipModelWhenNoIssues() {
        assertEquals("NO_ISSUES", generator.generateAdvice(new JSONArray(), new JSONObject(), channel, 9L, "中文").getString("status"));
        verifyNoInteractions(llmClient);
    }

    /** 模型调用失败：建议标为失败并给出提示。 */
    @Test
    void shouldMarkAdviceFailedWhenModelFails() {
        when(llmClient.query(any(), same(channel))).thenThrow(new LLMClientException("调用失败"));

        JSONObject advice = generator.generateAdvice(JSON.parseArray("[{\"id\":\"P1\"}]"), new JSONObject().fluentPut("name", "团油"), channel, 9L, "中文");
        assertEquals("FAILED", advice.getString("status"));
        assertEquals("优化建议生成失败，可先查看规则发现的问题", advice.getString("errorMessage"));
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

        generator.generateRegionStrategies(regionReport, new JSONObject().fluentPut("name", "Olight"), channel, 9L, "中文");

        JSONArray regions = regionReport.getJSONArray("regions");
        JSONObject usa = regions.getJSONObject(0).getJSONObject("strategy");
        assertEquals("SUCCEEDED", usa.getString("status"));
        assertEquals("进入推荐清单", usa.getJSONArray("mentionPlays").getJSONObject(0).getString("title"));
        assertEquals("FAILED", regions.getJSONObject(1).getJSONObject("strategy").getString("status"));
        assertEquals("NO_OPPORTUNITIES", regions.getJSONObject(2).getJSONObject("strategy").getString("status"));
        verify(llmClient, times(2)).query(any(), same(channel));
    }

    /** @return 使用真实提示词组件与模型替身的建议与策略生成 */
    private TacticGenerator generator() {
        var generator = new TacticGenerator();
        ReflectionTestUtils.setField(generator, "llmClient", llmClient);
        ReflectionTestUtils.setField(generator, "advicePrompt", new AdvicePrompt());
        ReflectionTestUtils.setField(generator, "regionStrategyPrompt", new RegionStrategyPrompt());
        return generator;
    }
}
