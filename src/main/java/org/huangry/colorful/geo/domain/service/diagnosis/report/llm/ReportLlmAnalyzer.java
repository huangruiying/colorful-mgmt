package org.huangry.colorful.geo.domain.service.diagnosis.report.llm;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService.ReportAnswer;
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

import static org.huangry.colorful.geo.infrastructure.client.llm.tools.LLMJsonOutputParser.listObjects;

/**
 * 诊断报告汇总阶段需要模型参与的步骤：维度归一、优化建议、⑦ 各地域的提升策略；生成报告的后台执行器与补全旧报告共用前两步。
 * <p>这些都不是核心步骤：调用失败或输出无效时不抛出，维度保持原名、建议或该地域策略记为失败，报告其余内容照常保存。
 * 维度归一、优化建议整份报告各调用一次，提升策略每个地域调用一次，都使用报告冻结的判定渠道、不联网，输出按报告语言书写。</p>
 */
@Component
@Slf4j
public class ReportLlmAnalyzer {
    @Resource
    private LLMClient llmClient;
    @Resource
    private DimensionPrompt dimensionPrompt;
    @Resource
    private AdvicePrompt advicePrompt;
    @Resource
    private RegionStrategyPrompt regionStrategyPrompt;

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

    /**
     * 调用一次模型，基于规则发现的问题写出主要发现与优化建议；没有问题时不调用。
     * @param issues 规则发现的问题
     * @param project 项目资料
     * @param channel 判定渠道
     * @param reportId 报告 ID，仅用于日志
     * @param reportLanguage 报告语言，发现与建议按此语言书写
     * @return advice 对象：status 为 SUCCEEDED（含 findings、actions）、NO_ISSUES（没有需要建议的问题）或 FAILED（含 errorMessage）
     */
    public JSONObject generateAdvice(JSONArray issues, JSONObject project, GeoLlmChannelEntity channel, long reportId,
                                     String reportLanguage) {
        if (issues.isEmpty()) {
            return new JSONObject(true).fluentPut("status", "NO_ISSUES");
        }
        try {
            String output = llmClient.query(new LLMQueryRequest(advicePrompt.buildPrompt(project, issues, reportLanguage)), channel).getContent();
            return advicePrompt.parseAdvice(output, issues).fluentPut("status", "SUCCEEDED");
        } catch (LLMClientException | IllegalArgumentException exception) {
            // 建议只是对规则问题的归纳，失败时页面仍能展示规则发现的问题与证据
            log.warn("诊断报告优化建议生成失败 reportId={}, issues={}, errorType={}", reportId, issues.size(), exception.getClass().getSimpleName());
            return new JSONObject(true).fluentPut("status", "FAILED").fluentPut("errorMessage", "优化建议生成失败，可先查看规则发现的问题");
        }
    }

    /**
     * 为 ⑦ 地域报告的每个地域生成提升策略，写入该地域的 strategy；地域之间互不影响。
     * @param regionReport 汇总中的 region 部分，regions 中每个地域带 opportunities
     * @param project 项目资料
     * @param channel 判定渠道
     * @param reportId 报告 ID，仅用于日志
     * @param reportLanguage 报告语言，策略按此语言书写
     */
    public void generateRegionStrategies(JSONObject regionReport, JSONObject project, GeoLlmChannelEntity channel, long reportId,
                                         String reportLanguage) {
        for (JSONObject region : listObjects(regionReport.get("regions"))) {
            region.put("strategy", generateRegionStrategy(region, project, channel, reportId, reportLanguage));
        }
    }

    /**
     * 调用一次模型，基于一个地域的机会清单写品牌优化方向与两类打法；没有机会时不调用。
     * @param region 一个地域的 ⑦ 内容
     * @param project 项目资料
     * @param channel 判定渠道
     * @param reportId 报告 ID，仅用于日志
     * @param reportLanguage 报告语言，策略按此语言书写
     * @return strategy 对象：status 为 SUCCEEDED（含 brandDirections、mentionPlays、rankPlays）、
     *         NO_OPPORTUNITIES（没有机会，未调用模型）或 FAILED（含 errorMessage）
     */
    private JSONObject generateRegionStrategy(JSONObject region, JSONObject project, GeoLlmChannelEntity channel, long reportId,
                                              String reportLanguage) {
        JSONArray opportunities = region.getJSONArray("opportunities");
        // 没有机会就没有可引用的依据，不调用模型，避免产生费用
        if (opportunities.isEmpty()) {
            return new JSONObject(true).fluentPut("status", "NO_OPPORTUNITIES");
        }
        try {
            String output = llmClient.query(new LLMQueryRequest(regionStrategyPrompt.buildPrompt(project, region, reportLanguage)), channel).getContent();
            return regionStrategyPrompt.parseStrategy(output, opportunities).fluentPut("status", "SUCCEEDED");
        } catch (LLMClientException | IllegalArgumentException exception) {
            // 策略只是对机会清单的归纳，失败时页面仍能展示该地域的宣传状态、可见度与机会
            log.warn("诊断报告地域策略生成失败 reportId={}, region={}, opportunities={}, errorType={}", reportId, region.getString("name"),
                    opportunities.size(), exception.getClass().getSimpleName());
            return new JSONObject(true).fluentPut("status", "FAILED").fluentPut("errorMessage", "该地域的提升策略生成失败，可先查看机会清单");
        }
    }
}
