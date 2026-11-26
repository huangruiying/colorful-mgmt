package org.huangry.colorful.geo.presentation.controller;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.application.diagnosis.DiagnosisPlanBusiness;
import org.huangry.colorful.geo.application.diagnosis.model.*;
import org.huangry.colorful.geo.infrastructure.common.model.PageResponse;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 诊断方案的管理接口：方案的创建、修改、查看、列表，以及候选问题的生成和按地域改写。
 * <p>候选生成、改写与保存分离，不调用被测渠道执行诊断。</p>
 */
@RestController
@RequestMapping("/diagnosis-plans")
public class MgmtDiagnosisPlanController {
	@Resource
	private DiagnosisPlanBusiness business;

	/**
	 * 创建完整方案，子集合与主表同时保存。
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public DiagnosisPlanResponse createPlan(@RequestBody SaveDiagnosisPlanRequest request) {
		return business.savePlan(null, request);
	}

	/**
	 * 修改方案的配置和问题集合，不改变任何诊断任务。
	 */
	@PutMapping("/{id}")
	public DiagnosisPlanResponse updatePlan(@PathVariable("id") long id, @RequestBody SaveDiagnosisPlanRequest request) {
		return business.savePlan(id, request);
	}

	/**
	 * 返回可编辑的完整方案。
	 */
	@GetMapping("/{id}")
	public DiagnosisPlanResponse getPlan(@PathVariable("id") long id) {
		return business.getPlan(id);
	}

	/**
	 * 诊断方案列表分页查询，可按名称筛选。
	 *
	 * @param keyword 方案名称关键词，可为空
	 * @param page 从零开始的页码
	 * @param size 每页条数
	 * @return 当前页方案及总数
	 */
	@GetMapping
	public PageResponse<DiagnosisPlanBusiness.PlanPageItem> listPlans(@RequestParam(value = "keyword", required = false) String keyword,
	                                           @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "20") int size) {
		return business.listPlans(keyword, page, size);
	}

	/**
	 * 按项目资料和本次地域生成一套母题，页面再逐个地域改写成候选；生成成功也不会自动落库。
	 * <p>输入错误（含未提供地域）由全局处理返回 400；模型调用或生成结果不合格由本类 generationFailed 返回 502。</p>
	 * @param request 项目、可选制题渠道、母题数量、母题语言与本次要改写到的地域
	 * @return 母题，地域为空
	 */
	@PostMapping("/generate-questions")
	public List<DiagnosisQuestion> generateQuestions(@RequestBody GenerateDiagnosisQuestionsRequest request) {
		return business.generateQuestions(request);
	}

	/**
	 * 把母题按一个地域改写成当地用户会问的问题，返回候选供审核，不落库。
	 * <p>输入错误由全局处理返回 400；模型调用或改写结果不合格由本类 generationFailed 返回 502。</p>
	 * @param request 项目、可选制题渠道、目标地域、可选语言与母题
	 * @return 与母题一一对应的改写候选
	 */
	@PostMapping("/localize-questions")
	public List<DiagnosisQuestion> localizeQuestions(@RequestBody LocalizeDiagnosisQuestionsRequest request) {
		return business.localizeQuestions(request);
	}

	/**
	 * 不透出远程响应、凭据或模型生成原文。
	 * @param exception 制题或模型调用异常
	 * @return 固定分类的网关失败提示，保留页面已有候选
	 */
	@ExceptionHandler(LLMClientException.class)
	public ProblemDetail generationFailed(LLMClientException exception) {
		// 先按异常链识别连接、超时与 Fastjson 格式错误，不回传远程异常消息
		String reason = "模型调用失败，请检查生成渠道配置或稍后重试";
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof javax.net.ssl.SSLException) {
				reason = "模型网关的 HTTPS 连接失败，请检查网关地址、证书和网络代理";
				break;
			}
			if (cause instanceof java.net.ConnectException) {
				reason = "无法连接模型网关，请检查网关地址及网络连接";
				break;
			}
			if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
				reason = "模型响应超时，请减少生成数量或检查服务的统一 HTTP 超时配置";
				break;
			}
			if (cause instanceof com.alibaba.fastjson.JSONException || cause instanceof IllegalArgumentException) {
				reason = "模型返回的问题格式、数量或品牌标记不符合要求，请重试或更换生成模型";
				break;
			}
		}
		// 所有模型生成失败沿用网关失败状态，页面继续保留用户已有编辑内容
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, reason + "；已有问题和候选保留");
	}
}
