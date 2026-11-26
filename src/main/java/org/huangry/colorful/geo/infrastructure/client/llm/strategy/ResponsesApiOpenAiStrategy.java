package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.List;

/**
 * OpenAI Responses 问答策略：请求要求联网时挂载 web_search 工具并要求必须调用。
 * 搜索由协议父类发出的请求在服务端执行，工具是否可用由实际模型和账号权限决定。
 * 渠道选择由路由器负责，HTTP 超时及连接资源由共用客户端管理。
 */
@Component
public class ResponsesApiOpenAiStrategy extends AbstractResponsesApiStrategy {
    /**
     * 声明配置页面展示的策略名称。
     * @return OpenAI
     */
    @Override
    public String displayName() {
        return "OpenAI";
    }

    /**
     * 声明 OpenAI 平台的 API Key 管理页，供配置页面引导获取密钥。
     * @return OpenAI 平台 API Key 管理页地址
     */
    @Override
    public String apiKeyConsoleUrl() {
        return "https://platform.openai.com/api-keys";
    }

    /**
     * 挂载 OpenAI 服务端搜索并要求必须调用，保证诊断结果均为联网回答；只在请求要求联网时由父类调用。
     * @param body 已含基础字段的请求体
     */
    @Override
    protected void addWebSearchParams(Map<String, Object> body) {
        body.put("tools", List.of(Map.of("type", "web_search")));
        body.put("tool_choice", "required");
    }
}
