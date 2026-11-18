package org.huangry.colorful.geo.presentation.controller;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.application.diagnosis.DiagnosisReportBusiness;
import org.huangry.colorful.geo.application.diagnosis.model.CreateDiagnosisReportRequest;
import org.huangry.colorful.geo.application.diagnosis.model.DiagnosisReportResponse;
import org.huangry.colorful.geo.infrastructure.common.model.PageResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;


/**
 * 诊断报告的管理接口：生成、查询诊断报告，以及暂停后的重试、忽略失败继续和已完成报告的重跑。
 * <p>生成和重跑都立即返回，判定与生成内容由后台执行器完成，关闭页面不影响生成。</p>
 */
@RestController
@RequestMapping("/diagnosis-reports")
public class MgmtDiagnosisReportController {
	@Resource
	private DiagnosisReportBusiness diagnosisReportBusiness;

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
	 * 查看报告详情与报告内容。
	 *
	 * @param id 报告 ID
	 * @return 报告详情
	 */
	@GetMapping("/{id}")
	public DiagnosisReportResponse getReport(@PathVariable("id") long id) {
		return diagnosisReportBusiness.getReport(id);
	}

	/**
	 * 重判报告中没有判定成功的回答：已暂停或已完成的报告可用，失败的回答改回待判定，成功的保留，
	 * 后台从判定段重新执行并接着生成报告内容。
	 *
	 * @param id 诊断报告 ID
	 * @return 重新排队后的报告详情
	 */
	@PostMapping("/{id}/rejudge")
	public DiagnosisReportResponse rejudgeReport(@PathVariable("id") long id) {
		return diagnosisReportBusiness.rejudgeReport(id);
	}

	/**
	 * 重新生成报告内容：停在第 2 段的已暂停报告或已完成的报告可用，后台整段重做第 2 段，判定结果不动，原有内容届时被替换。
	 *
	 * @param id 诊断报告 ID
	 * @return 重新排队后的报告详情
	 */
	@PostMapping("/{id}/regenerate-content")
	public DiagnosisReportResponse regenerateReportContent(@PathVariable("id") long id) {
		return diagnosisReportBusiness.regenerateReportContent(id);
	}

	/**
	 * 忽略失败继续：只对已暂停的报告可用。停在判定段时失败的回答不计入指标、进入第 2 段；停在第 2 段时按已生成的内容直接完成。
	 *
	 * @param id 诊断报告 ID
	 * @return 处理后的报告详情
	 */
	@PostMapping("/{id}/skip-failures")
	public DiagnosisReportResponse skipReportFailures(@PathVariable("id") long id) {
		return diagnosisReportBusiness.skipReportFailures(id);
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
