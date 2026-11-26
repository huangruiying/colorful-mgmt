package org.huangry.colorful.geo.domain.service.diagnosis.report.content.summary;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisReportService.ReportAnswer;

/**
 * <pre>
 * 诊断报告统计用的一条回答事实：一条回答属于哪个模型、哪道题、哪个问题分组，以及判定出的目标与品牌
 *
 * 负责：
 * - 承载各页签统计要用的数据，由 ReportSummarizer 从诊断结果与回答分析整理而来，各页签只读不改
 * - 给出问题分组的判断（非品牌题、点名题、对比题）和目标是否被推荐、首推等判断，各页签共用同一口径
 *
 * 约束：
 * - 点名题除了方案里的标记，还要求问题原文出现了目标名称或别名：标了点名题却问别的品牌的，回答里没有对目标的评价，
 *   不算点名题（见 isOffTargetBrandQuestion），否则会得出“问了目标 N 次、卖点一次没提”这种没发生过的结论
 * - 对比题、非品牌题只看方案里的标记。没问到目标的对比题仍是真实的对比场景，回答里推荐了谁、凭什么推荐照常统计；
 *   只有“目标在某个维度赢了还是输了”要逐条看是不是在说目标，由 ReportCompetitorSummarizer 判断
 * </pre>
 * @param answer 原始回答与分析
 * @param channelId 被测渠道 ID
 * @param channelName 被测渠道名称
 * @param modelName 被测模型
 * @param questionSortOrder 问题序号
 * @param questionText 问题原文，用作问题类证据
 * @param brandScope 方案里给问题标的分组（品牌范围）：NON_BRAND、BRAND、COMPARISON；统计时用下面的 is…Question 判断，不直接比较这个值
 * @param intent 问题意图
 * @param region 问题面向的地域，取自任务冻结的问题快照；不分地域或早于地域功能的快照为 null，⑦ 地域报告按它分组
 * @param targetNamed 问题原文是否出现了目标名称或别名，由 DiagnosisQuestionTextRule 判断；决定标为点名题的问题是否真的算，以及对比题的维度胜负要不要逐条核对
 * @param judged 判定是否成功
 * @param mentioned 是否提到目标
 * @param target 判定中的目标品牌项，未出现时为 null
 * @param brands 判定中的全部品牌项
 * @param searched 是否实际联网
 * @param sources 规则归类后的来源：url、title、domain、platform、sourceType
 */
public record ReportAnswerFacts(ReportAnswer answer, Long channelId, String channelName, String modelName, Integer questionSortOrder,
                                String questionText, String brandScope, String intent, String region, boolean targetNamed, boolean judged, boolean mentioned,
                                JSONObject target,
                                JSONArray brands, boolean searched, JSONArray sources) {
    /** 非品牌问题分组：问品类或场景，没有点名目标。 */
    private static final String NON_BRAND = "NON_BRAND";
    /** 点名问题分组：直接问目标。 */
    private static final String BRAND = "BRAND";
    /** 对比问题分组：目标与竞品对比。 */
    private static final String COMPARISON = "COMPARISON";

    /**
     * 判断是否非品牌问题；可见度类指标只统计这类问题。
     * @return 问题属于非品牌问题
     */
    public boolean isNonBrandQuestion() {
        return NON_BRAND.equals(brandScope);
    }

    /**
     * 判断是否点名问题；品牌认知只统计这类问题。
     * @return 方案里标为点名题，且问题原文确实问到了目标
     */
    public boolean isBrandQuestion() {
        return BRAND.equals(brandScope) && targetNamed;
    }

    /**
     * 判断是否对比问题；推荐份额、同题胜率、竞品被推荐的理由和分维度胜负统计这类问题。
     * @return 方案里标为对比题；问题有没有问到目标不影响这个判断，见 isOffTargetComparisonQuestion
     */
    public boolean isComparisonQuestion() {
        return COMPARISON.equals(brandScope);
    }

    /**
     * 判断是否“标了点名题，但问的不是目标”，例如只问某个竞品。
     * <p>这类回答不算点名题：不计入品牌认知的情感、准确率和卖点；仍是有效回答，照常计入样本与来源统计。</p>
     * @return 方案里标为点名题，但问题原文没有出现目标名称或别名
     */
    public boolean isOffTargetBrandQuestion() {
        return BRAND.equals(brandScope) && !targetNamed;
    }

    /**
     * 判断是否“标了对比题，但问题里没有目标”，例如只比较两个竞品，或比较两类产品。
     * <p>这类回答仍算对比题；只是其中的维度胜负未必在说目标，统计时要逐条核对文字里有没有点到目标。</p>
     * @return 方案里标为对比题，但问题原文没有出现目标名称或别名
     */
    public boolean isOffTargetComparisonQuestion() {
        return COMPARISON.equals(brandScope) && !targetNamed;
    }

    /**
     * 判断回答是否带来源：以规则归类后的来源为准，与 ⑤ 引用与内容的口径一致。
     * @return 至少有一条可识别网址的来源
     */
    public boolean hasSources() {
        return !sources.isEmpty();
    }

    /**
     * 判断判定结果中目标是否被推荐。
     * @return 目标被推荐
     */
    public boolean isTargetRecommended() {
        return isRecommended(target);
    }

    /**
     * 判断目标是否被首推。
     * @return 目标被推荐且排第一，或被判定为首推
     */
    public boolean isTargetFirstChoice() {
        return isFirstChoice(target);
    }

    /**
     * 在判定的品牌列表中查找某个品牌。
     * @param brandName 品牌名称；判定解析时已把竞品写法统一为预设名称，因此按名称精确查找
     * @return 判定中该品牌的条目，回答没提到时为 null
     */
    public JSONObject findBrand(String brandName) {
        return brands.stream().map(JSONObject.class::cast).filter(brand -> brandName.equals(brand.getString("name")))
                .findFirst().orElse(null);
    }

    /**
     * 判断某个品牌（如预设竞品）在这条回答里是否被推荐。
     * @param brandName 品牌名称
     * @return 该品牌被推荐；回答没提到时为 false
     */
    public boolean isBrandRecommended(String brandName) {
        return isRecommended(findBrand(brandName));
    }

    /**
     * 判断某个品牌（如预设竞品）在这条回答里是否被首推。
     * @param brandName 品牌名称
     * @return 该品牌被推荐且排第一，或被判定为首推
     */
    public boolean isBrandFirstChoice(String brandName) {
        return isFirstChoice(findBrand(brandName));
    }

    /**
     * 判断判定中的某个品牌条目是否被推荐。
     * @param brand 判定中的品牌条目，可为 null
     * @return 该品牌被推荐
     */
    public static boolean isRecommended(JSONObject brand) {
        return brand != null && Boolean.TRUE.equals(brand.getBoolean("recommended"));
    }

    /**
     * 判断某个品牌是否被首推：被推荐且排第一，或判定为首推。
     * @param brand 判定中的品牌条目，可为 null
     * @return 该品牌被推荐且排第一，或被判定为首推
     */
    public static boolean isFirstChoice(JSONObject brand) {
        return isRecommended(brand) && (Integer.valueOf(1).equals(brand.getInteger("rank"))
                || "FIRST_CHOICE".equals(brand.getString("strength")));
    }

    /**
     * 取品牌被推荐时的排名，用于同题胜率比较先后。
     * @param brand 判定中的品牌条目，可为 null
     * @return 被推荐时的排名；没被推荐或没有排名时为 null
     */
    public static Integer rankWhenRecommended(JSONObject brand) {
        return isRecommended(brand) ? brand.getInteger("rank") : null;
    }
}
