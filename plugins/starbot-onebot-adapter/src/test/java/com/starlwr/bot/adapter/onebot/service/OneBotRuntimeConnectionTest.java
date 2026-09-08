package com.starlwr.bot.adapter.onebot.service;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.starlwr.bot.adapter.onebot.controller.OneBotController;
import com.starlwr.bot.adapter.onebot.controller.OneBotTargetController;
import com.starlwr.bot.adapter.onebot.converter.OneBotMessageConverter;
import com.starlwr.bot.adapter.onebot.health.OneBotConnectionState;
import com.starlwr.bot.adapter.onebot.http.OneBotHttpAdapter;
import com.starlwr.bot.adapter.onebot.http.OneBotHttpAdapterProxy;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.adapter.onebot.security.PushApiTokenStore;
import com.starlwr.bot.core.account.BotConnectionTester;
import com.starlwr.bot.core.properties.LogProperties;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.service.NovaSenderService;
import com.starlwr.bot.core.util.HttpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运行期把 OneBot 连接接上
 *
 * <h2>这里守的是三件事</h2>
 * <ol>
 *   <li><b>一台什么都没配的机器，存下连接之后不重启就能用</b>——此前连接信息只在启动时读一次，
 *       于是初始设置第 2 步之后的第 4、5 步面对的是一个什么都没连上的进程：
 *       名单取回来是空的、消息发不出去，而屏幕上没有任何东西解释为什么</li>
 *   <li><b>换了地址，旧地址上那条连接真的断了</b>——不断的话，两条连接会同时收消息，
 *       同一条命令因此被执行两遍，而界面上只看得见新的那一条</li>
 *   <li><b>反复存不会越存越多</b>——使用者连按保存是常态，每按一下多一条连接的话，
 *       表现是消息成倍地重复，且要过一会儿才看得出来</li>
 * </ol>
 *
 * <h2>用真的服务端量</h2>
 * 假 OneBot 是两个真的服务端（{@link FakeOneBotHttpServer} 与 {@link FakeOneBotWebsocketServer}），
 * 不是桩。第二、三条要量的正是「没人喊断开的那些东西还在不在跑」——重连循环、
 * 已经发出去的握手、挂着的检测任务，这些在桩上一概看不见。
 */
@DisplayName("OneBot 连接运行期注册")
class OneBotRuntimeConnectionTest {
    /**
     * 等一件事发生的上限。真握手在本机是毫秒级的，给到秒级只为让慢机器也稳
     */
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    private FakeOneBotHttpServer http;

    private FakeOneBotWebsocketServer websocket;

    private OneBotAdapterPluginProperties properties;

    private OneBotConnectionState state;

    private OneBotWebsocketService websocketService;

    private OneBotHttpService httpService;

    private OneBotTargetDirectory directory;

    private NovaSenderService senderService;

    private OneBotConnectionManager manager;

    private ThreadPoolTaskExecutor executor;

    private ThreadPoolTaskScheduler scheduler;

    @BeforeEach
    void setUp() throws IOException {
        http = new FakeOneBotHttpServer();
        websocket = new FakeOneBotWebsocketServer();

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.initialize();

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.initialize();

        properties = new OneBotAdapterPluginProperties();
        // 定期检测在这组用例里只会制造噪声：它每隔一段时间往连接状态里写一次，
        // 而这里量的是「刚接上那一刻是什么样」
        properties.getDetect().setEnableHttpDetect(false);
        properties.getDetect().setEnableWebsocketDetect(false);

        state = new OneBotConnectionState();

        OneBotHttpAdapter adapter = (OneBotHttpAdapter) Proxy.newProxyInstance(
                OneBotHttpAdapter.class.getClassLoader(),
                new Class[]{OneBotHttpAdapter.class},
                new OneBotHttpAdapterProxy(new HttpUtil(executor, new RestTemplate(), new LogProperties()), state));

        websocketService = new OneBotWebsocketService(scheduler, executor, properties, state,
                mock(ApplicationEventPublisher.class));
        httpService = new OneBotHttpService(scheduler, executor, properties, adapter,
                new OneBotMessageConverter(), state);
        directory = new OneBotTargetDirectory(adapter, httpService, properties);

        senderService = new NovaSenderService(new StarBotCoreProperties());

        WebServer server = mock(WebServer.class);
        when(server.getPort()).thenReturn(8080);
        WebServerApplicationContext webContext = mock(WebServerApplicationContext.class);
        when(webContext.getWebServer()).thenReturn(server);

        OneBotController controller = new OneBotController(webContext,
                mock(RequestMappingHandlerMapping.class), properties, senderService, httpService,
                new PushApiTokenStore());

        manager = new OneBotConnectionManager(properties, controller, websocketService, httpService, state);
    }

