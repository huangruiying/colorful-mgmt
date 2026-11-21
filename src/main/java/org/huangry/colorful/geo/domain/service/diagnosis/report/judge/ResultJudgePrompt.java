package org.huangry.colorful.geo.domain.service.diagnosis.report.judge;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.domain.service.prompt.PromptTemplateRenderer;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisBrandSentiment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.huangry.colorful.geo.infrastructure.client.llm.tools.LLMJsonOutputParser.extractObject;
import static org.huangry.colorful.geo.infrastructure.client.llm.tools.LLMJsonOutputParser.isAllowed;
import static org.huangry.colorful.geo.infrastructure.client.llm.tools.LLMJsonOutputParser.listObjects;

/**
 * 诊断结果分析提示词：把一条回答的数据填进判定提示词模板，并严格校验模型返回的 JSON；本类不调用模型。
 * <p>一条回答调用一次，按问题分组只填写适用部分。模板在「提示词管理」维护，由执行器按报告冻结的版本取出后传入；
 * 本类不访问数据库、不调用模型。输出格式的校验仍在这里，模板里的【输出 JSON 格式】要与之对得上。
 * 原文片段必须逐字出现在回答中，校验不通过的观点丢弃，防止判定模型编造。</p>
 */
@Component
public class ResultJudgePrompt {
    /** 观点概括与原文片段的保存长度上限。 */
    private static final int TEXT_LIMIT = 300;
    /** 品牌类型：目标、预设竞品、回答中新出现的同类产品。 */
    private static final Set<String> BRAND_TYPES = Set.of("TARGET", "COMPETITOR", "CANDIDATE");
    /** 推荐强度：首推、强推、并列、顺带一提、附带缺点、明确不推荐。 */
    private static final Set<String> STRENGTHS = Set.of("FIRST_CHOICE", "STRONG", "PARALLEL", "PASSING", "WITH_RESERVATION", "NOT_RECOMMENDED");
    /** 观点类型，按问题分组决定适用范围，见提示词说明。 */
    private static final Set<String> POINT_TYPES = Set.of("SELECTION_CRITERION", "USER_CONCERN", "RECOMMEND_REASON", "RESERVATION",
            "POSITIVE", "NEGATIVE", "FACT_ERROR", "SELLING_POINT_COVERED", "DIMENSION_RESULT");
    /** 维度胜负结果，仅 DIMENSION_RESULT 使用。 */
    private static final Set<String> OUTCOMES = Set.of("WIN", "LOSE", "TIE");
    /** 点名目标的问题分组，只有它判定品牌情感与描述准确性。 */
    private static final String BRAND_SCOPE = "BRAND";

    @Resource
    private ResultRuleAnalyzer ruleAnalyzer;

    /**
     * 组装一条回答的判定提示词：按变量名把项目、问题、回答与报告语言填进模板；点名问题才附项目资料，其他问题该处留空。
     * @param template 判定提示词模板，取自提示词管理（报告冻结的版本）
     * @param project 任务快照中的项目资料
     * @param question 诊断结果中的问题快照，含 text、brandScope
     * @param answer 被判定的回答原文
     * @param reportLanguage 报告语言，观点概括与维度名按此语言书写；原文片段、产品名不翻译
     * @return 发送给判定模型的完整提示词
     */
    public String buildPrompt(String template, JSONObject project, JSONObject question, String answer, String reportLanguage) {
        String brandScope = question.getString("brandScope");
        // 取值与模板变量一一对应（见 PromptTemplateKey.DIAGNOSIS_REPORT_RESULT_JUDGE），别名与竞品用顿号连接
        Map<String, String> variables = new HashMap<>();
        variables.put("target", nullToEmpty(project.getString("name")));
        variables.put("aliases", String.join("、", ruleAnalyzer.convertToStringList(project.get("aliases"))));
        variables.put("competitors", String.join("、", ruleAnalyzer.convertToStringList(project.get("competitors"))));
        variables.put("brandScope", nullToEmpty(brandScope));
        variables.put("question", nullToEmpty(question.getString("text")));
        // 点名问题附项目资料用于判断描述是否准确，其他分组不附，避免模型用资料替代回答内容
        variables.put("projectMaterials", BRAND_SCOPE.equals(brandScope) ? buildProjectMaterials(project) : "");
        variables.put("answer", nullToEmpty(answer));
        variables.put("reportLanguage", nullToEmpty(reportLanguage));
        return PromptTemplateRenderer.render(template, variables);
    }

    /**
     * 组装点名问题附带的项目资料段，末尾带换行，直接接在模板的【回答原文】前面；未填写的资料留空。
     * @param project 任务快照中的项目资料
     * @return 项目资料段
     */
    private String buildProjectMaterials(JSONObject project) {
        return "【项目资料，仅用于判断回答对目标的描述是否准确】\n"
                + "核心功能：" + nullToEmpty(project.getString("coreFeatures")) + "\n"
                + "核心卖点：" + nullToEmpty(project.getString("sellingPoints")) + "\n"
                + "价格说明：" + nullToEmpty(project.getString("pricing")) + "\n"
                + "目标用户：" + nullToEmpty(project.getString("targetAudience")) + "\n";
    }

