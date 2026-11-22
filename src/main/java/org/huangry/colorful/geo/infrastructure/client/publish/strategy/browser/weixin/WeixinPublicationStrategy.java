package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.weixin;

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
 * 微信公众号的内容投放策略：把投放请求交给 WeixinBrowserClient 在平台上操作，并转换为统一的投放结果
 *
 * 负责：
 * - 声明本策略负责微信公众号平台
 * - 预发布：调用 WeixinBrowserClient.createDraft 创建并回读草稿，把草稿 ID 映射为“已建草稿”；编辑链接含后台会话 token，结果不带链接
 *
 * 核心入口：
 * - prePublish(...)：投放路由 PublicationPlatformStrategyRouter 按平台选中本策略后调用，父类校验后进入 execPrePublish
 * - publish(...)：公开发布尚未接入，调用即拒绝
 *
 * 约束：
 * - 不自动重试；客户端报告结果未确认时原样抛出，由上层提示人工核对
 *
 * 不负责：
 * - 浏览器页面操作与登录态读取（WeixinBrowserClient）
 * - 按平台去重与串行执行（ContentPublicationService）
 * - 投放记录的领取、结果回写与人工核对提示（ContentPrePublishWorker）
 * </pre>
 *
 * @author huangry
 */
@Component
@RequiredArgsConstructor
public class WeixinPublicationStrategy extends AbstractPublicationStrategy {

	private final WeixinBrowserClient browserClient;

	/**
	 * 返回微信公众号平台；当前仅支持创建草稿。
	 *
	 * @return 微信公众号平台
	 */
	@Override
	public PublicationPlatformType platformType() {
		return PublicationPlatformType.WECHAT_OFFICIAL_ACCOUNT;
	}

	/**
	 * 使用数据库中的公众号会话创建并回读文章草稿。
	 *
	 * @param request 目标平台、标题和正文
	 * @return 已确认的草稿结果
	 */
	@Override
	protected PublicationResult execPrePublish(PlatformPublicationRequest request) {
		String draftId = browserClient.createDraft(request.getTitle(), request.getContent());
		return PublicationResult.builder()
				.status(PublicationTaskStatus.DRAFT_CREATED)
				.remoteContentId(draftId)
				.message("微信公众号草稿已创建，尚未公开发布")
				.build();
	}

	/**
	 * 公众号公开发布尚未完成平台终态核验，不把草稿误标为已发表。
	 *
	 * @param request 已有草稿请求
	 * @return 不返回结果，始终抛出未接入异常
	 */
	@Override
	protected PublicationResult execPublish(PrePublicationRequest request) {
		throw new PublicationClientException("微信公众号草稿公开发布暂未接入");
	}
}