    @AfterEach
    void tearDown() {
        properties.getSenders().forEach(sender -> websocketService.stop(sender.getName()));
        websocket.close();
        http.close();
        scheduler.shutdown();
        executor.shutdown();
    }

    /**
     * 存一次连接信息，参数与初始设置第 2 步发过来的一致
     */
    private BotConnectionTester.Applied save(int websocketPort) {
        return manager.apply("127.0.0.1", http.port(), websocketPort, "http-token", "ws-token");
    }

    /**
     * 等一件事成立，超时即红
     */
    private void await(String what, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(20);
        }

        assertTrue(condition.getAsBoolean(), what + "：等了 " + PATIENCE.toSeconds() + " 秒仍不成立");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("⚠️ 一台什么都没配的机器：存下连接之后不重启就取得到推送目标名单")
    void registersFirstBotWithoutRestart() {
        assertTrue(properties.getSenders().isEmpty(), "这一格的前提是这台机器一台机器人都没配过");
        assertTrue(directory.snapshots(false).isEmpty(), "没配过的时候名单本来就该是空的");

        BotConnectionTester.Applied applied = save(websocket.port());

        assertTrue(applied.live(), "存下来的那一刻就该是接上的，回话: " + applied.detail());
        assertTrue(applied.detail().contains(FakeOneBotHttpServer.NICKNAME),
                "回话该说的是此刻的现状（登录着哪个账号），而不是「已保存」这种只描述动作的话: " + applied.detail());

        // 名单这条链走的是「配置面里有这条连接」加「它已经注册进 HTTP 服务」，
        // 缺任何一头都会静悄悄地回一份空名单
        List<OneBotTargetDirectory.Snapshot> snapshots = directory.snapshots(false);
        assertEquals(1, snapshots.size(), "存下来之后就该有一份名单");
        assertEquals(List.of(FakeOneBotHttpServer.GROUP_NUM),
                snapshots.get(0).groups().stream().map(OneBotTargetDirectory.Group::num).toList());
        assertEquals(List.of(FakeOneBotHttpServer.FRIEND_NUM),
                snapshots.get(0).friends().stream().map(OneBotTargetDirectory.Friend::num).toList());

        // 核心是按这个名字找回机器人的：不注册进来，推送投递不出去而没有任何报错
        assertTrue(senderService.getSenderNames().contains(OneBotConnectionManager.FIRST_SENDER_NAME),
                "推送平台该已经注册进核心: " + senderService.getSenderNames());

        // 初始设置第 4 步真正问的就是这一支：挑推送目标那张表从它来
        JSONObject listed = new OneBotTargetController(directory,
                mock(com.starlwr.bot.core.datasource.AbstractDataSource.class)).targets("group", null).getBody();
        assertNotNull(listed, "取名单那一支该有回包");
        assertTrue(listed.getBooleanValue("success"), listed.toJSONString());
        assertEquals(1, listed.getJSONArray("items").size(), "第 4 步该挑得到那个群: " + listed.toJSONString());

        await("Websocket 该连上", () -> websocket.open() == 1);
        assertEquals(List.of("Bearer ws-token"), websocket.authorizations(), "握手该带上刚存下的 Token");
    }

