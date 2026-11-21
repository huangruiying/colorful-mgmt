package org.huangry.colorful.geo.domain.service.diagnosis.report.content.advice;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.prompt.PromptTemplateRenderer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.huangry.colorful.geo.infrastructure.client.llm.tools.LLMJsonOutputParser.extractObject;
import static org.huangry.colorful.geo.infrastructure.client.llm.tools.LLMJsonOutputParser.isAllowed;
import static org.huangry.colorful.geo.infrastructure.client.llm.tools.LLMJsonOutputParser.listObjects;

/**
 * 诊断报告优化建议提示词：把规则发现的问题填进提示词模板交给模型，让它挑重点写出主要发现与优化建议，并严格校验输出；本类不调用模型。
 * <p>整份报告调用一次，由 AdviceGenerator 在统计之后调用；模板在「提示词管理」维护，由执行器取出当前版本后传入。
 * 模型只能引用问题编号，引用不存在的编号的条目丢弃，防止编造结论。</p>
 */
@Component
public class AdvicePrompt {
    /** 主要发现、优化建议各自最多保留的条数。 */
    private static final int ITEM_LIMIT = 5;
    /** 建议类别：补内容空缺、完善已有内容、增加可信证据、纠正和统一信息、改善公开访问与检索、补充第三方覆盖。 */
    private static final Set<String> CATEGORIES = Set.of("FILL_CONTENT_GAP", "IMPROVE_CONTENT", "ADD_EVIDENCE",
            "CORRECT_INFORMATION", "IMPROVE_ACCESS", "ADD_THIRD_PARTY");
    /** 证据状态：回答明确表达、待验证推测。来源内容本系统不抓取核实，所以没有“来源已核实”。 */
    private static final Set<String> EVIDENCE_STATUSES = Set.of("ANSWER_STATED", "INFERRED");

    /**
     * 组装优化建议提示词：把目标名称、核心卖点、规则发现的问题（JSON）和报告语言填进模板。
     * @param template 优化建议提示词模板，取自提示词管理的当前版本
     * @param project 任务快照中的项目资料，取名称与核心卖点
     * @param issues 规则发现的问题（id、title、channelName、metrics、evidence）
     * @param reportLanguage 报告语言，发现与建议的文字按此语言书写
     * @return 发送给判定模型的完整提示词
     */
    public String buildPrompt(String template, JSONObject project, JSONArray issues, String reportLanguage) {
        // 取值与模板变量一一对应（见 PromptTemplateKey.DIAGNOSIS_REPORT_ADVICE），未填写的卖点留空
        return PromptTemplateRenderer.render(template, Map.of(
                "target", Objects.toString(project.getString("name"), ""),
                "sellingPoints", Objects.toString(project.getString("sellingPoints"), ""),
                "issues", JSON.toJSONString(issues),
                "reportLanguage", reportLanguage));
    }

    /**
     * 解析并校验模型输出：只保留引用了已知问题编号的条目，类别、证据状态不在约定取值内的建议丢弃。
     * @param output 模型返回的正文，可带前后说明或代码块
     * @param issues 规则发现的问题，用于校验编号
     * @return findings（content、issueIds）与 actions（title、category、content、evidenceStatus、issueIds）
     * @throws IllegalArgumentException 输出不是有效 JSON 对象，或主要发现与优化建议都没有一条有效条目
     */
    public JSONObject parseAdvice(String output, JSONArray issues) {
        // 1. 截取最外层 JSON 对象，兼容模型在前后附带说明或代码块
        JSONObject root = extractObject(output, "优化建议");
        Set<String> knownIds = issues.stream().map(issue -> ((JSONObject) issue).getString("id")).collect(Collectors.toSet());
        // 2. 主要发现：内容不能为空，至少引用一个已知问题
        JSONArray findings = new JSONArray();
        for (JSONObject finding : listObjects(root.get("findings"))) {
            JSONArray issueIds = keepKnownIds(finding.get("issueIds"), knownIds);
            if (isBlank(finding.getString("content")) || issueIds.isEmpty() || findings.size() >= ITEM_LIMIT) {
                continue;
            }
            findings.add(new JSONObject(true).fluentPut("content", finding.getString("content").strip()).fluentPut("issueIds", issueIds));
        }
        // 3. 优化建议：除引用编号外，类别与证据状态也必须是约定取值，顺序即优先级
        JSONArray actions = new JSONArray();
        for (JSONObject action : listObjects(root.get("actions"))) {
            JSONArray issueIds = keepKnownIds(action.get("issueIds"), knownIds);
            if (isBlank(action.getString("title")) || issueIds.isEmpty() || !isAllowed(CATEGORIES, action.getString("category"))
                    || !isAllowed(EVIDENCE_STATUSES, action.getString("evidenceStatus")) || actions.size() >= ITEM_LIMIT) {
                continue;
            }
            actions.add(new JSONObject(true)
                    .fluentPut("title", action.getString("title").strip())
                    .fluentPut("category", action.getString("category"))
                    .fluentPut("content", action.getString("content") == null ? "" : action.getString("content").strip())
                    .fluentPut("evidenceStatus", action.getString("evidenceStatus"))
                    .fluentPut("issueIds", issueIds));
        }
        // 4. 两类全部被丢弃说明输出不可用，交给调用方记为失败，不把空建议当成功（与地域策略口径一致，2026-11-21 用户确认）
        if (findings.isEmpty() && actions.isEmpty()) {
            throw new IllegalArgumentException("优化建议没有有效条目");
        }
        return new JSONObject(true).fluentPut("findings", findings).fluentPut("actions", actions);
    }

    /**
     * @param value 模型给出的编号数组
     * @param knownIds 规则发现的问题编号
     * @return 去重后、只含已知编号的数组；非数组时为空
     */
    private JSONArray keepKnownIds(Object value, Set<String> knownIds) {
        JSONArray kept = new JSONArray();
        if (value instanceof JSONArray ids) {
            ids.stream().filter(String.class::isInstance).map(String.class::cast).map(String::strip)
                    .filter(knownIds::contains).distinct().forEach(kept::add);
        }
        return kept;
    }

    /**
     * @param text 文本
     * @return null 或只有空白时为 true
     */
    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
