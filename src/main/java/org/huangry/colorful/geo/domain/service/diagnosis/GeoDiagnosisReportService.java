package org.huangry.colorful.geo.domain.service.diagnosis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.domain.model.DiagnosisReportDashboardPoint;
import org.huangry.colorful.geo.domain.service.llm.GeoLlmChannelService;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisReportResultAnalysisStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisReportStage;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisReportStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskResultStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskStatus;
import org.huangry.colorful.geo.infrastructure.repository.ContentPublishRecordDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisReportResultAnalysisDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskResultDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisReportDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.ContentPublishRecordEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportResultAnalysisEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * 诊断报告的领域服务：管报告与回答分析怎么存、报告的段与状态怎么变，以及生成和查看报告时要读的数据
 *
 * 负责：
 * - 创建报告：每次手动生成创建一份新报告并为每条有效回答建待判定记录，旧报告保留
 * - 段与状态流转：报告分两段执行（stage）：JUDGE 判定每条回答 → CONTENT 生成报告内容；
 *   状态 PENDING → RUNNING → SUCCEEDED，任一段有失败时 → PAUSED；单条分析 PENDING → SUCCEEDED / FAILED，重判时失败的改回 PENDING
 * - 重跑的前置校验：用户重判、重新生成报告内容或忽略失败继续时，校验后改状态，后台执行器下次轮询领取
 * - 读取：生成报告用的任务快照、回答与分析、我方已发布链接；查看报告用的同方案更早任务的大盘数字
 *
 * 核心入口：
 * - createReport(...)：DiagnosisReportBusiness 在用户点击生成时调用
 * - rejudgeReport(...)、regenerateReportContent(...)、skipReportFailures(...)：DiagnosisReportBusiness 在用户操作暂停或已完成的报告时调用
 * - claimNextReport() 及各 update / finish / fail 方法：DiagnosisReportWorker 领取和推进报告时调用
 * - loadAnswers(...)、getTaskSnapshot(...)、listPublishedUrls()：DiagnosisReportWorker 生成报告时读取
 * - listEarlierDashboardPoints(...)：DiagnosisReportBusiness 打开报告详情时读取 ⓪ 诊断大盘的趋势
 *
 * 约束：
 * - 段与状态的每次变化都在这里写库并带上当前状态条件，执行器与接口不各自改表
 * - 只分析成功且未因输出上限截断的诊断结果
 * - 事务只覆盖数据库操作，不包含模型调用
 *
 * 不负责：
 * - 判定回答与生成报告内容的编排（DiagnosisReportWorker）
 * - 统计数字怎么算（report/content 下各生成类）
 * </pre>
 */
@Service
@Slf4j
public class GeoDiagnosisReportService {
    /** 输出达到上限的结束原因；这类回答不完整，不纳入分析。 */
    private static final String TRUNCATED_FINISH_REASON = "length";
    /** 未指定报告语言时使用中文；表字段默认值也是中文，供本字段加入前的报告和早期导出文件使用。 */
    private static final String DEFAULT_REPORT_LANGUAGE = "中文";
    /** 报告语言的长度上限，与表字段一致。 */
    private static final int REPORT_LANGUAGE_MAX_LENGTH = 50;
    /** 诊断大盘趋势往前最多回看多少个任务；加上当前报告，趋势图最多 12 个点。 */
    private static final int DASHBOARD_HISTORY_LIMIT = 11;

    @Resource
    private GeoDiagnosisReportDao reportDao;
    @Resource
    private GeoDiagnosisReportResultAnalysisDao analysisDao;
    @Resource
    private GeoDiagnosisTaskDao taskDao;
    @Resource
    private GeoDiagnosisTaskResultDao resultDao;
    @Resource
    private ContentPublishRecordDao publishRecordDao;
    @Resource
    private DiagnosisTaskSnapshots snapshots;
    @Resource
    private GeoLlmChannelService channelService;

