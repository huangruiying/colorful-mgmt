package org.huangry.colorful.geo.infrastructure.client.playwright.takeover.verification;

import com.microsoft.playwright.Page;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.BrowserTakeoverOutcomeEnum;
import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.BrowserTakeoverRegistry;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * <pre>
 * 浏览器人工接管的“人工验证”场景：页面停在平台验证（短信安全验证、滑块等）上时，交给用户在管理页亲自完成，验证消失后继续原流程
 *
 * 负责：
 * - 用 HumanVerificationRule 识别页面是否停在验证上；没有验证直接返回，不打扰用户
 * - 有验证时向 BrowserTakeoverRegistry 请求接管：管理页弹出通用接管窗口，显示服务器页面的实时画面，
 *   用户的鼠标、滚轮、键盘操作按原时间间隔转发回页面
 * - 以“验证消失”作为完成条件，等到完成、用户放弃或超时后返回结果
 *
 * 核心入口：
 * - ensureHumanVerified(page, platform, businessName)：持有 Playwright 页面的平台客户端（投放、登录，以后的数据抓取等）
 *   在打开页面、提交保存等可能遇到验证的步骤之后调用
 *
 * 约束：
 * - 必须在创建页面的浏览器线程里调用；请求接管后该线程等待用户，最长等待由 colorful.geo.browser-takeover.timeout-seconds 决定（默认 600 秒）
 * - 只识别验证是否存在，不识别验证码内容、不操作验证组件，验证由用户本人在接管窗口完成
 *
 * 不负责：
 * - 识别规则（HumanVerificationRule，新增一种验证只改那里）
 * - 接管窗口、实时画面与操作转发（BrowserTakeoverRegistry 及 takeover 包）
 * - 失败提示、验证通过后写回登录态等后续动作（调用方）
 *
 * 说明：调用方的接入步骤
 * 1. 注入本类；在可能遇到验证的步骤之后调用 ensureHumanVerified，传入当前页面、平台和业务名
 *    （业务名显示在接管窗口标题，如“预发布”“数据抓取”）
 * 2. 按返回值处理：NOT_REQUIRED（没有验证）和 COMPLETED（用户已完成）继续原流程，可用 canContinue() 判断；
 *    ABANDONED（用户放弃）和 TIMED_OUT（超时）按失败处理并提示用户
 * 3. 验证结果记在浏览器 Cookie 中的平台，完成后可导出会话写回登录态，下次不再要求验证（参考 SohuBrowserClient）
 * 4. 平台验证通过后不一定回到原页面：需要时重新打开目标地址，并再次调用本方法确认；仍停在验证上时按失败处理，不循环等待
 * </pre>
 */
@Component
public class HumanVerificationTakeover {

	@Resource
	private BrowserTakeoverRegistry takeoverRegistry;

	/**
	 * 确认页面不在验证上；在验证上时请求人工接管，等用户完成验证。
	 *
	 * @param page 自动化流程正在使用的页面，必须是调用线程创建的
	 * @param platform 页面所属平台，用于选取专属识别规则和窗口标题
	 * @param businessName 正在执行的业务名称，如“预发布”，用于窗口标题
	 * @return 无需验证、已完成、已放弃或超时；放弃和超时由调用方按失败处理
	 */
	public BrowserTakeoverOutcomeEnum ensureHumanVerified(Page page, PublicationPlatformType platform, String businessName) {
		Optional<HumanVerificationRule> rule = HumanVerificationRule.detect(page, platform);
		if (rule.isEmpty()) {
			return BrowserTakeoverOutcomeEnum.NOT_REQUIRED;
		}
		// 验证消失即算完成：用户提交后平台会跳离验证页或关闭验证组件
		return takeoverRegistry.requestTakeover(page, platform.getDisplayName() + " · " + businessName,
				rule.get().getReason(), current -> HumanVerificationRule.detect(current, platform).isEmpty());
	}
}
