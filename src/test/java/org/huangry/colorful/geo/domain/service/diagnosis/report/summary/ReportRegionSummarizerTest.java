package org.huangry.colorful.geo.domain.service.diagnosis.report.summary;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisReportService.ReportAnswer;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultAnalysisEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ⑦ 地域报告口径：按问题地域分组、宣传状态按态度占比下结论、机会按规则挑出并带全报告唯一的编号。
 * <p>通过汇总入口 buildSummary 计算，与报告生成时的组装方式一致；目标 Olight（别名 傲雷），预设竞品 Fenix。</p>
 */
class ReportRegionSummarizerTest {
    private final ReportSummarizer summarizer = ReportSummarizerTest.summarizer();
    private final JSONObject project = JSON.parseObject("{\"name\":\"Olight\",\"aliases\":[\"傲雷\"],\"competitors\":[\"Fenix\"],\"websiteUrl\":\"https://olight.com\"}");

    /** 地域按在问题顺序中首次出现的先后排列；不分地域的回答不计入，只给条数。 */
    @Test
    void shouldGroupAnswersByRegionAndCountUnassigned() {
        List<ReportAnswer> answers = List.of(
                answer(1, "德国", "NON_BRAND", "Fenix ist gut", brand("Fenix", "COMPETITOR", true, 1, null), "[]", null),
                answer(2, "美国", "NON_BRAND", "Olight is great", target(true, 1, "FIRST_CHOICE"), "[]", null),
                answer(3, "德国", "BRAND", "Olight ist gut", "[]", "[]", "POSITIVE"),
                answer(4, null, "NON_BRAND", "通用回答", "[]", "[]", null));

        JSONObject region = buildRegionReport(answers);

        assertEquals(1, region.getIntValue("unassignedAnswers"));
        assertEquals(List.of("德国", "美国"), names(region.getJSONArray("regions")));
        JSONObject germany = region.getJSONArray("regions").getJSONObject(0).getJSONObject("sample");
        assertEquals(2, germany.getIntValue("validAnswers"));
        assertEquals(2, germany.getIntValue("questions"));
        assertEquals(1, germany.getIntValue("models"));
    }

    /** 宣传状态：负面占比 ≥ 30% 为负向，正面 ≥ 50% 且负面 < 20% 为正向，有态度的回答少于 3 条为证据不足，其余为中性。 */
    @Test
    void shouldDecidePortrayalByAttitudeShare() {
        List<ReportAnswer> answers = new ArrayList<>();
        // 美国：点名正面 2 条、首推 1 条、并列 1 条 → 正面 3/4
        answers.add(answer(1, "美国", "BRAND", "Olight good", "[]", "[]", "POSITIVE"));
        answers.add(answer(2, "美国", "BRAND", "Olight nice", "[]", "[]", "POSITIVE"));
        answers.add(answer(3, "美国", "NON_BRAND", "Olight first", target(true, 1, "FIRST_CHOICE"), "[]", null));
        answers.add(answer(4, "美国", "NON_BRAND", "Olight also", target(true, 2, "PARALLEL"), "[]", null));
        // 德国：点名负面、明确不推荐、点名中性 → 负面 2/3
        answers.add(answer(5, "德国", "BRAND", "Olight schlecht", "[]", "[]", "NEGATIVE"));
        answers.add(answer(6, "德国", "NON_BRAND", "Olight nicht", target(false, null, "NOT_RECOMMENDED"), "[]", null));
        answers.add(answer(7, "德国", "BRAND", "Olight ok", "[]", "[]", "NEUTRAL"));
        // 日本：只有 2 条有态度
        answers.add(answer(8, "日本", "BRAND", "Olight いい", "[]", "[]", "POSITIVE"));
        answers.add(answer(9, "日本", "NON_BRAND", "Olight まあ", target(true, 3, "PASSING"), "[]", null));
        // 法国：正面 1、中性 2（附带缺点算中性）→ 正面不足一半
        answers.add(answer(10, "法国", "BRAND", "Olight bien", "[]", "[]", "POSITIVE"));
        answers.add(answer(11, "法国", "BRAND", "Olight bof", "[]", "[]", "NEUTRAL"));
        answers.add(answer(12, "法国", "NON_BRAND", "Olight aussi", target(true, 2, "WITH_RESERVATION"), "[]", null));

        JSONArray regions = buildRegionReport(answers).getJSONArray("regions");

        assertEquals(List.of("POSITIVE", "NEGATIVE", "INSUFFICIENT", "NEUTRAL"),
                regions.stream().map(item -> ((JSONObject) item).getJSONObject("portrayal").getString("status")).toList());
        JSONObject usa = regions.getJSONObject(0).getJSONObject("portrayal");
        assertEquals(3, usa.getIntValue("positive"));
        assertEquals(1, usa.getIntValue("neutral"));
        assertEquals(0.75, usa.getDouble("positiveRate"));
    }

