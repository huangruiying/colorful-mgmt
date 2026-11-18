package org.huangry.colorful.geo.application.diagnosis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.report.dimension.DimensionNormalizer;
import org.huangry.colorful.geo.domain.service.diagnosis.report.tactic.TacticGenerator;
import org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportSummarizer;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisReportService;
import org.huangry.colorful.geo.domain.service.llm.GeoLlmChannelService;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisReportDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 补全报告：只把 ③–⑥ 并入原汇总，①② 保持生成时的结论；不满足条件时拒绝，不调用模型；同一份报告不重复补全。 */
class DiagnosisReportSupplementBusinessTest {
    private final DiagnosisReportSupplementBusiness business = new DiagnosisReportSupplementBusiness();
    private final GeoDiagnosisReportDao reportDao = mock(GeoDiagnosisReportDao.class);
    private final GeoDiagnosisReportService reportService = mock(GeoDiagnosisReportService.class);
    private final ReportSummarizer summarizer = mock(ReportSummarizer.class);
    private final DimensionNormalizer dimensionNormalizer = mock(DimensionNormalizer.class);
    private final TacticGenerator tacticGenerator = mock(TacticGenerator.class);
    private final GeoLlmChannelService channelService = mock(GeoLlmChannelService.class);
    private final DiagnosisReportBusiness reportBusiness = mock(DiagnosisReportBusiness.class);
    private final GeoLlmChannelEntity channel = new GeoLlmChannelEntity();

    /** 重算结果里的 ①② 被丢弃，原汇总的 ①② 保留；③–⑥ 与建议写入，判定渠道和报告语言沿用报告记下的。 */
    @Test
    void shouldMergeOnlyNewPartsIntoFrozenSummary() {
        when(reportDao.selectById(9L)).thenReturn(report("SUCCEEDED", "{\"sample\":{\"validAnswers\":7},\"models\":[]}"));
        when(summarizer.buildSummary(any(), any(), any(), any(), eq("v1"))).thenReturn(new JSONObject()
                .fluentPut("sample", new JSONObject().fluentPut("validAnswers", 99)).fluentPut("brand", new JSONObject())
                .fluentPut("competitor", new JSONObject()).fluentPut("source", new JSONObject()).fluentPut("issues", new JSONArray()));
        when(tacticGenerator.generateAdvice(any(), any(), same(channel), eq(9L), eq("中文"))).thenReturn(new JSONObject().fluentPut("status", "NO_ISSUES"));

        business.supplementReport(9L);

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(reportService).updateReportSummarySupplemented(eq(9L), saved.capture());
        JSONObject summary = JSON.parseObject(saved.getValue());
        assertEquals(7, summary.getJSONObject("sample").getIntValue("validAnswers"));
        assertTrue(summary.keySet().containsAll(List.of("brand", "competitor", "source", "issues", "advice")));
        assertEquals("NO_ISSUES", summary.getJSONObject("advice").getString("status"));
        verify(reportBusiness).getReport(9L);
    }

    /** 不存在、未完成、已补全的报告都拒绝，且不调用模型。 */
    @Test
    void shouldRejectMissingUnfinishedOrSupplementedReport() {
        when(reportDao.selectById(8L)).thenReturn(report("RUNNING", null));
        JSONObject complete = new JSONObject();
        ReportSummarizer.SUMMARY_PARTS.forEach(part -> complete.put(part, new JSONObject()));
        when(reportDao.selectById(7L)).thenReturn(report("SUCCEEDED", complete.toJSONString()));

        assertThrows(NoSuchElementException.class, () -> business.supplementReport(99L));
        assertEquals("只有已完成的报告可以补全", assertThrows(IllegalArgumentException.class, () -> business.supplementReport(8L)).getMessage());
        assertEquals("该报告内容完整，无需补全", assertThrows(IllegalArgumentException.class, () -> business.supplementReport(7L)).getMessage());
        verifyNoInteractions(dimensionNormalizer, tacticGenerator);
    }

    /** 判定渠道已删除时不换用其他模型，也不调用模型。 */
    @Test
    void shouldRejectDeletedJudgeChannel() {
        when(reportDao.selectById(9L)).thenReturn(report("SUCCEEDED", "{\"sample\":{}}"));
        when(channelService.getById(6L)).thenReturn(null);

        assertTrue(assertThrows(IllegalArgumentException.class, () -> business.supplementReport(9L)).getMessage().contains("判定渠道已被删除"));
        verifyNoInteractions(dimensionNormalizer, tacticGenerator);
    }

