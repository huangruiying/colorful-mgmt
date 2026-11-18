package org.huangry.colorful.geo.presentation.controller;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.application.diagnosis.DiagnosisReportBusiness;
import org.huangry.colorful.geo.application.diagnosis.DiagnosisReportSupplementBusiness;
import org.huangry.colorful.geo.application.diagnosis.model.CreateDiagnosisReportRequest;
import org.huangry.colorful.geo.application.diagnosis.model.DiagnosisReportResponse;
import org.huangry.colorful.geo.application.diagnosis.model.DiagnosisTaskResponse;
import org.huangry.colorful.geo.infrastructure.common.model.PageResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;


/**
 * 诊断报告的管理接口：生成、查询与补全诊断报告。
 * <p>生成立即返回，判定与汇总由后台执行器完成，关闭页面不影响生成。</p>
 */
@RestController
@RequestMapping("/diagnosis-reports")
public class MgmtDiagnosisReportController {
	@Resource
	private DiagnosisReportBusiness diagnosisReportBusiness;
	@Resource
	private DiagnosisReportSupplementBusiness diagnosisReportSupplementBusiness;

	/**
	 * 生成诊断报告，可指定报告语言。
	 * <p>请求体可省略：页面刷新前的旧脚本不带请求体，按中文生成。</p>
	 *
	 * @param diagnosisTaskId 诊断任务 ID（geo_diagnosis_task.id）
	 * @param request 可选，报告语言
	 * @return 新建的报告
	 */
	@PostMapping("/diagnosis-tasks/{diagnosisTaskId}")
	@ResponseStatus(HttpStatus.CREATED)
	public DiagnosisReportResponse createReport(@PathVariable("diagnosisTaskId") long diagnosisTaskId,
	                                                @RequestBody(required = false) CreateDiagnosisReportRequest request) {
		return diagnosisReportBusiness.createReport(diagnosisTaskId, request == null ? null : request.reportLanguage());
	}

	/**
	 * 诊断报告列表分页查询，可按诊断任务和状态筛选。
	 *
	 * @param diagnosisTaskId 可选，按诊断任务 ID（geo_diagnosis_task.id）筛选
	 * @param status          报告状态
	 * @param page            页码
	 * @param size            每页数量
	 * @return 当前页报告及总数
	 */
	@GetMapping
	public PageResponse<DiagnosisReportResponse> listReports(@RequestParam(value = "diagnosisTaskId", required = false) Long diagnosisTaskId, @RequestParam(value = "status", required = false) String status, @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "20") int size) {
		return diagnosisReportBusiness.listReports(diagnosisTaskId, status, page, size);
	}

	/**
	 * 查看报告详情与冻结汇总。
	 *
	 * @param id 报告 ID
	 * @return 报告详情
	 */
	@GetMapping("/{id}")
	public DiagnosisReportResponse getReport(@PathVariable("id") long id) {
		return diagnosisReportBusiness.getReport(id);
	}

	/**
	 * 补全报告：报告内容缺失时，用已保存的判定结果一次补算 ③–⑥，不重新判定回答；会调用两次模型，请求持续几十秒。
	 *
	 * @param id 报告 ID
	 * @return 补全后的报告详情
	 */
	@PostMapping("/{id}/supplement")
	public DiagnosisReportResponse supplementReport(@PathVariable("id") long id) {
		return diagnosisReportSupplementBusiness.supplementReport(id);
	}

	/**
	 * 数据库或快照异常不透传 SQL 与内部数据。
	 *
	 * @param exception 内部读取或持久化失败
	 * @return 安全错误提示
	 */
	@ExceptionHandler({DataAccessException.class, IllegalStateException.class})
	public ProblemDetail storageFailed(RuntimeException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "诊断报告数据暂时无法处理，请稍后重试");
	}
}
