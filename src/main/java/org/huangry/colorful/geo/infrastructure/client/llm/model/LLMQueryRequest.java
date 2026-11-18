package org.huangry.colorful.geo.infrastructure.client.llm.model;

import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;

/**
 * 一次大模型查询的请求参数：问题、可选的渠道与模型，以及是否联网搜索。
 * <p>每次独立调用，不携带历史回答，重复执行由调用方多次发起。是否联网由调用方声明：只有诊断执行要测模型联网后怎么回答，
 * 用 {@link #withWebSearch(String)} 打开；制题、改写、报告判定等内部任务默认不联网，避免多花搜索时间和费用。</p>
 *
 * @param prompt 本次问题，必填且不能仅包含空白
 * @param channelCode 指定渠道编码，空值时按模型及优先级选择, {@linkplain GeoLlmChannelEntity#getChannelCode()}
 * @param modelName 指定模型标识，空值时使用选中渠道的模型
 * @param enableWebSearch 是否要求联网搜索；只对能开关搜索的策略生效，接口本身就是搜索的渠道不受影响
 * @author huangry
 */
public record LLMQueryRequest(String prompt, String channelCode, String modelName, boolean enableWebSearch) {
    /** 单次问题默认按数据库优先级选择渠道，不联网。 */
    public LLMQueryRequest(String prompt) {
        this(prompt, null, null, false);
    }

    /** 指定渠道或模型的单次问题，不联网。 */
    public LLMQueryRequest(String prompt, String channelCode, String modelName) {
        this(prompt, channelCode, modelName, false);
    }

    /**
     * 要求联网搜索的单次问题，供诊断执行等需要测联网回答的调用使用；渠道由调用方另行指定或按优先级选择。
     * @param prompt 本次问题
     * @return 联网的查询请求
     */
    public static LLMQueryRequest withWebSearch(String prompt) {
        return new LLMQueryRequest(prompt, null, null, true);
    }

    /** 拒绝无效问题，保留原文以保证重复执行输入一致。 */
    public LLMQueryRequest {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("大模型查询问题不能为空");
        }
    }
}
