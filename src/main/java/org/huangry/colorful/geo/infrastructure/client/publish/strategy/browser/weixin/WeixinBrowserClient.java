package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.weixin;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.AriaRole;
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

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * <pre>
 * 微信公众号的浏览器建稿客户端：用浏览器登录管理里保存的公众号登录态，在公众号后台保存文章草稿
 *
 * 负责：
 * - 从 platform_browser_login 读取已登录的公众号会话，恢复到独立浏览器
 * - 从后台首页取得本次会话 token，打开新建文章编辑器写入标题和正文，保存为草稿
 * - 从保存后的编辑地址取得草稿 ID，重新加载同一草稿回读标题和正文
 *
 * 核心入口：
 * - createDraft(...)：WeixinPublicationStrategy 预发布（execPrePublish）时调用，只返回草稿 ID
 *
 * 约束：
 * - 同一服务实例同时只建一篇公众号草稿，浏览器忙时直接拒绝
 * - 开始写入后出现异常按结果未确认处理，不自动重试
 * - 编辑地址含后台会话 token：不返回给上层、不写日志，浏览器异常也不带原异常抛出
 *
 * 不负责：
 * - 公开发布（WeixinPublicationStrategy 未接入）
 * - 把草稿映射为统一的投放结果（WeixinPublicationStrategy）
 * - 管理公众号登录（浏览器登录管理）
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeixinBrowserClient {

    private static final String HOME_URL = "https://mp.weixin.qq.com/";
    private static final String EDITOR_PATH = "https://mp.weixin.qq.com/cgi-bin/appmsg";
    private static final Pattern TOKEN = Pattern.compile("(?:^|&)token=(\\d+)(?:&|$)");
    private static final Pattern DRAFT_ID = Pattern.compile("(?:[?&])appmsgid=(\\d+)(?:&|$)");

    private final PlatformBrowserLoginDao loginDao;
    private final PlaywrightBrowserComponent browserComponent;
    private final PublicationBrowserProperties properties;
    private final ReentrantLock browserLock = new ReentrantLock();

    /**
     * 在公众号后台保存文章，并重新打开相同草稿回读标题和正文。
     *
     * @param title 已确认的文章标题
     * @param content 已确认的文章正文
     * @return 公众号草稿 ID；编辑链接含 token，因此不返回链接
     * @throws PublicationClientException 未登录、浏览器忙、后台会话不可用或编辑器打不开
     * @throws PublicationOutcomeUnknownException 已开始写入但保存结果无法确认
     */
    public String createDraft(String title, String content) {
        // 1. 只使用已确认登录的会话，未登录时不启动浏览器
        String storageState = requireSavedStorageState();
        // 2. 同一平台同时只操作一个编辑器，浏览器忙时直接拒绝
        if (!browserLock.tryLock()) {
            throw new PublicationClientException("微信公众号浏览器正在创建其他草稿，请稍后处理");
        }
        try {
            // 3. 在浏览器中写入标题和正文并回读确认
            return createDraftInBrowser(storageState, title, content);
        } finally {
            browserLock.unlock();
        }
    }


    /**
     * 只恢复数据库中确认已登录的公众号会话，不复用其他平台的微信账号。
     *
     * @return Playwright 浏览器状态
     */
    private String requireSavedStorageState() {
        PlatformBrowserLoginEntity saved = loginDao.findByPlatform("WECHAT_OFFICIAL_ACCOUNT");
        // 登录页可访问不代表会话有效；必须有已确认登录的状态与可恢复 JSON。
        if (saved == null || !PlatformBrowserLoginStatus.LOGGED_IN.name().equals(saved.getLoginStatus())
                || saved.getStorageState() == null || !saved.getStorageState().stripLeading().startsWith("{")) {
            throw new PublicationClientException("微信公众号浏览器未登录或登录态不可用，请先在浏览器登录管理中登录");
        }
        return saved.getStorageState();
    }

    /**
     * 打开后台编辑器、保存草稿并回读；写入开始后异常不自动重试。
     *
     * @param storageState 数据库登录会话
     * @param title 草稿标题
     * @param content 草稿正文
     * @return 已回读确认的草稿 ID
     */
    private String createDraftInBrowser(String storageState, String title, String content) {
        boolean draftMayExist = false;
        try (PlaywrightBrowserComponent.BrowserSession session = browserComponent.openBrowser(
                new Browser.NewContextOptions().setStorageState(storageState))) {
            Page page = session.newPage();
            page.setDefaultTimeout(properties.getTimeoutMillis());
            // 1. 先从后台首页取得当前会话 token；它只用于本次导航，不写入结果或日志。
            page.navigate(HOME_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            String token = readSessionToken(page.url());
            page.navigate(newDraftUrl(token), new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.locator(".ProseMirror[data-placeholder='请在这里输入标题']").waitFor();
            dismissFirstUseDialog(page);
            // 2. 在公众号编辑器写入标题和正文；此后可能触发自动保存。
            draftMayExist = true;
            page.locator(".ProseMirror[data-placeholder='请在这里输入标题']").fill(title);
            page.locator(".ProseMirror[contenteditable=true]").last().fill(content);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("保存为草稿")).click();
            // 3. 只接受平台生成的 appmsgid，重新打开同一草稿核验。
            page.waitForURL(url -> DRAFT_ID.matcher(url).find());
            String draftUrl = page.url();
            String draftId = readDraftId(draftUrl);
            verifySavedDraft(page, draftUrl, title, content);
            log.info("微信公众号草稿已创建并回读确认，remoteContentId={}", draftId);
            return draftId;
        } catch (PlaywrightException exception) {
            // Playwright 的导航异常可能包含带 token 的公众号后台地址，不把原异常带出客户端。
            log.warn("微信公众号草稿浏览器操作失败，draftMayExist={}，errorType={}",
                    draftMayExist, exception.getClass().getSimpleName());
            if (draftMayExist) {
                throw new PublicationOutcomeUnknownException("微信公众号草稿结果未确认，请核对草稿箱，勿直接重试");
            }
            throw new PublicationClientException("微信公众号编辑器无法打开，请检查浏览器登录态和网络");
        }
    }

    /**
     * 从公众号后台跳转 URL 读取当前会话 token，不接受外部传入的任意编辑地址。
     *
     * @param homeUrl 登录后的公众号首页 URL
     * @return 当前会话 token
     */
    private String readSessionToken(String homeUrl) {
        String query = URI.create(homeUrl).getRawQuery();
        Matcher match = TOKEN.matcher(query == null ? "" : query);
        if (!match.find()) {
            throw new PublicationClientException("微信公众号后台会话不可用，请重新登录");
        }
        return match.group(1);
    }

    /**
     * 只构造公众号固定的新建文章地址，token 仅在浏览器内使用。
     *
     * @param token 当前后台会话 token
     * @return 公众号新建文章地址
     */
    private String newDraftUrl(String token) {
        return EDITOR_PATH + "?t=media/appmsg_edit_v2&action=edit&isNew=1&type=77&createType=0&token="
                + token + "&lang=zh_CN";
    }

    /**
     * 关闭首次使用提示；普通账号没有提示时不改变页面。
     *
     * @param page 当前编辑页
     */
    private void dismissFirstUseDialog(Page page) {
        var acknowledge = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("我知道了"));
        if (acknowledge.isVisible()) {
            acknowledge.click();
        }
    }

    /**
     * 从公众号保存后的编辑地址提取数字草稿 ID，不把 token 返回给上层。
     *
     * @param draftUrl 当前编辑地址
     * @return 草稿 ID
     */
    private String readDraftId(String draftUrl) {
        Matcher match = DRAFT_ID.matcher(draftUrl);
        // 已点保存但地址里没有平台草稿 ID 时，草稿可能已建，不能报确定失败让上层重试。
        if (!match.find()) {
            throw new PublicationOutcomeUnknownException("微信公众号草稿标识未确认，请核对草稿箱，勿直接重试");
        }
        return match.group(1);
    }

    /**
     * 重新加载相同草稿，核验标题及正文；只忽略富文本增加的展示空行。
     *
     * @param page 当前浏览器页面
     * @param draftUrl 含当前会话 token 的内部编辑地址
     * @param title 预期标题
     * @param content 预期正文
     */
    private void verifySavedDraft(Page page, String draftUrl, String title, String content) {
        page.navigate(draftUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        // 1. 强制读取平台保存的草稿，避免同地址导航保留本地编辑器状态。
        page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.locator(".ProseMirror[data-placeholder='请在这里输入标题']").waitFor();
        page.waitForFunction("() => document.querySelector('.ProseMirror[data-placeholder=\"请在这里输入标题\"]')?.innerText?.trim().length > 0");
        // 2. 标题必须原样匹配；正文展示空行不代表丢失，非空文本及行边界必须一致。
        String savedTitle = page.locator(".ProseMirror[data-placeholder='请在这里输入标题']").innerText();
        String savedContent = page.locator(".ProseMirror[contenteditable=true]").last().innerText();
        if (!title.equals(savedTitle) || !normalizeParagraphs(content).equals(normalizeParagraphs(savedContent))) {
            throw new PublicationOutcomeUnknownException("微信公众号草稿内容未完整回读，请核对草稿箱，勿直接重试");
        }
    }

    /**
     * 忽略富文本回读的展示空行，保留非空行文字、顺序、行边界及行内空格。
     *
     * @param content 输入正文或草稿回读正文
     * @return 可比较的正文文本
     */
    static String normalizeParagraphs(String content) {
        return content.replace("\r\n", "\n").lines().filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"));
    }
}
