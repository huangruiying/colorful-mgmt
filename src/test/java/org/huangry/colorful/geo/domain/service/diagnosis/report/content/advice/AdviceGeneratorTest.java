package org.huangry.colorful.geo.domain.service.diagnosis.report.content.advice;

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

/** 优化建议：没有问题时不调用模型；调用失败或输出没有有效条目时记为失败，不抛出。 */
class AdviceGeneratorTest {
    private final LLMClient llmClient = mock(LLMClient.class);
    private final GeoLlmChannelEntity channel = new GeoLlmChannelEntity();
    private final AdviceGenerator generator = generator();
    /** 测试用的建议模板，内容不影响生成逻辑。 */
    private static final String ADVICE_TEMPLATE = "建议 {{target}} {{issues}} 用{{reportLanguage}}书写";

    /** 没有规则问题时不调用模型，建议记为没有需要处理的问题：算无需执行，不算失败。 */
    @Test
    void shouldSkipModelWhenNoIssues() {
        JSONObject advice = generator.generateAdvice(new JSONArray(), new JSONObject(), channel, 9L, "中文", ADVICE_TEMPLATE);

        assertEquals("NO_ISSUES", advice.getString("status"));
        assertTrue(AdviceGenerator.isSkipped(advice));
        assertFalse(AdviceGenerator.isFailed(advice));
        verifyNoInteractions(llmClient);
    }

    /** 模型输出有效：建议记为成功，既不算失败也不算跳过。 */
    @Test
    void shouldMarkAdviceSucceededWhenOutputHasUsableItems() {
        when(llmClient.query(any(), same(channel)))
                .thenReturn(new LLMResponse("{\"findings\":[{\"content\":\"提及率低\",\"issueIds\":[\"P1\"]}],\"actions\":[]}", List.of()));

        JSONObject advice = generator.generateAdvice(JSON.parseArray("[{\"id\":\"P1\"}]"), new JSONObject().fluentPut("name", "团油"), channel, 9L, "中文", ADVICE_TEMPLATE);

        assertEquals("SUCCEEDED", advice.getString("status"));
        assertEquals(1, advice.getJSONArray("findings").size());
        assertFalse(AdviceGenerator.isFailed(advice) || AdviceGenerator.isSkipped(advice));
    }

    /** 模型调用失败：建议标为失败并给出提示。 */
    @Test
    void shouldMarkAdviceFailedWhenModelFails() {
        when(llmClient.query(any(), same(channel))).thenThrow(new LLMClientException("调用失败"));

        JSONObject advice = generator.generateAdvice(JSON.parseArray("[{\"id\":\"P1\"}]"), new JSONObject().fluentPut("name", "团油"), channel, 9L, "中文", ADVICE_TEMPLATE);

        assertTrue(AdviceGenerator.isFailed(advice));
        assertEquals("优化建议生成失败，可先查看规则发现的问题", advice.getString("errorMessage"));
    }

    /** 模型输出的发现与建议全被校验丢弃：建议记为失败而不是空的成功，与地域策略口径一致。 */
    @Test
    void shouldMarkAdviceFailedWhenAllItemsDiscarded() {
        when(llmClient.query(any(), same(channel)))
                .thenReturn(new LLMResponse("{\"findings\":[{\"content\":\"编造\",\"issueIds\":[\"P9\"]}],\"actions\":[]}", List.of()));

        JSONObject advice = generator.generateAdvice(JSON.parseArray("[{\"id\":\"P1\"}]"), new JSONObject().fluentPut("name", "团油"), channel, 9L, "中文", ADVICE_TEMPLATE);

        assertTrue(AdviceGenerator.isFailed(advice));
        assertEquals("优化建议生成失败，可先查看规则发现的问题", advice.getString("errorMessage"));
    }

    /** @return 使用真实提示词组件与模型替身的建议生成器 */
    private AdviceGenerator generator() {
        var generator = new AdviceGenerator();
        ReflectionTestUtils.setField(generator, "llmClient", llmClient);
        ReflectionTestUtils.setField(generator, "advicePrompt", new AdvicePrompt());
        return generator;
    }
}
