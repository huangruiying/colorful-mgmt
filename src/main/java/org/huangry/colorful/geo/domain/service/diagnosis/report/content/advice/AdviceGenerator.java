package org.huangry.colorful.geo.domain.service.diagnosis.report.content.advice;

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
 * 诊断报告的优化建议生成器（第 2 段第 3 步「写优化建议」，页面 ⑥）
 *
 * 负责：
 * - 根据规则统计出的问题，调用一次模型写整份报告的主要发现与优化建议
 * - 给出结果状态：成功、没有问题而跳过（NO_ISSUES）、生成失败（FAILED）
 *
 * 核心入口：
 * - generateAdvice(...)：后台执行器在统计之后调用，整份报告一次
 * - isFailed(...)、isSkipped(...)：执行器与子步骤进度据此判断结果是否失败、是否跳过
 *
 * 约束：
 * - 使用报告冻结的判定渠道、不联网，按报告语言书写；提示词模板由执行器在第 2 段开始时从「提示词管理」取出当前版本后传入
 * - 模型调用失败或输出无效时不抛异常，只把结果记为 FAILED，报告其余内容照常生成
 *
 * 不负责：
 * - 提示词填充与模型输出校验（AdvicePrompt）
 * - 各地域的提升策略（RegionStrategyGenerator）
 * - 保存结果、决定是否因失败暂停报告（DiagnosisReportWorker）
 * </pre>
 */
@Component
@Slf4j
public class AdviceGenerator {
    /** 建议生成失败时的状态值，页面按它显示生成失败。 */
    private static final String FAILED_STATUS = "FAILED";
    /** 规则没有发现问题、没有调用模型时的状态值。 */
    private static final String NO_ISSUES_STATUS = "NO_ISSUES";

    @Resource
    private LLMClient llmClient;
    @Resource
    private AdvicePrompt advicePrompt;

    /**
     * 生成整份报告的主要发现与优化建议：把规则发现的问题交给模型归纳成建议，整份报告调用一次；没有问题时不调用模型。
     * @param issues 规则统计出的问题（编号、标题、渠道、指标、回答原文证据），模型只能引用这些编号
     * @param project 任务快照中的项目资料，提示词取名称与核心卖点
     * @param channel 报告冻结的判定渠道
     * @param reportId 诊断报告 ID，仅用于日志
     * @param reportLanguage 报告语言，发现与建议按此语言书写
     * @param promptTemplate 优化建议提示词模板，执行器在第 2 段开始时取出的当前版本
     * @return advice 对象，status 为 SUCCEEDED（含 findings、actions，至少一类有有效条目）、
     *         NO_ISSUES（没有问题，未调用模型）或 FAILED（模型调用失败、输出不是有效 JSON 或两类都没有有效条目，含 errorMessage）；
     *         这些失败不抛异常
     */
    public JSONObject generateAdvice(JSONArray issues, JSONObject project, GeoLlmChannelEntity channel, long reportId,
                                     String reportLanguage, String promptTemplate) {
        // 1. 规则没有发现问题时不调用模型：没有可引用的依据，记为 NO_ISSUES，不算失败
        if (issues.isEmpty()) {
            return new JSONObject(true).fluentPut("status", NO_ISSUES_STATUS);
        }
        try {
            // 2. 把问题清单填进当前版本的模板，用判定渠道调用一次模型，不联网
            String prompt = advicePrompt.buildPrompt(promptTemplate, project, issues, reportLanguage);
            String output = llmClient.query(new LLMQueryRequest(prompt), channel).getContent();
            // 3. 只保留引用了已知问题编号、类别与证据状态合法的条目；发现与建议全被丢弃时 parseAdvice 抛异常，按失败处理（与地域策略一致）
            return advicePrompt.parseAdvice(output, issues).fluentPut("status", "SUCCEEDED");
        } catch (LLMClientException | IllegalArgumentException exception) {
            // 4. 调用失败（LLMClientException），或输出不是有效 JSON、没有有效条目（parseAdvice 抛出的 IllegalArgumentException）记为生成失败；
            //    建议只是对规则问题的归纳，失败时页面仍能展示规则发现的问题与证据
            log.warn("诊断报告优化建议生成失败 reportId={}, issues={}, errorType={}", reportId, issues.size(), exception.getClass().getSimpleName());
            return new JSONObject(true).fluentPut("status", FAILED_STATUS).fluentPut("errorMessage", "优化建议生成失败，可先查看规则发现的问题");
        }
    }

    /**
     * 判断优化建议是否生成失败。
     * @param advice generateAdvice 返回的 advice
     * @return 状态为 FAILED 时为 true；没有问题而未调用模型的不算失败
     */
    public static boolean isFailed(JSONObject advice) {
        return FAILED_STATUS.equals(advice.getString("status"));
    }

    /**
     * 判断优化建议是否因为规则没有发现问题而没有调用模型。
     * @param advice generateAdvice 返回的 advice
     * @return 状态为 NO_ISSUES 时为 true
     */
    public static boolean isSkipped(JSONObject advice) {
        return NO_ISSUES_STATUS.equals(advice.getString("status"));
    }
}
