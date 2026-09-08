package com.starlwr.bot.adapter.onebot.controller;

import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.adapter.onebot.security.PushApiTokenStore;
import com.starlwr.bot.adapter.onebot.service.OneBotHttpService;
import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.service.NovaSenderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 适配器登记时自报的显示名必须真的从 {@link OneBotController#register} 走进核心
 * <p>
 * 额度面、首页今日格读的都是 {@link NovaSenderService#displayName(String)}。
 * 若这里把自报字改成别的，那些表面格自己造一份「QQ」送进去，一格都不会红。
 */
@DisplayName("OneBot 适配器自报显示名")
class OneBotAdapterDisplayNameTest {
    private static final String PLATFORM = "qq-onebot";

    private OneBotController controller(NovaSenderService senders) {
        WebServer server = mock(WebServer.class);
        when(server.getPort()).thenReturn(8080);
        WebServerApplicationContext webContext = mock(WebServerApplicationContext.class);
        when(webContext.getWebServer()).thenReturn(server);
        return new OneBotController(
                webContext,
                mock(RequestMappingHandlerMapping.class),
                new OneBotAdapterPluginProperties(),
                senders,
                mock(OneBotHttpService.class),
                new PushApiTokenStore());
    }

    private OneBotSender sender(String httpToken) {
        OneBotSender sender = new OneBotSender();
        sender.setName(PLATFORM);
        sender.setApi("/send");
        sender.setOneBotHttpToken(httpToken);
        return sender;
    }

    @Test
    @DisplayName("配了 HTTP Token 时 register 走到自报，核心 displayName 是 QQ")
    void registerWithTokenReportsDisplayNameQQ() {
        NovaSenderService senders = new NovaSenderService(new NovaCoreProperties());

        assertTrue(controller(senders).register(sender("http-token")), "有 Token 就该挂上");
        assertEquals("QQ", senders.displayName(PLATFORM),
                "适配器自报的显示名必须是 QQ, 改成别的字界面就会跟着漂");
    }

    @Test
    @DisplayName("缺 HTTP Token 时 register 早返回，不把显示名写进核心")
    void blankHttpTokenDoesNotInstallADisplayName() {
        NovaSenderService senders = new NovaSenderService(new NovaCoreProperties());

        assertFalse(controller(senders).register(sender("")), "缺 Token 挂不上");
        assertEquals(PLATFORM, senders.displayName(PLATFORM),
                "没走到自报时该回落成标识串本身, 不得凭空出现 QQ");
        assertFalse(senders.getSenderNames().contains(PLATFORM),
                "缺 Token 时推送平台也不该登记进去");
    }
}
