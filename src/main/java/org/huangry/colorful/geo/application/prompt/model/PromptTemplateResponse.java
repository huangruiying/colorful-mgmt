package org.huangry.colorful.geo.application.prompt.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.huangry.colorful.geo.infrastructure.common.enums.PromptTemplateKey.PromptVariable;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提示词管理接口的响应：一个可维护的提示词及其某一版（当前版本或所查版本）。
 * 列表不返回模板内容；还没配置的提示词没有版本号与内容。顶层空字段不输出到 JSON。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PromptTemplateResponse(
        /** 提示词标识，PromptTemplateKey 的枚举名，如 DIAGNOSIS_REPORT_ADVICE。 */
        String key,
        /** 页面显示的描述，如“诊断报告-优化建议提示词”。 */
        String title,
        /** 模板可用的变量：变量名、说明、是否必填，按页面显示顺序。 */
        List<PromptVariable> variables,
        /** 这一版的版本号；还没配置时为空。 */
        Integer version,
        /** 模板内容，{{变量名}} 处由程序填入数据；列表不返回。 */
        String content,
        /** 这一版的保存时间。 */
        LocalDateTime createdAt
) {
}
