package org.huangry.colorful.geo.domain.service.diagnosis.report.content.strategy;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.prompt.PromptTemplateRenderer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.huangry.colorful.geo.infrastructure.client.llm.tools.LLMJsonOutputParser.extractObject;
import static org.huangry.colorful.geo.infrastructure.client.llm.tools.LLMJsonOutputParser.isAllowed;
import static org.huangry.colorful.geo.infrastructure.client.llm.tools.LLMJsonOutputParser.listObjects;

/**
 * ⑦ 地域报告的提升策略提示词：把一个地域的宣传状态、可见度和机会清单交给模型，写品牌优化方向和两类打法，并严格校验输出；本类不调用模型。
 * <p>每个地域调用一次，由 RegionStrategyGenerator 调用；模板在「提示词管理」维护，由执行器取出当前版本后传入。两类打法必须引用该地域的机会编号，引用不存在的编号的条目丢弃；
 * 品牌优化方向没有对应机会时只能标为待验证推测。对当地市场、平台和渠道的判断属于模型推测，页面标为「待验证推测」。</p>
 */
@Component
public class RegionStrategyPrompt {
    /** 每类策略最多保留的条数。 */
    private static final int ITEM_LIMIT = 4;
    /** 每条打法最多保留的操作步骤数。 */
    private static final int STEP_LIMIT = 5;
    /** 证据状态：回答明确表达、待验证推测（含对当地市场的推断）。 */
    private static final Set<String> EVIDENCE_STATUSES = Set.of("ANSWER_STATED", "INFERRED");
    /** 没有对应机会时唯一允许的证据状态。 */
    private static final String INFERRED = "INFERRED";
    /** 提示词里宣传状态的每个维度排行最多带几项；完整排行带多句原文，全部放进提示词会拖慢调用、容易超时。 */
    private static final int PROMPT_RANKING_LIMIT = 5;
    /** 宣传状态中交给模型的维度排行。 */
    private static final List<String> PORTRAYAL_RANKINGS = List.of("positives", "recommendReasons", "negatives", "reservations");

    /**
     * 组装一个地域的提升策略提示词：把目标资料、地域名称、样本、精简后的宣传状态、可见度、机会清单（均为 JSON）和报告语言填进模板。
     * @param template 地域策略提示词模板，取自提示词管理的当前版本
     * @param project 任务快照中的项目资料，取名称、核心卖点与预设竞品
     * @param region 一个地域的 ⑦ 内容：name、sample、portrayal、visibility、opportunities
     * @param reportLanguage 报告语言，策略按此语言书写
     * @return 发送给判定模型的完整提示词
     */
    public String buildPrompt(String template, JSONObject project, JSONObject region, String reportLanguage) {
        // 取值与模板变量一一对应（见 PromptTemplateKey.DIAGNOSIS_REPORT_REGION_STRATEGY）；宣传状态先精简，避免提示词过长拖慢调用
        Map<String, String> variables = new HashMap<>();
        variables.put("reportLanguage", reportLanguage);
        variables.put("target", Objects.toString(project.getString("name"), ""));
        variables.put("sellingPoints", Objects.toString(project.getString("sellingPoints"), ""));
        variables.put("competitors", JSON.toJSONString(project.get("competitors")));
        variables.put("region", Objects.toString(region.getString("name"), ""));
        variables.put("sample", JSON.toJSONString(region.get("sample")));
        variables.put("portrayal", JSON.toJSONString(compactPortrayal(region.getJSONObject("portrayal"))));
        variables.put("visibility", JSON.toJSONString(region.get("visibility")));
        variables.put("opportunities", JSON.toJSONString(region.get("opportunities")));
        return PromptTemplateRenderer.render(template, variables);
    }

    /**
     * 精简宣传状态再交给模型：保留结论和计数，每个维度排行取前几项、每项一句原文，错误信息取前几条。
     * @param portrayal 一个地域的宣传状态
     * @return 精简后的宣传状态
     */
    private JSONObject compactPortrayal(JSONObject portrayal) {
        JSONObject compact = new JSONObject(true)
                .fluentPut("status", portrayal.get("status"))
                .fluentPut("positive", portrayal.get("positive"))
                .fluentPut("neutral", portrayal.get("neutral"))
                .fluentPut("negative", portrayal.get("negative"));
        for (String key : PORTRAYAL_RANKINGS) {
            JSONArray rows = new JSONArray();
            listObjects(portrayal.get(key)).stream().limit(PROMPT_RANKING_LIMIT).forEach(row -> rows.add(new JSONObject(true)
                    .fluentPut("dimension", row.getString("dimension"))
                    .fluentPut("count", row.get("count"))
                    .fluentPut("quote", listObjects(row.get("examples")).stream().findFirst().map(example -> example.getString("quote")).orElse(null))));
            compact.put(key, rows);
        }
        JSONArray factErrors = new JSONArray();
        listObjects(portrayal.get("factErrors")).stream().limit(PROMPT_RANKING_LIMIT).forEach(error -> factErrors.add(new JSONObject(true)
                .fluentPut("content", error.getString("content")).fluentPut("quote", error.getString("quote"))));
        return compact.fluentPut("factErrors", factErrors);
    }

