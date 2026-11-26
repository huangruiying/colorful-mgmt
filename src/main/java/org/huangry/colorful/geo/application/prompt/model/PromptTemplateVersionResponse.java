package org.huangry.colorful.geo.application.prompt.model;

import java.time.LocalDateTime;

/**
 * 提示词历史版本列表中的一行：只有版本号与保存时间，内容按需另行读取。
 *
 * @param version   版本号
 * @param createdAt 保存时间
 */
public record PromptTemplateVersionResponse(Integer version, LocalDateTime createdAt) {
}