    /** 机会按固定顺序挑出，编号在报告内唯一：第二个地域的第一个机会是 R2-O1。 */
    @Test
    void shouldListOpportunitiesWithRegionScopedIds() {
        List<ReportAnswer> answers = List.of(
                // 美国问题1：目标没出现、Fenix 被推荐（竞品占位）；回答强调亮度、提醒电池安全（用户在意）；引用 reddit（渠道空白）
                answer(1, "美国", "NON_BRAND", "Fenix is best", brand("Fenix", "COMPETITOR", true, 1, null),
                        "[{\"type\":\"SELECTION_CRITERION\",\"dimension\":\"亮度\",\"quote\":\"brightness\"},{\"type\":\"USER_CONCERN\",\"dimension\":\"电池安全\",\"quote\":\"battery\"}]",
                        null, source("https://reddit.com/r/flashlight", "reddit.com", "OTHER")),
                // 美国问题2：目标没出现，也没有品牌被推荐（空白题）
                answer(2, "美国", "NON_BRAND", "Look for lumens", "[]", "[]", null),
                // 美国问题3：目标被提到但没被推荐
                answer(3, "美国", "NON_BRAND", "Olight exists", target(false, null, "PASSING"), "[]", null),
                // 美国问题4：目标排第 2 且附带缺点（推荐未首推、口碑改善）
                answer(4, "美国", "NON_BRAND", "Olight second", target(true, 2, "WITH_RESERVATION"),
                        "[{\"type\":\"RESERVATION\",\"brand\":\"Olight\",\"dimension\":\"价格\",\"quote\":\"pricey\"}]", null),
                // 德国问题5：目标没出现、Fenix 被推荐
                answer(5, "德国", "NON_BRAND", "Fenix gut", brand("Fenix", "COMPETITOR", true, 1, null), "[]", null));

        JSONArray regions = buildRegionReport(answers).getJSONArray("regions");

        JSONArray usa = regions.getJSONObject(0).getJSONArray("opportunities");
        assertEquals(List.of("COMPETITOR_OCCUPIED", "WHITE_SPACE", "MENTIONED_NOT_RECOMMENDED", "NOT_FIRST_CHOICE",
                "USER_FOCUS", "PERCEPTION_GAP", "UNCOVERED_PLATFORM"), fieldValues(usa, "type"));
        assertEquals(List.of("R1-O1", "R1-O2", "R1-O3", "R1-O4", "R1-O5", "R1-O6", "R1-O7"), fieldValues(usa, "id"));
        assertEquals("1 道非品牌题从未提到目标，被推荐的是：Fenix", usa.getJSONObject(0).getString("title"));
        assertEquals("问题1", usa.getJSONObject(0).getJSONArray("evidence").getJSONObject(0).getString("question"));
        assertEquals("选择标准：亮度；顾虑：电池安全", usa.getJSONObject(4).getString("title"));
        assertEquals("需要改善的口碑：缺点：价格", usa.getJSONObject(5).getString("title"));
        assertEquals("pricey", usa.getJSONObject(5).getJSONArray("evidence").getJSONObject(0).getString("quote"));
        assertEquals("常被引用但没有我方内容的平台：reddit.com", usa.getJSONObject(6).getString("title"));
        assertEquals("https://reddit.com/r/flashlight", usa.getJSONObject(6).getJSONArray("evidence").getJSONObject(0).getString("url"));
        assertEquals("R2-O1", regions.getJSONObject(1).getJSONArray("opportunities").getJSONObject(0).getString("id"));
    }

