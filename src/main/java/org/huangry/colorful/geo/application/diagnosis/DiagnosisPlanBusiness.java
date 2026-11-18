package org.huangry.colorful.geo.application.diagnosis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.application.diagnosis.model.*;
import org.huangry.colorful.geo.infrastructure.common.model.PageQuery;
import org.huangry.colorful.geo.infrastructure.common.model.PageResponse;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisPlanService;
import org.huangry.colorful.geo.domain.service.QuestionGenerateService;
import org.huangry.colorful.geo.domain.service.project.GeoProjectInfoService;
import org.huangry.colorful.geo.infrastructure.repository.entity.*;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * 诊断方案页面的应用编排：方案的保存、查看、列表，以及生成母题和按地域改写候选问题。
 * <p>负责请求与实体、实体与响应的显式转换；问题规则与落库交给领域服务，不启动诊断任务。</p>
 */
@Service
public class DiagnosisPlanBusiness {
    @Resource
    private GeoDiagnosisPlanService planService;
    @Resource
    private GeoProjectInfoService projectService;
    @Resource
    private QuestionGenerateService questionGenerateService;

    /** 显式映射页面已确认的集合；校验与事务落库交由领域服务。 */
    public DiagnosisPlanResponse savePlan(Long id, SaveDiagnosisPlanRequest request) {
        // 1. 请求只提供可维护字段，ID 由路径或数据库决定。
        GeoDiagnosisPlanEntity plan = new GeoDiagnosisPlanEntity();
        plan.setId(id);
        plan.setName(request.name());
        plan.setProjectId(request.projectId());
        plan.setRepeatCount(request.repeatCount());
        if (request.questions() == null || request.channelIds() == null)
            throw new IllegalArgumentException("问题集合与测试渠道必填");
        // 2. 集合项逐字段转换，不接受数据库主键或其他方案关联。
        List<GeoDiagnosisPlanQuestionEntity> questions = request.questions().stream().map(this::toEntity).toList();
        List<GeoDiagnosisPlanChannelEntity> channels = request.channelIds().stream().map(channelId -> {
            var entity = new GeoDiagnosisPlanChannelEntity();
            entity.setChannelId(channelId);
            return entity;
        }).toList();
        // 3. 返回数据库实际保存后的方案，包含生成标识和更新时间。
        return toResponse(planService.savePlan(plan, questions, channels));
    }

    /** 缺失方案返回明确错误，详情包含整个问题集合。 */
    public DiagnosisPlanResponse getPlan(long id) {
        var plan = planService.getById(id);
        if (plan == null) throw new NoSuchElementException("诊断方案不存在");
        return toResponse(plan);
    }

    /**
     * 诊断方案列表分页查询，可按名称筛选；不加载问题和渠道。
     *
     * @param keyword 方案名称关键词，可为空
     * @param page 从零开始的页码
     * @param size 每页条数，上限 100
     * @return 当前页方案及总数
     */
    public PageResponse<PlanPageItem> listPlans(String keyword, int page, int size) {
        // 1. 分页参数和关键词越界直接拒绝，避免一次查询过多记录
        var pageQuery = new PageQuery(page, size, keyword);
        // 2. 按名称筛选查询当前页方案和总数，关键词通过绑定参数传入
        var query = new LambdaQueryWrapper<GeoDiagnosisPlanEntity>()
                .like(pageQuery.keyword() != null, GeoDiagnosisPlanEntity::getName, pageQuery.keyword());
        long total = planService.count(query);
        var plans = planService.list(query.orderByDesc(GeoDiagnosisPlanEntity::getId).last(pageQuery.limitClause()));
        // 3. 批量读取所属项目名称，避免逐条查询
        Map<Long, String> names = new HashMap<>();
        if (!plans.isEmpty()) {
            projectService.listByIds(plans.stream().map(GeoDiagnosisPlanEntity::getProjectId).distinct().toList())
                    .forEach(project -> names.put(project.getId(), project.getName()));
        }
        // 4. 组装列表行
        var items = plans.stream().map(plan -> new PlanPageItem(plan.getId(), plan.getName(),
                plan.getProjectId(), names.get(plan.getProjectId()), plan.getRepeatCount(), plan.getUpdatedAt())).toList();
        return pageQuery.toResponse(items, total);
    }

