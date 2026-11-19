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
    /** 测试用的建议与地域策略模板，内容不影响生成逻辑。 */
    private static final String ADVICE_TEMPLATE = "建议 {{target}} {{issues}} 用{{reportLanguage}}书写";
    private static final String STRATEGY_TEMPLATE = "策略 {{region}} {{opportunities}} 用{{reportLanguage}}书写";

    /** 没有规则问题时不调用模型，建议记为没有需要处理的问题。 */
    @Test
    void shouldSkipModelWhenNoIssues() {
        assertEquals("NO_ISSUES", generator.generateAdvice(new JSONArray(), new JSONObject(), channel, 9L, "中文", ADVICE_TEMPLATE).getString("status"));
        verifyNoInteractions(llmClient);
    }

    /** 模型调用失败：建议标为失败并给出提示。 */
    @Test
    void shouldMarkAdviceFailedWhenModelFails() {
        when(llmClient.query(any(), same(channel))).thenThrow(new LLMClientException("调用失败"));

        JSONObject advice = generator.generateAdvice(JSON.parseArray("[{\"id\":\"P1\"}]"), new JSONObject().fluentPut("name", "团油"), channel, 9L, "中文", ADVICE_TEMPLATE);
        assertEquals("FAILED", advice.getString("status"));
        assertEquals("优化建议生成失败，可先查看规则发现的问题", advice.getString("errorMessage"));
    }

    /** 每个有机会的地域调用一次模型；某个地域失败只影响该地域，没有机会的地域不调用模型，记为无需执行而不是失败。 */
    @Test
    void shouldGenerateStrategyPerRegionAndIsolateFailures() {
        when(llmClient.query(any(), same(channel)))
                .thenReturn(new LLMResponse("{\"mentionPlays\":[{\"title\":\"进入推荐清单\",\"steps\":[\"发测评\"],\"evidenceStatus\":\"INFERRED\",\"opportunityIds\":[\"R1-O1\"]}]}", List.of()))
                .thenThrow(new LLMClientException("调用失败"));
        JSONArray regions = JSON.parseArray("""
                [{"name":"美国","portrayal":{},"opportunities":[{"id":"R1-O1"}]},
                 {"name":"德国","portrayal":{},"opportunities":[{"id":"R2-O1"}]},
                 {"name":"日本","portrayal":{},"opportunities":[]}]""");
        JSONObject project = new JSONObject().fluentPut("name", "Olight");

        JSONObject usa = generator.generateRegionStrategy(regions.getJSONObject(0), project, channel, 9L, "中文", STRATEGY_TEMPLATE);
        JSONObject germany = generator.generateRegionStrategy(regions.getJSONObject(1), project, channel, 9L, "中文", STRATEGY_TEMPLATE);
        JSONObject japan = generator.generateRegionStrategy(regions.getJSONObject(2), project, channel, 9L, "中文", STRATEGY_TEMPLATE);

        assertEquals("SUCCEEDED", usa.getString("status"));
        assertEquals("进入推荐清单", usa.getJSONArray("mentionPlays").getJSONObject(0).getString("title"));
        assertTrue(TacticGenerator.isFailed(germany) && !TacticGenerator.isSkipped(germany));
        assertTrue(TacticGenerator.isSkipped(japan) && !TacticGenerator.isFailed(japan));
        assertFalse(TacticGenerator.isFailed(usa) || TacticGenerator.isSkipped(usa));
        verify(llmClient, times(2)).query(any(), same(channel));
    }

    /** 没有规则问题的建议算无需执行，不算失败。 */
    @Test
    void shouldTreatAdviceWithoutIssuesAsSkipped() {
        JSONObject advice = generator.generateAdvice(new JSONArray(), new JSONObject(), channel, 9L, "中文", ADVICE_TEMPLATE);

        assertTrue(TacticGenerator.isSkipped(advice));
        assertFalse(TacticGenerator.isFailed(advice));
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