    @Test
    @DisplayName("⚠️ 换个地址：旧地址上那条连接断掉，新地址上接起来")
    void movesTheConnectionToTheNewAddress() throws IOException {
        save(websocket.port());
        await("旧地址该先连上", () -> websocket.open() == 1);

        try (FakeOneBotWebsocketServer moved = new FakeOneBotWebsocketServer()) {
            save(moved.port());

            await("新地址该连上", () -> moved.open() == 1);
            await("旧地址那条该断掉", () -> websocket.open() == 0);

            // 断开之后不许自己爬回来。重连是这条链上唯一常驻的后台动作，
            // 而「断了一秒后自动重连」正是它平时该做的事——被换掉的那一代不该再做
            sleep(1500);
            assertEquals(0, websocket.open(), "被换掉的那一代不该自己重连回旧地址");
            assertEquals(1, moved.open(), "新地址上仍该只有一条");
        }
    }

    @Test
    @DisplayName("⚠️ 连存三次：仍然只有一条连接")
    void repeatedSaveKeepsOneConnection() {
        save(websocket.port());
        await("第一次该连上", () -> websocket.open() == 1);

        save(websocket.port());
        save(websocket.port());

        await("连存三次之后仍只该有一条", () -> websocket.open() == 1);
        // 等过重连那一秒：被换掉的两代若还活着，它们会在这段时间里爬回来
        sleep(1500);
        assertEquals(1, websocket.open(), "连存三次之后仍只该有一条");

        assertEquals(1, properties.getSenders().size(), "机器人也只该有一台，而不是每存一次多一台");
    }

    @Test
    @DisplayName("要落进配置文件的是整条元素，缺了平台名的那一条会让下次启动直接失败")
    void configurationCarriesTheWholeItem() {
        Map<String, String> configuration = save(websocket.port()).configuration();

        assertEquals(OneBotConnectionManager.FIRST_SENDER_NAME, configuration.get("name"));
        assertEquals(OneBotConnectionManager.FIRST_SENDER_API, configuration.get("api"));
        assertEquals("true", configuration.get("websocket"));
        assertEquals("127.0.0.1", configuration.get("one-bot-address"));
        assertEquals(String.valueOf(http.port()), configuration.get("one-bot-http-port"));
        assertEquals(String.valueOf(websocket.port()), configuration.get("one-bot-websocket-port"));
        assertEquals("http-token", configuration.get("one-bot-http-token"));
        assertEquals("ws-token", configuration.get("one-bot-websocket-token"));

        // 推送接口 Token 留空的语义是「每次启动生成一把、只在本次运行有效」。
        // 把生成出来的那一把写进文件，等于替使用者把这个决定改成了「固定一把长期有效的」
        assertFalse(configuration.containsKey("api-token"), "自动生成的推送接口 Token 不该被写进配置文件");
    }

    @Test
    @DisplayName("留空的字段保持原值：界面有意不回填 Token，留空是「不改」而不是「清掉」")
    void blankFieldsKeepTheCurrentValue() {
        save(websocket.port());

        manager.apply("  ", 0, 0, "", null);

        OneBotSender sender = properties.getSenders().get(0);
        assertEquals("127.0.0.1", sender.getOneBotAddress());
        assertEquals(http.port(), sender.getOneBotHttpPort());
        assertEquals(websocket.port(), sender.getOneBotWebsocketPort());
        assertEquals("http-token", sender.getOneBotHttpToken());
        assertEquals("ws-token", sender.getOneBotWebsocketToken());
    }

    @Test
    @DisplayName("缺 HTTP Token 时明说接不上，而不是报一个「已接上」")
    void refusesToClaimLiveWithoutHttpToken() {
        BotConnectionTester.Applied applied =
                manager.apply("127.0.0.1", http.port(), websocket.port(), null, "ws-token");

        assertFalse(applied.live(), "没有 HTTP Token 就挂不上推送接口，这条连接算不得接上了");
        assertFalse(applied.configuration().isEmpty(), "接不上也得把这条元素写下来，否则改了的那几项一并丢失");
    }
}
