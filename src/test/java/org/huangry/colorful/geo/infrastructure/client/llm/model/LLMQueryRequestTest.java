package org.huangry.colorful.geo.infrastructure.client.llm.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 单次查询输入校验：无效问题拒绝，有效问题保留原文；默认不联网，只有显式要求时才联网。 */
class LLMQueryRequestTest {
    /** 不清理或改写用户问题，保证重复调用使用相同输入。 */
    @Test
    void shouldRejectBlankPromptAndPreserveOriginalText() {
        for (String prompt : new String[]{null, "", " \n "}) {
            assertThrows(IllegalArgumentException.class, () -> new LLMQueryRequest(prompt));
        }
        assertEquals(" 问题\n", new LLMQueryRequest(" 问题\n").prompt());
    }

    /** 制题、判定等内部任务用的构造方式默认不联网，只有诊断执行用的 withWebSearch 打开；联网请求同样拒绝空问题。 */
    @Test
    void shouldDisableWebSearchUnlessRequested() {
        assertFalse(new LLMQueryRequest("问题").enableWebSearch());
        assertFalse(new LLMQueryRequest("问题", "qwen", null).enableWebSearch());
        assertTrue(LLMQueryRequest.withWebSearch("问题").enableWebSearch());
        assertThrows(IllegalArgumentException.class, () -> LLMQueryRequest.withWebSearch(" "));
    }
}
