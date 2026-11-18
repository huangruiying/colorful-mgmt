package org.huangry.colorful.geo.domain.service.diagnosis.report.dimension;

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
 * 诊断报告的维度归一：把各回答观点里意思相同、写法不同的维度名合并成统一名称，供统计排行使用。
 * <p>例如“价格偏高”“收费贵”合并为“价格”。生成报告的后台执行器与补全旧报告都在统计前调用一次；提示词组装与输出解析由
 * DimensionPrompt 负责，本类调用模型。不是核心步骤：调用失败或输出无效时不抛出，维度保持原名，报告照常生成。
 * 使用报告冻结的判定渠道、不联网，归一后的名称按报告语言书写。</p>
 */
@Component
@Slf4j
public class DimensionNormalizer {
    @Resource
    private LLMClient llmClient;
    @Resource
    private DimensionPrompt dimensionPrompt;

    /**
     * 收集报告各回答观点里的维度名，调用一次模型把同义维度合并为统一名称；失败时维度保持原名。
     * @param answers 报告的全部回答
     * @param channel 判定渠道
     * @param reportId 报告 ID，仅用于日志
     * @param reportLanguage 报告语言，归一后的名称按此语言书写
     * @return 原名称到归一名称的映射；没有维度时为空映射
     */
    public Map<String, String> normalizeDimensions(List<ReportAnswer> answers, GeoLlmChannelEntity channel, long reportId,
                                                   String reportLanguage) {
        Set<String> dimensions = dimensionPrompt.collectDimensions(answers);
        if (dimensions.isEmpty()) {
            return Map.of();
        }
        try {
            String output = llmClient.query(new LLMQueryRequest(dimensionPrompt.buildPrompt(dimensions, reportLanguage)), channel).getContent();
            return dimensionPrompt.parseDimensionMapping(output, dimensions);
        } catch (LLMClientException exception) {
            log.warn("诊断报告维度归一失败，保持原名 reportId={}, dimensions={}", reportId, dimensions.size());
            return dimensions.stream().collect(Collectors.toMap(Function.identity(), Function.identity()));
        }
    }
}
