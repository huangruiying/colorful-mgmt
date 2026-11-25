package org.huangry.colorful.geo.domain.service.diagnosis.report.content.summary;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisReportService.ReportAnswer;
import org.huangry.colorful.geo.domain.service.diagnosis.report.judge.ResultRuleAnalyzer;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisReportResultAnalysisStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * <pre>
 * 诊断报告各页签统计数字的总入口：把报告的全部回答算成一份 summary，冻结在报告上供页面展示
 *
 * 负责：
 * - 把每条回答整理成统计用的事实（模型、问题分组、地域、是否提到目标、判定的品牌与来源）
 * - 按页签交给各自的汇总类计算并拼在一起：⓪ ReportDashboardSummarizer，① ReportOverviewSummarizer，
 *   ② ReportVisibilitySummarizer，③ ReportBrandSummarizer，④ ReportCompetitorSummarizer，⑤ ReportSourceSummarizer，
 *   ⑥ 规则问题 ReportIssueSummarizer，⑦ 地域报告 ReportRegionSummarizer
 *
 * 核心入口：
 * - buildSummary(...)：后台执行器在报告第 2 段合并同义维度之后调用，整份报告一次
 *
 * 约束：
 * - 纯计算，不调用模型、不访问数据库
 * - 结果冻结到报告 summary，之后不随规则或数据变化重算
 *
 * 不负责：
 * - ⑥ 的建议文字与 ⑦ 各地域的提升策略：由后台执行器调用模型生成后写入（AdviceGenerator、RegionStrategyGenerator）
 * - ⓪ 的历次任务趋势：打开报告详情时由 DiagnosisReportBusiness 现读
 * </pre>
 */
@Component
public class ReportSummarizer {
    @Resource
    private ResultRuleAnalyzer resultRuleAnalyzer;
    @Resource
    private DiagnosisTaskSnapshots diagnosisTaskSnapshots;
    @Resource
    private ReportOverviewSummarizer reportOverviewSummarizer;
    @Resource
    private ReportVisibilitySummarizer reportVisibilitySummarizer;
    @Resource
    private ReportBrandSummarizer reportBrandSummarizer;
    @Resource
    private ReportCompetitorSummarizer reportCompetitorSummarizer;
    @Resource
    private ReportSourceSummarizer reportSourceSummarizer;
    @Resource
    private ReportIssueSummarizer reportIssueSummarizer;
    @Resource
    private ReportRegionSummarizer reportRegionSummarizer;
    @Resource
    private ReportDashboardSummarizer reportDashboardSummarizer;

    /**
     * 计算并组装冻结的汇总。
     * @param answers 报告的全部回答（含判定失败的）
     * @param project 任务快照中的项目资料
     * @param dimensionMapping 维度归一映射，未覆盖的维度保持原名
     * @param judgeChannel 判定渠道快照
     * @param promptVersion 判定提示词版本
     * @return 汇总 JSON：①② 使用 sample、models、strength、reservations、criteria、concerns；
     *         ③ 品牌认知使用 brand，④ 竞品对比使用 competitor，⑤ 引用与内容使用 source，⑥ 使用 issues（advice 由后台执行器另行写入）；
     *         ⑦ 地域报告使用 region（各地域的 strategy 由后台执行器另行写入）；
     *         ⓪ 诊断大盘使用 dashboard
     */
    public JSONObject buildSummary(List<ReportAnswer> answers, JSONObject project, Map<String, String> dimensionMapping,
                                   JSONObject judgeChannel, String promptVersion) {
        // 1. 逐条整理成统计用的事实：模型、问题、分组、是否判定成功、是否提到目标、目标品牌项、来源
        List<String> targetNames = resultRuleAnalyzer.listTargetNames(project);
        List<ReportAnswerFacts> facts = answers.stream().map(answer -> buildAnswerFacts(answer, targetNames)).toList();
        List<ReportAnswerFacts> judged = facts.stream().filter(ReportAnswerFacts::judged).toList();
        // 2. ① 概览：样本说明、各模型核心指标；② 可见度：推荐强度、维度排行、按意图拆分
        JSONObject summary = new JSONObject(true);
        summary.putAll(reportOverviewSummarizer.buildOverview(facts, judged, judgeChannel, promptVersion));
        summary.putAll(reportVisibilitySummarizer.buildVisibility(judged, targetNames, dimensionMapping));
        // 3. ③④⑤ 各页签一个对象：品牌认知、竞品对比、引用与内容
        summary.put("brand", reportBrandSummarizer.buildBrandPerception(judged, dimensionMapping));
        summary.put("competitor", reportCompetitorSummarizer.buildCompetitorComparison(judged, project, dimensionMapping));
        summary.put("source", reportSourceSummarizer.buildSourceAnalysis(facts, judged, dimensionMapping));
        // 4. ⑥ 规则发现的问题：从上面算好的各部分里按阈值挑出问题并附证据，供模型写建议、页面查看证据
        summary.put("issues", reportIssueSummarizer.buildIssues(summary, judged));
        // 5. ⑦ 地域报告：按问题地域分组，算宣传状态、可见度与机会；不分地域的回答不计入
        summary.put("region", reportRegionSummarizer.buildRegionReport(facts, project, dimensionMapping));
        // 6. ⓪ 诊断大盘：合计与各模型的被看见、口碑与内容数字，直接从回答事实算，不依赖前面各部分的结果
        summary.put("dashboard", reportDashboardSummarizer.buildDashboard(facts, project));
        return summary;
    }

    /**
     * 把一条回答整理成各页签统计用的事实；判定失败的回答没有品牌与观点，但有来源。
     * @param answer 一条回答
     * @param targetNames 目标名称与别名
     * @return 统计用事实
     */
    private ReportAnswerFacts buildAnswerFacts(ReportAnswer answer, List<String> targetNames) {
        JSONObject channel = (JSONObject) diagnosisTaskSnapshots.parseSnapshot(answer.result().getChannelSnapshot());
        JSONObject question = (JSONObject) diagnosisTaskSnapshots.parseSnapshot(answer.result().getQuestionSnapshot());
        boolean judged = DiagnosisReportResultAnalysisStatus.SUCCEEDED.name().equals(answer.analysis().getStatus());
        JSONArray brands = judged && answer.analysis().getBrands() != null ? JSON.parseArray(answer.analysis().getBrands()) : new JSONArray();
        JSONObject target = brands.stream().map(JSONObject.class::cast).filter(brand -> "TARGET".equals(brand.getString("type")))
                .findFirst().orElse(null);
        // 提及以规则匹配名称或别名为准，判定识别出的其他称呼补充
        boolean mentioned = resultRuleAnalyzer.containsAnyName(answer.result().getAnswerText(), targetNames) || target != null;
        // 来源归类在判定前由规则完成，判定失败的回答也有，因此不看判定状态
        JSONArray sources = answer.analysis().getSources() != null ? JSON.parseArray(answer.analysis().getSources()) : new JSONArray();
        // 地域取自冻结的问题快照，早于地域功能的快照没有 region，按不分地域处理
        return new ReportAnswerFacts(answer, channel.getLong("id"), channel.getString("channelName"), channel.getString("modelName"),
                answer.result().getQuestionSortOrder(), question.getString("text"), question.getString("brandScope"), question.getString("intent"),
                question.getString("region"), judged, mentioned, target, brands,
                resultRuleAnalyzer.isSearchPerformed(answer.result().getRawResponse(), answer.result().getCitations()), sources);
    }
}
