package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.eastmoney;

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
 * 东方财富的内容投放策略：把投放请求交给 EastmoneyBrowserClient 在平台上操作，并转换为统一的投放结果
 *
 * 负责：
 * - 声明本策略负责东方财富平台
 * - 预发布：调用 EastmoneyBrowserClient.createDraft 创建并回读草稿，把草稿 ID、编辑链接映射为“已建草稿”
 * - 草稿 ID 取不到时在提示里引导到草稿箱核对，不伪造标识
 *
 * 核心入口：
 * - prePublish(...)：投放路由 PublicationPlatformStrategyRouter 按平台选中本策略后调用，父类校验后进入 execPrePublish
 * - publish(...)：公开发布尚未接入，调用即拒绝
 *
 * 约束：
 * - 不自动重试；客户端报告结果未确认时原样抛出，由上层提示人工核对
 *
 * 不负责：
 * - 浏览器页面操作与登录态读取（EastmoneyBrowserClient）
 * - 按平台去重与串行执行（ContentPublicationService）
 * - 投放记录的领取、结果回写与人工核对提示（ContentPrePublishWorker）
 * </pre>
 *
 * @author huangry
 */
@Component
@RequiredArgsConstructor
public class EastmoneyPublicationStrategy extends AbstractPublicationStrategy {

	private final EastmoneyBrowserClient browserClient;

	/**
	 * 返回东方财富平台。
	 *
	 * @return 东方财富平台
	 */
	@Override
	public PublicationPlatformType platformType() {
		return PublicationPlatformType.EASTMONEY;
	}

	/**
	 * 使用数据库浏览器会话创建东方财富草稿（当前受客户端选择器守卫保护）。
	 *
	 * @param request 目标平台、标题和正文
	 * @return 草稿结果（成功或明确异常）
	 */
	@Override
	protected PublicationResult execPrePublish(PlatformPublicationRequest request) {
		EastmoneyBrowserClient.DraftCreated draft = browserClient.createDraft(
				request.getTitle(), request.getContent());
		String idTip = draft.remoteContentId() != null
				? "平台草稿ID=" + draft.remoteContentId()
				: "未取到平台草稿ID，请到东方财富草稿箱核对";
		return PublicationResult.builder()
				.status(PublicationTaskStatus.DRAFT_CREATED)
				.remoteContentId(draft.remoteContentId())
				.draftUrl(draft.draftUrl())
				.message("东方财富草稿已创建，尚未公开发布；" + idTip)
				.build();
	}

	/**
	 * 东方财富草稿公开发布暂未接入，禁止把草稿误标成已发布。
	 *
	 * <p>东方财富草稿建联调已在真实 macOS 浏览器跑通（见 EastmoneyBrowserClient），但编辑器"发布"按钮
	 * 触发方式尚未经真实联调确认，发布链路待确认后再实现，避免伪造发布成功。</p>
	 *
	 * @param request 已有平台草稿
	 * @return 不返回结果，始终抛出未接入异常
	 */
	@Override
	protected PublicationResult execPublish(PrePublicationRequest request) {
		throw new PublicationClientException("东方财富草稿公开发布暂未接入，当前仅支持创建草稿");
	}
}
