package org.huangry.colorful.geo.application.prompt;

import org.huangry.colorful.geo.application.prompt.model.PromptTemplateResponse;
import org.huangry.colorful.geo.application.prompt.model.SavePromptTemplateRequest;
import org.huangry.colorful.geo.domain.service.prompt.GeoPromptTemplateService;
import org.huangry.colorful.geo.infrastructure.common.enums.PromptTemplateKey;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoPromptTemplateEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 提示词管理编排：清单取自枚举、未配置的没有版本号、列表不带内容；路径里的提示词标识不存在时 404。 */
class PromptTemplateBusinessTest {
    private final PromptTemplateBusiness business = new PromptTemplateBusiness();
    private final GeoPromptTemplateService service = mock(GeoPromptTemplateService.class);

    /** 列表按枚举声明顺序列出全部提示词（即页面下拉顺序）：已配置的带版本号、不带内容，未配置的没有版本号。 */
    @Test
    void shouldListEveryPromptWithoutContent() {
        ReflectionTestUtils.setField(business, "geoPromptTemplateService", service);
        when(service.findCurrentTemplate(PromptTemplateKey.DIAGNOSIS_REPORT_RESULT_JUDGE)).thenReturn(template(2, "{{answer}}"));

        var items = business.listPromptTemplates();

        assertEquals(Arrays.stream(PromptTemplateKey.values()).map(PromptTemplateKey::getTitle).toList(),
                items.stream().map(PromptTemplateResponse::title).toList());
        PromptTemplateResponse judge = findItem(items, PromptTemplateKey.DIAGNOSIS_REPORT_RESULT_JUDGE);
        assertEquals(2, judge.version());
        assertNull(judge.content());
        assertNull(findItem(items, PromptTemplateKey.DIAGNOSIS_PLAN_QUESTION_GENERATE).version());
    }

    /**
     * @param items 提示词列表
     * @param key 要找的提示词
     * @return 列表中该提示词的一行
     */
    private PromptTemplateResponse findItem(List<PromptTemplateResponse> items, PromptTemplateKey key) {
        return items.stream().filter(item -> item.key().equals(key.name())).findFirst().orElseThrow();
    }

    /** 详情带内容；提示词标识不存在时 404，不会落到服务层。 */
    @Test
    void shouldReadContentAndRejectUnknownKey() {
        ReflectionTestUtils.setField(business, "geoPromptTemplateService", service);
        when(service.findCurrentTemplate(PromptTemplateKey.DIAGNOSIS_REPORT_RESULT_JUDGE)).thenReturn(template(2, "{{answer}}"));

        assertEquals("{{answer}}", business.getPromptTemplate("DIAGNOSIS_REPORT_RESULT_JUDGE").content());
        assertThrows(NoSuchElementException.class, () -> business.getPromptTemplate("UNKNOWN"));
        assertThrows(NoSuchElementException.class, () -> business.createPromptTemplateVersion("UNKNOWN", new SavePromptTemplateRequest("x")));
        verify(service, never()).createTemplateVersion(any(), any());
    }

    /**
     * @param version 版本号
     * @param content 模板内容
     * @return 一版判定提示词
     */
    private GeoPromptTemplateEntity template(int version, String content) {
        var template = new GeoPromptTemplateEntity();
        template.setPromptKey(PromptTemplateKey.DIAGNOSIS_REPORT_RESULT_JUDGE.name());
        template.setVersion(version);
        template.setContent(content);
        return template;
    }
}
