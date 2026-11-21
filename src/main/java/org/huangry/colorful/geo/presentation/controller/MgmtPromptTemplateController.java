package org.huangry.colorful.geo.presentation.controller;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.application.prompt.PromptTemplateBusiness;
import org.huangry.colorful.geo.application.prompt.model.PromptTemplateResponse;
import org.huangry.colorful.geo.application.prompt.model.PromptTemplateVersionResponse;
import org.huangry.colorful.geo.application.prompt.model.SavePromptTemplateRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 提示词管理接口：列出可维护的提示词，查看当前版本与历史版本，保存新版本。
 * <p>保存总是新增一版、不覆盖，旧版本可载入后再保存实现回滚；校验失败返回 400，提示词或版本不存在返回 404。</p>
 */
@RestController
@RequestMapping("/prompt-templates")
public class MgmtPromptTemplateController {
	@Resource
	private PromptTemplateBusiness promptTemplateBusiness;

	/**
	 * 提示词列表：描述、可用变量与当前版本号，不含内容。
	 *
	 * @return 全部可维护的提示词
	 */
	@GetMapping
	public List<PromptTemplateResponse> listPromptTemplates() {
		return promptTemplateBusiness.listPromptTemplates();
	}

	/**
	 * 查看一个提示词的当前版本与内容；还没配置时只有描述与可用变量。
	 *
	 * @param promptKey 提示词标识
	 * @return 当前版本
	 */
	@GetMapping("/{promptKey}")
	public PromptTemplateResponse getPromptTemplate(@PathVariable("promptKey") String promptKey) {
		return promptTemplateBusiness.getPromptTemplate(promptKey);
	}

	/**
	 * 列出一个提示词的历史版本，新的在前。
	 *
	 * @param promptKey 提示词标识
	 * @return 各版本的版本号与保存时间
	 */
	@GetMapping("/{promptKey}/versions")
	public List<PromptTemplateVersionResponse> listPromptTemplateVersions(@PathVariable("promptKey") String promptKey) {
		return promptTemplateBusiness.listPromptTemplateVersions(promptKey);
	}

	/**
	 * 查看一个提示词某一版的内容，用于载入后回滚。
	 *
	 * @param promptKey 提示词标识
	 * @param version   版本号
	 * @return 该版本
	 */
	@GetMapping("/{promptKey}/versions/{version}")
	public PromptTemplateResponse getPromptTemplateVersion(@PathVariable("promptKey") String promptKey, @PathVariable("version") int version) {
		return promptTemplateBusiness.getPromptTemplateVersion(promptKey, version);
	}

	/**
	 * 把编辑后的内容保存为新版本。
	 *
	 * @param promptKey 提示词标识
	 * @param request   编辑后的模板
	 * @return 新版本
	 */
	@PostMapping("/{promptKey}/versions")
	@ResponseStatus(HttpStatus.CREATED)
	public PromptTemplateResponse createPromptTemplateVersion(@PathVariable("promptKey") String promptKey,
	                                                          @RequestBody(required = false) SavePromptTemplateRequest request) {
		return promptTemplateBusiness.createPromptTemplateVersion(promptKey, request);
	}

	/**
	 * 数据库异常不透传 SQL 与内部数据。
	 *
	 * @param exception 读取或保存失败
	 * @return 安全错误提示
	 */
	@ExceptionHandler(DataAccessException.class)
	public ProblemDetail storageFailed(DataAccessException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "提示词数据暂时无法处理，请稍后重试");
	}
}