    /** 只缺建议时只补建议；建议不缺时不调用模型写建议，已有部分保持原样。 */
    @Test
    void shouldSupplementOnlyMissingParts() {
        JSONObject frozen = new JSONObject();
        ReportSummarizer.SUMMARY_PARTS.stream().filter(part -> !part.equals("brand"))
                .forEach(part -> frozen.put(part, new JSONObject().fluentPut("frozen", true)));
        when(reportDao.selectById(9L)).thenReturn(report("SUCCEEDED", frozen.toJSONString()));
        when(summarizer.buildSummary(any(), any(), any(), any(), any())).thenReturn(new JSONObject()
                .fluentPut("brand", new JSONObject().fluentPut("recalculated", true)).fluentPut("issues", new JSONObject().fluentPut("recalculated", true)));

        business.supplementReport(9L);

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(reportService).updateReportSummarySupplemented(eq(9L), saved.capture());
        JSONObject summary = JSON.parseObject(saved.getValue());
        assertTrue(summary.getJSONObject("brand").getBooleanValue("recalculated"));
        assertTrue(summary.getJSONObject("issues").getBooleanValue("frozen"));
        verify(tacticGenerator, never()).generateAdvice(any(), any(), any(), anyLong(), any());
    }

    /** 同一份报告补全期间再次补全被拒绝；补全结束后释放，可以再次进入。 */
    @Test
    void shouldRejectSameReportWhileSupplementing() {
        when(reportDao.selectById(9L)).thenReturn(report("SUCCEEDED", "{\"sample\":{}}"));
        when(summarizer.buildSummary(any(), any(), any(), any(), any())).thenReturn(new JSONObject().fluentPut("issues", new JSONArray()));
        when(tacticGenerator.generateAdvice(any(), any(), any(), anyLong(), any())).thenReturn(new JSONObject().fluentPut("status", "NO_ISSUES"));
        // 第一次补全调用模型期间，同一份报告再次请求补全
        when(dimensionNormalizer.normalizeDimensions(any(), any(), anyLong(), any())).thenAnswer(invocation -> {
            assertEquals("该报告正在补全，请稍候", assertThrows(IllegalArgumentException.class, () -> business.supplementReport(9L)).getMessage());
            return Map.of();
        });

        business.supplementReport(9L);
        business.supplementReport(9L);

        verify(reportService, times(2)).updateReportSummarySupplemented(eq(9L), any());
    }

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(business, "geoDiagnosisReportDao", reportDao);
        ReflectionTestUtils.setField(business, "geoDiagnosisReportService", reportService);
        ReflectionTestUtils.setField(business, "reportSummarizer", summarizer);
        ReflectionTestUtils.setField(business, "dimensionNormalizer", dimensionNormalizer);
        ReflectionTestUtils.setField(business, "tacticGenerator", tacticGenerator);
        ReflectionTestUtils.setField(business, "diagnosisTaskSnapshots", new DiagnosisTaskSnapshots());
        ReflectionTestUtils.setField(business, "geoLlmChannelService", channelService);
        ReflectionTestUtils.setField(business, "diagnosisReportBusiness", reportBusiness);
        when(channelService.getById(6L)).thenReturn(channel);
        when(reportService.getTaskSnapshot(30L)).thenReturn(new JSONObject().fluentPut("project", new JSONObject().fluentPut("name", "团油")));
        when(reportService.loadAnswers(anyLong())).thenReturn(List.of());
        when(dimensionNormalizer.normalizeDimensions(any(), any(), anyLong(), any())).thenReturn(Map.of());
    }

    /**
     * @param status 报告状态
     * @param summary 冻结的汇总 JSON，可为 null
     * @return 诊断任务 30 的报告 9，判定渠道为 6，报告语言为中文（报告语言加入前生成的报告由表默认值记为中文）
     */
    private GeoDiagnosisReportEntity report(String status, String summary) {
        var report = new GeoDiagnosisReportEntity();
        report.setId(9L);
        report.setTaskId(30L);
        report.setStatus(status);
        report.setSummary(summary);
        report.setJudgeChannelSnapshot("{\"id\":6,\"channelName\":\"判定\"}");
        report.setPromptVersion("v1");
        report.setReportLanguage("中文");
        return report;
    }
}
