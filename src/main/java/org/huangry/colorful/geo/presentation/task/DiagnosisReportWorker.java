package org.huangry.colorful.geo.presentation.task;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisReportService.ReportAnswer;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisReportService;
import org.huangry.colorful.geo.domain.service.diagnosis.report.analyze.ResultAnalyzer;
import org.huangry.colorful.geo.domain.service.diagnosis.report.analyze.ResultJudgePrompt;
import org.huangry.colorful.geo.domain.service.diagnosis.report.dimension.DimensionNormalizer;
import org.huangry.colorful.geo.domain.service.diagnosis.report.dimension.DimensionNormalizer.DimensionNormalization;
import org.huangry.colorful.geo.domain.service.diagnosis.report.tactic.TacticGenerator;
import org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportSummarizer;
import org.huangry.colorful.geo.domain.service.llm.GeoLlmChannelService;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMStrategyRouter;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisReportStage;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportResultAnalysisEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.presentation.task.DiagnosisReportContentProgress.Step;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * 诊断报告离线 Job：领取报告，分两段生成——第 1 段逐条判定有效回答，第 2 段生成报告内容（维度归一、统计、优化建议、地域策略）。
 * <p>start 向统一线程池提交单个轮询，tick 领取报告，generate 从报告停在的段开始编排两段，close 通知退出。
 * 任一段有失败都暂停报告，等用户重试该段或忽略失败继续，不自动重试；段与状态的变化都交给报告服务写库。
 * 第 2 段逐步记录四个子步骤的进度（{@link DiagnosisReportContentProgress}），供页面显示走到哪一步。
 * 数据库会话锁限定同一库唯一执行者，中断的报告下次领取时续跑当前段：判定段接着判剩余记录，第 2 段整段重做。</p>
 */
@Component
@Slf4j
public class DiagnosisReportWorker {
	/**
	 * 数据库会话锁名称，与诊断任务执行器的锁区分。
	 */
	private static final String LOCK_NAME = ":diagnosis-report-worker";

	@Resource
	private GeoDiagnosisReportService geoDiagnosisReportService;
	@Resource
	private ResultAnalyzer resultAnalyzer;
	@Resource
	private ReportSummarizer reportSummarizer;
	@Resource
	private DimensionNormalizer dimensionNormalizer;
	@Resource
	private TacticGenerator tacticGenerator;
	@Resource
	private DiagnosisTaskSnapshots diagnosisTaskSnapshots;
	@Resource
	private LLMStrategyRouter router;
	@Resource
	private GeoLlmChannelService channelService;
	@Resource
	private DataSource dataSource;
	@Value("${diagnosis.reports.enabled:true}")
	private boolean enabled;
	@Resource(name = "diagnosisTaskExecutor")
	private TaskExecutor taskExecutor;

	/**
	 * 避免重复启动事件提交多个轮询任务。
	 */
	private final AtomicBoolean started = new AtomicBoolean();
	/**
	 * 本组件实际执行线程，仅用于停机通知。
	 */
	private volatile Thread workerThread;
	/**
	 * 持有数据库会话锁的连接，执行线程退出前保持占用。
	 */
	private Connection lease;
	/**
	 * 停机后禁止领取新报告和新的判定。
	 */
	private volatile boolean stopped;
	/**
	 * 基础设施故障后的下次轮询时间，使用单调时钟纳秒值。
	 */
	private long nextAttemptAt;

