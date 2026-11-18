package org.huangry.colorful.geo.presentation.controller;

import org.huangry.colorful.geo.application.diagnosis.DiagnosisReportBusiness;
import org.huangry.colorful.geo.application.diagnosis.model.DiagnosisReportResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.NoSuchElementException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 报告接口契约：生成返回 201，重判、重新生成内容与忽略失败继续返回 200，校验失败 400，缺失 404，内部异常不透传。 */
class MgmtDiagnosisReportControllerTest {
    private final DiagnosisReportBusiness business = mock(DiagnosisReportBusiness.class);
    private final MockMvc mvc = createMvc();

    /** 生成报告返回 201 与判定进度，请求体带报告语言；刷新前的旧页面不带请求体，按未指定语言交给业务层；业务拒绝映射为 400 并带原因。 */
    @Test
    void shouldCreateReportOrExplainRejection() throws Exception {
        when(business.createReport(30L, "英语")).thenReturn(report("PENDING", "JUDGE"));
        when(business.createReport(30L, null)).thenThrow(new IllegalArgumentException("该诊断任务已有报告正在生成，请等待完成"));

        mvc.perform(post("/diagnosis-reports/diagnosis-tasks/30").contentType(MediaType.APPLICATION_JSON).content("{\"reportLanguage\":\"英语\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.stage").value("JUDGE"))
                .andExpect(jsonPath("$.reportLanguage").value("英语"))
                .andExpect(jsonPath("$.progress.total").value(70))
                .andExpect(jsonPath("$.summary").doesNotExist());
        mvc.perform(post("/diagnosis-reports/diagnosis-tasks/30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("该诊断任务已有报告正在生成，请等待完成"));
    }

    /** 重判与忽略失败继续返回处理后的报告；重新生成内容被业务拒绝时映射为 400 并带原因。 */
    @Test
    void shouldRerunReportOrExplainRejection() throws Exception {
        when(business.rejudgeReport(9L)).thenReturn(report("PENDING", "JUDGE"));
        when(business.skipReportFailures(9L)).thenReturn(report("PENDING", "CONTENT"));
        when(business.regenerateReportContent(9L)).thenThrow(new IllegalArgumentException("报告停在判定段，请先重判失败的回答或忽略失败继续"));

        mvc.perform(post("/diagnosis-reports/9/rejudge")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.stage").value("JUDGE"));
        mvc.perform(post("/diagnosis-reports/9/skip-failures")).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("CONTENT"));
        mvc.perform(post("/diagnosis-reports/9/regenerate-content")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("报告停在判定段，请先重判失败的回答或忽略失败继续"));
    }

    /** 不存在的报告 404；内部状态异常 500 且不透传原文。 */
    @Test
    void shouldMapMissingAndInternalFailures() throws Exception {
        when(business.getReport(99L)).thenThrow(new NoSuchElementException("诊断报告不存在"));
        when(business.getReport(98L)).thenThrow(new IllegalStateException("sql detail"));

        mvc.perform(get("/diagnosis-reports/99")).andExpect(status().isNotFound());
        mvc.perform(get("/diagnosis-reports/98")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("诊断报告数据暂时无法处理，请稍后重试"));
    }

    /**
     * @param status 报告状态
     * @param stage 报告所在段
     * @return 报告 9 的响应，70 条有效回答尚未判定，报告语言为英语
     */
    private DiagnosisReportResponse report(String status, String stage) {
        return new DiagnosisReportResponse(9L, 30L, "加油诊断", "团油", status, stage,
                new DiagnosisReportResponse.ProgressResponse(70, 70, 0, 0), null, null, null, "英语", null, null, null, null, null);
    }

    /** @return 注入模拟业务的独立 MVC */
    private MockMvc createMvc() {
        var controller = new MgmtDiagnosisReportController();
        ReflectionTestUtils.setField(controller, "diagnosisReportBusiness", business);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
