package org.huangry.colorful.geo.domain.service.diagnosis.report.content.summary;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.domain.service.diagnosis.report.judge.ResultRuleAnalyzer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.huangry.colorful.geo.domain.service.diagnosis.report.content.summary.ReportMetrics.calculateRate;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.content.summary.ReportMetrics.countMatching;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.content.summary.ReportMetrics.groupByChannel;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.content.summary.ReportMetrics.listPoints;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.content.summary.ReportMetrics.round;

/**
 * <pre>
 * 诊断报告 ⓪ 诊断大盘的数字：一次诊断任务里目标与预设竞品被看见的程度，以及目标被信任、被喜欢的程度
 *
 * 负责：
 * - 被看见：目标与每个预设竞品的提及率、首推率、前三率、平均排名、提及份额
 * - 被信任：我方内容被引用的次数与比率、官网引用率、描述准确率
 * - 被喜欢：点名问题里评价的正面、中性、负面分布与卖点提及率
 * - 同一套数字出两份：所有模型合在一起算的合计，和每个模型各算一份（页面按模型筛选时用）
 *
 * 核心入口：
 * - buildDashboard(...)：ReportSummarizer 在报告第 2 段统计步骤里调用，结果随报告冻结在 summary.dashboard
 *
 * 约束：
 * - 纯计算，不调用模型、不访问数据库
 * - 被看见的指标只算判定成功的非品牌问题回答，口径与 ① 概览、④ 竞品对比相同；对比问题点了竞品的名，不计入
 * - 合计是把各模型的回答放在一起重新算，不是各模型比率的平均
 * - 分母为 0 的比率为 null，页面显示为“—”
 *
 * 不负责：
 * - 历次任务的趋势与“较上次”：打开报告详情时由 DiagnosisReportBusiness 读取同方案更早任务的大盘拼出
 * - ①–⑦ 各页签自己的统计，本类不改它们的口径和输出
 * </pre>
 */
@Component
public class ReportDashboardSummarizer {
    /** 前三率的排名上限：被推荐且排名不超过它算进前三。 */
    private static final int TOP_RANK_LIMIT = 3;

    @Resource
    private ResultRuleAnalyzer resultRuleAnalyzer;

    /**
     * ⓪ 诊断大盘：算出合计与各模型的两份数字。
     * @param facts 报告的全部回答事实（含判定失败的；我方内容被引用率不依赖判定）
     * @param project 任务快照中的项目资料，取目标名称与预设竞品
     * @return dashboard 对象：questions 非品牌问题数（用来看两次任务的题目有没有变）；
     *         total 所有模型合计；models 每个模型一行，带 channelId、channelName、modelName，其余字段与 total 相同。
     *         total 的字段见 {@link #buildScope}
     */
    public JSONObject buildDashboard(List<ReportAnswerFacts> facts, JSONObject project) {
        String targetName = project.getString("name");
        List<String> competitors = resultRuleAnalyzer.convertToStringList(project.get("competitors"));
        // 1. 合计：所有模型的回答放在一起算
        JSONObject dashboard = new JSONObject(true)
                .fluentPut("questions", facts.stream().filter(ReportAnswerFacts::isNonBrandQuestion)
                        .map(ReportAnswerFacts::questionSortOrder).distinct().count())
                .fluentPut("total", buildScope(facts, targetName, competitors));
        // 2. 各模型：每个模型的回答单独算一份，结构与合计相同
        JSONArray models = new JSONArray();
        for (List<ReportAnswerFacts> channelFacts : groupByChannel(facts).values()) {
            ReportAnswerFacts first = channelFacts.get(0);
            JSONObject model = new JSONObject(true)
                    .fluentPut("channelId", first.channelId())
                    .fluentPut("channelName", first.channelName())
                    .fluentPut("modelName", first.modelName());
            model.putAll(buildScope(channelFacts, targetName, competitors));
            models.add(model);
        }
        return dashboard.fluentPut("models", models);
    }

    /**
     * 对一组回答（全部模型或单个模型）算一份大盘数字。
     * @param facts 这组回答事实（含判定失败的）
     * @param targetName 目标名称
     * @param competitors 预设竞品名称
     * @return nonBrandAnswers 判定成功的非品牌回答数（被看见各比率的分母）；
     *         brands 目标与预设竞品各一行，见 {@link #listBrandVisibility}；
     *         reputation 目标被信任、被喜欢的数字，见 {@link #buildReputation}
     */
    private JSONObject buildScope(List<ReportAnswerFacts> facts, String targetName, List<String> competitors) {
        List<ReportAnswerFacts> judged = facts.stream().filter(ReportAnswerFacts::judged).toList();
        List<ReportAnswerFacts> nonBrand = judged.stream().filter(ReportAnswerFacts::isNonBrandQuestion).toList();
        return new JSONObject(true)
                .fluentPut("nonBrandAnswers", nonBrand.size())
                .fluentPut("brands", listBrandVisibility(nonBrand, targetName, competitors))
                .fluentPut("reputation", buildReputation(facts, judged));
    }