    /**
     * 解析并校验判定模型的输出。
     * @param output 判定模型返回的正文，可包含 Markdown 代码块包裹
     * @param answer 被判定的回答原文，用于校验原文片段
     * @param brandScope 问题分组，非 BRAND 时丢弃品牌情感与描述准确性
     * @param project 任务快照中的项目资料，用于统一目标与竞品名称
     * @return 校验后的判定结果
     * @throws IllegalArgumentException 输出不是有效 JSON 对象
     */
    public AnalysisResult parseAnalysisResult(String output, String answer, String brandScope, JSONObject project) {
        // 1. 截取最外层 JSON 对象，兼容模型在前后附带说明或代码块
        JSONObject root = extractObject(output, "判定结果");
        String normalizedAnswer = normalize(answer);
        // 2. 品牌：类型与强度必须是约定取值，名称为空的条目丢弃；名称统一后同一产品只保留首次出现
        JSONArray brands = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (JSONObject brand : listObjects(root.get("brands"))) {
            String type = brand.getString("type");
            if (brand.getString("name") == null || brand.getString("name").isBlank() || !isAllowed(BRAND_TYPES, type)) {
                continue;
            }
            // 目标统一为项目名；竞品与待确认产品能对应预设的统一为预设竞品，对不上的为待确认产品，保证按竞品统计时不被不同写法拆散
            String name = brand.getString("name").strip();
            if ("TARGET".equals(type)) {
                name = project.getString("name");
            } else {
                String preset = presetCompetitor(name, ruleAnalyzer.convertToStringList(project.get("competitors")));
                type = preset == null ? "CANDIDATE" : "COMPETITOR";
                name = preset == null ? name : preset;
            }
            if (!seen.add(name)) {
                continue;
            }
            String strength = isAllowed(STRENGTHS, brand.getString("strength")) ? brand.getString("strength") : null;
            Integer rank = brand.get("rank") instanceof Integer value && value > 0 ? value : null;
            brands.add(new JSONObject(true)
                    .fluentPut("name", name.strip())
                    .fluentPut("type", type)
                    .fluentPut("recommended", Boolean.TRUE.equals(brand.getBoolean("recommended")))
                    .fluentPut("rank", rank)
                    .fluentPut("strength", strength));
        }
        // 3. 观点：类型须在约定范围，原文片段必须出现在回答中，否则视为编造并丢弃
        JSONArray points = new JSONArray();
        for (JSONObject point : listObjects(root.get("points"))) {
            String type = point.getString("type");
            String quote = point.getString("quote");
            if (!isAllowed(POINT_TYPES, type) || quote == null || quote.isBlank() || !normalizedAnswer.contains(normalize(quote))) {
                continue;
            }
            String outcome = "DIMENSION_RESULT".equals(type) && isAllowed(OUTCOMES, point.getString("outcome")) ? point.getString("outcome") : null;
            if ("DIMENSION_RESULT".equals(type) && outcome == null) {
                continue;
            }
            points.add(new JSONObject(true)
                    .fluentPut("type", type)
                    .fluentPut("brand", blankToNull(point.getString("brand")))
                    .fluentPut("dimension", blankToNull(point.getString("dimension")))
                    .fluentPut("outcome", outcome)
                    .fluentPut("content", truncate(point.getString("content")))
                    .fluentPut("quote", truncate(quote.strip())));
        }
        // 4. 品牌情感与描述准确性只对点名问题有效，其他分组即使模型填写也丢弃
        boolean brandQuestion = BRAND_SCOPE.equals(brandScope);
        String sentiment = brandQuestion && Arrays.stream(DiagnosisBrandSentiment.values()).map(Enum::name)
                .collect(Collectors.toSet()).contains(root.getString("brandSentiment")) ? root.getString("brandSentiment") : null;
        Boolean accurate = brandQuestion && root.get("descriptionAccurate") instanceof Boolean value ? value : null;
        return new AnalysisResult(brands, points, sentiment, accurate);
    }

    /**
     * 把竞品名称对应到预设竞品：完全一致优先，其次互相包含（处理简称与全称，如“支付宝”与“支付宝加油服务”）。
     * @param name 判定给出的竞品名称
     * @param competitors 预设竞品名称
     * @return 对应的预设名称，对不上时为 null
     */
    private String presetCompetitor(String name, List<String> competitors) {
        for (String competitor : competitors) {
            if (competitor.equalsIgnoreCase(name)) {
                return competitor;
            }
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String competitor : competitors) {
            String presetLower = competitor.toLowerCase(Locale.ROOT);
            if (presetLower.contains(lower) || lower.contains(presetLower)) {
                return competitor;
            }
        }
        return null;
    }

    /**
     * 比较原文片段前统一格式：去掉空白、Markdown 格式符号和字面量换行符。
     * <p>模型引用时常省略加粗、列表、标题等符号或把换行写成 \\n，这些只是格式差异；跨段拼接仍会对不上并被丢弃。</p>
     * @param text 文本
     * @return 只保留正文字符的文本
     */
    private String normalize(String text) {
        return text == null ? "" : text.replace("\\n", "").replaceAll("[\\s*#>`|_~\\-]+", "");
    }

    /**
     * @param text 概括或原文
     * @return 截断到保存上限的文本，null 保持 null
     */
    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= TEXT_LIMIT ? text : text.substring(0, TEXT_LIMIT);
    }

    /**
     * @param text 文本
     * @return 去除首尾空白，空白及字面量 null 转为 null
     */
    private String blankToNull(String text) {
        return text == null || text.isBlank() || "null".equalsIgnoreCase(text.strip()) ? null : text.strip();
    }

    /**
     * @param text 文本
     * @return null 转为空字符串
     */
    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /**
     * 校验后的判定结果。
     * @param brands 品牌数组：name、type、recommended、rank、strength
     * @param points 观点数组：type、brand、dimension、outcome、content、quote
     * @param brandSentiment 品牌情感枚举名称，仅点名问题有值
     * @param descriptionAccurate 描述是否准确，仅点名问题有值
     */
    public record AnalysisResult(JSONArray brands, JSONArray points, String brandSentiment, Boolean descriptionAccurate) {
    }
}
