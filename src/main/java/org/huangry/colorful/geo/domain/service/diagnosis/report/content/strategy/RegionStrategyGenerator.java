package org.huangry.colorful.geo.domain.service.diagnosis.report.content.strategy;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.springframework.stereotype.Component;

/**
 * <pre>
 * 诊断报告的地域提升策略生成器（第 2 段第 4 步「写地域策略」，页面 ⑦）
 *
 * 负责：
 * - 根据一个地域的机会清单，调用一次模型写该地域的品牌优化方向和两类打法
 * - 给出结果状态：成功、没有机会而跳过（NO_OPPORTUNITIES）、生成失败（FAILED）
 *
 * 核心入口：
 * - generateRegionStrategy(...)：执行器逐个地域调用，以便页面逐个地域显示进度
 * - isFailed(...)、isSkipped(...)：执行器据此统计失败的地域和实际写出策略的地域数
 *
 * 约束：
 * - 使用报告冻结的判定渠道、不联网，按报告语言书写；提示词模板由执行器在第 2 段开始时从「提示词管理」取出当前版本后传入
 * - 模型调用失败或输出无效时不抛异常，只把该地域记为 FAILED，其他地域和报告其余内容照常生成
 *
 * 不负责：
 * - 提示词填充与模型输出校验（RegionStrategyPrompt）
 * - 整份报告的优化建议（AdviceGenerator）
 * - 保存结果、决定是否因失败暂停报告（DiagnosisReportWorker）
 * </pre>
 */
@Component
@Slf4j
public class RegionStrategyGenerator {
    /** 地域策略生成失败时的状态值，页面按它显示生成失败。 */
    private static final String FAILED_STATUS = "FAILED";
    /** 该地域没有机会、没有调用模型时的状态值。 */
    private static final String NO_OPPORTUNITIES_STATUS = "NO_OPPORTUNITIES";

    @Resource
    private LLMClient llmClient;
    @Resource
    private RegionStrategyPrompt regionStrategyPrompt;

    /**
     * 为地域报告（报告页 ⑦）中的一个地域生成提升策略：把该地域的机会清单交给模型，写出品牌优化方向和两类打法——
     * 从未点名到正向点名（mentionPlays）、已正向点名后提升排名（rankPlays）；每个有机会的地域调用一次，没有机会时不调用模型。
     * <p>执行器逐个地域调用并把结果写入该地域的 strategy，以便逐个地域显示进度；某个地域失败不影响其他地域。</p>
     * @param region 地域报告中的一个地域：name、sample、portrayal、visibility 与 opportunities（机会清单，每条有编号）
     * @param project 任务快照中的项目资料，提示词取名称、核心卖点与预设竞品
     * @param channel 报告冻结的判定渠道
     * @param reportId 诊断报告 ID，仅用于日志
     * @param reportLanguage 报告语言，策略按此语言书写
     * @param promptTemplate 地域策略提示词模板，执行器在第 2 段开始时取出的当前版本
     * @return strategy 对象，status 为 SUCCEEDED（含 brandDirections、mentionPlays、rankPlays，至少一类有有效条目）、
     *         NO_OPPORTUNITIES（没有机会，未调用模型）或 FAILED（模型调用失败、输出不是有效 JSON 或三类都没有有效条目，含 errorMessage）；
     *         这些失败不抛异常
     */
    public JSONObject generateRegionStrategy(JSONObject region, JSONObject project, GeoLlmChannelEntity channel, long reportId,
                                             String reportLanguage, String promptTemplate) {
        // 1. 该地域没有机会时不调用模型：没有可引用的依据，避免产生费用，记为 NO_OPPORTUNITIES，不算失败
        JSONArray opportunities = region.getJSONArray("opportunities");
        if (opportunities.isEmpty()) {
            return new JSONObject(true).fluentPut("status", NO_OPPORTUNITIES_STATUS);
        }
        try {
            // 2. 把该地域的宣传状态、可见度与机会清单填进当前版本的模板，用判定渠道调用一次模型，不联网
            String prompt = regionStrategyPrompt.buildPrompt(promptTemplate, project, region, reportLanguage);
            String output = llmClient.query(new LLMQueryRequest(prompt), channel).getContent();
            // 3. 只保留依据了已知机会编号、证据状态合法的条目（打法还须有操作步骤）；三类全被丢弃时 parseStrategy 抛异常，按失败处理
            return regionStrategyPrompt.parseStrategy(output, opportunities).fluentPut("status", "SUCCEEDED");
        } catch (LLMClientException | IllegalArgumentException exception) {
            // 4. 调用失败（LLMClientException），或输出不是有效 JSON、没有有效条目（IllegalArgumentException）记为该地域生成失败；
            //    策略只是对机会清单的归纳，失败时页面仍能展示该地域的宣传状态、可见度与机会
            log.warn("诊断报告地域策略生成失败 reportId={}, region={}, opportunities={}, errorType={}", reportId, region.getString("name"),
                    opportunities.size(), exception.getClass().getSimpleName());
            return new JSONObject(true).fluentPut("status", FAILED_STATUS).fluentPut("errorMessage", "该地域的提升策略生成失败，可先查看机会清单");
        }
    }

    /**
     * 判断一个地域的提升策略是否生成失败。
     * @param strategy generateRegionStrategy 返回的 strategy
     * @return 状态为 FAILED 时为 true；没有机会而未调用模型的不算失败
     */
    public static boolean isFailed(JSONObject strategy) {
        return FAILED_STATUS.equals(strategy.getString("status"));
    }

    /**
     * 判断一个地域的提升策略是否因为没有机会而没有调用模型。
     * @param strategy generateRegionStrategy 返回的 strategy
     * @return 状态为 NO_OPPORTUNITIES 时为 true
     */
    public static boolean isSkipped(JSONObject strategy) {
        return NO_OPPORTUNITIES_STATUS.equals(strategy.getString("status"));
    }
}