    /**
     * 解析并校验模型输出：只保留有标题、证据状态合法、依据了已知机会编号的条目；打法还必须有操作步骤。
     * @param output 模型返回的正文，可带前后说明或代码块
     * @param opportunities 该地域的机会清单，用于校验编号
     * @return brandDirections、mentionPlays、rankPlays，每条含 title、content、steps、evidenceStatus、opportunityIds
     * @throws IllegalArgumentException 输出不是有效 JSON 对象，或三类策略都没有一条有效条目
     */
    public JSONObject parseStrategy(String output, JSONArray opportunities) {
        // 1. 截取最外层 JSON 对象，兼容模型在前后附带说明或代码块
        JSONObject root = extractObject(output, "地域提升策略");
        Set<String> knownIds = opportunities.stream().map(item -> ((JSONObject) item).getString("id")).collect(Collectors.toSet());
        // 2. 品牌方向可以没有对应机会（此时只能是推测）；两类打法必须引用已知机会并有操作路径
        JSONArray brandDirections = keepValidItems(root.get("brandDirections"), knownIds, false);
        JSONArray mentionPlays = keepValidItems(root.get("mentionPlays"), knownIds, true);
        JSONArray rankPlays = keepValidItems(root.get("rankPlays"), knownIds, true);
        // 3. 全部被丢弃说明输出不可用，交给调用方记为失败，不把空策略当成功
        if (brandDirections.isEmpty() && mentionPlays.isEmpty() && rankPlays.isEmpty()) {
            throw new IllegalArgumentException("地域提升策略没有有效条目");
        }
        return new JSONObject(true)
                .fluentPut("brandDirections", brandDirections)
                .fluentPut("mentionPlays", mentionPlays)
                .fluentPut("rankPlays", rankPlays);
    }

    /**
     * 过滤一类策略条目，最多保留 ITEM_LIMIT 条。
     * @param value 模型给出的条目数组
     * @param knownIds 该地域的机会编号
     * @param play 是否为打法；打法必须引用已知机会并有操作步骤，品牌方向没有对应机会时证据状态必须是 INFERRED
     * @return 校验后的条目：title、content、steps、evidenceStatus、opportunityIds
     */
    private JSONArray keepValidItems(Object value, Set<String> knownIds, boolean play) {
        JSONArray kept = new JSONArray();
        for (JSONObject item : listObjects(value)) {
            JSONArray ids = keepKnownIds(item.get("opportunityIds"), knownIds);
            List<String> steps = listSteps(item.get("steps"));
            String status = item.getString("evidenceStatus");
            // 打法要能落地和复测：必须依据已知机会并有操作步骤；品牌方向可以只基于对当地市场的判断，但此时只能标为推测
            boolean grounded = play ? !ids.isEmpty() && !steps.isEmpty() : !ids.isEmpty() || INFERRED.equals(status);
            if (isBlank(item.getString("title")) || !isAllowed(EVIDENCE_STATUSES, status) || !grounded || kept.size() >= ITEM_LIMIT) {
                continue;
            }
            kept.add(new JSONObject(true)
                    .fluentPut("title", item.getString("title").strip())
                    .fluentPut("content", isBlank(item.getString("content")) ? "" : item.getString("content").strip())
                    .fluentPut("steps", new JSONArray(new ArrayList<>(steps)))
                    .fluentPut("evidenceStatus", status)
                    .fluentPut("opportunityIds", ids));
        }
        return kept;
    }

    /**
     * @param value 模型给出的步骤数组
     * @return 去除空白项后的前 STEP_LIMIT 个步骤；非数组时为空
     */
    private List<String> listSteps(Object value) {
        if (!(value instanceof JSONArray steps)) {
            return List.of();
        }
        return steps.stream().filter(String.class::isInstance).map(String.class::cast).map(String::strip)
                .filter(step -> !step.isEmpty()).limit(STEP_LIMIT).toList();
    }

    /**
     * @param value 模型给出的机会编号数组
     * @param knownIds 该地域的机会编号
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