    /**
     * 被看见：目标与每个预设竞品在非品牌回答里的提及、首推、前三、平均排名，以及各自占全部提及的份额。
     * @param nonBrand 判定成功的非品牌回答
     * @param targetName 目标名称
     * @param competitors 预设竞品名称
     * @return 第一行是目标，其后按项目资料的顺序是预设竞品；每行字段见 {@link #buildBrandRow}，另有 mentionShare
     *         （该品牌提及次数 ÷ 所有行提及次数之和，都没被提及时为 null）
     */
    private JSONArray listBrandVisibility(List<ReportAnswerFacts> nonBrand, String targetName, List<String> competitors) {
        JSONArray rows = new JSONArray();
        // 1. 目标：提及看规则匹配名称或别名（与 ① 相同），推荐与排名看判定里的目标条目
        rows.add(buildBrandRow(nonBrand, targetName, true, ReportAnswerFacts::mentioned, ReportAnswerFacts::target));
        // 2. 预设竞品：判定列出了该竞品，或回答原文出现了竞品名称，都算提及（与 ④ 相同）
        for (String competitor : competitors) {
            rows.add(buildBrandRow(nonBrand, competitor, false,
                    fact -> fact.findBrand(competitor) != null
                            || resultRuleAnalyzer.containsAnyName(fact.answer().result().getAnswerText(), List.of(competitor)),
                    fact -> fact.findBrand(competitor)));
        }
        // 3. 提及份额：分母是目标加预设竞品的提及次数之和，待确认的新产品不计入
        long totalMentions = rows.stream().mapToLong(row -> ((JSONObject) row).getLongValue("mentions")).sum();
        for (Object row : rows) {
            JSONObject brand = (JSONObject) row;
            brand.put("mentionShare", calculateRate(brand.getLongValue("mentions"), totalMentions));
        }
        return rows;
    }

    /**
     * 算一个品牌在非品牌回答里的一行数字，比率的分母都是非品牌回答数。
     * @param nonBrand 判定成功的非品牌回答
     * @param brandName 品牌名称
     * @param target 是否目标
     * @param mentioned 判断一条回答是否提到该品牌
     * @param brandItem 取一条回答的判定里该品牌的条目，回答没列出时返回 null
     * @return brandName、target、mentions（提及次数）、mentionRate、firstChoiceRate、top3Rate、
     *         averageRank（被推荐且有明确排名的回答的平均排名，没有这类回答时为 null）、rankedAnswers（这类回答的条数）
     */
    private JSONObject buildBrandRow(List<ReportAnswerFacts> nonBrand, String brandName, boolean target,
                                     Predicate<ReportAnswerFacts> mentioned, Function<ReportAnswerFacts, JSONObject> brandItem) {
        long mentions = countMatching(nonBrand, mentioned);
        long firstChoice = countMatching(nonBrand, fact -> ReportAnswerFacts.isFirstChoice(brandItem.apply(fact)));
        long top3 = countMatching(nonBrand, fact -> isTop3(brandItem.apply(fact)));
        List<Integer> ranks = nonBrand.stream().map(fact -> ReportAnswerFacts.rankWhenRecommended(brandItem.apply(fact)))
                .filter(Objects::nonNull).toList();
        return new JSONObject(true)
                .fluentPut("brandName", brandName)
                .fluentPut("target", target)
                .fluentPut("mentions", mentions)
                .fluentPut("mentionRate", calculateRate(mentions, nonBrand.size()))
                .fluentPut("firstChoiceRate", calculateRate(firstChoice, nonBrand.size()))
                .fluentPut("top3Rate", calculateRate(top3, nonBrand.size()))
                .fluentPut("averageRank", ranks.isEmpty() ? null : round(ranks.stream().mapToInt(Integer::intValue).average().orElse(0)))
                .fluentPut("rankedAnswers", ranks.size());
    }

