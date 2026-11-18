package org.huangry.colorful.geo.presentation.task;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.report.dimension.DimensionNormalizer.DimensionNormalization;
import org.huangry.colorful.geo.presentation.task.DiagnosisReportContentProgress.Step;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 第 2 段子步骤进度：四步按执行顺序写成 JSON，各步结果的状态与说明，失败步骤拼成暂停原因，程序出错只标记正在执行的那一步。 */
class DiagnosisReportContentProgressTest {

    /** 新建时四步都是未开始，按执行顺序输出，没有说明时不写 detail。 */
    @Test
    void shouldStartWithFourPendingStepsInOrder() {
        assertEquals(List.of("DIMENSION:PENDING", "SUMMARY:PENDING", "ADVICE:PENDING", "REGION_STRATEGY:PENDING"),
                describe(new DiagnosisReportContentProgress()));
        assertFalse(new DiagnosisReportContentProgress().toJson().contains("detail"));
    }

    /** 各步结果：合并前后维度数、统计数字、无需执行与地域失败的说明；地域进度在这一步结束后不再保留。 */
    @Test
    void shouldDescribeEachStepResult() {
        var progress = new DiagnosisReportContentProgress()
                .finishDimensionStep(new DimensionNormalization(Map.of("价格偏高", "价格", "收费贵", "价格", "油品", "油品"), false))
                .finishSummaryStep(6, 4)
                .finishAdviceStep(new JSONObject().fluentPut("status", "NO_ISSUES"))
                .startStep(Step.REGION_STRATEGY, "1 / 4");
        assertEquals("REGION_STRATEGY:RUNNING:1 / 4", describe(progress).get(3));

        progress.finishRegionStrategyStep(4, 2, List.of("美国", "德国"));

        assertEquals(List.of("DIMENSION:SUCCEEDED:3 个维度合并为 2 个", "SUMMARY:SUCCEEDED:规则问题 6 个，地域 4 个",
                "ADVICE:SKIPPED:规则没有发现问题", "REGION_STRATEGY:FAILED:美国、德国（共 4 个地域）"), describe(progress));
        assertEquals(List.of("写地域策略失败：美国、德国（共 4 个地域）"), progress.listFailures());
    }

    /** 维度归一失败、建议失败都列入暂停原因；没有地域或各地域都没有机会时地域策略为无需执行，只写了部分地域时写明。 */
    @Test
    void shouldListFailuresAndSkippedRegions() {
        var progress = new DiagnosisReportContentProgress()
                .finishDimensionStep(new DimensionNormalization(Map.of("价格偏高", "价格偏高"), true))
                .finishAdviceStep(new JSONObject().fluentPut("status", "FAILED"));

        assertEquals(List.of("合并同义维度失败：维度保持原名", "写优化建议失败"), progress.listFailures());
        assertEquals("REGION_STRATEGY:SKIPPED:没有分地域的问题", describe(progress.finishRegionStrategyStep(0, 0, List.of())).get(3));
        assertEquals("REGION_STRATEGY:SKIPPED:各地域都没有机会", describe(progress.finishRegionStrategyStep(3, 0, List.of())).get(3));
        assertEquals("REGION_STRATEGY:SUCCEEDED:已写 2 个地域，其余没有机会", describe(progress.finishRegionStrategyStep(3, 2, List.of())).get(3));
        assertEquals("DIMENSION:SKIPPED:没有需要合并的维度",
                describe(progress.finishDimensionStep(new DimensionNormalization(Map.of(), false))).get(0));
    }

    /** 程序出错只把正在执行的那一步记为失败，已完成的保持完成，后面的保持未开始。 */
    @Test
    void shouldFailOnlyTheRunningStep() {
        var progress = new DiagnosisReportContentProgress()
                .finishDimensionStep(new DimensionNormalization(Map.of(), false))
                .startStep(Step.SUMMARY, null);

        progress.failRunningStep("程序出错");

        assertEquals(List.of("DIMENSION:SKIPPED:没有需要合并的维度", "SUMMARY:FAILED:程序出错", "ADVICE:PENDING", "REGION_STRATEGY:PENDING"),
                describe(progress));
    }

    /**
     * @param progress 第 2 段进度
     * @return 按 JSON 中的顺序，每步写成“key:status”或“key:status:detail”
     */
    private List<String> describe(DiagnosisReportContentProgress progress) {
        return JSON.parseObject(progress.toJson()).getJSONArray("steps").stream().map(JSONObject.class::cast)
                .map(step -> step.getString("key") + ":" + step.getString("status") + (step.containsKey("detail") ? ":" + step.getString("detail") : ""))
                .toList();
    }
}
