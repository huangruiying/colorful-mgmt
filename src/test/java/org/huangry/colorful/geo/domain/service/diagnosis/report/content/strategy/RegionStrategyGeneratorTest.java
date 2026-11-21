package org.huangry.colorful.geo.domain.service.diagnosis.report.content.strategy;

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

/** 地域提升策略：每个有机会的地域调用一次模型；没有机会的地域不调用；某个地域失败只记在该地域上，不抛出。 */
class RegionStrategyGeneratorTest {
    private final LLMClient llmClient = mock(LLMClient.class);
    private final GeoLlmChannelEntity channel = new GeoLlmChannelEntity();
    private final RegionStrategyGenerator generator = generator();
    /** 测试用的地域策略模板，内容不影响生成逻辑。 */
    private static final String STRATEGY_TEMPLATE = "策略 {{region}} {{opportunities}} 用{{reportLanguage}}书写";

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
        assertFalse(RegionStrategyGenerator.isFailed(usa) || RegionStrategyGenerator.isSkipped(usa));
        assertTrue(RegionStrategyGenerator.isFailed(germany) && !RegionStrategyGenerator.isSkipped(germany));
        assertEquals("该地域的提升策略生成失败，可先查看机会清单", germany.getString("errorMessage"));
        assertTrue(RegionStrategyGenerator.isSkipped(japan) && !RegionStrategyGenerator.isFailed(japan));
        verify(llmClient, times(2)).query(any(), same(channel));
    }

    /** 模型输出三类策略都没有有效条目：该地域记为失败，不把空策略当成功。 */
    @Test
    void shouldMarkRegionFailedWhenAllItemsDiscarded() {
        when(llmClient.query(any(), same(channel)))
                .thenReturn(new LLMResponse("{\"brandDirections\":[],\"mentionPlays\":[{\"title\":\"x\",\"steps\":[\"a\"],\"evidenceStatus\":\"INFERRED\",\"opportunityIds\":[\"R9-O1\"]}],\"rankPlays\":[]}", List.of()));
        JSONObject region = JSON.parseObject("{\"name\":\"美国\",\"portrayal\":{},\"opportunities\":[{\"id\":\"R1-O1\"}]}");

        JSONObject strategy = generator.generateRegionStrategy(region, new JSONObject().fluentPut("name", "Olight"), channel, 9L, "中文", STRATEGY_TEMPLATE);

        assertTrue(RegionStrategyGenerator.isFailed(strategy));
    }

    /** @return 使用真实提示词组件与模型替身的地域策略生成器 */
    private RegionStrategyGenerator generator() {
        var generator = new RegionStrategyGenerator();
        ReflectionTestUtils.setField(generator, "llmClient", llmClient);
        ReflectionTestUtils.setField(generator, "regionStrategyPrompt", new RegionStrategyPrompt());
        return generator;
    }
}
