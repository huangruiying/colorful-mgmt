package org.huangry.colorful.geo.domain.service.diagnosis.report.tactic;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 生成诊断报告的优化建议和 ⑦ 各地域的提升策略：把统计出的规则问题、地域机会交给模型，写成“接下来怎么做”的文字。
 * <p>后台执行器在第 2 段统计之后调用：建议整份报告调用一次，策略由执行器逐个地域调用，以便逐个地域显示进度。
 * 提示词组装与输出校验由 AdvicePrompt、RegionStrategyPrompt 负责，本类调用模型。调用失败或输出无效时不抛出，
 * 建议或该地域策略标记为生成失败，报告其余内容照常生成；是否因此暂停报告由执行器决定。
 * 都使用报告冻结的判定渠道、不联网，输出按报告语言书写。</p>
 */
@Component
@Slf4j
public class TacticGenerator {
    /** 建议或地域策略生成失败时的状态值，页面按它显示生成失败。 */
    private static final String FAILED_STATUS = "FAILED";
    /** 没有可依据的问题或机会、没有调用模型时的状态值：建议为 NO_ISSUES，地域策略为 NO_OPPORTUNITIES。 */
    private static final Set<String> SKIPPED_STATUSES = Set.of("NO_ISSUES", "NO_OPPORTUNITIES");

    @Resource
    private LLMClient llmClient;
    @Resource
    private AdvicePrompt advicePrompt;
    @Resource
    private RegionStrategyPrompt regionStrategyPrompt;

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
            return new JSONObject(true).fluentPut("status", FAILED_STATUS).fluentPut("errorMessage", "优化建议生成失败，可先查看规则发现的问题");
        }
    }

    /**
     * 判断一份优化建议或一个地域的提升策略是否生成失败。
     * @param tactic generateAdvice 返回的 advice，或 generateRegionStrategy 返回的 strategy
     * @return 状态为 FAILED 时为 true；没有问题、没有机会而未调用模型的不算失败
     */
    public static boolean isFailed(JSONObject tactic) {
        return FAILED_STATUS.equals(tactic.getString("status"));
    }

    /**
     * 判断一份优化建议或一个地域的提升策略是否因为没有可依据的内容而没有调用模型。
     * @param tactic generateAdvice 返回的 advice，或 generateRegionStrategy 返回的 strategy
     * @return 规则没有发现问题（NO_ISSUES）或地域没有机会（NO_OPPORTUNITIES）时为 true
     */
    public static boolean isSkipped(JSONObject tactic) {
        return SKIPPED_STATUSES.contains(tactic.getString("status"));
    }

    /**
     * 为 ⑦ 的一个地域调用一次模型，基于该地域的机会清单写品牌优化方向与两类打法；没有机会时不调用。
     * 执行器逐个地域调用并写入该地域的 strategy，某个地域失败不影响其他地域。
     * @param region 一个地域的 ⑦ 内容，带 opportunities
     * @param project 项目资料
     * @param channel 判定渠道
     * @param reportId 报告 ID，仅用于日志
     * @param reportLanguage 报告语言，策略按此语言书写
     * @return strategy 对象：status 为 SUCCEEDED（含 brandDirections、mentionPlays、rankPlays）、
     *         NO_OPPORTUNITIES（没有机会，未调用模型）或 FAILED（含 errorMessage）
     */
    public JSONObject generateRegionStrategy(JSONObject region, JSONObject project, GeoLlmChannelEntity channel, long reportId,
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
            return new JSONObject(true).fluentPut("status", FAILED_STATUS).fluentPut("errorMessage", "该地域的提升策略生成失败，可先查看机会清单");
        }
    }
}