    /**
     * 把 诊断任务 转成 待处理的诊断报告(初始化)
     * 为已结束的诊断任务创建一份待生成的报告，并为每条有效回答建立待判定记录；不调用模型，判定与生成内容由 DiagnosisReportWorker 后台完成。
     * <p>写入（同一事务，任一步失败整体回滚）：
     * <ul>
     *   <li>geo_diagnosis_report：新增 1 条，stage = JUDGE、status = PENDING，记下报告语言，判定渠道与内容留空；</li>
     *   <li>geo_diagnosis_report_result_analysis：每条有效回答新增 1 条，status = PENDING，判定字段留空。</li>
     * </ul>
     * 有效回答：geo_diagnosis_task_result 中 status = SUCCEEDED 且结束原因不是 length（未被截断）。</p>
     * @param taskId 诊断任务 ID
     * @param reportLanguage 报告语言，可为空；为空或空白时为中文，最多 50 字符
     * @return 回读后的新报告，状态为 PENDING
     * @throws NoSuchElementException 诊断任务不存在
     * @throws IllegalArgumentException 报告语言超长、任务未结束或全部失败、已有报告正在生成、没有可分析的有效回答
     */
    @Transactional
    public GeoDiagnosisReportEntity createReport(long taskId, String reportLanguage) {
        // 1. 先确认报告语言，再锁住诊断任务，与重新发起、后台领取及并发生成串行；只有全部成功或部分失败的任务才能分析
        String language = confirmReportLanguage(reportLanguage);
        GeoDiagnosisTaskEntity task = taskDao.selectOne(new LambdaQueryWrapper<GeoDiagnosisTaskEntity>()
                .eq(GeoDiagnosisTaskEntity::getId, taskId).last("FOR UPDATE"));
        if (task == null) {
            throw new NoSuchElementException("诊断任务不存在");
        }
        if (!List.of(DiagnosisTaskStatus.SUCCEEDED.name(), DiagnosisTaskStatus.PARTIAL_FAILED.name()).contains(task.getStatus())) {
            throw new IllegalArgumentException("诊断任务尚未结束或全部失败，无法生成报告");
        }
        // 2. 同一任务同时只允许一份报告在生成，避免重复判定计费
        validateNoReportGenerating(taskId);
        // 3. 创建报告并为有效回答批量建立待判定记录；没有有效回答时整体回滚
        GeoDiagnosisReportEntity report = new GeoDiagnosisReportEntity();
        report.setTaskId(taskId);
        report.setStatus(DiagnosisReportStatus.PENDING.name());
        report.setStage(DiagnosisReportStage.JUDGE.name());
        report.setReportLanguage(language);
        reportDao.insert(report);
        int analysisCount = analysisDao.insertPendingFromResults(report.getId(), taskId,
                DiagnosisTaskResultStatus.SUCCEEDED.name(), TRUNCATED_FINISH_REASON, DiagnosisReportResultAnalysisStatus.PENDING.name());
        if (analysisCount == 0) {
            throw new IllegalArgumentException("该诊断任务没有可分析的有效回答");
        }
        log.info("诊断报告已创建 reportId={}, taskId={}, analysisCount={}, reportLanguage={}", report.getId(), taskId, analysisCount, language);
        // 4. 回读数据库生成的时间字段
        return reportDao.selectById(report.getId());
    }

    /**
     * 确认报告语言：去首尾空白，未指定时用中文。
     * @param reportLanguage 页面填写的报告语言，可为空
     * @return 确定的报告语言
     * @throws IllegalArgumentException 超过 50 字符
     */
    private String confirmReportLanguage(String reportLanguage) {
        if (reportLanguage == null || reportLanguage.isBlank()) {
            return DEFAULT_REPORT_LANGUAGE;
        }
        String language = reportLanguage.strip();
        if (language.length() > REPORT_LANGUAGE_MAX_LENGTH) {
            throw new IllegalArgumentException("报告语言最多 50 字符");
        }
        return language;
    }

