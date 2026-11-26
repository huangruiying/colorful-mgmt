package org.huangry.colorful.geo.domain.service.diagnosis.report.content.summary;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.domain.service.diagnosis.report.judge.ResultRuleAnalyzer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.huangry.colorful.geo.domain.service.diagnosis.report.content.summary.ReportMetrics.calculateRate;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.content.summary.ReportMetrics.countMatching;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.content.summary.ReportMetrics.round;

/**
 * 诊断报告 ⑦ 地域报告：按问题面向的地域把回答分组，给出每个地域的宣传状态、大模型可见度和机会清单。
 * <p>地域取自诊断任务冻结的问题快照；不分地域的回答不计入，只给出条数。指标口径与 ①–④ 相同，只是范围缩小到一个地域。
 * 机会编号形如 R1-O1（第 1 个地域的第 1 个机会），全报告唯一，供提升策略引用和页面「看证据」。</p>
 * <p>纯计算，不调用模型、不访问数据库；各地域的提升策略由后台执行器调用模型另行写入 strategy。</p>
 */
@Component
public class ReportRegionSummarizer {
    /** 宣传状态至少要有这么多条有态度的回答，否则为证据不足。 */
    private static final int MIN_ATTITUDE_ANSWERS = 3;
    /** 正面占比达到此值、且负面占比低于 NEGATIVE_CEILING 时为正向。 */
    private static final double POSITIVE_FLOOR = 0.5;
    /** 正向结论允许的负面占比上限（不含）。 */
    private static final double NEGATIVE_CEILING = 0.2;
    /** 负面占比达到此值时为负向，先于正向判断。 */
    private static final double NEGATIVE_FLOOR = 0.3;
    /** 每个机会最多附带的证据条数；题目是写内容和复测的对象，比原文示例多留几条。 */
    private static final int EVIDENCE_LIMIT = 5;
    /** 维度类、平台类机会各取次数最多的几项。 */
    private static final int TOP_LIMIT = 3;
    /** 算作正面态度的推荐强度：首推、强推。 */
    private static final Set<String> POSITIVE_STRENGTHS = Set.of("FIRST_CHOICE", "STRONG");
    /** 我方内容的来源类型：官网、我方发布。 */
    private static final Set<String> OWN_SOURCE_TYPES = Set.of(ResultRuleAnalyzer.SOURCE_OWNED, ResultRuleAnalyzer.SOURCE_PUBLISHED);

    @Resource
    private ResultRuleAnalyzer resultRuleAnalyzer;
    @Resource
    private ReportDimensionRanking reportDimensionRanking;
    @Resource
    private ReportOverviewSummarizer reportOverviewSummarizer;
    @Resource
    private ReportCompetitorSummarizer reportCompetitorSummarizer;
    @Resource
    private ReportBrandSummarizer reportBrandSummarizer;

    /**
     * ⑦ 地域报告：按问题地域分组，逐个地域计算宣传状态、可见度与机会。
     * @param facts 全部回答事实（含判定失败的，来源统计会用到）
     * @param project 任务快照中的项目资料，取目标名称、别名与预设竞品
     * @param dimensionMapping 维度归一映射
     * @return region 对象：unassignedAnswers 不分地域的回答数；regions 按地域在问题顺序中首次出现的先后排列，
     *         每个地域含 name、sample、portrayal、visibility、opportunities；没有任何地域时 regions 为空数组
     */
    public JSONObject buildRegionReport(List<ReportAnswerFacts> facts, JSONObject project, Map<String, String> dimensionMapping) {
        // 1. 按问题顺序排好后按地域分组，地域的先后与方案里的问题顺序一致；不分地域的回答只计数
        Map<String, List<ReportAnswerFacts>> byRegion = facts.stream().filter(fact -> fact.region() != null)
                .sorted(Comparator.comparing(ReportAnswerFacts::questionSortOrder))
                .collect(Collectors.groupingBy(ReportAnswerFacts::region, LinkedHashMap::new, Collectors.toList()));
        // 2. 逐个地域计算，地域序号用于生成全报告唯一的机会编号
        JSONArray regions = new JSONArray();
        for (Map.Entry<String, List<ReportAnswerFacts>> entry : byRegion.entrySet()) {
            regions.add(buildRegion("R" + (regions.size() + 1), entry.getKey(), entry.getValue(), project, dimensionMapping));
        }
        return new JSONObject(true)
                .fluentPut("unassignedAnswers", countMatching(facts, fact -> fact.region() == null))
                .fluentPut("regions", regions);
    }

