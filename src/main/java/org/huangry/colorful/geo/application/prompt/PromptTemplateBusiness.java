package org.huangry.colorful.geo.application.prompt;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.application.prompt.model.PromptTemplateResponse;
import org.huangry.colorful.geo.application.prompt.model.PromptTemplateVersionResponse;
import org.huangry.colorful.geo.application.prompt.model.SavePromptTemplateRequest;
import org.huangry.colorful.geo.domain.service.prompt.GeoPromptTemplateService;
import org.huangry.colorful.geo.infrastructure.common.enums.PromptTemplateKey;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoPromptTemplateEntity;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 提示词管理的应用编排：列出可维护的提示词，读取当前版本与历史版本，保存新版本，并转换为接口响应。
 * <p>可维护的提示词清单取自 {@link PromptTemplateKey}，内容与版本由 {@link GeoPromptTemplateService} 读写和校验；本类不填变量、不调用模型。</p>
 */
@Service
public class PromptTemplateBusiness {
    @Resource
    private GeoPromptTemplateService geoPromptTemplateService;

    /**
     * 提示词列表：每个可维护的提示词一行，带描述、可用变量与当前版本号，不含模板内容；还没配置的没有版本号。
     * @return 按枚举声明顺序的提示词
     */
    public List<PromptTemplateResponse> listPromptTemplates() {
        return Arrays.stream(PromptTemplateKey.values())
                .map(key -> toResponse(key, geoPromptTemplateService.findCurrentTemplate(key), false))
                .toList();
    }

    /**
     * 读取一个提示词的当前版本，带模板内容；还没配置时只返回描述与可用变量，页面据此显示“未配置”。
     * @param promptKey 提示词标识，PromptTemplateKey 的枚举名
     * @return 当前版本
     * @throws NoSuchElementException 没有这个提示词
     */
    public PromptTemplateResponse getPromptTemplate(String promptKey) {
        PromptTemplateKey key = getPromptKey(promptKey);
        return toResponse(key, geoPromptTemplateService.findCurrentTemplate(key), true);
    }

    /**
     * 列出一个提示词的历史版本，新的在前，不含内容。
     * @param promptKey 提示词标识
     * @return 各版本的版本号与保存时间
     * @throws NoSuchElementException 没有这个提示词
     */
    public List<PromptTemplateVersionResponse> listPromptTemplateVersions(String promptKey) {
        return geoPromptTemplateService.listTemplateVersions(getPromptKey(promptKey)).stream()
                .map(template -> new PromptTemplateVersionResponse(template.getVersion(), template.getCreatedAt()))
                .toList();
    }

    /**
     * 读取一个提示词的某一版，带模板内容，页面载入后再保存即为回滚。
     * @param promptKey 提示词标识
     * @param version 版本号
     * @return 该版本
     * @throws NoSuchElementException 没有这个提示词或这一版
     */
    public PromptTemplateResponse getPromptTemplateVersion(String promptKey, int version) {
        PromptTemplateKey key = getPromptKey(promptKey);
        return toResponse(key, geoPromptTemplateService.getTemplate(key, version), true);
    }

    /**
     * 把编辑后的内容保存为新版本，旧版本保留。
     * @param promptKey 提示词标识
     * @param request 编辑后的模板
     * @return 新版本，带内容与保存时间
     * @throws NoSuchElementException 没有这个提示词
     * @throws IllegalArgumentException 内容为空或超长、变量不合规、与当前版本相同，或同时有另一次保存抢先
     */
    public PromptTemplateResponse createPromptTemplateVersion(String promptKey, SavePromptTemplateRequest request) {
        PromptTemplateKey key = getPromptKey(promptKey);
        String content = request == null ? null : request.content();
        return toResponse(key, geoPromptTemplateService.createTemplateVersion(key, content), true);
    }

    /**
     * 把接口路径里的提示词标识转成枚举。
     * @param promptKey 提示词标识
     * @return 对应的提示词
     * @throws NoSuchElementException 没有这个提示词，接口返回 404
     */
    private PromptTemplateKey getPromptKey(String promptKey) {
        return Arrays.stream(PromptTemplateKey.values()).filter(key -> key.name().equals(promptKey)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("提示词不存在"));
    }

    /**
     * 组装响应：描述与可用变量取自枚举，版本号、内容与保存时间取自这一版。
     * @param key 提示词
     * @param template 这一版，还没配置时为 null
     * @param withContent 是否带模板内容；列表不带
     * @return 接口响应
     */
    private PromptTemplateResponse toResponse(PromptTemplateKey key, GeoPromptTemplateEntity template, boolean withContent) {
        if (template == null) {
            return new PromptTemplateResponse(key.name(), key.getTitle(), key.getVariables(), null, null, null);
        }
        return new PromptTemplateResponse(key.name(), key.getTitle(), key.getVariables(), template.getVersion(),
                withContent ? template.getContent() : null, template.getCreatedAt());
    }
}
