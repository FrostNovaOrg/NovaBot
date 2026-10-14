package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录页上的「登录故障排查」链接
 * <p>
 * 抓的用户故障：登不进控制台的人，恰恰是那张页上唯一没有帮助入口的人——
 * 手册随包那份要登录后才看得到，所以这一条只能链站上那份的排障章。
 * 链接是站外地址，而登录页的地址栏里可能带着一次性令牌（{@code ?token=…}），
 * 经 Referer 送出去就等于把门禁卡递了出去：新开一页、不带本窗口、不给来源，三样都要在。
 */
@DisplayName("登录页的排障链接")
class LoginManualHintTest {
    /**
     * 站上手册的排障章与那一节：配置界面打不开
     */
    private static final String EXPECTED_HREF =
            "https://frostnovaorg.github.io/NovaBot/14-troubleshooting.html"
                    + "#%E9%85%8D%E7%BD%AE%E7%95%8C%E9%9D%A2%E6%89%93%E4%B8%8D%E5%BC%80";

    @Test
    @DisplayName("登不进的人也有处可查：链到站上手册的排障章，令牌不经 Referer 带出去")
    void loginPageLinksTroubleshootingChapter() throws IOException {
        String html = ConfigUiLoginPage.html("");
        int at = html.indexOf("登录故障排查");
        assertTrue(at > 0, "登录页上要有那一行小字「登录故障排查」");

        int open = html.lastIndexOf("<a ", at);
        int close = html.indexOf("</a>", at);
        assertTrue(open > 0 && close > at, "那行小字得是个链接");
        String anchor = html.substring(open, close);

        assertTrue(anchor.contains("href=\"" + EXPECTED_HREF + "\""),
                "链接要去站上手册的排障章那一节，得到：" + anchor);
        assertTrue(anchor.contains("target=\"_blank\""), "要新开一页：" + anchor);
        assertTrue(anchor.contains("noopener"), "新页不许拿本窗口：" + anchor);
        assertTrue(anchor.contains("noreferrer"), "不许把本页地址带出去：" + anchor);
        assertTrue(anchor.contains("referrerpolicy=\"no-referrer\""),
                "来源一项要显式不给，令牌在地址栏里：" + anchor);
    }
}
