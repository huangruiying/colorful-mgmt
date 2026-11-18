package org.huangry.colorful.geo.application.diagnosis.model;

import java.util.List;

/**
 * 按地域改写问题的接口入参：把一批母题改写成某个地域的用户会问的问题。
 * <p>questions 沿用诊断问题结构，只读取 text、intent、brandScope；language 为空表示用该地域的常用语言，由制题模型给出。</p>
 *
 * @param projectId   项目 ID，项目资料作为改写背景
 * @param channelCode 可选制题渠道编码，空值按数据库优先级选择
 * @param region      目标地域，如美国、东南亚
 * @param language    问题语言，可为空
 * @param questions   母题
 */
public record LocalizeDiagnosisQuestionsRequest(Long projectId, String channelCode, String region, String language,
                                                List<DiagnosisQuestion> questions) {}