	/**
	 * 应用启动完成后向统一线程池提交唯一轮询任务。
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		if (enabled && ! stopped && started.compareAndSet(false, true)) {
			taskExecutor.execute(this::poll);
		}
	}

	/**
	 * 顺序轮询；每轮结束后等待一秒，退出时由执行线程释放数据库锁。
	 */
	private void poll() {
		workerThread = Thread.currentThread();
		try {
			// 1. 首次及每轮结束后间隔一秒，停机或线程中断时退出
			while (! stopped && ! Thread.currentThread().isInterrupted()) {
				TimeUnit.SECONDS.sleep(1);
				if (! stopped) {
					// 2. 顺序处理一份报告，再等待下一次轮询
					tick();
				}
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		} finally {
			// 3. 判定调用结束后才释放锁，避免其他实例提前接管同一报告
			releaseLease();
			workerThread = null;
		}
	}

	/**
	 * 隔离一次报告故障；数据库异常后报告保持 RUNNING，下次轮询续跑。
	 */
	private void tick() {
		if (stopped || System.nanoTime() < nextAttemptAt) {
			return;
		}
		Long reportId = null;
		try {
			// 1. 先取得同库执行权，未持锁的实例不调用模型
			if (! acquireLease()) {
				return;
			}
			// 2. 领取要生成的诊断报告
			GeoDiagnosisReportEntity report = geoDiagnosisReportService.claimNextReport();
			if (report != null) {
				reportId = report.getId();
				// 进行报告生成
				generate(report);
			}
		} catch (RuntimeException | SQLException exception) {
			// 基础设施故障等待五秒再轮询；已判定的回答不重复判定，异常日志不带远端原文
			log.error("诊断报告生成中断 reportId={}, errorType={}", reportId, exception.getClass().getSimpleName());
			nextAttemptAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		}
	}

	/**
	 * 生成一份报告：确定判定渠道后，从报告停在的段开始执行；判定段全部成功才接着生成报告内容。
	 * <p>新报告由 createReport 建好、停在判定段；用户重判时回到判定段，重新生成报告内容或在判定段忽略失败后直接从第 2 段开始。</p>
	 *
	 * @param report 已处于 RUNNING 的报告
	 * @throws SQLException 执行锁连接检查失败，交由轮询处理
	 */
	private void generate(GeoDiagnosisReportEntity report) throws SQLException {
		// 1. 读取任务快照中的项目资料；确定判定渠道，首次冻结，续跑沿用已冻结的同一渠道
		JSONObject project = geoDiagnosisReportService.getTaskSnapshot(report.getTaskId()).getJSONObject("project");
		GeoLlmChannelEntity channel = judgeChannel(report);
		if (channel == null) {
			return;
		}
		// 2. 第 1 段：停在判定段时逐条判定剩余记录，有失败时报告已暂停，不进入第 2 段
		boolean atJudgeStage = DiagnosisReportStage.JUDGE.name().equals(report.getStage());
		// 3. 第 2 段：不在判定段，或判定段全部成功，才生成报告内容
		if (! atJudgeStage || judgeAnswers(report, project, channel)) {
			generateContent(report, project, channel);
		}
	}

	/**
	 * 确定本报告的判定渠道：未冻结时选默认渠道（分数最高）并冻结，已冻结时按快照 ID 读取同一渠道。
	 *
	 * @param report 报告
	 * @return 判定渠道；没有已启用渠道时报告已暂停，冻结的渠道被删除时报告已失败，两种情况都返回 null
	 */
	private GeoLlmChannelEntity judgeChannel(GeoDiagnosisReportEntity report) {
		try {
			if (report.getJudgeChannelSnapshot() == null) {
				GeoLlmChannelEntity channel = router.selectDefaultChannel();
				geoDiagnosisReportService.freezeJudgeChannel(report.getId(), JSON.toJSONString(diagnosisTaskSnapshots.buildChannelSnapshot(channel), SerializerFeature.WriteMapNullValue),
						ResultJudgePrompt.PROMPT_VERSION);
				report.setJudgeChannelSnapshot(JSON.toJSONString(diagnosisTaskSnapshots.buildChannelSnapshot(channel), SerializerFeature.WriteMapNullValue));
				report.setPromptVersion(ResultJudgePrompt.PROMPT_VERSION);
				log.info("诊断报告判定渠道已冻结 reportId={}, channel={}, model={}", report.getId(), channel.getChannelCode(), channel.getModelName());
				return channel;
			}
            // 历史执行过，则选用历史的大模型通道快照再执行
			Long channelId = ((JSONObject) diagnosisTaskSnapshots.parseSnapshot(report.getJudgeChannelSnapshot())).getLong("id");
			GeoLlmChannelEntity channel = channelService.getById(channelId);
			if (channel == null) {
				// 重跑不换渠道，冻结的渠道被删后无法保持同一判定口径，报告失败，只能重新生成
				geoDiagnosisReportService.failReport(report.getId(), "判定渠道已被删除，请重新生成报告");
				return null;
			}
			return channel;
		} catch (LLMClientException exception) {
			// 没有已启用渠道时无法判定，暂停报告，配好渠道后由用户重试
			geoDiagnosisReportService.updateReportPaused(report.getId(), "没有已启用的模型渠道，配置后请重试");
			return null;
		}
	}

	/**
	 * 第 1 段：逐条判定剩余待判定记录，判完看有没有失败；有失败时暂停报告并写明失败条数与原因，全部成功则把报告切到第 2 段。
	 *
	 * @param report  停在判定段的报告
	 * @param project 项目资料
	 * @param channel 判定渠道
	 * @return 全部判定成功、可以接着生成报告内容时为 true
	 * @throws SQLException 执行锁连接检查失败，交由轮询处理
	 */
	private boolean judgeAnswers(GeoDiagnosisReportEntity report, JSONObject project, GeoLlmChannelEntity channel) throws SQLException {
		// 1. 逐条判定剩余待判定记录，单条失败只记录原因并继续下一条
		Set<String> publishedUrls = geoDiagnosisReportService.listPublishedUrls();
		while (! stopped && ! Thread.currentThread().isInterrupted()) {
			if (lease == null || ! lease.isValid(2)) {
				throw new IllegalStateException("诊断报告执行锁连接已失效");
			}
			GeoDiagnosisReportResultAnalysisEntity analysis = geoDiagnosisReportService.findNextPendingAnalysis(report.getId());
			if (analysis == null) {
				break;
			}
			resultAnalyzer.analyzeResult(analysis, project, publishedUrls, channel, report.getReportLanguage());
		}
		if (stopped || Thread.currentThread().isInterrupted()) {
			throw new IllegalStateException("诊断报告执行服务正在停止");
		}
		// 2. 有判定失败时暂停，等用户重判失败的回答或忽略失败继续
		Map<String, Long> failureReasons = geoDiagnosisReportService.countAnalysisFailureReasons(report.getId());
		if (! failureReasons.isEmpty()) {
			geoDiagnosisReportService.updateReportPaused(report.getId(), buildJudgeFailureReason(failureReasons));
			log.warn("诊断报告判定有失败，已暂停 reportId={}, failureReasons={}", report.getId(), failureReasons);
			return false;
		}
		// 3. 全部判定成功，进入第 2 段
		geoDiagnosisReportService.updateReportStageContent(report.getId());
		return true;
	}

	/**
	 * 把判定失败按原因汇总成一句暂停原因，例如“判定失败 6 条：判定模型调用超时 4 条，判定模型限制调用频率 2 条”。
	 *
	 * @param failureReasons 失败原因到条数的映射，按条数从多到少
	 * @return 暂停原因
	 */
	private String buildJudgeFailureReason(Map<String, Long> failureReasons) {
		long total = failureReasons.values().stream().mapToLong(Long::longValue).sum();
		String details = failureReasons.entrySet().stream().map(entry -> entry.getKey() + " " + entry.getValue() + " 条")
				.collect(Collectors.joining("，"));
		return "判定失败 " + total + " 条：" + details;
	}

	/**
	 * 第 2 段：用判定结果生成报告内容，四步都跑完再看结果——全部成功则冻结内容、报告完成；有失败时保存本次内容并暂停，写明哪几步失败。
	 * <p>每步开始、结束时更新子步骤进度供页面显示，进度从头记录。维度归一、优化建议或地域策略失败时内容照常生成（维度保持原名，
	 * 建议或该地域策略标记为生成失败），用户可整段重试或忽略失败继续。生成过程中出现程序异常（如统计出错）时重试也会重复失败，
	 * 出错的那一步记为失败，不保存内容并暂停，修复后再重新生成；读取和写库失败交由轮询续跑。</p>
	 *
	 * @param report  停在第 2 段的报告
	 * @param project 项目资料
	 * @param channel 判定渠道，用于维度归一、优化建议与地域策略调用
	 */
	private void generateContent(GeoDiagnosisReportEntity report, JSONObject project, GeoLlmChannelEntity channel) {
		// 1. 读取全部回答及判定结果，第 2 段进度从头记录
		List<ReportAnswer> answers = geoDiagnosisReportService.loadAnswers(report.getId());
		DiagnosisReportContentProgress progress = new DiagnosisReportContentProgress();
		// 2. 依次归一、统计、写建议和地域策略；程序异常时不保存内容，暂停等修复，避免轮询反复重跑产生模型费用
		JSONObject summary;
		try {
			summary = buildContent(report, project, channel, answers, progress);
		} catch (RuntimeException exception) {
			log.error("诊断报告内容生成出错 reportId={}, errorType={}", report.getId(), exception.getClass().getSimpleName(), exception);
			progress.failRunningStep("程序出错");
			geoDiagnosisReportService.updateReportContentPaused(report.getId(), null, "报告内容生成出错，请联系管理员修复后重新生成报告内容",
					progress.toJson());
			return;
		}
		// 3. 全部成功则冻结内容、报告完成；有失败则保存本次内容并暂停，等用户重试或忽略失败继续；进度随结果一起写入
		String content = JSON.toJSONString(summary, SerializerFeature.WriteMapNullValue);
		List<String> failures = progress.listFailures();
		if (failures.isEmpty()) {
			geoDiagnosisReportService.finishReport(report.getId(), content, progress.toJson());
			log.info("诊断报告生成完成 reportId={}, answers={}", report.getId(), answers.size());
		} else {
			geoDiagnosisReportService.updateReportContentPaused(report.getId(), content, String.join("；", failures), progress.toJson());
			log.warn("诊断报告内容有失败，已暂停 reportId={}, failures={}", report.getId(), failures);
		}
	}

	/**
	 * 依次执行第 2 段的四步并逐步保存进度，失败的步骤只记下、不中断：合并同义维度 → 统计 ①–⑦ → 写优化建议 → 写地域策略；
	 * 维度名、建议与策略按报告语言书写。
	 *
	 * @param report   报告，提供报告语言、判定渠道快照与提示词版本
	 * @param project  项目资料
	 * @param channel  判定渠道
	 * @param answers  报告的全部回答
	 * @param progress 本次第 2 段的进度，各步结果记在这里
	 * @return 本次生成的报告内容，失败的建议或地域策略已标记为生成失败
	 */
	private JSONObject buildContent(GeoDiagnosisReportEntity report, JSONObject project, GeoLlmChannelEntity channel, List<ReportAnswer> answers,
	                                DiagnosisReportContentProgress progress) {
		String reportLanguage = report.getReportLanguage();
		// 1. 合并同义维度，失败时维度保持原名、记为失败，后面照常统计
		saveContentProgress(report, progress.startStep(Step.DIMENSION, null));
		DimensionNormalization dimensions = dimensionNormalizer.normalizeDimensions(answers, channel, report.getId(), reportLanguage);
		saveContentProgress(report, progress.finishDimensionStep(dimensions));
		// 2. 统计 ①–⑦ 的指标、规则问题与各地域机会，只是计算，不调用模型
		saveContentProgress(report, progress.startStep(Step.SUMMARY, null));
		JSONObject summary = reportSummarizer.buildSummary(answers, project, dimensions.mapping(),
				(JSONObject) diagnosisTaskSnapshots.parseSnapshot(report.getJudgeChannelSnapshot()), report.getPromptVersion());
		JSONArray regions = summary.getJSONObject("region").getJSONArray("regions");
		saveContentProgress(report, progress.finishSummaryStep(summary.getJSONArray("issues").size(), regions.size()));
		// 3. 基于规则发现的问题写优化建议
		saveContentProgress(report, progress.startStep(Step.ADVICE, null));
		JSONObject advice = tacticGenerator.generateAdvice(summary.getJSONArray("issues"), project, channel, report.getId(), reportLanguage);
		summary.put("advice", advice);
		saveContentProgress(report, progress.finishAdviceStep(advice));
		// 4. ⑦ 逐个地域写提升策略，每写完一个地域更新一次进度
		writeRegionStrategies(report, regions, project, channel, progress);
		return summary;
	}

	/**
	 * ⑦ 逐个地域写提升策略并写入该地域的 strategy，每开始一个地域更新一次进度（已写 x / 共 y 个）；
	 * 没有机会的地域不调用模型，某个地域失败只记在该地域上、接着写下一个，最后按各地域结果记下这一步。
	 *
	 * @param report   报告，提供报告语言
	 * @param regions  统计出的 ⑦ 地域列表，策略写回各地域
	 * @param project  项目资料
	 * @param channel  判定渠道
	 * @param progress 本次第 2 段的进度
	 */
	private void writeRegionStrategies(GeoDiagnosisReportEntity report, JSONArray regions, JSONObject project, GeoLlmChannelEntity channel,
	                                   DiagnosisReportContentProgress progress) {
		List<String> failedRegions = new ArrayList<>();
		int generatedCount = 0;
		// 1. 逐个地域写策略，失败只记下地域名，没有机会的不计入已写
		for (int index = 0; index < regions.size(); index++) {
			saveContentProgress(report, progress.startStep(Step.REGION_STRATEGY, index + " / " + regions.size()));
			JSONObject region = regions.getJSONObject(index);
			JSONObject strategy = tacticGenerator.generateRegionStrategy(region, project, channel, report.getId(), report.getReportLanguage());
			region.put("strategy", strategy);
			if (TacticGenerator.isFailed(strategy)) {
				failedRegions.add(region.getString("name"));
			} else if (! TacticGenerator.isSkipped(strategy)) {
				generatedCount++;
			}
		}
		// 2. 按各地域结果记下这一步
		saveContentProgress(report, progress.finishRegionStrategyStep(regions.size(), generatedCount, failedRegions));
	}

	/**
	 * 保存第 2 段的子步骤进度，供页面显示第 2 段走到哪一步。
	 * <p>进度不是核心数据：写库失败只记警告，不中断第 2 段，下一次更新或结束时会写入最新进度。</p>
	 *
	 * @param report   停在第 2 段、生成中的报告
	 * @param progress 本次第 2 段的进度
	 */
	private void saveContentProgress(GeoDiagnosisReportEntity report, DiagnosisReportContentProgress progress) {
		try {
			geoDiagnosisReportService.updateReportContentProgress(report.getId(), progress.toJson());
		} catch (DataAccessException exception) {
			log.warn("诊断报告进度保存失败 reportId={}, errorType={}", report.getId(), exception.getClass().getSimpleName());
		}
	}

	/**
	 * 取得数据库会话锁后才允许判定，其他实例只提供页面与接口。
	 *
	 * @return 当前实例是否持有执行锁
	 * @throws SQLException 连接检查或数据库锁申请失败
	 */
	private boolean acquireLease() throws SQLException {
		// 1. 复用有效的持锁会话，失效连接先归还连接池
		if (lease != null && lease.isValid(2)) {
			return true;
		}
		if (lease != null) {
			lease.close();
			lease = null;
		}
		// 2. 用独立会话非阻塞申请同库锁
		Connection candidate = dataSource.getConnection();
		try (var statement = candidate.prepareStatement("SELECT GET_LOCK(CONCAT(DATABASE(),?),0)")) {
			statement.setString(1, LOCK_NAME);
			try (var rows = statement.executeQuery()) {
				if (rows.next() && rows.getInt(1) == 1) {
					lease = candidate;
					return true;
				}
			}
		} finally {
			// 3. 未取得锁的候选连接立即归还，持锁连接保留至执行线程退出
			if (lease != candidate) {
				candidate.close();
			}
		}
		return false;
	}

	/**
	 * 停止本组件轮询，不关闭由 Spring 统一管理的线程池。
	 */
	@PreDestroy
	public void close() {
		// 先禁止继续领取，再中断本组件线程；数据库锁留给执行线程退出时释放
		stopped = true;
		Thread thread = workerThread;
		if (thread != null) {
			thread.interrupt();
		}
	}

	/**
	 * 执行线程退出后释放会话锁，未完成的报告由下次启动续跑。
	 */
	private void releaseLease() {
		try {
			if (lease != null) {
				// 1. 显式释放当前数据库会话持有的执行锁
				try (var statement = lease.prepareStatement("SELECT RELEASE_LOCK(CONCAT(DATABASE(),?))")) {
					statement.setString(1, LOCK_NAME);
					statement.execute();
				} finally {
					// 2. 即使释放锁语句失败，也关闭连接并清除本组件引用
					lease.close();
					lease = null;
				}
			}
		} catch (SQLException exception) {
			log.warn("诊断报告执行锁关闭失败 errorType={}", exception.getClass().getSimpleName());
		}
	}
}