    /**
     * 判断品牌是否进了前三：被推荐且排名不超过 3；判定为首推但没写排名的也算，避免首推率高于前三率。
     * @param brand 判定中的品牌条目，可为 null
     * @return 进了前三时为 true
     */
    private boolean isTop3(JSONObject brand) {
        Integer rank = ReportAnswerFacts.rankWhenRecommended(brand);
        return ReportAnswerFacts.isFirstChoice(brand) || (rank != null && rank <= TOP_RANK_LIMIT);
    }

    /**
     * 被信任与被喜欢：带来源的回答有没有用上我方内容、用户点名问目标时描述准不准，以及评价好不好、说没说到卖点。
     * @param facts 这组回答事实（含判定失败的；来源归类在判定前由规则完成，判定失败的回答也有）
     * @param judged 其中判定成功的回答
     * @return 被信任：sourcedAnswers（带来源的回答数）、ownCitations（官网与我方发布内容被引用的次数，一条回答引用几条算几次）、
     *         ownCitedRate（带来源的回答里至少引用一条官网或我方发布内容的占比）、ownedRate（至少引用一条官网内容的占比，与 ⑤ 同口径）、
     *         accurateRate（点名问题里判定给出了对错的回答中描述准确的占比，与 ③ 同口径）；
     *         被喜欢：sentimentAnswers（点名问题里有情感判定的回答数）、positive、neutral、negative、positiveRate（与 ① 的品牌正面率同口径）、
     *         sellingPointRate（点名问题的回答里说到至少一个核心卖点的占比，与 ③ 同口径）
     */
    private JSONObject buildReputation(List<ReportAnswerFacts> facts, List<ReportAnswerFacts> judged) {
        // 1. 被信任·引用：分母是带来源的回答；官网与我方发布都算我方内容
        List<ReportAnswerFacts> sourced = facts.stream().filter(ReportAnswerFacts::hasSources).toList();
        long ownCitations = sourced.stream().mapToLong(fact -> countSources(fact, ResultRuleAnalyzer.SOURCE_OWNED)
                + countSources(fact, ResultRuleAnalyzer.SOURCE_PUBLISHED)).sum();
        long ownCited = countMatching(sourced, fact -> countSources(fact, ResultRuleAnalyzer.SOURCE_OWNED)
                + countSources(fact, ResultRuleAnalyzer.SOURCE_PUBLISHED) > 0);
        long ownedCited = countMatching(sourced, fact -> countSources(fact, ResultRuleAnalyzer.SOURCE_OWNED) > 0);
        // 2. 被信任·准确：只看点名问题，判定没填对错的回答不算准确也不算错误
        List<ReportAnswerFacts> brandFacts = judged.stream().filter(ReportAnswerFacts::isBrandQuestion).toList();
        List<Boolean> accuracies = brandFacts.stream().map(fact -> fact.answer().analysis().getDescriptionAccurate()).filter(Objects::nonNull).toList();
        // 3. 被喜欢：情感只算判定给出了情感的点名回答；卖点看判定有没有在回答里找到核心卖点
        List<String> sentiments = brandFacts.stream().map(fact -> fact.answer().analysis().getBrandSentiment()).filter(Objects::nonNull).toList();
        long positive = countMatching(sentiments, "POSITIVE"::equals);
        long coveredSellingPoint = countMatching(brandFacts, fact -> listPoints(fact.answer()).stream()
                .anyMatch(point -> "SELLING_POINT_COVERED".equals(point.getString("type"))));
        return new JSONObject(true)
                .fluentPut("sourcedAnswers", sourced.size())
                .fluentPut("ownCitations", ownCitations)
                .fluentPut("ownCitedRate", calculateRate(ownCited, sourced.size()))
                .fluentPut("ownedRate", calculateRate(ownedCited, sourced.size()))
                .fluentPut("accurateRate", calculateRate(countMatching(accuracies, Boolean::booleanValue), accuracies.size()))
                .fluentPut("sentimentAnswers", sentiments.size())
                .fluentPut("positive", positive)
                .fluentPut("neutral", countMatching(sentiments, "NEUTRAL"::equals))
                .fluentPut("negative", countMatching(sentiments, "NEGATIVE"::equals))
                .fluentPut("positiveRate", calculateRate(positive, sentiments.size()))
                .fluentPut("sellingPointRate", calculateRate(coveredSellingPoint, brandFacts.size()));
    }

    /**
     * 数一条回答引用了几条某类来源。
     * @param fact 一条回答事实
     * @param sourceType 来源类型，取值见 ResultRuleAnalyzer.SOURCE_*
     * @return 该类来源的条数
     */
    private long countSources(ReportAnswerFacts fact, String sourceType) {
        return fact.sources().stream().filter(source -> sourceType.equals(((JSONObject) source).getString("sourceType"))).count();
    }
}
