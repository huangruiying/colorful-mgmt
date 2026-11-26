package org.huangry.colorful.geo.presentation.task;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import org.huangry.colorful.geo.domain.service.diagnosis.report.content.dimension.DimensionNormalizer.DimensionNormalization;
import org.huangry.colorful.geo.domain.service.diagnosis.report.content.advice.AdviceGenerator;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * <pre>
 * 诊断报告第 2 段（生成报告内容）的子步骤进度：合并同义维度、统计指标、规则问题与地域机会、写优化建议、写地域策略四步各自的状态和说明
 *
 * 负责：
 * - 记录四步的状态（未开始、执行中、完成、无需执行、失败）与给页面看的说明
 * - 序列化为 JSON 写入报告的 content_progress，页面据此显示第 2 段走到哪一步、哪一步失败
 * - 列出失败的步骤，供拼暂停原因，步骤名与页面显示一致
 *
 * 核心入口：
 * - startStep(...)、finish*Step(...)、failRunningStep(...)：DiagnosisReportWorker 每次运行第 2 段时新建一份，在每步开始、结束时更新
 * - listFailures()：DiagnosisReportWorker 第 2 段跑完后拼暂停原因
 *
 * 约束：
 * - 只用于显示，不参与重试判断：第 2 段失败或中断时整段重做，进度从头记录
 *
 * 不负责：
 * - 执行各步与保存进度（DiagnosisReportWorker）
 *
 * 说明：JSON 结构为 {"steps":[{"key":"DIMENSION","status":"SUCCEEDED","detail":"38 个维度合并为 21 个"}, …]}，
 * 四步按执行顺序排列，没有说明时不写 detail。
 * </pre>
 */
final class DiagnosisReportContentProgress {
	/** 每一步的状态。 */
	private final Map<Step, StepStatus> statuses = new EnumMap<>(Step.class);
	/** 每一步给页面看的说明，没有说明的步骤不在其中。 */
	private final Map<Step, String> details = new EnumMap<>(Step.class);

	/**
	 * 新建一次第 2 段的进度，四步都还没开始。
	 */
	DiagnosisReportContentProgress() {
		for (Step step : Step.values()) {
			statuses.put(step, StepStatus.PENDING);
		}
	}

	/**
	 * 标记某一步开始执行。
	 * @param step 子步骤
	 * @param detail 执行中的说明，如写地域策略的“2 / 4”；没有时为 null
	 * @return 本进度，便于直接保存
	 */
	DiagnosisReportContentProgress startStep(Step step, String detail) {
		return updateStep(step, StepStatus.RUNNING, detail);
	}

	/**
	 * 记下合并同义维度的结果：调用失败或输出无法解析时为失败、维度保持原名；没有维度时无需执行；否则写明合并前后的维度数。
	 * @param dimensions 维度归一结果
	 * @return 本进度，便于直接保存
	 */
	DiagnosisReportContentProgress finishDimensionStep(DimensionNormalization dimensions) {
		if (dimensions.failed()) {
			return updateStep(Step.DIMENSION, StepStatus.FAILED, "维度保持原名");
		}
		if (dimensions.mapping().isEmpty()) {
			return updateStep(Step.DIMENSION, StepStatus.SKIPPED, "没有需要合并的维度");
		}
		long mergedCount = dimensions.mapping().values().stream().distinct().count();
		return updateStep(Step.DIMENSION, StepStatus.SUCCEEDED, dimensions.mapping().size() + " 个维度合并为 " + mergedCount + " 个");
	}

	/**
	 * 记下统计完成，写明规则发现的问题数和地域数。
	 * @param issueCount 规则发现的问题数
	 * @param regionCount ⑦ 的地域数
	 * @return 本进度，便于直接保存
	 */
	DiagnosisReportContentProgress finishSummaryStep(int issueCount, int regionCount) {
		return updateStep(Step.SUMMARY, StepStatus.SUCCEEDED, "规则问题 " + issueCount + " 个，地域 " + regionCount + " 个");
	}

	/**
	 * 记下写优化建议的结果：生成失败为失败；规则没有发现问题、没有调用模型时无需执行；否则为完成。
	 * @param advice 本次生成的 advice
	 * @return 本进度，便于直接保存
	 */
	DiagnosisReportContentProgress finishAdviceStep(JSONObject advice) {
		if (AdviceGenerator.isFailed(advice)) {
			return updateStep(Step.ADVICE, StepStatus.FAILED, null);
		}
		if (AdviceGenerator.isSkipped(advice)) {
			return updateStep(Step.ADVICE, StepStatus.SKIPPED, "规则没有发现问题");
		}
		return updateStep(Step.ADVICE, StepStatus.SUCCEEDED, null);
	}

