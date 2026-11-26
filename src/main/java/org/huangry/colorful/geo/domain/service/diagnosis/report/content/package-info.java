/**
 * 诊断报告第 2 段「生成报告内容」（{@code DiagnosisReportStage.CONTENT}）：判定每条回答之后，把判定结果汇成整份报告。
 * <p>四个子步骤按下面的顺序执行，一个子包对应一步，包名与页面上的子步骤、{@code DiagnosisReportContentProgress.Step} 一致；
 * 包名的字母序不代表顺序，顺序以执行器 {@code DiagnosisReportWorker} 的调用为准：</p>
 * <ol>
 *   <li>{@code dimension}：合并同义维度，入口 {@code DimensionNormalizer}，调用 1 次模型，失败时维度保持原名、后面照常统计</li>
 *   <li>{@code summary}：统计页面 ⓪–⑦ 的指标、规则问题与各地域机会，入口 {@code ReportSummarizer}，纯计算，不调用模型</li>
 *   <li>{@code advice}：写优化建议（页面 ⑥），入口 {@code AdviceGenerator}，整份报告调用 1 次模型</li>
 *   <li>{@code strategy}：写地域提升策略（页面 ⑦），入口 {@code RegionStrategyGenerator}，每个有机会的地域调用 1 次模型</li>
 * </ol>
 * <p>第 3、4 步失败时报告保存已生成的内容并暂停在本段，由用户选择重试或忽略失败继续；本段只引用第 1 段 {@code judge} 的判定口径，第 1 段不引用本段。</p>
 */
package org.huangry.colorful.geo.domain.service.diagnosis.report.content;
