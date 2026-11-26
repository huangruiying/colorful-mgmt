package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * <pre>
 * 各平台浏览器投放共用的页面动作超时配置
 *
 * 负责：
 * - 提供单个页面动作（导航、等待元素、等待接口响应）的等待上限，配置项 colorful.geo.publish.browser.timeout-millis
 *
 * 不负责：
 * - 平台登录态（各平台浏览器客户端从 platform_browser_login 读取）
 *
 * 说明：配置前缀从知乎专用改为通用 publish.browser，供各平台的浏览器投放复用。
 * </pre>
 *
 * @author huangry
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "colorful.geo.publish.browser")
public class PublicationBrowserProperties {

    /** 单个页面动作等待上限，单位毫秒。 */
    private int timeoutMillis = 30000;
}
