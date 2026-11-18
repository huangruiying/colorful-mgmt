package org.huangry.colorful.geo.application.diagnosis.model;

/**
 * 生成诊断报告的接口入参：本次报告用什么语言书写解析内容。
 * <p>诊断任务由路径指定；请求体可省略，省略或留空时报告语言为中文。</p>
 *
 * @param reportLanguage 报告语言，如中文、英语，最多 50 字符；观点概括、维度名、建议与地域策略按此语言书写，问题、回答、引用等原文不翻译
 */
public record CreateDiagnosisReportRequest(String reportLanguage) {}
