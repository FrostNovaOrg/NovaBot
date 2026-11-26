package org.frostnova.nova.core.api;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.protocol.EventStreamTokenService;
import org.frostnova.nova.core.protocol.NovaEventEndpoint;
import org.frostnova.nova.core.protocol.NovaEventStreamConfiguration;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 按生产的装配接起来之后，事件流认不认访问令牌
 */
class EventStreamAccessTokenWiringTest {
    @TempDir
    Path directory;

    private ApplicationContextRunner runner(boolean enabled) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        NovaStateStore state = new NovaStateStore(properties);
        return new ApplicationContextRunner()
                .withPropertyValues("novabot.core.event-stream.enabled=" + enabled)
                .withBean(NovaCoreProperties.class, () -> properties)
                .withBean(NovaStateStore.class, () -> state)
                .withBean(EventStreamTokenService.class)
                .withUserConfiguration(NovaApiConfiguration.class, NovaEventStreamConfiguration.class);
    }

    private static NovaEventEndpoint endpoint(org.springframework.context.ApplicationContext context) {
        SimpleUrlHandlerMapping mapping = context.getBean("novaEventStreamHandlerMapping", SimpleUrlHandlerMapping.class);
        WebSocketHttpRequestHandler handler = (WebSocketHttpRequestHandler) mapping.getUrlMap().get("/nova/events");
        assertNotNull(handler, "事件输出打开时应当挂在 /nova/events");
        return (NovaEventEndpoint) WebSocketHandlerDecorator.unwrap(handler.getWebSocketHandler());
    }

    // 抓的故障：管理员签了一把勾了「读取事件流」的访问令牌，按生产装配起来的事件流却只认只读口令，
    // 面板首帧带它连上就收到 bad_token；或者签发页根本没有这一项可勾。
    @Test
    void productionWiringAcceptsAnEventTokenAndKeepsReadonlyTokens() {
        runner(true).run(context -> {
            assertNull(context.getStartupFailure());
            NovaApiRegistry registry = context.getBean(NovaApiRegistry.class);
            assertTrue(registry.adminOnly(NovaApiEventStreamTokens.READ), "签发页上应有这一项，并标仅管理员");
            NovaEventEndpoint endpoint = endpoint(context);
            NovaApiTokenStore tokens = context.getBean(NovaApiTokenStore.class);

            var issued = tokens.issue(ConfigUiAccount.LOCAL_ID, "直播间大屏", List.of(NovaApiEventStreamTokens.READ), null);
            EventStreamProbe panel = new EventStreamProbe("panel").connect(endpoint, issued.plaintext());
            JSONObject hello = panel.next();
            assertNotNull(hello);
            assertEquals("hello", hello.getString("kind"));

            String pushOnly = tokens.issue(ConfigUiAccount.LOCAL_ID, "推送脚本", List.of("push:send"), null).plaintext();
            EventStreamProbe script = new EventStreamProbe("script").connect(endpoint, pushOnly);
            JSONObject failed = script.next();
            assertEquals("auth_failed", failed.getString("kind"));
            assertEquals("bad_token", failed.getString("reason"));

            String readonly = context.getBean(EventStreamTokenService.class).issue("老面板");
            EventStreamProbe old = new EventStreamProbe("old").connect(endpoint, readonly);
            assertEquals("hello", old.next().getString("kind"), "旧的只读口令照常能连");

            tokens.revoke(issued.token().id());
            assertEquals(CloseStatus.NORMAL.withReason("口令已吊销"), panel.awaitClose(),
                    "吊销之后用它连着的面板应当当场被关掉");
            assertTrue(old.isOpen(), "只读口令的连接不受影响");
        });
    }

    // 抓的故障：升级之后事件流默认被打开了。
    @Test
    void eventStreamStaysOffByDefault() {
        runner(false).run(context -> {
            assertNull(context.getStartupFailure());
            SimpleUrlHandlerMapping mapping = context.getBean("novaEventStreamHandlerMapping", SimpleUrlHandlerMapping.class);
            assertTrue(mapping.getUrlMap().isEmpty(), "没打开时不该占路径");
        });
    }
}
