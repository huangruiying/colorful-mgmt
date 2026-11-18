package org.huangry.colorful.geo.domain.service.diagnosis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisReportResultAnalysisStatus;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 诊断报告的领域服务：报告与回答分析的持久化、状态流转，以及生成报告时读取所需数据；每次手动生成创建一份新报告，旧报告保留。
 * <p>状态：PENDING → RUNNING → SUCCEEDED / FAILED；单条分析：PENDING → SUCCEEDED / FAILED。
 * 只分析成功且未因输出上限截断的诊断结果；判定与汇总由后台执行器编排，本服务事务只覆盖数据库操作，不包含模型调用。</p>
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

    /**
     * 把 诊断任务 转成 待处理的诊断报告(初始化)
     * 为已结束的诊断任务创建一份待生成的报告，并为每条有效回答建立待判定记录；不调用模型，判定与汇总由 DiagnosisReportWorker 后台完成。
     * <p>写入（同一事务，任一步失败整体回滚）：
     * <ul>
     *   <li>geo_diagnosis_report：新增 1 条，status = PENDING，记下报告语言，判定渠道与汇总留空；</li>
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
        boolean generating = reportDao.exists(new LambdaQueryWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getTaskId, taskId)
                .in(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.PENDING.name(), DiagnosisReportStatus.RUNNING.name()));
        if (generating) {
            throw new IllegalArgumentException("该诊断任务已有报告正在生成，请等待完成");
        }
        // 3. 创建报告并为有效回答批量建立待判定记录；没有有效回答时整体回滚
        GeoDiagnosisReportEntity report = new GeoDiagnosisReportEntity();
        report.setTaskId(taskId);
        report.setStatus(DiagnosisReportStatus.PENDING.name());
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
     * 领取下一份要生成的报告：优先继续上次中断的 RUNNING 报告，其次领取最早的 PENDING 报告并改为 RUNNING。
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
     * 冻结本报告的判定渠道与提示词版本；已冻结的报告不改写，保证续跑时使用同一判定口径。
     * @param reportId 报告 ID
     * @param judgeChannelSnapshot 判定渠道的非敏感配置快照 JSON
     * @param promptVersion 判定提示词版本
     */
    @Transactional
    public void freezeJudgeChannel(long reportId, String judgeChannelSnapshot, String promptVersion) {
        reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getId, reportId)
                .isNull(GeoDiagnosisReportEntity::getJudgeChannelSnapshot)
                .set(GeoDiagnosisReportEntity::getJudgeChannelSnapshot, judgeChannelSnapshot)
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
     * 冻结汇总并结束报告；只更新 RUNNING 报告。
     * @param reportId 报告 ID
     * @param summary 冻结的汇总 JSON
     */
    @Transactional
    public void finishReport(long reportId, String summary) {
        reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getId, reportId)
                .eq(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.RUNNING.name())
                .set(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.SUCCEEDED.name())
                .set(GeoDiagnosisReportEntity::getSummary, summary)
                .set(GeoDiagnosisReportEntity::getFinishedAt, LocalDateTime.now()));
    }

    /**
     * 补全报告缺失的汇总内容：用补算后的完整汇总替换 summary，状态、时间与判定记录都不改。
     * <p>写入 geo_diagnosis_report 1 条的 summary 字段，只更新已完成（SUCCEEDED）的报告；同一份报告不重复补全由调用方保证。</p>
     * @param reportId 报告 ID
     * @param summary 补全后的完整汇总 JSON
     * @throws IllegalStateException 报告已不是已完成状态，未写入
     */
    @Transactional
    public void updateReportSummarySupplemented(long reportId, String summary) {
        int changed = reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisReportEntity>()
                .eq(GeoDiagnosisReportEntity::getId, reportId)
                .eq(GeoDiagnosisReportEntity::getStatus, DiagnosisReportStatus.SUCCEEDED.name())
                .set(GeoDiagnosisReportEntity::getSummary, summary));
        if (changed != 1) {
            throw new IllegalStateException("诊断报告补全结果保存失败");
        }
    }

    /**
     * 将报告标记为失败；只更新 RUNNING 报告，已保存的分析结果保留。
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