    /**
     * 确认同一诊断任务当前没有报告在生成（待生成或生成中）；已暂停的报告不算，后台不会自动领取它。
     * @param taskId 诊断任务 ID
     * @throws IllegalArgumentException 已有报告在生成
     */
    private void validateNoReportGenerating(long taskId) {
        boolean generating = reportDao.exists(new LambdaQueryWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getTaskId, taskId)
                .in(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.PENDING.name(), DiagnosisReportStatus.RUNNING.name()));
        if (generating) {
            throw new IllegalArgumentException("该诊断任务已有报告正在生成，请等待完成");
        }
    }

    /**
     * 重判一份报告中没有判定成功的回答：判定失败的记录改回待判定，判定成功的保留；报告回到待生成、从判定段重新开始，
     * 判完由后台接着生成报告内容，已完成报告的原有内容届时被替换。
     * <p>写入（同一事务，任一步失败整体回滚）：
     * <ul>
     *   <li>geo_diagnosis_report_result_analysis：本报告 status = FAILED 的记录改为 PENDING，失败原因与来源清空，由判定重新写入；</li>
     *   <li>geo_diagnosis_report：1 条，stage = JUDGE、status = PENDING，暂停原因、结束时间与冻结的判定提示词版本清空（重判用最新版），
     *       判定渠道沿用已冻结的同一渠道。</li>
     * </ul>
     * 适用：已暂停或已完成、且有判定失败或待判定回答的报告（没有已启用渠道时暂停的报告只有待判定记录）。</p>
     * @param reportId 诊断报告 ID
     * @throws NoSuchElementException 报告不存在
     * @throws IllegalArgumentException 报告正在生成或已失败、没有需要重判的回答、同一任务已有报告在生成、冻结的判定渠道已被删除，或状态已被其他操作改变
     */
    @Transactional
    public void rejudgeReport(long reportId) {
        // 1. 锁住所属任务，确认报告可以重新排队
        GeoDiagnosisReportEntity report = getReport(reportId);
        lockTaskForRequeue(report, DiagnosisReportStatus.PAUSED, DiagnosisReportStatus.SUCCEEDED);
        // 2. 只重判失败或待判定的回答，判定成功的不重判，避免重复计费
        boolean hasUnjudged = analysisDao.exists(new LambdaQueryWrapper<GeoDiagnosisReportResultAnalysisEntity>()
                .eq(GeoDiagnosisReportResultAnalysisEntity::getReportId, reportId)
                .in(GeoDiagnosisReportResultAnalysisEntity::getStatus, DiagnosisReportResultAnalysisStatus.PENDING.name(),
                        DiagnosisReportResultAnalysisStatus.FAILED.name()));
        if (!hasUnjudged) {
            throw new IllegalArgumentException("没有判定失败的回答，无需重判");
        }
        // 3. 失败的回答改回待判定，清掉旧的失败原因和来源，由判定重新写入
        analysisDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportResultAnalysisEntity>()
                .eq(GeoDiagnosisReportResultAnalysisEntity::getReportId, reportId)
                .eq(GeoDiagnosisReportResultAnalysisEntity::getStatus, DiagnosisReportResultAnalysisStatus.FAILED.name())
                .set(GeoDiagnosisReportResultAnalysisEntity::getStatus, DiagnosisReportResultAnalysisStatus.PENDING.name())
                .set(GeoDiagnosisReportResultAnalysisEntity::getErrorMessage, null)
                .set(GeoDiagnosisReportResultAnalysisEntity::getSources, null));
        // 4. 报告回到待生成，从判定段开始
        updateReportRequeued(report, DiagnosisReportStage.JUDGE);
    }

    /**
     * 读取一份诊断报告。
     * @param reportId 诊断报告 ID
     * @return 报告
     * @throws NoSuchElementException 报告不存在
     */
    private GeoDiagnosisReportEntity getReport(long reportId) {
        GeoDiagnosisReportEntity report = reportDao.selectById(reportId);
        if (report == null) {
            throw new NoSuchElementException("诊断报告不存在");
        }
        return report;
    }

    /**
     * 锁住报告所属的诊断任务，并确认报告可以重新排队：状态在允许范围内、同一任务没有别的报告在生成、冻结的判定渠道仍在。
     * <p>与生成新报告（createReport）锁同一行任务，重新排队与新建报告串行，同一任务始终最多一份报告在生成。
     * 报告状态在加锁前读取，最终由写库时的状态条件兜底。</p>
     * @param report 要重新排队的报告
     * @param allowedStatuses 允许重新排队的报告状态
     * @throws IllegalArgumentException 状态不允许、同一任务已有报告在生成，或冻结的判定渠道已被删除
     */
    private void lockTaskForRequeue(GeoDiagnosisReportEntity report, DiagnosisReportStatus... allowedStatuses) {
        // 1. 锁住所属任务行，与生成新报告及其他重跑操作串行
        taskDao.selectOne(new LambdaQueryWrapper<GeoDiagnosisTaskEntity>()
                .eq(GeoDiagnosisTaskEntity::getId, report.getTaskId()).last("FOR UPDATE"));
        // 2. 确认可以重新排队：状态允许、同一任务没有别的报告在生成、冻结的判定渠道仍在
        if (Arrays.stream(allowedStatuses).noneMatch(status -> status.name().equals(report.getStatus()))) {
            throw new IllegalArgumentException("报告正在生成或已失败，不能执行此操作");
        }
        validateNoReportGenerating(report.getTaskId());
        validateJudgeChannelExists(report);
    }

    /**
     * 确认报告冻结的判定渠道仍然存在；还没开始判定、尚未冻结渠道的报告不检查，领取时再选默认渠道。
     * @param report 诊断报告
     * @throws IllegalArgumentException 冻结的判定渠道已被删除
     */
    private void validateJudgeChannelExists(GeoDiagnosisReportEntity report) {
        if (report.getJudgeChannelSnapshot() == null) {
            return;
        }
        Long channelId = ((JSONObject) snapshots.parseSnapshot(report.getJudgeChannelSnapshot())).getLong("id");
        // 重跑不换判定渠道，渠道被删后无法保持同一份报告的判定口径
        if (channelService.getById(channelId) == null) {
            throw new IllegalArgumentException("报告使用的判定渠道已被删除，无法重跑，请重新生成报告");
        }
    }

    /**
     * 把报告重新排队：写入后台要执行的段，状态改为待生成，清空暂停原因、结束时间和上一次第 2 段的子步骤进度；只改仍是读取时状态的报告。
     * 回到判定段（重判）时还清掉冻结的判定提示词版本，重判换用最新版，方便改好提示词后重试。
     * @param report 已确认可重新排队的报告
     * @param stage 后台领取后从哪一段开始
     * @throws IllegalArgumentException 报告状态已被其他操作改变，未写入
     */
    private void updateReportRequeued(GeoDiagnosisReportEntity report, DiagnosisReportStage stage) {
        int changed = reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getId, report.getId())
                .eq(GeoDiagnosisReportEntity::getStatus, report.getStatus())
                .set(GeoDiagnosisReportEntity::getStage, stage.name())
                .set(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.PENDING.name())
                .set(GeoDiagnosisReportEntity::getErrorMessage, null)
                .set(GeoDiagnosisReportEntity::getFinishedAt, null)
                .set(GeoDiagnosisReportEntity::getContentProgress, null)
                .set(stage == DiagnosisReportStage.JUDGE, GeoDiagnosisReportEntity::getPromptVersion, null));
        if (changed != 1) {
            throw new IllegalArgumentException("报告状态已变化，请刷新后再试");
        }
        log.info("诊断报告已重新排队 reportId={}, stage={}, previousStatus={}", report.getId(), stage, report.getStatus());
    }

    /**
     * 重新生成报告内容：报告回到待生成、停在第 2 段，由后台整段重做维度归一、统计、优化建议与地域策略，判定结果不动；
     * 已完成报告的原有内容在新内容写入时被替换，生成期间报告不能查看。
     * <p>写入 geo_diagnosis_report 1 条：stage = CONTENT、status = PENDING，暂停原因与结束时间清空。
     * 适用：停在第 2 段的已暂停报告，或已完成的报告。</p>
     * @param reportId 诊断报告 ID
     * @throws NoSuchElementException 报告不存在
     * @throws IllegalArgumentException 报告停在判定段、正在生成或已失败，同一任务已有报告在生成，冻结的判定渠道已被删除，或状态已被其他操作改变
     */
    @Transactional
    public void regenerateReportContent(long reportId) {
        // 1. 停在判定段的报告还有判定失败没处理，要先重判或忽略失败继续
        GeoDiagnosisReportEntity report = getReport(reportId);
        boolean pausedAtJudge = DiagnosisReportStatus.PAUSED.name().equals(report.getStatus())
                && DiagnosisReportStage.JUDGE.name().equals(report.getStage());
        if (pausedAtJudge) {
            throw new IllegalArgumentException("报告停在判定段，请先重判失败的回答或忽略失败继续");
        }
        // 2. 锁住所属任务，确认报告可以重新排队
        lockTaskForRequeue(report, DiagnosisReportStatus.PAUSED, DiagnosisReportStatus.SUCCEEDED);
        // 3. 报告回到待生成，第 2 段整段重做
        updateReportRequeued(report, DiagnosisReportStage.CONTENT);
    }

    /**
     * 忽略失败继续：停在判定段时，判定失败的回答保持失败、不计入依赖判定的指标，报告回到待生成并进入第 2 段；
     * 停在第 2 段时，按已保存的内容直接完成报告，失败的建议或地域策略在报告上显示为生成失败，维度归一失败时维度保持原名。
     * <p>写入 geo_diagnosis_report 1 条，暂停原因清空：判定段改为 stage = CONTENT、status = PENDING；第 2 段改为 status = SUCCEEDED 并记下结束时间。</p>
     * @param reportId 诊断报告 ID
     * @throws NoSuchElementException 报告不存在
     * @throws IllegalArgumentException 报告未暂停；判定段没有判定成功的回答、同一任务已有报告在生成或冻结的判定渠道已被删除；
     *         第 2 段没有已生成的内容；或状态已被其他操作改变
     */
    @Transactional
    public void skipReportFailures(long reportId) {
        // 1. 只有已暂停的报告才有可忽略的失败
        GeoDiagnosisReportEntity report = getReport(reportId);
        if (!DiagnosisReportStatus.PAUSED.name().equals(report.getStatus())) {
            throw new IllegalArgumentException("只有已暂停的报告可以忽略失败继续");
        }
        // 2. 判定段忽略后还要生成报告内容，需要重新排队；第 2 段忽略后不再调用模型，直接完成
        if (DiagnosisReportStage.JUDGE.name().equals(report.getStage())) {
            skipJudgeFailures(report);
        } else {
            updateReportSucceededWithFailures(report);
        }
    }

    /**
     * 判定段忽略失败：确认至少有一条判定成功的回答后，把报告重新排队到第 2 段；失败的回答保持失败，不计入依赖判定的指标。
     * @param report 停在判定段的已暂停报告
     * @throws IllegalArgumentException 没有判定成功的回答、同一任务已有报告在生成、冻结的判定渠道已被删除，或状态已被其他操作改变
     */
    private void skipJudgeFailures(GeoDiagnosisReportEntity report) {
        // 1. 锁住所属任务，确认报告可以重新排队
        lockTaskForRequeue(report, DiagnosisReportStatus.PAUSED);
        // 2. 没有判定成功的回答就没有可统计的指标，只能重判
        boolean anyJudged = analysisDao.exists(new LambdaQueryWrapper<GeoDiagnosisReportResultAnalysisEntity>()
                .eq(GeoDiagnosisReportResultAnalysisEntity::getReportId, report.getId())
                .eq(GeoDiagnosisReportResultAnalysisEntity::getStatus, DiagnosisReportResultAnalysisStatus.SUCCEEDED.name()));
        if (!anyJudged) {
            throw new IllegalArgumentException("没有判定成功的回答，无法生成报告内容，请重判");
        }
        // 3. 报告进入第 2 段，等后台生成报告内容
        updateReportRequeued(report, DiagnosisReportStage.CONTENT);
    }

    /**
     * 第 2 段忽略失败：按暂停时保存的内容直接完成报告，不再调用模型；只改仍停在第 2 段的已暂停报告。
     * @param report 停在第 2 段的已暂停报告
     * @throws IllegalArgumentException 没有已生成的内容（内容生成出错），或状态已被其他操作改变
     */
    private void updateReportSucceededWithFailures(GeoDiagnosisReportEntity report) {
        // 1. 内容生成出错时没有可用的内容，忽略也完成不了，只能修复后重新生成
        if (report.getSummary() == null) {
            throw new IllegalArgumentException("报告内容生成出错，没有可用的内容，请修复后重新生成报告内容");
        }
        // 2. 按暂停时保存的内容完成报告；期间被其他操作改走状态时不写入
        int changed = reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getId, report.getId())
                .eq(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.PAUSED.name())
                .eq(GeoDiagnosisReportEntity::getStage, DiagnosisReportStage.CONTENT.name())
                .set(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.SUCCEEDED.name())
                .set(GeoDiagnosisReportEntity::getErrorMessage, null)
                .set(GeoDiagnosisReportEntity::getFinishedAt, LocalDateTime.now()));
        if (changed != 1) {
            throw new IllegalArgumentException("报告状态已变化，请刷新后再试");
        }
        log.info("诊断报告已忽略失败并完成 reportId={}", report.getId());
    }

    /**
     * 领取下一份要生成的报告：优先继续上次中断的 RUNNING 报告，其次领取最早的 PENDING 报告并改为 RUNNING；已暂停的报告不领取，等用户处理。
     * <p>单实例执行由后台执行器的数据库会话锁保证，因此残留的 RUNNING 报告可以直接续跑；已判定的回答不会重复判定。</p>
     * @return 已处于 RUNNING 的报告，没有可处理的报告时为 null
     */
    @Transactional
    public GeoDiagnosisReportEntity claimNextReport() {
        // 1. 存在在分析中的报告，优先处理
        GeoDiagnosisReportEntity running = reportDao.selectOne(new LambdaQueryWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.RUNNING.name())
                .orderByAsc(GeoDiagnosisReportEntity::getId).last("LIMIT 1 FOR UPDATE"));
        if (running != null) {
            return running;
        }
        // 2. 获取待处理的诊断报告
        GeoDiagnosisReportEntity report = reportDao.selectOne(new LambdaQueryWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.PENDING.name())
                .orderByAsc(GeoDiagnosisReportEntity::getId).last("LIMIT 1 FOR UPDATE"));
        if (report == null) {
            return null;
        }
        // 2.1 标记诊断报告为处理中
        LocalDateTime now = LocalDateTime.now();
        int changed = reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getId, report.getId())
                .eq(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.PENDING.name())
                .set(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.RUNNING.name())
                .set(GeoDiagnosisReportEntity::getStartedAt, now));
        if (changed != 1) {
            // 标记失败：报告已被其他操作改走状态，本轮不领取，下次轮询再取
            return null;
        }
        report.setStatus(DiagnosisReportStatus.RUNNING.name());
        report.setStartedAt(now);
        return report;
    }

    /**
     * 冻结本报告的判定渠道；已冻结的报告不改写，保证续跑与重跑时使用同一判定渠道。
     * @param reportId 报告 ID
     * @param judgeChannelSnapshot 判定渠道的非敏感配置快照 JSON
     */
    @Transactional
    public void freezeJudgeChannel(long reportId, String judgeChannelSnapshot) {
        reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getId, reportId)
                .isNull(GeoDiagnosisReportEntity::getJudgeChannelSnapshot)
                .set(GeoDiagnosisReportEntity::getJudgeChannelSnapshot, judgeChannelSnapshot));
    }

    /**
     * 冻结本报告判定用的提示词版本（如 v3）；已冻结的不改写，服务重启后续跑沿用同一版，同一份报告判定口径一致。
     * 用户手动重判时版本被清空（见 updateReportRequeued），下次判定重新冻结为当时的最新版。
     * @param reportId 报告 ID
     * @param promptVersion 判定提示词版本，取自提示词管理
     */
    @Transactional
    public void freezeJudgePromptVersion(long reportId, String promptVersion) {
        reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getId, reportId)
                .isNull(GeoDiagnosisReportEntity::getPromptVersion)
                .set(GeoDiagnosisReportEntity::getPromptVersion, promptVersion));
    }

    /**
     * 按 ID 顺序取下一条待判定记录。
     * @param reportId 报告 ID
     * @return 待判定记录，没有剩余时为 null
     */
    public GeoDiagnosisReportResultAnalysisEntity findNextPendingAnalysis(long reportId) {
        return analysisDao.selectOne(new LambdaQueryWrapper<GeoDiagnosisReportResultAnalysisEntity>()
                .eq(GeoDiagnosisReportResultAnalysisEntity::getReportId, reportId)
                .eq(GeoDiagnosisReportResultAnalysisEntity::getStatus, DiagnosisReportResultAnalysisStatus.PENDING.name())
                .orderByAsc(GeoDiagnosisReportResultAnalysisEntity::getId).last("LIMIT 1"));
    }

    /**
     * 保存一条判定成功的分析结果；只允许待判定记录进入成功，避免覆盖已有结果。
     * @param analysis 填好品牌情感、描述准确性及 brands、points、sources JSON 的分析记录
     * @throws IllegalStateException 记录不处于待判定状态，未保存
     */
    @Transactional
    public void updateAnalysisSucceeded(GeoDiagnosisReportResultAnalysisEntity analysis) {
        int changed = analysisDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportResultAnalysisEntity>()
                .eq(GeoDiagnosisReportResultAnalysisEntity::getId, analysis.getId())
                .eq(GeoDiagnosisReportResultAnalysisEntity::getStatus, DiagnosisReportResultAnalysisStatus.PENDING.name())
                .set(GeoDiagnosisReportResultAnalysisEntity::getStatus, DiagnosisReportResultAnalysisStatus.SUCCEEDED.name())
                .set(GeoDiagnosisReportResultAnalysisEntity::getBrandSentiment, analysis.getBrandSentiment())
                .set(GeoDiagnosisReportResultAnalysisEntity::getDescriptionAccurate, analysis.getDescriptionAccurate())
                .set(GeoDiagnosisReportResultAnalysisEntity::getBrands, analysis.getBrands())
                .set(GeoDiagnosisReportResultAnalysisEntity::getPoints, analysis.getPoints())
                .set(GeoDiagnosisReportResultAnalysisEntity::getSources, analysis.getSources()));
        if (changed != 1) {
            throw new IllegalStateException("诊断回答分析结果保存失败");
        }
    }

    /**
     * 记录一条判定失败；失败不重试，该回答不计入依赖判定的指标。
     * @param analysisId 分析记录 ID
     * @param reason 脱敏后的失败原因，不接收远程异常原文
     * @param sources 规则归类的来源 JSON，判定失败仍保留供来源分析使用，可为 null
     * @throws IllegalStateException 记录不处于待判定状态，未保存
     */
    @Transactional
    public void updateAnalysisFailed(long analysisId, String reason, String sources) {
        int changed = analysisDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportResultAnalysisEntity>()
                .eq(GeoDiagnosisReportResultAnalysisEntity::getId, analysisId)
                .eq(GeoDiagnosisReportResultAnalysisEntity::getStatus, DiagnosisReportResultAnalysisStatus.PENDING.name())
                .set(GeoDiagnosisReportResultAnalysisEntity::getStatus, DiagnosisReportResultAnalysisStatus.FAILED.name())
                .set(GeoDiagnosisReportResultAnalysisEntity::getErrorMessage, reason)
                .set(GeoDiagnosisReportResultAnalysisEntity::getSources, sources));
        if (changed != 1) {
            throw new IllegalStateException("诊断回答分析失败结果保存失败");
        }
    }

    /**
     * 统计一份报告中判定失败的回答按失败原因各有几条，供暂停原因使用。
     * @param reportId 诊断报告 ID
     * @return 失败原因到条数的映射，按条数从多到少；没有判定失败时为空映射，没有记录原因的计为“原因未记录”
     */
    public Map<String, Long> countAnalysisFailureReasons(long reportId) {
        Map<String, Long> reasons = new LinkedHashMap<>();
        for (Map<String, Object> row : analysisDao.countFailureReasons(reportId, DiagnosisReportResultAnalysisStatus.FAILED.name())) {
            String reason = row.get("error_message") == null ? "原因未记录" : (String) row.get("error_message");
            reasons.put(reason, ((Number) row.get("analysis_count")).longValue());
        }
        return reasons;
    }

    /**
     * 第 1 段全部判定成功后，把报告切到第 2 段生成报告内容；只改生成中、停在判定段的报告。
     * @param reportId 诊断报告 ID
     * @throws IllegalStateException 报告已不在判定段生成中，未写入
     */
    @Transactional
    public void updateReportStageContent(long reportId) {
        int changed = reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getId, reportId)
                .eq(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.RUNNING.name())
                .eq(GeoDiagnosisReportEntity::getStage, DiagnosisReportStage.JUDGE.name())
                .set(GeoDiagnosisReportEntity::getStage, DiagnosisReportStage.CONTENT.name()));
        if (changed != 1) {
            throw new IllegalStateException("诊断报告进入生成报告内容失败");
        }
    }

    /**
     * 保存第 2 段的子步骤进度，供页面显示第 2 段走到哪一步；只改生成中、停在第 2 段的报告，其他状态不写入也不报错。
     * @param reportId 报告 ID
     * @param contentProgress 子步骤进度 JSON
     */
    @Transactional
    public void updateReportContentProgress(long reportId, String contentProgress) {
        reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getId, reportId)
                .eq(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.RUNNING.name())
                .eq(GeoDiagnosisReportEntity::getStage, DiagnosisReportStage.CONTENT.name())
                .set(GeoDiagnosisReportEntity::getContentProgress, contentProgress));
    }

    /**
     * 第 2 段全部成功后冻结报告内容并完成报告，子步骤进度一并写入；只更新 RUNNING 报告。
     * @param reportId 报告 ID
     * @param summary 第 2 段生成的报告内容 JSON
     * @param contentProgress 第 2 段结束时的子步骤进度 JSON
     */
    @Transactional
    public void finishReport(long reportId, String summary, String contentProgress) {
        reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getId, reportId)
                .eq(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.RUNNING.name())
                .set(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.SUCCEEDED.name())
                .set(GeoDiagnosisReportEntity::getSummary, summary)
                .set(GeoDiagnosisReportEntity::getContentProgress, contentProgress)
                .set(GeoDiagnosisReportEntity::getFinishedAt, LocalDateTime.now()));
    }

    /**
     * 暂停报告：当前段有失败时写入原因，等用户重试或忽略失败继续；只改生成中的报告，段、已保存的判定结果与内容都不动。
     * @param reportId 报告 ID
     * @param reason 脱敏后的暂停原因，写明哪一步失败、失败几条
     */
    @Transactional
    public void updateReportPaused(long reportId, String reason) {
        reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getId, reportId)
                .eq(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.RUNNING.name())
                .set(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.PAUSED.name())
                .set(GeoDiagnosisReportEntity::getErrorMessage, reason));
    }

    /**
     * 第 2 段有失败时保存本次生成的内容并暂停报告；只改生成中的报告。
     * <p>写入 geo_diagnosis_report 1 条：summary 替换为本次内容（内容生成出错时清空，上一次的内容不再保留），status = PAUSED，
     * error_message 写明哪几步失败，content_progress 写入结束时的子步骤进度。</p>
     * @param reportId 报告 ID
     * @param summary 本次生成的内容 JSON，失败的建议或地域策略已标记为生成失败；内容生成出错时为 null
     * @param reason 脱敏后的暂停原因
     * @param contentProgress 第 2 段结束时的子步骤进度 JSON，失败的步骤已标记
     */
    @Transactional
    public void updateReportContentPaused(long reportId, String summary, String reason, String contentProgress) {
        reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getId, reportId)
                .eq(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.RUNNING.name())
                .set(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.PAUSED.name())
                .set(GeoDiagnosisReportEntity::getSummary, summary)
                .set(GeoDiagnosisReportEntity::getErrorMessage, reason)
                .set(GeoDiagnosisReportEntity::getContentProgress, contentProgress));
    }

    /**
     * 将报告标记为失败；只更新 RUNNING 报告，已保存的分析结果保留。
     * <p>现在只在冻结的判定渠道被删除时调用：重跑不换渠道，报告无法再执行，只能重新生成一份。</p>
     * @param reportId 报告 ID
     * @param reason 脱敏后的失败原因
     */
    @Transactional
    public void failReport(long reportId, String reason) {
        reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getId, reportId)
                .eq(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.RUNNING.name())
                .set(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.FAILED.name())
                .set(GeoDiagnosisReportEntity::getErrorMessage, reason)
                .set(GeoDiagnosisReportEntity::getFinishedAt, LocalDateTime.now()));
    }

    /**
     * 读取被分析的诊断结果。
     * @param resultId 诊断结果 ID
     * @return 诊断结果
     * @throws NoSuchElementException 诊断结果不存在
     */
    public GeoDiagnosisTaskResultEntity getTaskResult(long resultId) {
        GeoDiagnosisTaskResultEntity result = resultDao.selectById(resultId);
        if (result == null) {
            throw new NoSuchElementException("诊断结果不存在");
        }
        return result;
    }

    /**
     * 读取报告所属诊断任务的冻结快照，提供项目资料、问题与渠道配置。
     * @param taskId 诊断任务 ID
     * @return 任务快照 JSON
     * @throws NoSuchElementException 诊断任务不存在
     */
    public JSONObject getTaskSnapshot(long taskId) {
        GeoDiagnosisTaskEntity task = taskDao.selectById(taskId);
        if (task == null) {
            throw new NoSuchElementException("诊断任务不存在");
        }
        return (JSONObject) snapshots.parseSnapshot(task.getPlanSnapshot());
    }

    /**
     * 读取我方已公开发布内容的链接，用于判断来源是否为我方发布。
     * @return 去除首尾空白的公开链接集合
     */
    public Set<String> listPublishedUrls() {
        Set<String> urls = new HashSet<>();
        for (ContentPublishRecordEntity record : publishRecordDao.selectList(new LambdaQueryWrapper<ContentPublishRecordEntity>()
                .isNotNull(ContentPublishRecordEntity::getPublishedUrl).select(ContentPublishRecordEntity::getPublishedUrl))) {
            if (record.getPublishedUrl() != null && !record.getPublishedUrl().isBlank()) {
                urls.add(record.getPublishedUrl().strip());
            }
        }
        return urls;
    }

    /**
     * 读取诊断大盘趋势里排在某份报告之前的点：同一方案、比该报告所属任务更早创建的任务，每个任务取最新一份成功报告的大盘数字。
     * <p>同一任务的多份报告是同一批回答重新判定，只算一个点。最多回看最近 11 个有成功报告的任务；
     * 其中报告生成于大盘功能之前、没有大盘数字的任务跳过，不再往更早补。只读，不写库。</p>
     * @param taskId 当前报告所属的诊断任务 ID
     * @return 按任务从早到晚排列的点；任务不存在、没有更早的任务或它们都没有大盘数字时为空列表
     */
    public List<DiagnosisReportDashboardPoint> listEarlierDashboardPoints(long taskId) {
        GeoDiagnosisTaskEntity task = taskDao.selectById(taskId);
        if (task == null) {
            return List.of();
        }
        // 1. 同方案更早的任务：任务 ID 随创建时间递增，按 ID 比先后；只取标识与时间，不读快照大字段
        Map<Long, GeoDiagnosisTaskEntity> earlierTasks = taskDao.selectList(new LambdaQueryWrapper<GeoDiagnosisTaskEntity>()
                        .eq(GeoDiagnosisTaskEntity::getPlanId, task.getPlanId()).lt(GeoDiagnosisTaskEntity::getId, taskId)
                        .select(GeoDiagnosisTaskEntity::getId, GeoDiagnosisTaskEntity::getFinishedAt, GeoDiagnosisTaskEntity::getCreatedAt))
                .stream().collect(Collectors.toMap(GeoDiagnosisTaskEntity::getId, Function.identity()));
        if (earlierTasks.isEmpty()) {
            return List.of();
        }
        // 2. 每个任务最新一份成功报告的 ID；先不读报告内容，内容是大字段
        Map<Long, Long> latestReportIds = reportDao.selectList(new LambdaQueryWrapper<GeoDiagnosisReportEntity>()
                        .in(GeoDiagnosisReportEntity::getTaskId, earlierTasks.keySet())
                        .eq(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.SUCCEEDED.name())
                        .select(GeoDiagnosisReportEntity::getId, GeoDiagnosisReportEntity::getTaskId))
                .stream().collect(Collectors.toMap(GeoDiagnosisReportEntity::getTaskId, GeoDiagnosisReportEntity::getId, Math::max));
        // 3. 只读最近若干个任务的报告内容，取出其中的大盘数字
        List<Long> recentReportIds = latestReportIds.entrySet().stream().sorted(Map.Entry.<Long, Long>comparingByKey().reversed())
                .limit(DASHBOARD_HISTORY_LIMIT).map(Map.Entry::getValue).toList();
        return recentReportIds.isEmpty() ? List.of() : loadDashboardPoints(recentReportIds, earlierTasks);
    }

    /**
     * 读取若干份报告的内容，把其中的大盘数字连同所属任务的时间整理成趋势点。
     * @param reportIds 诊断报告 ID，每个任务一份
     * @param tasks 这些报告所属的诊断任务，按任务 ID 索引，提供趋势点的时间
     * @return 按任务从早到晚排列的点，没有大盘数字的报告不出现
     */
    private List<DiagnosisReportDashboardPoint> loadDashboardPoints(List<Long> reportIds, Map<Long, GeoDiagnosisTaskEntity> tasks) {
        List<DiagnosisReportDashboardPoint> points = new ArrayList<>();
        for (GeoDiagnosisReportEntity report : reportDao.selectBatchIds(reportIds)) {
            JSONObject summary = report.getSummary() == null ? null : (JSONObject) snapshots.parseSnapshot(report.getSummary());
            JSONObject dashboard = summary == null ? null : summary.getJSONObject("dashboard");
            // 大盘功能之前生成的报告没有这部分数字，不进趋势
            if (dashboard == null) {
                continue;
            }
            GeoDiagnosisTaskEntity earlierTask = tasks.get(report.getTaskId());
            points.add(new DiagnosisReportDashboardPoint(earlierTask.getId(), report.getId(),
                    earlierTask.getFinishedAt() != null ? earlierTask.getFinishedAt() : earlierTask.getCreatedAt(), dashboard));
        }
        points.sort(Comparator.comparing(DiagnosisReportDashboardPoint::taskId));
        return points;
    }

    /**
     * 读取报告的全部分析记录及对应诊断结果，供汇总使用。
     * @param reportId 报告 ID
     * @return 按分析 ID 顺序的回答列表
     */
    public List<ReportAnswer> loadAnswers(long reportId) {
        // 1. 读取本报告全部分析记录
        List<GeoDiagnosisReportResultAnalysisEntity> analyses = analysisDao.selectList(new LambdaQueryWrapper<GeoDiagnosisReportResultAnalysisEntity>()
                .eq(GeoDiagnosisReportResultAnalysisEntity::getReportId, reportId)
                .orderByAsc(GeoDiagnosisReportResultAnalysisEntity::getId));
        if (analyses.isEmpty()) {
            return List.of();
        }
        // 2. 一次查询对应诊断结果，按 ID 组装，避免逐条往返
        Map<Long, GeoDiagnosisTaskResultEntity> results = resultDao.selectBatchIds(
                analyses.stream().map(GeoDiagnosisReportResultAnalysisEntity::getTaskResultId).toList()).stream()
                .collect(Collectors.toMap(GeoDiagnosisTaskResultEntity::getId, Function.identity()));
        return analyses.stream().map(analysis -> new ReportAnswer(analysis, results.get(analysis.getTaskResultId()))).toList();
    }

    /**
     * 汇总用的一条回答：分析记录与对应诊断结果。
     * @param analysis 分析记录
     * @param result 诊断结果，包含问题与渠道快照、回答、引用与原始响应
     */
    public record ReportAnswer(GeoDiagnosisReportResultAnalysisEntity analysis, GeoDiagnosisTaskResultEntity result) {
    }
}
