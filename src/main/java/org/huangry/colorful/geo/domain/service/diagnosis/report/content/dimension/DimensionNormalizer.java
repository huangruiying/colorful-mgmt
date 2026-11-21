package org.huangry.colorful.geo.domain.service.diagnosis.report.content.dimension;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisReportService.ReportAnswer;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * 诊断报告的维度归一：把各回答观点里意思相同、写法不同的维度名合并成统一名称，供统计排行使用
 *
 * 负责：
 * - 汇总报告各回答观点里的维度名，没有维度时不调用模型
 * - 调用一次模型，把同义写法合并为统一名称，如“价格偏高”“收费贵”合并为“价格”
 * - 给出归一结果：原名称到统一名称的映射，以及是否失败
 *
 * 核心入口：
 * - normalizeDimensions(...)：后台执行器在报告第 2 段统计之前调用一次
 *
 * 约束：
 * - 使用报告冻结的判定渠道、不联网，归一后的名称按报告语言书写；提示词模板由执行器在第 2 段开始时从「提示词管理」取出当前版本后传入
 * - 模型调用失败或输出无法解析时不抛异常：维度保持原名、结果标记为失败，统计照常进行
 *
 * 不负责：
 * - 维度名的收集规则、提示词填充与输出解析（DimensionPrompt）
 * - 保存结果、决定是否因失败暂停报告（DiagnosisReportWorker）
 * </pre>
 */
@Component
@Slf4j
public class DimensionNormalizer {
    @Resource
    private LLMClient llmClient;
    @Resource
    private DimensionPrompt dimensionPrompt;

    /**
     * 合并报告里的同义维度名：从各回答判定出的观点收集维度名，调用一次模型把同义写法合并为统一名称；失败时维度保持原名并标记失败。
     * @param answers 报告的全部回答（分析记录与诊断结果），维度名取自判定出的观点
     * @param channel 报告冻结的判定渠道
     * @param reportId 诊断报告 ID，仅用于日志
     * @param reportLanguage 报告语言，归一后的名称按此语言书写
     * @param promptTemplate 合并同义维度提示词模板，执行器在第 2 段开始时取出的当前版本
     * @return 归一结果：mapping 覆盖全部收集到的维度（模型漏掉或给空值的保持原名）；没有维度时为空映射，不调用模型也不算失败；
     *         模型调用失败或输出无法解析时每个维度映射到自身、failed 为 true，不抛异常
     */
    public DimensionNormalization normalizeDimensions(List<ReportAnswer> answers, GeoLlmChannelEntity channel, long reportId,
                                                      String reportLanguage, String promptTemplate) {
        // 1. 从各回答的观点里收集维度名；没有维度时不调用模型，返回空映射且不算失败
        Set<String> dimensions = dimensionPrompt.collectDimensions(answers);
        if (dimensions.isEmpty()) {
            return new DimensionNormalization(Map.of(), false);
        }
        try {
            // 2. 把维度名填进当前版本的模板，用判定渠道调用一次模型，不联网
            String prompt = dimensionPrompt.buildPrompt(promptTemplate, dimensions, reportLanguage);
            String output = llmClient.query(new LLMQueryRequest(prompt), channel).getContent();
            // 3. 解析原名称到归一名称的映射，模型漏掉或给空值的维度保持原名
            return new DimensionNormalization(dimensionPrompt.parseDimensionMapping(output, dimensions), false);
        } catch (LLMClientException | IllegalArgumentException exception) {
            // 4. 调用失败（LLMClientException）或输出里没有可解析的 JSON（IllegalArgumentException）时同义维度不合并：
            //    每个维度映射到自身、记为失败，排行照常计算；是否因此暂停报告由执行器决定
            log.warn("诊断报告维度归一失败，保持原名 reportId={}, dimensions={}, errorType={}", reportId, dimensions.size(),
                    exception.getClass().getSimpleName());
            return new DimensionNormalization(dimensions.stream().collect(Collectors.toMap(Function.identity(), Function.identity())), true);
        }
    }

    /**
     * 一次维度归一的结果。
     * @param mapping 原名称到归一名称的映射，覆盖全部收集到的维度；失败时每个维度映射到自身
     * @param failed 模型调用失败或输出无法解析，同义维度没有合并
     */
    public record DimensionNormalization(Map<String, String> mapping, boolean failed) {
    }
}