    /**
     * 计算一个地域的样本说明、宣传状态、可见度与机会，不含提升策略。
     * @param prefix 机会编号前缀，如 R1
     * @param name 地域名称
     * @param regionFacts 该地域的全部回答事实
     * @param project 项目资料
     * @param dimensionMapping 维度归一映射
     * @return 一个地域的 ⑦ 内容：name、sample、portrayal、visibility、opportunities
     */
    private JSONObject buildRegion(String prefix, String name, List<ReportAnswerFacts> regionFacts, JSONObject project,
                                   Map<String, String> dimensionMapping) {
        List<ReportAnswerFacts> judged = regionFacts.stream().filter(ReportAnswerFacts::judged).toList();
        List<String> targetNames = resultRuleAnalyzer.listTargetNames(project);
        return new JSONObject(true)
                .fluentPut("name", name)
                .fluentPut("sample", buildSample(regionFacts, judged))
                .fluentPut("portrayal", buildPortrayal(judged, targetNames, dimensionMapping))
                .fluentPut("visibility", buildVisibility(judged, project))
                .fluentPut("opportunities", listOpportunities(prefix, regionFacts, judged, targetNames, dimensionMapping));
    }

    /**
     * 地域的样本说明，交代结论有多少依据。
     * @param regionFacts 该地域的全部回答
     * @param judged 该地域判定成功的回答
     * @return validAnswers、judged、questions（题数）、models（模型数）
     */
    private JSONObject buildSample(List<ReportAnswerFacts> regionFacts, List<ReportAnswerFacts> judged) {
        return new JSONObject(true)
                .fluentPut("validAnswers", regionFacts.size())
                .fluentPut("judged", judged.size())
                .fluentPut("questions", regionFacts.stream().map(ReportAnswerFacts::questionSortOrder).distinct().count())
                .fluentPut("models", regionFacts.stream().map(ReportAnswerFacts::channelId).distinct().count());
    }