    /**
     * 按项目资料和本次地域生成一套母题，返回给页面逐个地域改写；不保存、不修改已保存方案或用户现有集合。
     * @param request 项目、可选制题渠道、母题数量、母题语言与本次要改写到的地域
     * @return 母题，地域为空；页面按地域改写后才成为候选
     * @throws IllegalArgumentException 项目无效，或数量、语言、渠道编码、地域不合法，此时不调用模型
     */
    public List<DiagnosisQuestion> generateQuestions(GenerateDiagnosisQuestionsRequest request) {
        // 1. 项目资料是制题背景，缺失时不调用模型
        var project = request.projectId() == null ? null : projectService.getById(request.projectId());
        if (project == null) throw new IllegalArgumentException("请选择有效项目");
        // 2. 交给制题服务生成母题，返回不落库
        return questionGenerateService.generate(project, request.channelCode(), request.count(), request.language(), request.regions())
                .stream().map(this::toQuestion).toList();
    }

    /**
     * 把母题按一个地域改写成当地用户会问的问题，返回候选供页面审核，不保存、不修改用户现有集合。
     * <p>页面每个地域行调用一次；同一批母题改写到多个地域时，候选总数为母题数 × 地域行数。</p>
     * @param request 项目、可选制题渠道、目标地域、可选语言与母题
     * @return 与母题一一对应的改写候选，带本次地域与语言
     * @throws IllegalArgumentException 项目无效、未提供母题或其他请求字段不合法，此时不调用模型
     */
    public List<DiagnosisQuestion> localizeQuestions(LocalizeDiagnosisQuestionsRequest request) {
        // 1. 项目资料是改写背景，缺失时不调用模型
        var project = request.projectId() == null ? null : projectService.getById(request.projectId());
        if (project == null) throw new IllegalArgumentException("请选择有效项目");
        if (request.questions() == null) throw new IllegalArgumentException("请提供要改写的母题");
        // 2. 母题显式转换为实体后交给制题服务改写，返回候选不落库
        List<GeoDiagnosisPlanQuestionEntity> questions = request.questions().stream().map(this::toEntity).toList();
        return questionGenerateService.localizeQuestions(project, request.channelCode(), request.region(), request.language(), questions)
                .stream().map(this::toQuestion).toList();
    }

    /** 构造详情，渠道关联只返回 ID，凭据留在模型配置表。 */
    private DiagnosisPlanResponse toResponse(GeoDiagnosisPlanEntity plan) {
        var project = projectService.getById(plan.getProjectId());
        return new DiagnosisPlanResponse(plan.getId(), plan.getName(), plan.getProjectId(),
                project == null ? null : project.getName(), plan.getRepeatCount(),
                planService.listChannels(plan.getId()).stream().map(GeoDiagnosisPlanChannelEntity::getChannelId).toList(),
                planService.listQuestions(plan.getId()).stream().map(this::toQuestion).toList(), plan.getUpdatedAt());
    }

    /**
     * 接口问题转换为实体：输入枚举仅转换为受控数据库值，空项与地域格式留给规则校验。
     * @param question 页面提交的问题或母题
     * @return 未保存的问题实体
     * @throws IllegalArgumentException 问题项为空
     */
    private GeoDiagnosisPlanQuestionEntity toEntity(DiagnosisQuestion question) {
        if (question == null) throw new IllegalArgumentException("问题不能为空");
        var entity = new GeoDiagnosisPlanQuestionEntity();
        entity.setQuestionText(question.text());
        entity.setSource(question.source() == null ? null : question.source().name());
        entity.setIntent(question.intent() == null ? null : question.intent().name());
        entity.setBrandScope(question.brandScope() == null ? null : question.brandScope().name());
        entity.setLanguage(question.language());
        entity.setRegion(question.region());
        return entity;
    }

    /**
     * 已校验的数据库值显式映射到接口枚举，地域原样返回（空值表示不分地域）。
     * @param entity 已保存或已校验的问题
     * @return 接口问题
     */
    private DiagnosisQuestion toQuestion(GeoDiagnosisPlanQuestionEntity entity) {
        return new DiagnosisQuestion(entity.getQuestionText(), DiagnosisQuestion.Source.valueOf(entity.getSource()),
                DiagnosisQuestion.Intent.valueOf(entity.getIntent()), DiagnosisQuestion.BrandScope.valueOf(entity.getBrandScope()),
                entity.getLanguage(), entity.getRegion());
    }

    /** 「诊断方案」页面列表的一行；不携带问题正文或任何模型密钥。 */
    public record PlanPageItem(Long id, String name, Long projectId, String projectName,
                               int repeatCount, java.time.LocalDateTime updatedAt) {}
}
