package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 验证独立策略仍只发送既有参数和工具，不扩大搜索能力或携带历史上下文。 */
class LLMStrategyRequestContractTest {
    /** 通用 Chat 不注入厂商工具，可选生成参数仍按用户是否填写决定发送。 */
    @Test
    void shouldKeepGenericChatIndependentOfVendorSearch() {
        var channel = channel();
        var strategy = new ChatCompletionsApiCommonStrategy();
        var body = strategy.buildRequestBody(new LLMQueryRequest("问题"), channel);
        assertEquals(List.of(Map.of("role", "user", "content", "问题")), body.get("messages"));
        assertEquals(0.2, body.get("temperature"));
        assertEquals(300, body.get("max_tokens"));
        assertFalse(body.containsKey("tools"));
        assertFalse(body.containsKey("web_search_options"));
        channel.setTemperature(null);
        channel.setMaxTokens(null);
        var optional = strategy.buildRequestBody(new LLMQueryRequest("问题"), channel);
        assertFalse(optional.containsKey("temperature"));
        assertFalse(optional.containsKey("max_tokens"));
    }

    /** OpenAI Chat 的既有搜索例外只匹配精确模型名，普通模型参数不受影响；搜索专用模型本身即搜索，不受请求的联网开关控制。 */
    @Test
    void shouldKeepOpenAiChatSearchLimitedToExistingModel() {
        var channel = channel();
        var strategy = new ChatCompletionsApiOpenAiStrategy();
        var normal = strategy.buildRequestBody(new LLMQueryRequest("问题"), channel);
        assertFalse(normal.containsKey("web_search_options"));
        assertEquals(0.2, normal.get("temperature"));
        channel.setModelName("gpt-5-search-api");
        var search = strategy.buildRequestBody(new LLMQueryRequest("问题"), channel);
        assertEquals(Map.of(), search.get("web_search_options"));
        assertFalse(search.containsKey("temperature"));
        assertFalse(search.containsKey("max_tokens"));
    }

    /** 要求联网时，通用、OpenAI 和豆包的 Responses 请求工具互不串用，基础输入和可选参数保持一致。 */
    @Test
    void shouldKeepResponsesToolsSpecificToEachStrategy() {
        var channel = channel();
        var request = LLMQueryRequest.withWebSearch("独立问题");
        var generic = new ResponsesApiCommonStrategy().buildRequestBody(request, channel);
        var openAi = new ResponsesApiOpenAiStrategy().buildRequestBody(request, channel);
        var doubao = new ResponsesApiDoubaoStrategy().buildRequestBody(request, channel);
        assertFalse(generic.containsKey("tools"));
        assertFalse(generic.containsKey("tool_choice"));
        assertEquals(List.of(Map.of("type", "web_search")), openAi.get("tools"));
        assertEquals(List.of(Map.of("type", "web_search", "sources", List.of("doubao"))), doubao.get("tools"));
        assertEquals("required", openAi.get("tool_choice"));
        assertEquals("required", doubao.get("tool_choice"));
        for (var body : List.of(generic, openAi, doubao)) {
            assertEquals(List.of(Map.of("role", "user", "content",
                    List.of(Map.of("type", "input_text", "text", "独立问题")))), body.get("input"));
            assertEquals(0.2, body.get("temperature"));
            assertEquals(300, body.get("max_output_tokens"));
            assertEquals(false, body.get("store"));
            assertEquals(body == doubao ? Boolean.TRUE : null, body.get("stream"));
        }
    }

    /**
     * 只有请求要求联网时才挂载搜索参数：诊断执行带 tools，制题、判定等内部任务的请求体不带 tools 与 tool_choice；
     * 流式读取与是否联网无关。
     */
    @Test
    void shouldMountWebSearchOnlyWhenRequested() {
        var channel = channel();
        List<AbstractResponsesApiStrategy> searchStrategies = List.of(new ResponsesApiOpenAiStrategy(), new ResponsesApiDoubaoStrategy(),
                new ResponsesApiDeepSeekStrategy(), new ResponsesApiZhipuStrategy(), new ResponsesApiQwenStrategy());
        for (var strategy : searchStrategies) {
            var internal = strategy.buildRequestBody(new LLMQueryRequest("内部任务"), channel);
            var diagnosis = strategy.buildRequestBody(LLMQueryRequest.withWebSearch("诊断问题"), channel);
            String name = strategy.getClass().getSimpleName();
            assertFalse(internal.containsKey("tools"), name);
            assertFalse(internal.containsKey("tool_choice"), name);
            assertTrue(diagnosis.containsKey("tools"), name);
            assertEquals(diagnosis.get("stream"), internal.get("stream"), name);
        }
    }

    /**
     * 构造用于请求契约检查的渠道，不连接实际平台。
     * @return 含已填写生成参数的渠道
     */
    private GeoLlmChannelEntity channel() {
        var channel = new GeoLlmChannelEntity();
        channel.setModelName("test-model");
        channel.setTemperature(0.2);
        channel.setMaxTokens(300);
        return channel;
    }
}