    /** 平台上出现过我方内容（官网或我方发布）时，不算渠道空白。 */
    @Test
    void shouldSkipPlatformsAlreadyCitingOwnContent() {
        List<ReportAnswer> answers = List.of(answer(1, "美国", "NON_BRAND", "Olight first", target(true, 1, "FIRST_CHOICE"), "[]", null,
                source("https://olight.com/a", "olight.com", "OWNED"), source("https://olight.com/b", "olight.com", "OTHER")));

        JSONArray opportunities = buildRegionReport(answers).getJSONArray("regions").getJSONObject(0).getJSONArray("opportunities");

        assertFalse(fieldValues(opportunities, "type").contains("UNCOVERED_PLATFORM"));
    }

    /** 问题都不分地域（含早于地域功能的旧快照）时没有地域，也不生成机会。 */
    @Test
    void shouldReturnNoRegionsWhenQuestionsHaveNoRegion() {
        JSONObject region = buildRegionReport(List.of(answer(1, null, "NON_BRAND", "通用回答", "[]", "[]", null)));

        assertEquals(0, region.getJSONArray("regions").size());
        assertEquals(1, region.getIntValue("unassignedAnswers"));
    }

    /**
     * @param answers 报告回答
     * @return 汇总中的 ⑦ 部分
     */
    private JSONObject buildRegionReport(List<ReportAnswer> answers) {
        return summarizer.buildSummary(answers, project, Map.of(), new JSONObject(), "v1").getJSONObject("region");
    }

    /**
     * 一条判定成功的回答，问题快照带地域（为空时不写 region，等同旧快照）。
     * @param question 问题序号
     * @param region 问题地域，可为 null
     * @param brandScope 问题分组
     * @param text 回答原文
     * @param brands 判定的品牌 JSON
     * @param points 判定的观点 JSON
     * @param sentiment 点名题的品牌情感，其他题为 null
     * @param sources 规则归类后的来源 JSON
     * @return 报告中的一条回答
     */
    private ReportAnswer answer(int question, String region, String brandScope, String text, String brands, String points, String sentiment,
                                String... sources) {
        var result = new GeoDiagnosisTaskResultEntity();
        result.setId((long) question * 10);
        result.setQuestionSortOrder(question);
        result.setRepeatIndex(1);
        result.setAnswerText(text);
        result.setCitations("[]");
        result.setChannelSnapshot("{\"id\":52,\"channelName\":\"chatgpt联网搜索\",\"modelName\":\"gpt-5.5\"}");
        JSONObject snapshot = new JSONObject(true).fluentPut("text", "问题" + question).fluentPut("brandScope", brandScope).fluentPut("intent", "RECOMMENDATION");
        if (region != null) {
            snapshot.put("region", region);
        }
        result.setQuestionSnapshot(snapshot.toJSONString());
        var analysis = new GeoDiagnosisTaskResultAnalysisEntity();
        analysis.setStatus("SUCCEEDED");
        analysis.setBrands(brands);
        analysis.setPoints(points);
        analysis.setBrandSentiment(sentiment);
        analysis.setSources("[" + String.join(",", sources) + "]");
        return new ReportAnswer(analysis, result);
    }

    /** @return 只含目标的品牌判定 JSON */
    private String target(boolean recommended, Integer rank, String strength) {
        return brand("Olight", "TARGET", recommended, rank, strength);
    }

    /** @return 只含一个品牌的品牌判定 JSON */
    private String brand(String name, String type, boolean recommended, Integer rank, String strength) {
        return "[" + new JSONObject(true).fluentPut("name", name).fluentPut("type", type).fluentPut("recommended", recommended)
                .fluentPut("rank", rank).fluentPut("strength", strength).toJSONString() + "]";
    }

    /** @return 规则归类后的一条来源 JSON */
    private String source(String url, String platform, String sourceType) {
        return new JSONObject(true).fluentPut("url", url).fluentPut("title", "标题").fluentPut("platform", platform)
                .fluentPut("sourceType", sourceType).toJSONString();
    }

    /** @return 各地域的名称 */
    private List<String> names(JSONArray regions) {
        return fieldValues(regions, "name");
    }

    /** @return 数组中每个对象的某个文本字段 */
    private List<String> fieldValues(JSONArray items, String field) {
        return items.stream().map(item -> ((JSONObject) item).getString(field)).toList();
    }
}
