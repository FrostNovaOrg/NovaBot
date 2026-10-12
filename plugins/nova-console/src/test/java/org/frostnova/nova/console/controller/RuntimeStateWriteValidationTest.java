package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandDispatcher;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.command.NovaCommand;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.SessionQuietHoursService;
import org.frostnova.nova.core.service.StreamerNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 状态接口几个写口的号码与订阅类型收得严不严
 * <p>
 * 静音那一格已经把会话号收成正整数了，其余写口还在用宽松的读法：12.5 截成 12、
 * true 读成 1，状态就被写到一个没人用的会话上，回复照样是成功；移除订阅时
 * 用户号读不出会被当成「清空整份名单」。这里对每个写口量同一件事：
 * <b>坏号要回失败、什么也不写；正常的号照旧成功、写对地方。</b>
 * <p>
 * 存储用真的（落在临时目录），替身只给出网与命令清单：要量的正是「落没落盘、
 * 落到了哪个会话上」，替身答不了这一问。
 */
@DisplayName("状态接口写口的号码与订阅类型")
class RuntimeStateWriteValidationTest {
    private static final String PLATFORM = "qq-onebot";

    private static final long GROUP = 30003L;

    private static final long STREAMER = 10001L;

    /** 页面与调用方都可能送来的几类坏号：小数、真假、0、负数、认不出的串 */
    private static final Object[] BAD_NUMBERS = {12.5, true, 0, -3, "abc"};

    private CommandSettingsService settings;

    private RevenueVisibilityService revenueVisibility;

    private AtSubscriptionService subscriptions;

    private RuntimeStateController controller;

    /** 状态文件放这里，不落在工作目录 */
    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        NovaStateStore store = new NovaStateStore(properties);
        settings = new CommandSettingsService(store);
        revenueVisibility = new RevenueVisibilityService(store);
        subscriptions = new AtSubscriptionService(store);
        SessionQuietHoursService quietHours = new SessionQuietHoursService(store);

        // 默认方法走真实现：disableable 为真、用法名就是命令名，禁用这一条路径才走得通
        NovaCommand command = mock(NovaCommand.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        when(command.name()).thenReturn("礼物榜");
        when(command.description()).thenReturn("看看这个群收到的礼物");
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        when(dispatcher.all()).thenReturn(List.of(command));

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of());

