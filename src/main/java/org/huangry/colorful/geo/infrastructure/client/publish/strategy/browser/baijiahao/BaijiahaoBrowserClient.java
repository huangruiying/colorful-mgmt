package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.baijiahao;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.PublicationBrowserProperties;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <pre>
 * 百家号的浏览器建稿客户端：用浏览器登录管理里保存的百家号登录态，在百家号编辑器创建文章草稿
 *
 * 负责：
 * - 从 platform_browser_login 读取已登录的百家号会话，恢复到独立浏览器
 * - 在百家号编辑器写入标题与正文、点击“存草稿”
 * - 在当前页回读标题与正文，确认输入被保留
 *
 * 核心入口：
 * - createDraft(...)：BaijiahaoPublicationStrategy 预发布（execPrePublish）时调用，返回草稿 ID（可能为空）与编辑链接
 *
 * 约束：
 * - 同一服务实例同时只建一篇百家号草稿，浏览器忙时直接拒绝
 * - 开始写入后出现异常按结果未确认处理，不自动重试，也不伪造草稿标识
 *
 * 不负责：
 * - 公开发布（BaijiahaoPublicationStrategy 未接入）
 * - 把草稿映射为统一的投放结果（BaijiahaoPublicationStrategy）
 * - 管理百家号登录（浏览器登录管理）
 *
 * 说明：百家号编辑器类名混淆，标题用 data-testid 定位，正文在 UEditor 的 iframe 内（见 findUEditorFrame）。
 * 选择器依据真实已登录会话探索获得（见 .doc/平台发文入口与选择器.md），
 * 但“存草稿后草稿 URL 是否带 id、能否按 URL 回读同一篇草稿”尚未经真实提交联调，
 * 相关步骤标注“待真实联调”；取不到草稿 ID 时返回空，是否落库以草稿箱核对为准。
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BaijiahaoBrowserClient {

	private static final String EDITOR_URL = "https://baijiahao.baidu.com/builder/rc/edit?type=news";
	private static final Pattern DRAFT_ID = Pattern.compile("[?&]article_id=(\\d+)");

	private static final String TITLE_SELECTOR =
			"div[data-testid=\"news-title-input\"] div[contenteditable=\"true\"]";
	// 百家号正文位于 UEditor 的 iframe 内（主文档只有标题一个 contenteditable），
	// 正文 body 的 class 含 "news-editor-pc"，必须按 frame 定位，见 findUEditorFrame。

	private final PlatformBrowserLoginDao loginDao;
	private final PlaywrightBrowserComponent browserComponent;
	private final PublicationBrowserProperties properties;
	private final ReentrantLock browserLock = new ReentrantLock();

	/**
	 * 使用已保存的百家号登录态创建草稿，并从编辑器回读确认。
	 *
	 * @param title 已审核标题
	 * @param content 已审核正文
	 * @return 平台草稿 ID 和编辑链接
	 * @throws PublicationClientException 未登录、浏览器忙或编辑器打不开
	 * @throws PublicationOutcomeUnknownException 已开始写入但保存结果无法确认
	 */
	public DraftCreated createDraft(String title, String content) {
		// 1. 只使用已确认登录的会话，未登录时不启动浏览器
		String storageState = requireSavedStorageState();
		// 2. 同一平台同时只操作一个编辑器，浏览器忙时直接拒绝
		if (!browserLock.tryLock()) {
			throw new PublicationClientException("百家号浏览器正在创建其他草稿，请稍后处理");
		}
		try {
			// 3. 在浏览器中写入标题和正文并回读确认
			return createDraftInBrowser(storageState, title, content);
		} finally {
			browserLock.unlock();
		}
	}

	/**
	 * 只接收数据库中状态为已登录的百家号 Playwright 会话。
	 *
	 * @return 可恢复的浏览器状态
	 */
	private String requireSavedStorageState() {
		PlatformBrowserLoginEntity saved = loginDao.findByPlatform("BAIJIAHAO");
		// 登录页可访问不代表会话有效；必须有已确认登录的状态与可恢复 JSON。
		if (saved == null || !PlatformBrowserLoginStatus.LOGGED_IN.name().equals(saved.getLoginStatus())
				|| saved.getStorageState() == null || !saved.getStorageState().stripLeading().startsWith("{")) {
			throw new PublicationClientException("百家号浏览器未登录或登录态不可用，请先在浏览器登录管理中登录");
		}
		return saved.getStorageState();
	}

	/**
	 * 在百家号编辑器写入内容并点击存草稿，最后回读核验。
	 *
	 * <p>草稿 URL 形态待真实联调确认，无法可靠按 URL 重新打开同一篇草稿，
	 * 此处做页内回读作为软校验，最终以草稿箱核对为准。</p>
	 *
	 * @param storageState 数据库浏览器状态
	 * @param title 草稿标题
	 * @param content 草稿正文
	 * @return 已确认的草稿
	 */
	private DraftCreated createDraftInBrowser(String storageState, String title, String content) {
		boolean draftMayExist = false;
		try (PlaywrightBrowserComponent.BrowserSession session = browserComponent.openBrowser(
				new Browser.NewContextOptions().setStorageState(storageState))) {
			Page page = session.newPage();
			page.setDefaultTimeout(properties.getTimeoutMillis());
			// 1. 打开已登录的百家号编辑器；SPA 需等待标题与正文节点。
			page.navigate(EDITOR_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
			// 待真实联调：以下选择器依据真实已登录会话探索获得，需实测确认。
			Locator titleLocator = page.locator(TITLE_SELECTOR);
			titleLocator.waitFor();
			// 百家号正文在 UEditor 的 iframe 内（主文档只有标题一个 contenteditable），
			// 必须以 frame 定位，否则正文选择器 count=0 会被误报为“编辑器无法打开”。
			Frame ueditorFrame = findUEditorFrame(page);
			Locator bodyLocator = ueditorFrame.locator("body");
			bodyLocator.waitFor();
			// 2. 标题与正文均为 contenteditable，需先聚焦再用键盘输入触发编辑器状态。
			draftMayExist = true;
			titleLocator.click();
			page.keyboard().insertText(title);
			bodyLocator.click();
			page.keyboard().insertText(content);
			// 3. 点击存草稿；等待保存落库（待真实联调：以“已保存”提示或 URL 变化为准）。
			page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
					new Page.GetByRoleOptions().setName("存草稿")).click();
			page.waitForTimeout(2000);
			String draftUrl = page.url();
			String draftId = readDraftId(draftUrl);
			verifySavedDraft(page, ueditorFrame, title, content);
			log.info("百家号草稿已创建，remoteContentId={}", draftId);
			return new DraftCreated(draftId, draftUrl);
		} catch (PlaywrightException exception) {
			// 写入开始后页面可能已提交草稿，结果不确定，不能自动重试。
			if (draftMayExist) {
				throw new PublicationOutcomeUnknownException("百家号草稿结果未确认，请核对草稿箱，勿直接重试", exception);
			}
			throw new PublicationClientException("百家号编辑器无法打开，请检查浏览器登录态和网络", exception);
		}
	}

	/**
	 * 从草稿链接提取数字标识；无法确认时不伪造草稿 ID。
	 *
	 * @param draftUrl 当前编辑页链接
	 * @return 草稿标识（可能为 null）
	 */
	private String readDraftId(String draftUrl) {
		Matcher match = DRAFT_ID.matcher(draftUrl);
		return match.find() ? match.group(1) : null;
	}

	/**
	 * 页内回读标题与正文，防止把编辑器的本地乐观状态误报为保存成功。
	 *
	 * <p>百家号草稿 URL 形态待真实联调确认，此处仅在当前页面校验输入是否被保留，
	 * 最终是否真正落库以草稿箱核对为准。</p>
	 *
	 * @param page 当前浏览器页面
	 * @param ueditorFrame 正文所在的 UEditor iframe
	 * @param title 预期标题
	 * @param content 预期正文
	 */
	private void verifySavedDraft(Page page, Frame ueditorFrame, String title, String content) {
		// 2026-10-08 实测：UEditor 会在正文后追加含零宽字符（U+200D 等）的空段落
		// （<p>‍</p>），innerText 回读带 "\n\n\u200D"，严格相等会把已保存成功的
		// 草稿误判为“内容未完整回读”，故比较前先归一化。
		String savedTitle = normalizeVisibleText(page.locator(TITLE_SELECTOR).innerText());
		String savedBody = normalizeVisibleText(ueditorFrame.locator("body").innerText());
		// 标题或正文未回读到原文时结果不确定，不能把草稿记录为成功。
		if (!normalizeVisibleText(title).equals(savedTitle) || !normalizeVisibleText(content).equals(savedBody)) {
			throw new PublicationOutcomeUnknownException("百家号草稿内容未完整回读，请核对草稿箱，勿直接重试");
		}
	}

	/**
	 * 归一化回读文本：去掉零宽字符并折叠所有空白，只比较可见内容。
	 *
	 * @param text 编辑器回读或预期文本
	 * @return 用于比较的归一化文本
	 */
	private String normalizeVisibleText(String text) {
		if (text == null) {
			return "";
		}
		return text.replaceAll("[\\u200B-\\u200D\\uFEFF]", "").replaceAll("\\s+", "");
	}

	/**
	 * 定位百家号 UEditor 正文所在的 iframe。
	 * 正文 body 的 class 含 "news-editor-pc"（如 "view news-editor-pc"），主文档没有该节点；
	 * 若只按主文档选择器查找会 count=0，导致 30s 超时并被误报成“编辑器无法打开”。
	 *
	 * @param page 当前浏览器页面
	 * @return UEditor 正文所在的 Frame
	 */
	private Frame findUEditorFrame(Page page) {
		for (Frame frame : page.frames()) {
			if (frame == page.mainFrame()) {
				continue;
			}
			try {
				String className = frame.locator("body").getAttribute("class");
				if (className != null && className.contains("news-editor-pc")) {
					return frame;
				}
			} catch (PlaywrightException ignored) {
				// 该 frame 暂无可访问的 body，跳过。
			}
		}
		throw new PublicationClientException("百家号正文编辑器（UEditor iframe）未加载，请检查编辑器是否打开");
	}

	/** @param remoteContentId 平台草稿 ID @param draftUrl 平台编辑链接 */
	public record DraftCreated(String remoteContentId, String draftUrl) { }
}
