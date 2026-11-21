package org.huangry.colorful.geo.application.prompt.model;

/**
 * 保存提示词新版本的请求。
 *
 * @param content 编辑后的模板全文，{{变量名}} 只能使用该提示词声明的变量
 */
public record SavePromptTemplateRequest(String content) {
}