        controller = new RuntimeStateController(dispatcher, settings, subscriptions, store,
                dataSource, revenueVisibility, mock(LiveDataService.class),
                new StreamerNames(new LiveSessionArchive(properties)), quietHours,
                new PushGate(properties, quietHours));
    }

    @Test
    @DisplayName("开关命令的会话号不是正整数：回 200 加失败说明，什么也不写")
    void commandRejectsNonPositiveIntegerSession() {
        for (Object num : BAD_NUMBERS) {
            ResponseEntity<JSONObject> response = controller.toggleCommand(toggle(num));
            assertEquals(200, response.getStatusCode().value(), num + " 也该是 200 加 success:false，不是改状态码");
            JSONObject result = response.getBody();
            assertFalse(result.getBooleanValue("success"), num + ": " + result);
            assertTrue(result.getString("message").contains("不是正整数"), num + ": " + result);
            assertTrue(settings.all().isEmpty(), num + " 不该写进任何会话: " + settings.all());
        }
    }

    @Test
    @DisplayName("开关命令拿正常的号：照旧成功，禁用落在那个会话上")
    void commandWritesTheGivenSession() {
        assertTrue(controller.toggleCommand(toggle(30007L)).getBody().getBooleanValue("success"));

        assertTrue(settings.isDisabled(PLATFORM, PushTargetType.GROUP, 30007L, "礼物榜"));
        assertEquals(1, settings.all().size(), settings.all().toString());
        assertEquals(30007L, settings.all().get(0).num());
    }

    @Test
    @DisplayName("成批开关命令的会话号不是正整数：回 200 加失败说明，整批不动")
    void commandsRejectsNonPositiveIntegerSession() {
        for (Object num : BAD_NUMBERS) {
            ResponseEntity<JSONObject> response = controller.toggleCommands(toggles(num));
            assertEquals(200, response.getStatusCode().value(), num + " 也该是 200 加 success:false，不是改状态码");
            JSONObject result = response.getBody();
            assertFalse(result.getBooleanValue("success"), num + ": " + result);
            assertTrue(result.getString("message").contains("不是正整数"), num + ": " + result);
            assertTrue(settings.all().isEmpty(), num + " 不该写进任何会话: " + settings.all());
        }
    }

    @Test
    @DisplayName("成批开关拿正常的号：照旧成功，记到那个会话名下")
    void commandsWritesTheGivenSession() {
        JSONObject result = controller.toggleCommands(toggles(30007L)).getBody();

        assertTrue(result.getBooleanValue("success"), result.toJSONString());
        assertTrue(settings.isDisabled(PLATFORM, PushTargetType.GROUP, 30007L, "礼物榜"));
    }

    @Test
    @DisplayName("金额可见性的会话号不是正整数：回 200 加失败说明，不落设置")
    void revenueRejectsNonPositiveIntegerSession() {
        for (Object num : BAD_NUMBERS) {
            ResponseEntity<JSONObject> response = controller.setRevenueVisibility(revenue(num));
            assertEquals(200, response.getStatusCode().value(), num + " 也该是 200 加 success:false，不是改状态码");
            JSONObject result = response.getBody();
            assertFalse(result.getBooleanValue("success"), num + ": " + result);
            assertTrue(result.getString("message").contains("不是正整数"), num + ": " + result);
            assertTrue(revenueVisibility.all().isEmpty(), num + " 不该写进任何会话: " + revenueVisibility.all());
        }
    }

    @Test
    @DisplayName("金额可见性拿正常的号：照旧成功，设置落在那个会话上")
    void revenueWritesTheGivenSession() {
        assertTrue(controller.setRevenueVisibility(revenue(30007L)).getBody().getBooleanValue("success"));

        assertEquals(List.of(new RevenueVisibilityService.Setting(PLATFORM, PushTargetType.GROUP, 30007L, true)),
                revenueVisibility.all());
    }

    @Test
    @DisplayName("移除订阅的会话号或主播号不是正整数：回失败说明，名单不动")
    void subscriptionRejectsNonPositiveIntegerSessionAndStreamer() {
        subscribe(12L, 100L);

        for (Object num : BAD_NUMBERS) {
            JSONObject result = controller.removeSubscription(subscription(num, STREAMER, "live", 100L));
            assertFalse(result.getBooleanValue("success"), "会话号 " + num + ": " + result);
            assertTrue(result.getString("message").contains("不是正整数"), "会话号 " + num + ": " + result);
            assertEquals(List.of(12L, 100L), subscriptions.list(PLATFORM, GROUP, STREAMER, "live"),
                    "会话号 " + num + " 这一下不该动名单");
        }
        for (Object streamerUid : BAD_NUMBERS) {
            JSONObject result = controller.removeSubscription(subscription(GROUP, streamerUid, "live", 100L));
            assertFalse(result.getBooleanValue("success"), "主播号 " + streamerUid + ": " + result);
            assertTrue(result.getString("message").contains("不是正整数"), "主播号 " + streamerUid + ": " + result);
            assertEquals(List.of(12L, 100L), subscriptions.list(PLATFORM, GROUP, STREAMER, "live"),
                    "主播号 " + streamerUid + " 这一下不该动名单");
        }
    }

    @Test
    @DisplayName("移除订阅的订阅类型不是开播或动态：回失败说明，名单不变")
    void subscriptionRejectsUnknownType() {
        subscribe(12L, 100L);

        JSONObject result = controller.removeSubscription(subscription(GROUP, STREAMER, "foo", 12L));

        assertFalse(result.getBooleanValue("success"), result.toJSONString());
        assertEquals(List.of(12L, 100L), subscriptions.list(PLATFORM, GROUP, STREAMER, "live"));
    }

    @Test
    @DisplayName("移除订阅给了一个坏的用户号：回失败说明，既不清空整份名单也不删错人")
    void subscriptionRejectsBadUserUid() {
        subscribe(12L, 100L);

        for (Object userUid : BAD_NUMBERS) {
            JSONObject result = controller.removeSubscription(subscription(GROUP, STREAMER, "live", userUid));
            assertFalse(result.getBooleanValue("success"), userUid + ": " + result);
            assertTrue(result.getString("message").contains("不是正整数"), userUid + ": " + result);
            assertEquals(List.of(12L, 100L), subscriptions.list(PLATFORM, GROUP, STREAMER, "live"),
                    userUid + " 这一下不该动名单");
        }
    }

    @Test
    @DisplayName("移除订阅拿正常的号：指名移除一个人，没给用户号照旧清空整份")
    void subscriptionRemovesTheGivenUserOrClears() {
        subscribe(12L, 100L);

        assertTrue(controller.removeSubscription(subscription(GROUP, STREAMER, "live", 100L))
                .getBooleanValue("success"));
        assertEquals(List.of(12L), subscriptions.list(PLATFORM, GROUP, STREAMER, "live"));

        assertTrue(controller.removeSubscription(subscription(GROUP, STREAMER, "live", null))
                .getBooleanValue("success"));
        assertTrue(subscriptions.list(PLATFORM, GROUP, STREAMER, "live").isEmpty());
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private void subscribe(long... users) {
        for (long user : users) {
            subscriptions.subscribe(PLATFORM, GROUP, STREAMER, "live", user);
        }
    }

    private JSONObject toggle(Object num) {
        return new JSONObject()
                .fluentPut("platform", PLATFORM)
                .fluentPut("type", PushTargetType.GROUP.getCode())
                .fluentPut("num", num)
                .fluentPut("command", "礼物榜")
                .fluentPut("disabled", true);
    }

    private JSONObject toggles(Object num) {
        JSONArray names = new JSONArray();
        names.add("礼物榜");
        return new JSONObject()
                .fluentPut("platform", PLATFORM)
                .fluentPut("type", PushTargetType.GROUP.getCode())
                .fluentPut("num", num)
                .fluentPut("commands", names)
                .fluentPut("disabled", true);
    }

    private JSONObject revenue(Object num) {
        return new JSONObject()
                .fluentPut("platform", PLATFORM)
                .fluentPut("type", PushTargetType.GROUP.getCode())
                .fluentPut("num", num)
                .fluentPut("visible", true);
    }

    /**
     * @param userUid 为 null 时表示请求体里没带这一项，照旧表示清空整份名单
     */
    private JSONObject subscription(Object num, Object streamerUid, String type, Object userUid) {
        JSONObject body = new JSONObject()
                .fluentPut("platform", PLATFORM)
                .fluentPut("num", num)
                .fluentPut("streamerUid", streamerUid)
                .fluentPut("type", type);
        if (userUid != null) {
            body.put("userUid", userUid);
        }
        return body;
    }
}
