package org.huangry.colorful.geo.infrastructure.client.playwright.takeover.verification;

import com.microsoft.playwright.Page;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;

import java.util.Arrays;
import java.util.Optional;

/**
 * <pre>
 * 人工验证的识别规则表：列出平台页面上需要用户本人完成的验证，以及每种验证怎么认出来
 *
 * 负责：
 * - 每条规则描述一种验证：适用平台（为空表示所有平台）、展示给用户的原因、怎样判断页面停在这个验证上
 * - 识别依据可以是页面地址（如搜狐号跳到 clientAuth），也可以是页面元素（如腾讯滑块的 iframe）
 * - 同一条规则同时决定“要不要请求接管”和“接管何时算完成”：页面停在验证上就请求，验证消失即完成
 *
 * 核心入口：
 * - detect(...)：HumanVerificationTakeover 调用，判断页面当前是否停在某个验证上
 * - matches(...)：单条规则的判断；业务不直接调用，统一经 HumanVerificationTakeover.ensureHumanVerified 接入
 *
 * 约束：
 * - 只判断验证是否存在，不识别验证码内容、不操作验证组件
 * - 只收录实际遇到并核对过页面特征的验证
 * - 平台专属规则声明在前，与通用规则同时命中时，优先用专属规则的原因说明
 *
 * 不负责：
 * - 接管窗口、实时画面、操作转发、超时与放弃（BrowserTakeoverRegistry，所有验证通用）
 * - 何时检查、检查后怎么处理（调用方，接入步骤见 HumanVerificationTakeover）
 *
 * 说明：这是人工验证场景里唯一需要按平台定制的部分。新增一种验证的步骤：
 * 1. 在真实会话里确认验证出现时的页面特征（地址或元素），以及验证通过后的变化（跳离或组件消失）
 * 2. 在这里新增一个枚举值：写明适用平台、原因，在 matches 里按第 1 步的特征判断；不需要改接管窗口或前端
 * 3. 已调用 HumanVerificationTakeover.ensureHumanVerified 的位置自动生效；新的平台或步骤按其类注释接入
 * </pre>
 */
@Getter
@RequiredArgsConstructor
public enum HumanVerificationRule {

	/** 搜狐号对新设备的短信安全验证：打开后台页面会被跳到 clientAuth，页内有腾讯滑块和短信验证码。 */
	SOHU_CLIENT_AUTH(PublicationPlatformType.SOHU, "搜狐号要求对本浏览器做短信安全验证") {
		@Override
		public boolean matches(Page page) {
			return page.url().contains("/mpfe/v4/clientAuth");
		}
	},

	/** 腾讯滑块验证码：以 turing.captcha 域名的 iframe 弹出，可见即视为待验证。 */
	TENCENT_CAPTCHA(null, "平台弹出了滑块验证码") {
		@Override
		public boolean matches(Page page) {
			return page.locator("iframe[src*='turing.captcha']").first().isVisible();
		}
	};

	/** 适用平台；为空表示通用规则。 */
	private final PublicationPlatformType platform;
	/** 展示给用户的验证原因。 */
	private final String reason;

	/**
	 * 判断页面当前是否停在本规则描述的验证上。
	 *
	 * @param page 自动化流程正在使用的页面，只能在创建它的线程调用
	 * @return 命中本规则时为 true
	 */
	public abstract boolean matches(Page page);

	/**
	 * 按通用规则和当前平台的专属规则识别页面上的验证。
	 *
	 * @param page 自动化流程正在使用的页面，只能在创建它的线程调用
	 * @param platform 页面所属平台
	 * @return 第一条命中的规则；没有验证时为空
	 */
	public static Optional<HumanVerificationRule> detect(Page page, PublicationPlatformType platform) {
		return Arrays.stream(values())
				.filter(rule -> rule.platform == null || rule.platform == platform)
				.filter(rule -> rule.matches(page))
				.findFirst();
	}
}
