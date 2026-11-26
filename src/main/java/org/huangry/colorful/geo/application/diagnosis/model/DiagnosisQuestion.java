package org.huangry.colorful.geo.application.diagnosis.model;

/**
 * 诊断方案里一道问题的接口数据：保存方案时的输入，也是生成、按地域改写候选时的输出。
 * <p>意图和品牌范围相互独立，来源保留供后续追踪；地域与语言相互独立，地域为空表示不分地域。</p>
 */
public record DiagnosisQuestion(String text, Source source, Intent intent, BrandScope brandScope, String language, String region) {
    /** 人工维护或模型生成。 */
    public enum Source { MANUAL, GENERATED }
    /** 用户提问目的，侧面问题通常属于问题解决或行业了解。 */
    public enum Intent { RECOMMENDATION, PROBLEM_SOLVING, COMPARISON, PURCHASE, TECHNICAL, AWARENESS }
    /** 决定是否可纳入自然发现统计，含项目名称或别名的问题不能标为非品牌。 */
    public enum BrandScope { NON_BRAND, BRAND, COMPARISON }
}