	/**
	 * 记下写地域策略的结果：有地域失败时为失败并列出地域；没有分地域的问题、或各地域都没有机会时无需执行；否则为完成。
	 * @param regionCount ⑦ 的地域数
	 * @param generatedCount 调用模型并成功写出策略的地域数
	 * @param failedRegions 策略生成失败的地域名称，按地域顺序
	 * @return 本进度，便于直接保存
	 */
	DiagnosisReportContentProgress finishRegionStrategyStep(int regionCount, int generatedCount, List<String> failedRegions) {
		if (! failedRegions.isEmpty()) {
			return updateStep(Step.REGION_STRATEGY, StepStatus.FAILED, String.join("、", failedRegions) + "（共 " + regionCount + " 个地域）");
		}
		if (regionCount == 0) {
			return updateStep(Step.REGION_STRATEGY, StepStatus.SKIPPED, "没有分地域的问题");
		}
		if (generatedCount == 0) {
			return updateStep(Step.REGION_STRATEGY, StepStatus.SKIPPED, "各地域都没有机会");
		}
		String detail = generatedCount == regionCount ? "共 " + regionCount + " 个地域" : "已写 " + generatedCount + " 个地域，其余没有机会";
		return updateStep(Step.REGION_STRATEGY, StepStatus.SUCCEEDED, detail);
	}

	/**
	 * 程序出错时把正在执行的那一步记为失败，后面的步骤保持未开始。
	 * @param detail 失败说明
	 */
	void failRunningStep(String detail) {
		for (Step step : Step.values()) {
			if (statuses.get(step) == StepStatus.RUNNING) {
				updateStep(step, StepStatus.FAILED, detail);
			}
		}
	}

	/**
	 * 列出失败的步骤，用作暂停原因，例如“合并同义维度失败：维度保持原名”“写地域策略失败：美国（共 4 个地域）”。
	 * @return 失败步骤的说明，按执行顺序；没有失败时为空
	 */
	List<String> listFailures() {
		List<String> failures = new ArrayList<>();
		for (Step step : Step.values()) {
			if (statuses.get(step) == StepStatus.FAILED) {
				failures.add(step.label + "失败" + (details.containsKey(step) ? "：" + details.get(step) : ""));
			}
		}
		return failures;
	}

	/**
	 * @return 写入 content_progress 的 JSON，结构见类注释
	 */
	String toJson() {
		JSONArray steps = new JSONArray();
		for (Step step : Step.values()) {
			JSONObject node = new JSONObject(true).fluentPut("key", step.name()).fluentPut("status", statuses.get(step).name());
			if (details.containsKey(step)) {
				node.put("detail", details.get(step));
			}
			steps.add(node);
		}
		return JSON.toJSONString(new JSONObject(true).fluentPut("steps", steps));
	}

	/**
	 * 更新某一步的状态和说明。
	 * @param step 子步骤
	 * @param status 新状态
	 * @param detail 说明，没有时为 null
	 * @return 本进度
	 */
	private DiagnosisReportContentProgress updateStep(Step step, StepStatus status, String detail) {
		statuses.put(step, status);
		// 没有新说明时去掉上一状态的说明，例如地域进度“2 / 4”在这一步结束后不再保留
		if (detail == null) {
			details.remove(step);
		} else {
			details.put(step, detail);
		}
		return this;
	}

	/** 第 2 段的四个子步骤，按执行顺序声明；名称写入 JSON 的 key，页面按它显示步骤名。 */
	@RequiredArgsConstructor
	enum Step {
		/** 合并同义维度：调用一次模型，把写法不同、意思相同的维度名合并。 */
		DIMENSION("合并同义维度"),
		/** 统计指标、规则问题与地域机会：纯计算，得出 ①–⑤ 的指标、⑥ 的规则问题与 ⑦ 各地域的状态和机会，供后两步的模型引用。 */
		SUMMARY("统计指标、规则问题与地域机会"),
		/** 写优化建议：基于规则发现的问题调用一次模型。 */
		ADVICE("写优化建议"),
		/** 写地域策略：每个有机会的地域调用一次模型。 */
		REGION_STRATEGY("写地域策略");

		/** 步骤名称，拼暂停原因时使用，与页面显示一致。 */
		private final String label;
	}

	/** 子步骤的状态，名称写入 JSON 的 status。 */
	enum StepStatus {
		/** 还没开始。 */
		PENDING,
		/** 正在执行。 */
		RUNNING,
		/** 完成。 */
		SUCCEEDED,
		/** 失败，说明写在 detail。 */
		FAILED,
		/** 无需执行：没有维度、规则没发现问题或地域都没有机会，没有调用模型。 */
		SKIPPED
	}
}
