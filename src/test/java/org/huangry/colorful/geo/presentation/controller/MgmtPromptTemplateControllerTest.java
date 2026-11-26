package org.huangry.colorful.geo.presentation.controller;

import org.huangry.colorful.geo.application.prompt.PromptTemplateBusiness;
import org.huangry.colorful.geo.application.prompt.model.PromptTemplateResponse;
import org.huangry.colorful.geo.application.prompt.model.PromptTemplateVersionResponse;
import org.huangry.colorful.geo.application.prompt.model.SavePromptTemplateRequest;
import org.huangry.colorful.geo.infrastructure.common.enums.PromptTemplateKey;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 提示词管理接口契约：列表不带内容，保存返回 201，校验失败 400，提示词或版本不存在 404，数据库异常不透传。 */
class MgmtPromptTemplateControllerTest {
    private final PromptTemplateBusiness business = mock(PromptTemplateBusiness.class);
    private final MockMvc mvc = createMvc();
    private static final PromptTemplateKey ADVICE = PromptTemplateKey.DIAGNOSIS_REPORT_ADVICE;

    /** 列表带描述、变量与版本号，不带内容；详情与历史版本按路径里的提示词标识查询。 */
    @Test
    void shouldListAndReadPromptTemplates() throws Exception {
        when(business.listPromptTemplates()).thenReturn(List.of(response(3, null)));
        when(business.getPromptTemplate("DIAGNOSIS_REPORT_ADVICE")).thenReturn(response(3, "【目标】{{target}}"));
        when(business.listPromptTemplateVersions("DIAGNOSIS_REPORT_ADVICE")).thenReturn(List.of(new PromptTemplateVersionResponse(3, LocalDateTime.of(2026, 11, 19, 10, 0))));
        when(business.getPromptTemplateVersion("DIAGNOSIS_REPORT_ADVICE", 2)).thenThrow(new NoSuchElementException("诊断报告-优化建议提示词第 2 版不存在"));

        mvc.perform(get("/prompt-templates")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("诊断报告-优化建议提示词"))
                .andExpect(jsonPath("$[0].variables[2].name").value("issues"))
                .andExpect(jsonPath("$[0].variables[2].required").value(true))
                .andExpect(jsonPath("$[0].content").doesNotExist());
        mvc.perform(get("/prompt-templates/DIAGNOSIS_REPORT_ADVICE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3)).andExpect(jsonPath("$.content").value("【目标】{{target}}"));
        mvc.perform(get("/prompt-templates/DIAGNOSIS_REPORT_ADVICE/versions")).andExpect(status().isOk()).andExpect(jsonPath("$[0].version").value(3));
        mvc.perform(get("/prompt-templates/DIAGNOSIS_REPORT_ADVICE/versions/2")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("诊断报告-优化建议提示词第 2 版不存在"));
    }

    /** 保存新版本返回 201；变量不合规等校验失败映射为 400 并带原因；数据库异常返回 500 且不透传原文。 */
    @Test
    void shouldCreateVersionOrExplainRejection() throws Exception {
        when(business.createPromptTemplateVersion(eq("DIAGNOSIS_REPORT_ADVICE"), any(SavePromptTemplateRequest.class)))
                .thenReturn(response(4, "{{issues}}"))
                .thenThrow(new IllegalArgumentException("提示词里有未定义的变量：{{isues}}，可用变量见页面右侧"))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        mvc.perform(post("/prompt-templates/DIAGNOSIS_REPORT_ADVICE/versions").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"{{issues}}\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(4));
        mvc.perform(post("/prompt-templates/DIAGNOSIS_REPORT_ADVICE/versions").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"{{isues}}\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("提示词里有未定义的变量：{{isues}}，可用变量见页面右侧"));
        mvc.perform(post("/prompt-templates/DIAGNOSIS_REPORT_ADVICE/versions").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.detail").value("提示词数据暂时无法处理，请稍后重试"));
    }

    /**
     * @param version 版本号
     * @param content 模板内容，列表为 null
     * @return 优化建议提示词的响应
     */
    private PromptTemplateResponse response(int version, String content) {
        return new PromptTemplateResponse(ADVICE.name(), ADVICE.getTitle(), ADVICE.getVariables(), version, content, LocalDateTime.of(2026, 11, 19, 10, 0));
    }

    /** @return 注入模拟业务的独立 MVC */
    private MockMvc createMvc() {
        var controller = new MgmtPromptTemplateController();
        ReflectionTestUtils.setField(controller, "promptTemplateBusiness", business);
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
