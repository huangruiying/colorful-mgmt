package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.zhihu;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.AbstractPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.springframework.stereotype.Component;

/**
 * <pre>
 * 知乎的内容投放策略：把投放请求交给 ZhihuBrowserClient 在平台上操作，并转换为统一的投放结果
 *
 * 负责：
 * - 声明本策略负责知乎平台
 * - 预发布：调用 ZhihuBrowserClient.createDraft 创建并回读草稿，把草稿 ID、编辑链接映射为“已建草稿”
 * - 公开发布：调用 ZhihuBrowserClient.publishDraft 发布已有草稿，核验公开链接后映射为“已发布”
 *
 * 核心入口：
 * - prePublish(...)：投放路由 PublicationPlatformStrategyRouter 按平台选中本策略后调用，父类校验后进入 execPrePublish
 * - publish(...)：ContentPublicationService.publishDraft 经投放路由调用，父类校验后进入 execPublish；该入口目前没有页面或后台任务调用（2026-11-22）
 *
 * 约束：
 * - 不自动重试；客户端报告结果未确认时原样抛出，由上层提示人工核对
 *
 * 不负责：
 * - 浏览器页面操作与登录态读取（ZhihuBrowserClient）
 * - 按平台去重与串行执行（ContentPublicationService）
 * - 投放记录的领取、结果回写与人工核对提示（ContentPrePublishWorker）
 * </pre>
 *
 * @author huangry
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZhihuPublicationStrategy extends AbstractPublicationStrategy {

	private final ZhihuBrowserClient browserClient;

	/**
	 * 返回知乎平台；创建草稿或发布已有草稿由调用入口决定。
	 *
	 * @return 知乎平台
	 */
	@Override
	public PublicationPlatformType platformType() {
		return PublicationPlatformType.ZHIHU;
	}

	/**
	 * 使用已保存的知乎浏览器登录态导入 Markdown 并创建草稿。
	 *
	 * @param request 目标平台、标题和正文
	 * @return 含草稿标识和编辑链接的结果
	 */
	@Override
	protected PublicationResult execPrePublish(PlatformPublicationRequest request) {
		// 浏览器客户端回读确认草稿后，才映射为预发布成功。
		log.info("开始创建知乎草稿");
		ZhihuBrowserClient.DraftCreated draft = browserClient.createDraft(
				request.getTitle(), request.getContent());
		return PublicationResult.builder()
				.status(PublicationTaskStatus.DRAFT_CREATED)
				.remoteContentId(draft.remoteContentId())
				.draftUrl(draft.draftUrl())
				.message("知乎草稿已创建，尚未公开发布")
				.build();
	}

	/**
	 * 仅对已有知乎草稿执行公开发布，浏览器确认后才返回 PUBLISHED。
	 *
	 * @param request 知乎草稿标识
	 * @return 公开文章链接及发布状态
	 */
	@Override
	protected PublicationResult execPublish(PrePublicationRequest request) {
		// 1. 只按已校验的远程草稿标识点击发布，不重新提交正文。
		String publishedUrl = browserClient.publishDraft(request.getRemoteContentId());
		// 2. 只有客户端核验成功才映射为已发布状态。
		return PublicationResult.builder()
				.status(PublicationTaskStatus.PUBLISHED)
				.remoteContentId(request.getRemoteContentId())
				.publishedUrl(publishedUrl)
				.message("知乎草稿已公开发布")
				.build();
	}

}