    /**
     * 1 宣传状态：该地域的回答对目标是正向还是负向，并附正面、负面证据。
     * <p>计入有态度的回答：点名题里有品牌情感的，加上其他题里提到目标且有推荐强度的。</p>
     * @param judged 该地域判定成功的回答
     * @param targetNames 目标名称与别名，推荐理由和保留意见只统计针对目标的
     * @param dimensionMapping 维度归一映射
     * @return status（POSITIVE、NEUTRAL、NEGATIVE、INSUFFICIENT）、正中负回答数与正负占比，positives、recommendReasons、
     *         negatives、reservations 维度排行（附原文），factErrors 错误信息，negativeSources 负面回答引用的平台
     */
    private JSONObject buildPortrayal(List<ReportAnswerFacts> judged, List<String> targetNames, Map<String, String> dimensionMapping) {
        // 1. 逐条判断对目标的态度，没有态度的回答不计入
        Map<String, Long> attitudes = judged.stream().map(this::judgeAttitude).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(attitude -> attitude, Collectors.counting()));
        long positive = attitudes.getOrDefault("POSITIVE", 0L);
        long neutral = attitudes.getOrDefault("NEUTRAL", 0L);
        long negative = attitudes.getOrDefault("NEGATIVE", 0L);
        long total = positive + neutral + negative;
        // 2. 证据：正面点、负面点只看点名题，推荐理由与保留意见只看针对目标的；错误信息与负面来源沿用 ③ 的口径
        Predicate<ReportAnswerFacts> anyQuestion = fact -> true;
        List<ReportAnswerFacts> brandFacts = judged.stream().filter(ReportAnswerFacts::isBrandQuestion).toList();
        return new JSONObject(true)
                .fluentPut("status", decidePortrayalStatus(positive, negative, total))
                .fluentPut("positive", positive)
                .fluentPut("neutral", neutral)
                .fluentPut("negative", negative)
                .fluentPut("positiveRate", calculateRate(positive, total))
                .fluentPut("negativeRate", calculateRate(negative, total))
                .fluentPut("positives", reportDimensionRanking.listDimensionRanking(judged, ReportAnswerFacts::isBrandQuestion, "POSITIVE", null, dimensionMapping))
                .fluentPut("recommendReasons", reportDimensionRanking.listDimensionRanking(judged, anyQuestion, "RECOMMEND_REASON", targetNames, dimensionMapping))
                .fluentPut("negatives", reportDimensionRanking.listDimensionRanking(judged, ReportAnswerFacts::isBrandQuestion, "NEGATIVE", null, dimensionMapping))
                .fluentPut("reservations", reportDimensionRanking.listDimensionRanking(judged, anyQuestion, "RESERVATION", targetNames, dimensionMapping))
                .fluentPut("factErrors", reportBrandSummarizer.listFactErrors(brandFacts))
                .fluentPut("negativeSources", reportBrandSummarizer.listNegativeSources(brandFacts));
    }

    /**
     * 判断一条回答对目标的态度：点名题看品牌情感；其他题看目标的推荐强度，首推、强推为正面，明确不推荐为负面，其余为中性。
     * @param fact 判定成功的回答
     * @return POSITIVE、NEUTRAL、NEGATIVE；点名题没有情感、其他题没提到目标或没有推荐强度时为 null（不计入）
     */
    private String judgeAttitude(ReportAnswerFacts fact) {
        if (fact.isBrandQuestion()) {
            return fact.answer().analysis().getBrandSentiment();
        }
        String strength = fact.target() == null ? null : fact.target().getString("strength");
        if (strength == null) {
            return null;
        }
        if (POSITIVE_STRENGTHS.contains(strength)) {
            return "POSITIVE";
        }
        return "NOT_RECOMMENDED".equals(strength) ? "NEGATIVE" : "NEUTRAL";
    }

    /**
     * 按正负面占比给出宣传状态结论；负面占比达标先判负向，样本太少时不下结论。
     * @param positive 正面回答数
     * @param negative 负面回答数
     * @param total 有态度的回答数
     * @return INSUFFICIENT（有态度的回答少于 3 条）、NEGATIVE（负面 ≥ 30%）、POSITIVE（正面 ≥ 50% 且负面 < 20%），其余为 NEUTRAL
     */
    private String decidePortrayalStatus(long positive, long negative, long total) {
        if (total < MIN_ATTITUDE_ANSWERS) {
            return "INSUFFICIENT";
        }
        double positiveRate = (double) positive / total;
        double negativeRate = (double) negative / total;
        if (negativeRate >= NEGATIVE_FLOOR) {
            return "NEGATIVE";
        }
        return positiveRate >= POSITIVE_FLOOR && negativeRate < NEGATIVE_CEILING ? "POSITIVE" : "NEUTRAL";
    }

    /**
     * 2 大模型可见度：该地域各模型的核心指标，以及目标与预设竞品的同口径对比，口径与 ① ④ 相同（只算非品牌题）。
     * @param judged 该地域判定成功的回答
     * @param project 项目资料
     * @return models 各模型核心指标；brands 目标与竞品在各模型上的提及率、推荐率、首推率
     */
    private JSONObject buildVisibility(List<ReportAnswerFacts> judged, JSONObject project) {
        List<String> competitors = resultRuleAnalyzer.convertToStringList(project.get("competitors"));
        return new JSONObject(true)
                .fluentPut("models", reportOverviewSummarizer.listModelMetrics(judged))
                .fluentPut("brands", reportCompetitorSummarizer.listBrandVisibilityByModel(judged, project.getString("name"), competitors));
    }

    /**
     * 3 机会清单：从该地域的回答里按固定规则挑出可以争取的点，每条带编号、数字和证据；没有数据的类型不出现。
     * @param prefix 编号前缀，如 R1
     * @param regionFacts 该地域的全部回答，来源统计包含判定失败的回答
     * @param judged 该地域判定成功的回答
     * @param targetNames 目标名称与别名
     * @param dimensionMapping 维度归一映射
     * @return 机会列表，每条含 id、type、title、metrics、evidence
     */
    private JSONArray listOpportunities(String prefix, List<ReportAnswerFacts> regionFacts, List<ReportAnswerFacts> judged,
                                        List<String> targetNames, Map<String, String> dimensionMapping) {
        JSONArray opportunities = new JSONArray();
        List<ReportAnswerFacts> nonBrand = judged.stream().filter(ReportAnswerFacts::isNonBrandQuestion).toList();
        // 1. 目标从未出现的非品牌题：有别的品牌被推荐是竞品占位，没有任何品牌被推荐是空白题
        addMissedQuestionOpportunities(opportunities, prefix, nonBrand);
        // 2. 目标已出现但位置不理想：提到却没被推荐是正向点名的对象，被推荐却不是首推是提升排名的对象
        addTargetPositionOpportunities(opportunities, prefix, nonBrand);
        // 3. 内容方向：用户在意的标准与顾虑，以及被说到的缺点、负面评价与错误信息
        addUserFocusOpportunity(opportunities, prefix, judged, dimensionMapping);
        addPerceptionGapOpportunity(opportunities, prefix, judged, targetNames, dimensionMapping);
        // 4. 渠道：该地域常被引用、但没有我方内容的平台
        addUncoveredPlatformOpportunity(opportunities, prefix, regionFacts);
        return opportunities;
    }

    /**
     * 目标在所有回答里都没出现的非品牌题，按是否有别的品牌被推荐，分成竞品占位与空白题两类机会。
     * @param opportunities 机会列表，满足条件时追加
     * @param prefix 编号前缀
     * @param nonBrand 该地域判定成功的非品牌回答
     */
    private void addMissedQuestionOpportunities(JSONArray opportunities, String prefix, List<ReportAnswerFacts> nonBrand) {
        // 1. 按题判断：所有回答都没提到目标的题，再按是否有别的品牌被推荐分成两类
        List<List<ReportAnswerFacts>> missed = groupByQuestion(nonBrand).stream()
                .filter(group -> group.stream().noneMatch(ReportAnswerFacts::mentioned)).toList();
        List<List<ReportAnswerFacts>> occupied = missed.stream().filter(group -> group.stream().anyMatch(this::hasOtherBrandRecommended)).toList();
        List<List<ReportAnswerFacts>> blank = missed.stream().filter(group -> group.stream().noneMatch(this::hasOtherBrandRecommended)).toList();
        // 2. 每类各记一个机会，证据是题目本身，写内容和复测都围绕这些题
        if (!occupied.isEmpty()) {
            addOpportunity(opportunities, prefix, "COMPETITOR_OCCUPIED",
                    occupied.size() + " 道非品牌题从未提到目标，被推荐的是：" + joinTopRecommendedBrands(occupied),
                    List.of(buildMetric("题数", occupied.size())), listQuestionEvidence(occupied));
        }
        if (!blank.isEmpty()) {
            addOpportunity(opportunities, prefix, "WHITE_SPACE", blank.size() + " 道非品牌题从未提到目标，也没有任何品牌被推荐",
                    List.of(buildMetric("题数", blank.size())), listQuestionEvidence(blank));
        }
    }

    /**
     * 目标已出现但位置不理想的非品牌回答：提到却没被推荐，或被推荐却不是首推（排名靠后、并列或附带缺点）。
     * @param opportunities 机会列表，满足条件时追加
     * @param prefix 编号前缀
     * @param nonBrand 该地域判定成功的非品牌回答
     */
    private void addTargetPositionOpportunities(JSONArray opportunities, String prefix, List<ReportAnswerFacts> nonBrand) {
        // 1. 按回答判断目标的位置：提到未推荐、推荐未首推
        List<ReportAnswerFacts> mentionedOnly = nonBrand.stream().filter(fact -> fact.mentioned() && !fact.isTargetRecommended()).toList();
        List<ReportAnswerFacts> notFirst = nonBrand.stream().filter(fact -> fact.isTargetRecommended() && !fact.isTargetFirstChoice()).toList();
        // 2. 各记一个机会，证据按题去重；推荐未首推另给平均排名，说明离首推有多远
        if (!mentionedOnly.isEmpty()) {
            addOpportunity(opportunities, prefix, "MENTIONED_NOT_RECOMMENDED", "目标被提到但没被推荐的回答 " + mentionedOnly.size() + " 条",
                    List.of(buildMetric("回答数", mentionedOnly.size()), buildMetric("题数", groupByQuestion(mentionedOnly).size())),
                    listQuestionEvidence(groupByQuestion(mentionedOnly)));
        }
        if (!notFirst.isEmpty()) {
            List<Integer> ranks = notFirst.stream().map(fact -> fact.target().getInteger("rank")).filter(Objects::nonNull).toList();
            Double averageRank = ranks.isEmpty() ? null : round(ranks.stream().mapToInt(Integer::intValue).average().orElse(0));
            addOpportunity(opportunities, prefix, "NOT_FIRST_CHOICE", "目标被推荐但不是首推的回答 " + notFirst.size() + " 条",
                    List.of(buildMetric("回答数", notFirst.size()), buildMetric("平均排名", averageRank)),
                    listQuestionEvidence(groupByQuestion(notFirst)));
        }
    }

    /**
     * 用户在意的内容方向：没提到目标的非品牌回答里出现最多的选择标准与顾虑，是要补的内容。
     * @param opportunities 机会列表，有数据时追加
     * @param prefix 编号前缀
     * @param judged 该地域判定成功的回答
     * @param dimensionMapping 维度归一映射
     */
    private void addUserFocusOpportunity(JSONArray opportunities, String prefix, List<ReportAnswerFacts> judged,
                                         Map<String, String> dimensionMapping) {
        // 1. 只看没提到目标的非品牌回答，它们强调的标准和顾虑就是我方内容没覆盖到的方向
        Predicate<ReportAnswerFacts> withoutTarget = fact -> fact.isNonBrandQuestion() && !fact.mentioned();
        List<JSONObject> criteria = topRows(reportDimensionRanking.listDimensionRanking(judged, withoutTarget, "SELECTION_CRITERION", null, dimensionMapping));
        List<JSONObject> concerns = topRows(reportDimensionRanking.listDimensionRanking(judged, withoutTarget, "USER_CONCERN", null, dimensionMapping));
        if (criteria.isEmpty() && concerns.isEmpty()) {
            return;
        }
        // 2. 标准与顾虑合成一个机会，标题分开写，每个维度取一句原文作证据
        List<JSONObject> rows = Stream.concat(criteria.stream(), concerns.stream()).toList();
        addOpportunity(opportunities, prefix, "USER_FOCUS", joinLabelled(List.of(labelDimensions("选择标准", criteria), labelDimensions("顾虑", concerns))),
                listDimensionMetrics(rows), listDimensionEvidence(rows));
    }

    /**
     * 需要改善的口碑：推荐目标时附带的缺点、点名题里的负面评价、与项目资料不符的错误信息，是品牌优化的依据。
     * @param opportunities 机会列表，有数据时追加
     * @param prefix 编号前缀
     * @param judged 该地域判定成功的回答
     * @param targetNames 目标名称与别名，缺点只统计针对目标的保留意见
     * @param dimensionMapping 维度归一映射
     */
    private void addPerceptionGapOpportunity(JSONArray opportunities, String prefix, List<ReportAnswerFacts> judged,
                                             List<String> targetNames, Map<String, String> dimensionMapping) {
        // 1. 收集针对目标的缺点、点名题的负面评价与错误信息，都没有时不记机会
        List<JSONObject> reservations = topRows(reportDimensionRanking.listDimensionRanking(judged, fact -> true, "RESERVATION", targetNames, dimensionMapping));
        List<JSONObject> negatives = topRows(reportDimensionRanking.listDimensionRanking(judged, ReportAnswerFacts::isBrandQuestion, "NEGATIVE", null, dimensionMapping));
        JSONArray factErrors = reportBrandSummarizer.listFactErrors(judged.stream().filter(ReportAnswerFacts::isBrandQuestion).toList());
        if (reservations.isEmpty() && negatives.isEmpty() && factErrors.isEmpty()) {
            return;
        }
        // 2. 维度排行各取一句原文作证据，错误信息逐条作证据并写明错在哪
        List<JSONObject> rows = Stream.concat(reservations.stream(), negatives.stream()).toList();
        List<JSONObject> metrics = new ArrayList<>(listDimensionMetrics(rows));
        JSONArray evidence = listDimensionEvidence(rows);
        if (!factErrors.isEmpty()) {
            metrics.add(buildMetric("错误信息", factErrors.size()));
            factErrors.stream().map(JSONObject.class::cast).limit(TOP_LIMIT).forEach(error -> evidence.add(new JSONObject(true)
                    .fluentPut("resultId", error.getLong("resultId"))
                    .fluentPut("channelName", error.getString("channelName"))
                    .fluentPut("brand", error.getString("content"))
                    .fluentPut("quote", error.getString("quote"))));
        }
        // 3. 标题按缺点、负面评价、错误信息分段写，只写有内容的部分
        String errorPart = factErrors.isEmpty() ? null : "错误信息 " + factErrors.size() + " 条";
        addOpportunity(opportunities, prefix, "PERCEPTION_GAP",
                "需要改善的口碑：" + joinLabelled(List.of(labelDimensions("缺点", reservations), labelDimensions("负面评价", negatives), Objects.toString(errorPart, ""))),
                metrics, evidence);
    }

    /**
     * 渠道机会：该地域回答常引用、但从未引用到我方内容（官网或我方发布）的平台，是内容投放的去处。
     * @param opportunities 机会列表，有数据时追加
     * @param prefix 编号前缀
     * @param regionFacts 该地域的全部回答，判定失败的回答也有规则归类的来源
     */
    private void addUncoveredPlatformOpportunity(JSONArray opportunities, String prefix, List<ReportAnswerFacts> regionFacts) {
        // 1. 按平台统计引用次数，记下平台上是否出现过我方内容，每个平台留第一条链接作证据
        Map<String, JSONObject> byPlatform = new LinkedHashMap<>();
        for (ReportAnswerFacts fact : regionFacts) {
            for (Object value : fact.sources()) {
                JSONObject source = (JSONObject) value;
                if (source.getString("platform") == null) {
                    continue;
                }
                JSONObject row = byPlatform.computeIfAbsent(source.getString("platform"), key -> new JSONObject(true)
                        .fluentPut("platform", key).fluentPut("count", 0).fluentPut("own", false)
                        .fluentPut("evidence", new JSONObject(true).fluentPut("resultId", fact.answer().result().getId())
                                .fluentPut("channelName", fact.channelName()).fluentPut("url", source.getString("url"))
                                .fluentPut("title", source.getString("title"))));
                row.put("count", row.getIntValue("count") + 1);
                row.put("own", row.getBooleanValue("own") || OWN_SOURCE_TYPES.contains(source.getString("sourceType")));
            }
        }
        // 2. 只留没有我方内容的平台，按引用次数取前几个
        List<JSONObject> uncovered = byPlatform.values().stream().filter(row -> !row.getBooleanValue("own"))
                .sorted((left, right) -> Integer.compare(right.getIntValue("count"), left.getIntValue("count"))).limit(TOP_LIMIT).toList();
        if (uncovered.isEmpty()) {
            return;
        }
        JSONArray evidence = new JSONArray();
        uncovered.forEach(row -> evidence.add(row.getJSONObject("evidence")));
        addOpportunity(opportunities, prefix, "UNCOVERED_PLATFORM",
                "常被引用但没有我方内容的平台：" + uncovered.stream().map(row -> row.getString("platform")).collect(Collectors.joining("、")),
                uncovered.stream().map(row -> buildMetric(row.getString("platform") + " 引用", row.getIntValue("count"))).toList(), evidence);
    }

    /**
     * 追加一个机会，编号为前缀加地域内的序号（R1-O1、R1-O2…），全报告唯一。
     * @param opportunities 机会列表
     * @param prefix 编号前缀
     * @param type 机会类型
     * @param title 一句话说明机会
     * @param metrics 支撑机会的数字
     * @param evidence 证据：resultId、channelName，以及 question（问题原文）、quote（回答原文）或 url、title（来源链接）
     */
    private void addOpportunity(JSONArray opportunities, String prefix, String type, String title, List<JSONObject> metrics, JSONArray evidence) {
        opportunities.add(new JSONObject(true)
                .fluentPut("id", prefix + "-O" + (opportunities.size() + 1))
                .fluentPut("type", type)
                .fluentPut("title", title)
                .fluentPut("metrics", new JSONArray(new ArrayList<>(metrics)))
                .fluentPut("evidence", evidence));
    }

    /**
     * 机会下的一个支撑数字，结构与 ⑥ 规则问题的数字相同，页面共用同一种展示；这里的数字都是次数或排名，不是比率。
     * @param label 指标名称
     * @param value 指标值，可为 null
     * @return 支撑数字：label、value、rate
     */
    private JSONObject buildMetric(String label, Object value) {
        return new JSONObject(true).fluentPut("label", label).fluentPut("value", value).fluentPut("rate", false);
    }

    /**
     * 把回答按问题分组，按问题序号排列；同一题在不同模型、不同重复执行次数下的回答在一组。
     * @param facts 回答事实
     * @return 每题一组
     */
    private List<List<ReportAnswerFacts>> groupByQuestion(List<ReportAnswerFacts> facts) {
        return new ArrayList<>(facts.stream()
                .collect(Collectors.groupingBy(ReportAnswerFacts::questionSortOrder, TreeMap::new, Collectors.toList())).values());
    }

    /**
     * 判断回答里是否有目标以外的品牌（预设竞品或待确认产品）被推荐。
     * @param fact 判定成功的回答
     * @return 有其他品牌被推荐时为 true
     */
    private boolean hasOtherBrandRecommended(ReportAnswerFacts fact) {
        return fact.brands().stream().map(JSONObject.class::cast)
                .anyMatch(brand -> !"TARGET".equals(brand.getString("type")) && ReportAnswerFacts.isRecommended(brand));
    }

    /**
     * 竞品占位的题里被推荐次数最多的其他品牌，说明这些题被谁占着。
     * @param groups 竞品占位的题，每题一组回答
     * @return 前几个品牌名，用顿号连接
     */
    private String joinTopRecommendedBrands(List<List<ReportAnswerFacts>> groups) {
        Map<String, Long> counts = groups.stream().flatMap(List::stream).flatMap(fact -> fact.brands().stream().map(JSONObject.class::cast))
                .filter(brand -> !"TARGET".equals(brand.getString("type")) && ReportAnswerFacts.isRecommended(brand))
                .collect(Collectors.groupingBy(brand -> brand.getString("name"), LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(TOP_LIMIT)
                .map(Map.Entry::getKey).collect(Collectors.joining("、"));
    }

    /**
     * 把题目转成问题类证据，每题取第一条回答，页面显示问题原文并可打开该回答。
     * @param groups 每题一组回答
     * @return 前 EVIDENCE_LIMIT 道题的证据：resultId、channelName、question
     */
    private JSONArray listQuestionEvidence(List<List<ReportAnswerFacts>> groups) {
        JSONArray evidence = new JSONArray();
        groups.stream().limit(EVIDENCE_LIMIT).map(group -> group.get(0)).forEach(fact -> evidence.add(new JSONObject(true)
                .fluentPut("resultId", fact.answer().result().getId())
                .fluentPut("channelName", fact.channelName())
                .fluentPut("question", fact.questionText())));
        return evidence;
    }

    /**
     * 取维度排行中次数最多的几项。
     * @param ranking 已按次数降序的维度排行
     * @return 前 TOP_LIMIT 项
     */
    private List<JSONObject> topRows(JSONArray ranking) {
        return ranking.stream().map(JSONObject.class::cast).limit(TOP_LIMIT).toList();
    }

    /**
     * 维度行的支撑数字：每个维度的出现次数。
     * @param rows 维度排行行
     * @return 每行一个数字
     */
    private List<JSONObject> listDimensionMetrics(List<JSONObject> rows) {
        return rows.stream().map(row -> buildMetric(row.getString("dimension"), row.getIntValue("count"))).toList();
    }

    /**
     * 维度行的证据：每个维度取第一句原文。
     * @param rows 维度排行行，examples 由维度排行生成
     * @return 每个维度一条原文证据：resultId、channelName、brand、quote
     */
    private JSONArray listDimensionEvidence(List<JSONObject> rows) {
        JSONArray evidence = new JSONArray();
        rows.stream().map(row -> row.getJSONArray("examples")).filter(examples -> !examples.isEmpty())
                .forEach(examples -> evidence.add(examples.getJSONObject(0)));
        return evidence;
    }

    /**
     * 给一组维度加上类别名，如「选择标准：价格、亮度」。
     * @param label 类别名
     * @param rows 维度排行行
     * @return 带类别的维度列表；没有维度时为空字符串
     */
    private String labelDimensions(String label, List<JSONObject> rows) {
        return rows.isEmpty() ? "" : label + "：" + rows.stream().map(row -> row.getString("dimension")).distinct().collect(Collectors.joining("、"));
    }

    /**
     * 用分号连接非空的几段说明。
     * @param parts 各段说明，空字符串跳过
     * @return 连接后的说明
     */
    private String joinLabelled(List<String> parts) {
        return parts.stream().filter(part -> !part.isEmpty()).collect(Collectors.joining("；"));
    }
}
