package org.huangry.colorful.geo.application.diagnosis.model;

import java.util.List;

/**
 * 生成母题的接口入参：按项目资料生成一套母题，页面再逐个地域改写成候选问题。
 * <p>regions 告诉制题模型这批母题要改写到哪些地域，母题需在这些地域都成立；生成渠道用于内部制题，与方案的被测渠道独立。</p>
 *
 * @param projectId   项目 ID，项目资料作为制题背景
 * @param channelCode 可选制题渠道编码，空值按数据库优先级选择
 * @param count       母题数量，1 到 100
 * @param language    母题语言
 * @param regions     本次要改写到的地域，至少一个；同一地域测多种语言时可重复出现
 */
public record GenerateDiagnosisQuestionsRequest(Long projectId, String channelCode, Integer count, String language,
                                                List<String> regions) {}
