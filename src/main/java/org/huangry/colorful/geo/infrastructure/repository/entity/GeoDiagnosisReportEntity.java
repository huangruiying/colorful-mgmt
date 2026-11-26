package org.huangry.colorful.geo.infrastructure.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 一份诊断报告的数据库记录：生成状态、判定口径、报告语言与冻结的汇总结果。
 * <p>对应表 geo_diagnosis_report，每次手动生成一份；通过 DAO 持久化，不负责判定与汇总。</p>
 */
@Data
@TableName("geo_diagnosis_report")
public class GeoDiagnosisReportEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属诊断任务 ID；同一任务可多次生成报告，旧报告保留。 */
    private Long taskId;
    /** 报告生成状态，保存 {@link org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisReportStatus} 的枚举名称。 */
    private String status;
    /** 报告执行到或停在哪一段，保存 {@link org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisReportStage} 的枚举名称；与 status 一起表示进度。 */
    private String stage;
    /** JSON 字符串：判定所用渠道（默认渠道，即已启用渠道中分数最高者）的非敏感配置，不保存凭据；开始判定时写入。 */
    private String judgeChannelSnapshot;
    /** 判定提示词版本（如 v2），取自提示词管理，开始判定时冻结、续跑沿用、手动重判时换成最新版；用于说明报告结论的判定口径。 */
    private String promptVersion;
    /** 报告语言，生成报告时选择，默认中文：解析出来的观点概括、维度名、建议与地域策略按此语言书写，问题、回答、引用等原文不翻译。 */
    private String reportLanguage;
    /** JSON 字符串：报告内容（汇总指标、主要发现、优化建议与各地域策略）；第 2 段结束时写入，暂停时保存已生成的内容，完成后不随规则或数据变化重算，用户重新生成报告内容时整份替换。 */
    private String summary;
    /**
     * JSON 字符串：第 2 段（生成报告内容）四个子步骤的状态与说明，执行中逐步更新，结构见 DiagnosisReportContentProgress；
     * 只用于页面显示，不参与重试判断。重新排队时清空，还没运行过第 2 段的报告为空。
     */
    private String contentProgress;
    /** 脱敏后的暂停或失败原因，不保存远程异常原文；用户重试、重跑或忽略失败继续时清空。 */
    private String errorMessage;
    /** 后台最近一次领取执行的时间；新建的报告尚未领取时为空。 */
    private LocalDateTime startedAt;
    /** 报告完成或失败的时间；生成中、已暂停或重新排队时为空。 */
    private LocalDateTime finishedAt;
    /** 由数据库生成的创建时间，插入后重新查询回读。 */
    private LocalDateTime createdAt;
    /** 由数据库维护的最后更新时间。 */
    private LocalDateTime updatedAt;
}
