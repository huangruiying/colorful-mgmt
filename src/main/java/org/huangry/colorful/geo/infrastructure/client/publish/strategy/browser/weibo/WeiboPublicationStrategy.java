package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.weibo;

import lombok.RequiredArgsConstructor;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.AbstractPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.springframework.stereotype.Component;

/**
 * <pre>
 * 微博的内容投放策略：把投放请求交给 WeiboBrowserClient 在平台上操作，并转换为统一的投放结果
 *
 * 负责：
 * - 声明本策略负责微博平台
 * - 预发布：调用 WeiboBrowserClient.createDraft 创建并回读草稿，把草稿 ID、编辑链接映射为“已建草稿”
 *
 * 核心入口：
 * - prePublish(...)：投放路由 PublicationPlatformStrategyRouter 按平台选中本策略后调用，父类校验后进入 execPrePublish
 * - publish(...)：公开发布尚未接入，调用即拒绝
 *
 * 约束：
 * - 不自动重试；客户端报告结果未确认时原样抛出，由上层提示人工核对
 *
 * 不负责：
 * - 浏览器页面操作与登录态读取（WeiboBrowserClient）
 * - 按平台去重与串行执行（ContentPublicationService）
 * - 投放记录的领取、结果回写与人工核对提示（ContentPrePublishWorker）
 * </pre>
 *
 * @author huangry
 */
@Component
@RequiredArgsConstructor
public class WeiboPublicationStrategy extends AbstractPublicationStrategy {

	private final WeiboBrowserClient browserClient;

	/**
	 * 返回微博平台；当前仅支持创建草稿。
	 *
	 * @return 微博平台
	 */
	@Override
	public PublicationPlatformType platformType() {
		return PublicationPlatformType.WEIBO;
	}

	/**
	 * 使用数据库浏览器会话创建并回读微博头条文章草稿。
	 *
	 * @param request 目标平台、标题和正文
	 * @return 已确认的草稿结果
	 */
	@Override
	protected PublicationResult execPrePublish(PlatformPublicationRequest request) {
		WeiboBrowserClient.DraftCreated draft = browserClient.createDraft(request.getTitle(), request.getContent());
		return PublicationResult.builder()
				.status(PublicationTaskStatus.DRAFT_CREATED)
				.remoteContentId(draft.remoteContentId())
				.draftUrl(draft.draftUrl())
				.message("微博草稿已创建，尚未公开发布")
				.build();
	}

	/**
	 * 微博公开发布尚未接入，禁止把草稿误标成已发布。
	 *
	 * @param request 已有平台草稿
	 * @return 不返回结果，始终抛出未接入异常
	 */
	@Override
	protected PublicationResult execPublish(PrePublicationRequest request) {
		throw new PublicationClientException("微博草稿公开发布暂未接入");
	}
}
