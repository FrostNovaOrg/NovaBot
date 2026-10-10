package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 新版提示里「看完整说明」的链接
 * <p>
 * 地址来自新版来源的回包。来源被换成别人的镜像、或发布页被篡改时，它可能是一段
 * {@code javascript:} 脚本；画成链接的话，管理员一点，脚本就在已登录的控制台里跑。
 * 每格都真把首页画一遍，再看画出来的链接。
 */
@DisplayName("新版提示的说明链接")
class UpdateLinkFrontendTest {
    private static final String FIXTURE = FrontendFixture.fixture("update-link-fixture.mjs");

    @Test
    @DisplayName("关于卡：说明地址是 javascript: 一类时，画出来的气泡里没有链接指向它")
    void aboutCardDropsScriptUrl() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "关于卡不画脚本地址", "about-script");
    }

    @Test
    @DisplayName("首页待办：说明地址是 javascript: 一类时，「有新版」那一行没有链接指向它")
    void homeTodoDropsScriptUrl() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "首页待办不画脚本地址", "todo-script");
    }

    @Test
    @DisplayName("关于卡：https 说明地址照常画成链接，新开一页、不带本页地址")
    void aboutCardHttpsLinkOpensNewTab() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "关于卡的 https 链接", "https-about");
    }

    @Test
    @DisplayName("首页待办：https 说明地址照常画成链接，新开一页、不带本页地址")
    void homeTodoHttpsLinkOpensNewTab() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "首页待办的 https 链接", "https-todo");
    }
}
