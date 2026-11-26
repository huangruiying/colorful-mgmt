package org.huangry.colorful.geo.domain.service.diagnosis.report.content.dimension;

import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisReportService.ReportAnswer;
import org.huangry.colorful.geo.domain.service.prompt.PromptTemplateRenderer;
import org.huangry.colorful.geo.infrastructure.client.llm.tools.LLMJsonOutputParser;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.huangry.colorful.geo.domain.service.diagnosis.report.content.summary.ReportMetrics.listPoints;

/**
 * 诊断报告维度归一提示词：收集各回答观点里的维度名，填进“合并同义维度”的提示词模板，并解析模型返回的映射；本类不调用模型。
 * <p>例如“价格偏高”“收费贵”合并为“价格”，排行时才不会被不同写法拆散。整份报告调用一次，调用由 DimensionNormalizer 完成；
 * 模板在「提示词管理」维护，由执行器取出当前版本后传入，本类不访问数据库。</p>
 */
@Component
public class DimensionPrompt {
    /** 需要按维度合并同义写法的观点类型：选择标准、顾虑、保留意见、推荐理由、正负面点、卖点、对比维度。 */
    private static final Set<String> DIMENSION_POINT_TYPES = Set.of("SELECTION_CRITERION", "USER_CONCERN", "RESERVATION",
            "RECOMMEND_REASON", "POSITIVE", "NEGATIVE", "SELLING_POINT_COVERED", "DIMENSION_RESULT");

    /**
     * 收集需要归一的维度名；报告页按维度排行的观点类型都在 DIMENSION_POINT_TYPES 中。
     * @param answers 报告的全部回答
     * @return 去重后的维度名，保持首次出现顺序
     */
    public Set<String> collectDimensions(List<ReportAnswer> answers) {
        Set<String> dimensions = new LinkedHashSet<>();
        for (ReportAnswer answer : answers) {
            for (JSONObject point : listPoints(answer)) {
                if (DIMENSION_POINT_TYPES.contains(point.getString("type")) && point.getString("dimension") != null) {
                    dimensions.add(point.getString("dimension"));
                }
            }
        }
        return dimensions;
    }

    /**
     * 组装维度归一提示词：把报告语言和待合并的维度名（每行一个）填进模板，模型只输出“原名称 → 归一名称”的 JSON。
     * @param template 合并同义维度提示词模板，取自提示词管理的当前版本
     * @param dimensions 待归一的维度名
     * @param reportLanguage 报告语言，归一名称按此语言书写
     * @return 提示词
     */
    public String buildPrompt(String template, Collection<String> dimensions, String reportLanguage) {
        // 取值与模板变量一一对应（见 PromptTemplateKey.DIAGNOSIS_REPORT_DIMENSION）
        return PromptTemplateRenderer.render(template, Map.of("reportLanguage", reportLanguage, "dimensions", String.join("\n", dimensions)));
    }

    /**
     * 解析维度归一结果；整段输出无法解析时抛出，由调用方记为归一失败。
     * @param output 模型正文
     * @param dimensions 待归一的维度名
     * @return 原名称到归一名称的映射，覆盖全部输入名称
     * @throws IllegalArgumentException 正文里没有可解析的 JSON 对象
     */
    public Map<String, String> parseDimensionMapping(String output, Collection<String> dimensions) {
        JSONObject parsed = LLMJsonOutputParser.extractObject(output, "维度归一结果");
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String dimension : dimensions) {
            String normalized = parsed.getString(dimension);
            // 模型漏掉或给空值的维度保持原名，排行照常计算，只是该维度不与同义写法合并
            mapping.put(dimension, normalized == null || normalized.isBlank() ? dimension : normalized.strip());
        }
        return mapping;
    }
}
