package org.frostnova.nova.core.api.push;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.api.NovaApiFilter;
import org.frostnova.nova.core.api.NovaApiOpenGroups;
import org.frostnova.nova.core.api.NovaApiPage;
import org.frostnova.nova.core.api.NovaApiProvider;
import org.frostnova.nova.core.api.NovaApiRegistry;
import org.frostnova.nova.core.api.NovaApiRoute;
import org.frostnova.nova.core.api.NovaApiScope;
import org.frostnova.nova.core.api.NovaApiThrottle;
import org.frostnova.nova.core.api.NovaApiTokenStore;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.ConfigUiApiTokenController;
import org.frostnova.nova.core.config.ui.ConsoleAllowList;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcClient;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.sender.AtAllPermissionResolver;
import org.frostnova.nova.core.sender.FirstPushTipService;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PlatformDeliveryGate;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.AtAllQuotaService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 推送接口组：经底座的门（来源、令牌、范围）与核心的发送器，去向用进程内替身
 */
class PushApiTest {
    private static final String API = "/nova/api/v1/push/";

    private static final String ONEBOT = "qq-onebot";

    private static final String OFFICIAL = "qq-official";

    private static final String GROUP_NUMBER = "987654321";

    private static final String OUTSIDER = "203.0.113.9";

    @TempDir
    Path directory;

    private NovaCoreProperties properties;

    private NovaSenderService senders;

    private ConfigUiAccountStore accounts;

    private NovaApiTokenStore tokens;

    private NovaApiRegistry registry;

    private final MutableClock clock = new MutableClock();

    /** 时间线：程序接口自己记的那些 */
    private final List<TimelineEvent> timeline = new CopyOnWriteArrayList<>();

    /** 推送记录写进的时间线：程序接口的发送不该出现在这里 */
    private final List<TimelineEvent> pushActivity = new CopyOnWriteArrayList<>();

    /** 交到替身去向手里的每一次 */
    private final List<Map<String, Object>> delivered = new CopyOnWriteArrayList<>();

    private Function<Map<String, Object>, JSONObject> onebotAnswer = params -> JSONObject.of("code", 0, "id", "m-1");

    private volatile String blockedPlatform;

    private MockMvc mvc;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    private final Logger root = (Logger) LoggerFactory.getLogger("org.frostnova.nova.core");

    private ch.qos.logback.classic.Level previousLevel;

    /** 只要一项普通范围的另一组，代替外发箱 */
    static final class ParcelApi implements NovaApiProvider {
        @Override
        public String segment() {
            return "parcel";
        }

        @Override
        public List<NovaApiScope> scopes() {
            return List.of(new NovaApiScope("parcel:read", "读取取件", "测试用"));
        }

        @Override
        public List<NovaApiRoute> routes() {
            return List.of(NovaApiRoute.get("items", "parcel:read", request -> NovaApiPage.last(List.of())));
        }
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-27T03:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void api() {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        NovaStateStore state = new NovaStateStore(properties);
        accounts = new ConfigUiAccountStore(state);
        tokens = new NovaApiTokenStore(state);
        senders = new NovaSenderService(properties);
        senders.addSender(new Sender(ONEBOT, "http://127.0.0.1:7827/onebot/send", "legacy-push-secret", 0, (headers, params) -> {
            delivered.add(Map.copyOf(params));
            return onebotAnswer.apply(params);
        }), "QQ");
        senders.addSender(new Sender(OFFICIAL, null, "", 0, (headers, params) -> {
            delivered.add(Map.copyOf(params));
            return JSONObject.of("code", 0, "id", "official-1");
        }), "QQ 官方机器人", Set.of(PushTargetType.GROUP));
        rebuild(senders);
        logs.list.clear();
        logs.start();
        previousLevel = root.getLevel();
        root.setLevel(ch.qos.logback.classic.Level.INFO);
        root.addAppender(logs);
    }

    @AfterEach
    void detach() {
        root.detachAppender(logs);
        root.setLevel(previousLevel);
        logs.stop();
    }

    private void rebuild(NovaSenderService registered) {
        NovaMessageSender sender = messageSender(registered);
        PushApiProvider push = new PushApiProvider(() -> registered, () -> sender, () -> timeline::add,
                new PushSendQuota(PushSendQuota.PER_MINUTE, PushSendQuota.WINDOW, clock));
        registry = new NovaApiRegistry(List.of(new ParcelApi(), push));
        NovaApiFilter filter = new NovaApiFilter(new ConsoleAllowList(properties.getConfigUi()),
                new NovaApiOpenGroups(properties.getConfigUi()::getApiAnySourceGroups, registry), tokens, accounts,
                () -> true, registry, new NovaApiThrottle(), () -> List.of("bilibili"), List::of);
        mvc = MockMvcBuilders.standaloneSetup().addFilters(filter).build();
    }

    private NovaMessageSender messageSender(NovaSenderService registered) {
        @SuppressWarnings("unchecked")
        ObjectProvider<AtAllPermissionResolver> resolvers = mock(ObjectProvider.class);
        when(resolvers.iterator()).thenAnswer(invocation -> List.<AtAllPermissionResolver>of().iterator());
        NovaMessageSender sender = new NovaMessageSender(mock(HttpUtil.class), registered,
                new PushActivityRecorder(pushActivity::add), new PushGate(properties), pushActivity::add,
                new AtAllQuotaService(properties), resolvers,
                new FirstPushTipService(new NovaStateStore(properties)));
        PlatformDeliveryGate gate = (platform, createdAt) -> platform.equals(blockedPlatform) ? "这个机器人通道已被管理员停用" : null;
        sender.setPlatformGates(List.of(gate));
        return sender;
    }

    // 抓的故障：升级之后什么都没做，旧令牌就能发消息；或者推送组默认就对名单以外的地址开着。
    @Test
    void afterUpgradeNoTokenCanSendAndThePushGroupIsNotOpen() throws Exception {
        String old = issue(ConfigUiAccount.LOCAL_ID, "parcel:read");
        assertFalse(tokens.list().stream().anyMatch(token -> token.scopes().contains(PushApiProvider.SEND)));
        assertError(perform(send(old, groupMessage("你好"))), 403, "insufficient_scope");
        assertTrue(properties.getConfigUi().getApiAnySourceGroups().isEmpty());
        assertTrue(new NovaApiOpenGroups(properties.getConfigUi()::getApiAnySourceGroups, registry).effective().isEmpty());

        String admin = issue(ConfigUiAccount.LOCAL_ID, PushApiProvider.SEND);
        assertError(perform(send(admin, groupMessage("你好")).with(peer(OUTSIDER))), 403, "source_not_allowed");
        assertTrue(delivered.isEmpty());
    }

    // 抓的故障：访问令牌页上看不到「推送」这一组或这项范围，或者它被标成了成员也能勾。
    @Test
    void theGroupAndTheAdminOnlyScopeShowUpForTheConsole() {
        var group = registry.groups().stream().filter(g -> g.segment().equals("push")).findFirst().orElseThrow();
        assertEquals("推送", group.name());
        assertFalse(group.description().isBlank());
        NovaApiScope scope = registry.scope(PushApiProvider.SEND).orElseThrow();
        assertEquals("发送推送消息", scope.name());
        assertTrue(scope.adminOnly());

        ResponseEntity<String> listed = new ConfigUiApiTokenController(tokens, registry, accounts, () -> true)
                .list(console(accounts.find(ConfigUiAccount.LOCAL_ID).orElseThrow()));
        JSONObject item = JSONObject.parseObject(listed.getBody()).getJSONArray("scopes").stream()
                .map(JSONObject.class::cast).filter(s -> s.getString("id").equals(PushApiProvider.SEND)).findFirst().orElseThrow();
        assertTrue(item.getBooleanValue("adminOnly"));
    }

    // 抓的故障：外部程序不知道能往哪儿发；只收群消息的去向被列成也能发私聊，发出去才被拒。
    @Test
    void platformsListsTheRegisteredDestinations() throws Exception {
        String admin = issue(ConfigUiAccount.LOCAL_ID, PushApiProvider.SEND);
        blockedPlatform = OFFICIAL;
        MockHttpServletResponse response = perform(get(API + "platforms").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertEquals(200, response.getStatus(), response.getContentAsString());
        JSONObject data = data(response);
        assertTrue(data.containsKey("next") && data.get("next") == null);
        JSONArray items = data.getJSONArray("items");
        assertEquals(2, items.size());
        JSONObject official = items.getJSONObject(0);
        JSONObject onebot = items.getJSONObject(1);
        assertEquals(ONEBOT, onebot.getString("id"), "按标识排序");
        assertEquals("QQ", onebot.getString("name"));
        assertEquals(List.of("FRIEND", "GROUP"), onebot.getJSONArray("targetTypes").toJavaList(String.class));
        assertTrue(onebot.getBooleanValue("enabled"));
        assertEquals(OFFICIAL, official.getString("id"));
        assertEquals("QQ 官方机器人", official.getString("name"));
        assertEquals(List.of("GROUP"), official.getJSONArray("targetTypes").toJavaList(String.class));
        assertFalse(official.getBooleanValue("enabled"), "被停用的去向照样列出，标明此刻停用");
    }

    // 抓的故障：回了「已发出」，消息却没有真的交到那个去向手里；或交过去的号码、内容、目标种类不对。
    @Test
    void aGroupMessageGoesThroughThatDestination() throws Exception {
        String admin = issue(ConfigUiAccount.LOCAL_ID, PushApiProvider.SEND);
        MockHttpServletResponse response = perform(send(admin, groupMessage("开播提醒 {at=all}")));
        assertEquals(200, response.getStatus(), response.getContentAsString());
        JSONObject data = data(response);
        assertEquals("m-1", data.getString("id"));
        assertEquals("SENT", data.getString("delivery"));
        assertEquals(1, delivered.size());
        Map<String, Object> params = delivered.get(0);
        assertEquals(ONEBOT, params.get("platform"));
        assertEquals(PushTargetType.GROUP.getCode(), params.get("type"));
        assertEquals(Long.parseLong(GROUP_NUMBER), params.get("num"));
        assertEquals("开播提醒 {at=all}", params.get("content"));

        delivered.clear();
        String numeric = "{\"platform\":\"" + OFFICIAL + "\",\"targetType\":\"GROUP\",\"target\":" + GROUP_NUMBER
                + ",\"content\":\"数字也收\"}";
        JSONObject second = data(perform(send(admin, numeric)));
        assertEquals("official-1", second.getString("id"));
        assertEquals(1, delivered.size());
        assertTrue(pushActivity.isEmpty(), "推送记录里不另记一份（那里是完整号码与正文）");
    }

    // 抓的故障：发送结果不明时自动又发了一遍，群里多出一条一样的。
    @Test
    void anUnknownResultIsReportedAndNotSentAgain() throws Exception {
        String admin = issue(ConfigUiAccount.LOCAL_ID, PushApiProvider.SEND);
        onebotAnswer = params -> JSONObject.of("code", -1, "message", "送达不明: 请求已送出, 等回包超时",
                Sender.LocalDelivery.DELIVERY_UNKNOWN, true);
        MockHttpServletResponse response = perform(send(admin, groupMessage("带图 {image_url=https://media.example.org/a.png}")));
        assertEquals(200, response.getStatus(), response.getContentAsString());
        JSONObject data = data(response);
        assertEquals("UNKNOWN", data.getString("delivery"));
        assertTrue(data.containsKey("id") && data.get("id") == null);
        assertEquals(1, delivered.size(), "不重试、不剥图补发");
        assertEquals(TimelineEventType.PUSH_UNCERTAIN, only(timeline).type());
    }

    // 抓的故障：含图消息没发出时，又悄悄补发了一条去掉图的，却回「已发出」。
    @Test
    void aRefusedMessageWithAnImageIsNotResentAsText() throws Exception {
        String admin = issue(ConfigUiAccount.LOCAL_ID, PushApiProvider.SEND);
        onebotAnswer = params -> JSONObject.of("code", 2, "message", "OneBot API 调用失败: retcode=1200 ref=internal-detail-7731", "id", null);
        MockHttpServletResponse response = perform(send(admin, groupMessage("封面 {image_url=https://media.example.org/a.png}")));
        assertError(response, 502, "delivery_failed");
        assertFalse(response.getContentAsString().contains("internal-detail-7731"), "去向回包的原话不进回包");
        assertFalse(response.getContentAsString().contains("retcode"));
        assertEquals(1, delivered.size());
        TimelineEvent event = only(timeline);
        assertEquals(TimelineEventType.PUSH_FAILED, event.type());
        assertTrue(event.detail().get("reason").contains("retcode=1200"), "原因留在时间线上给管理员看");
    }

    // 抓的故障：成员拿自己的令牌往别人的群里发了消息（签得出这项范围，或降成成员之后旧令牌还能用）。
    @Test
    void membersCanNeitherHoldNorUseTheScope() throws Exception {
        ConfigUiAccount member = member("subject-m", "成员甲");
        JSONObject forged = JSONObject.of("name", "伪造", "scopes", List.of(PushApiProvider.SEND));
        ResponseEntity<String> issued = new ConfigUiApiTokenController(tokens, registry, accounts, () -> true)
                .issue(forged, console(member));
        assertEquals(403, issued.getStatusCode().value(), issued.getBody());
        assertTrue(tokens.list().isEmpty());

        String memberToken = issue(member.id(), "parcel:read");
        assertError(perform(send(memberToken, groupMessage("越权"))), 403, "insufficient_scope");
        assertError(perform(get(API + "platforms").header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)),
                403, "insufficient_scope");

        ConfigUiAccount admin = accounts.role(member("subject-a", "管理员乙").id(), ConfigUiAccount.Role.ADMIN);
        String adminToken = issue(admin.id(), PushApiProvider.SEND);
        assertEquals(200, perform(send(adminToken, groupMessage("降级前"))).getStatus());
        accounts.role(admin.id(), ConfigUiAccount.Role.MEMBER);
        delivered.clear();
        assertError(perform(send(adminToken, groupMessage("降级后"))), 403, "forbidden");
        assertError(perform(get(API + "platforms").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)), 403, "forbidden");
        assertTrue(delivered.isEmpty());
    }

    // 抓的故障：只给外发箱开了公网，结果推送也能从公网调。
    @Test
    void openingAnotherGroupDoesNotOpenPush() throws Exception {
        String token = issue(ConfigUiAccount.LOCAL_ID, PushApiProvider.SEND, "parcel:read");
        properties.getConfigUi().setApiAnySourceGroups(List.of("parcel"));
        assertEquals(200, perform(get("/nova/api/v1/parcel/items").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .with(peer(OUTSIDER))).getStatus());
        assertError(perform(send(token, groupMessage("外面来的")).with(peer(OUTSIDER))), 403, "source_not_allowed");
        assertError(perform(get(API + "platforms").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).with(peer(OUTSIDER))),
                403, "source_not_allowed");
        assertTrue(delivered.isEmpty());

        properties.getConfigUi().setApiAnySourceGroups(List.of("push"));
        assertEquals(200, perform(send(token, groupMessage("开放之后")).with(peer(OUTSIDER))).getStatus());
        assertEquals(401, perform(post(API + "messages").contentType(MediaType.APPLICATION_JSON)
                .content(groupMessage("没带令牌")).with(peer(OUTSIDER))).getStatus(), "开放只免来源名单这一道");
        assertEquals(1, delivered.size());
    }

    // 抓的故障：请求体写错一项，消息照样发了出去（发到号码 0、发成私聊、发了一条空消息），或回包不说是哪一项错了。
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"targetType\":\"GROUP\",\"target\":\"987654321\",\"content\":\"x\"}|platform",
            "{\"platform\":42,\"targetType\":\"GROUP\",\"target\":\"987654321\",\"content\":\"x\"}|platform",
            "{\"platform\":\"qq-onebot\",\"target\":\"987654321\",\"content\":\"x\"}|targetType",
            "{\"platform\":\"qq-onebot\",\"targetType\":\"group\",\"target\":\"987654321\",\"content\":\"x\"}|targetType",
            "{\"platform\":\"qq-onebot\",\"targetType\":1,\"target\":\"987654321\",\"content\":\"x\"}|targetType",
            "{\"platform\":\"qq-onebot\",\"targetType\":\"GROUP\",\"content\":\"x\"}|target",
            "{\"platform\":\"qq-onebot\",\"targetType\":\"GROUP\",\"target\":\"98765x321\",\"content\":\"x\"}|target",
            "{\"platform\":\"qq-onebot\",\"targetType\":\"GROUP\",\"target\":\"0\",\"content\":\"x\"}|target",
            "{\"platform\":\"qq-onebot\",\"targetType\":\"GROUP\",\"target\":\"0987654321\",\"content\":\"x\"}|target",
            "{\"platform\":\"qq-onebot\",\"targetType\":\"GROUP\",\"target\":-5,\"content\":\"x\"}|target",
            "{\"platform\":\"qq-onebot\",\"targetType\":\"GROUP\",\"target\":12.5,\"content\":\"x\"}|target",
            "{\"platform\":\"qq-onebot\",\"targetType\":\"GROUP\",\"target\":\"99999999999999999999\",\"content\":\"x\"}|target",
            "{\"platform\":\"qq-onebot\",\"targetType\":\"GROUP\",\"target\":\"987654321\"}|content",
            "{\"platform\":\"qq-onebot\",\"targetType\":\"GROUP\",\"target\":\"987654321\",\"content\":\"   \"}|content",
            "{\"platform\":\"qq-onebot\",\"targetType\":\"GROUP\",\"target\":\"987654321\",\"content\":7}|content",
            "{\"platform\":\"qq-onebot\",\"targetType\":\"GROUP\",\"target\":\"987654321\",\"content\":\"上{next}下\"}|content",
            "{\"platform\":\"qq-official\",\"targetType\":\"FRIEND\",\"target\":\"987654321\",\"content\":\"x\"}|targetType",
            "[1,2]|JSON",
            "{not json|JSON"})
    void badRequestsNameTheFieldAndSendNothing(String row) throws Exception {
        String[] parts = row.split("\\|");
        String admin = issue(ConfigUiAccount.LOCAL_ID, PushApiProvider.SEND);
        MockHttpServletResponse response = perform(send(admin, parts[0]));
        assertError(response, 400, "bad_request");
        assertTrue(message(response).contains(parts[1]), message(response));
        assertTrue(delivered.isEmpty());
        assertTrue(timeline.isEmpty());
    }

    // 抓的故障：去向名写错时去了别的去向，或回包把请求里的原文照抄回去。
    @Test
    void anUnknownDestinationIsNotFoundAndNotEchoed() throws Exception {
        String admin = issue(ConfigUiAccount.LOCAL_ID, PushApiProvider.SEND);
        MockHttpServletResponse response = perform(send(admin,
                "{\"platform\":\"<b>nope</b>\",\"targetType\":\"GROUP\",\"target\":\"1\",\"content\":\"x\"}"));
        assertError(response, 404, "not_found");
        assertTrue(message(response).contains("platform"));
        assertFalse(response.getContentAsString().contains("nope"));
        assertTrue(delivered.isEmpty());
    }

    // 抓的故障：实例上一个去向也没装时，列表报错，或发送回了个莫名其妙的 500。
    @Test
    void anInstanceWithoutDestinations() throws Exception {
        rebuild(new NovaSenderService(properties));
        String admin = issue(ConfigUiAccount.LOCAL_ID, PushApiProvider.SEND);
        JSONObject data = data(perform(get(API + "platforms").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)));
        assertTrue(data.getJSONArray("items").isEmpty());
        assertError(perform(send(admin, groupMessage("x"))), 404, "not_found");
    }

    // 抓的故障：管理员在控制台停了这个机器人通道，外部程序照样能借它发消息；或去向没连上时回了「已发出」。
    @Test
    void aDisabledOrUnreachableDestinationRefusesAndSendsNothing() throws Exception {
        String admin = issue(ConfigUiAccount.LOCAL_ID, PushApiProvider.SEND);
        blockedPlatform = ONEBOT;
        MockHttpServletResponse disabled = perform(send(admin, groupMessage("停用时")));
        assertError(disabled, 409, "conflict");
        assertTrue(message(disabled).contains("停用"), message(disabled));
        assertTrue(delivered.isEmpty());

        blockedPlatform = null;
        onebotAnswer = params -> {
            throw new IllegalStateException("Connection refused: 127.0.0.1:3000 secret-host-detail");
        };
        MockHttpServletResponse unreachable = perform(send(admin, groupMessage("没连上")));
        assertError(unreachable, 409, "conflict");
        assertTrue(message(unreachable).contains("连不上"), message(unreachable));
        assertFalse(unreachable.getContentAsString().contains("secret-host-detail"));
        assertEquals(List.of("BLOCKED", "UNREACHABLE"), timeline.stream().map(e -> e.detail().get("outcome")).toList());
    }

    // 抓的故障：全局推送开关关着、正处在免打扰时段，脚本明确要发的那一条被悄悄丢了。
    @Test
    void theGlobalSwitchAndQuietHoursDoNotApply() throws Exception {
        properties.getPush().setEnabled(false);
        DateTimeFormatter hhmm = DateTimeFormatter.ofPattern("HH:mm");
        properties.getPush().setQuietStart(LocalTime.now().minusHours(1).format(hhmm));
        properties.getPush().setQuietEnd(LocalTime.now().plusHours(1).format(hhmm));
        assertFalse(new PushGate(properties).allowed(), "前置条件：此刻推送闸门是关着的");
        String admin = issue(ConfigUiAccount.LOCAL_ID, PushApiProvider.SEND);
        assertEquals("SENT", data(perform(send(admin, groupMessage("照发")))).getString("delivery"));
        assertEquals(1, delivered.size());
    }

    // 抓的故障：一把令牌被拿去刷屏，一分钟发了几千条；或者一把令牌超了，别的令牌也跟着发不出去。
    @Test
    void oneTokenSendsAtMostSixHundredAMinute() throws Exception {
        String busy = issue(ConfigUiAccount.LOCAL_ID, PushApiProvider.SEND);
        String other = issue(ConfigUiAccount.LOCAL_ID, PushApiProvider.SEND);
        for (int i = 0; i < PushSendQuota.PER_MINUTE; i++) {
            MockHttpServletResponse ok = perform(send(busy, groupMessage("第 " + i + " 条")));
            assertEquals(200, ok.getStatus(), "第 " + (i + 1) + " 条: " + ok.getContentAsString());
            clock.now = clock.now.plusMillis(50);
        }
        assertEquals(PushSendQuota.PER_MINUTE, delivered.size());

        MockHttpServletResponse limited = perform(send(busy, groupMessage("第 601 条")));
        assertError(limited, 429, "too_many_requests");
        long wait = Long.parseLong(limited.getHeader(HttpHeaders.RETRY_AFTER));
        assertTrue(wait >= 1 && wait <= 60, "Retry-After=" + wait);
        assertEquals(429, perform(send(busy, groupMessage("再来"))).getStatus());
        assertEquals(PushSendQuota.PER_MINUTE, delivered.size(), "超了的那几条没有交出去");
        assertEquals(200, perform(send(other, groupMessage("另一把"))).getStatus(), "另一把令牌不受连累");

        long limits = timeline.stream().filter(e -> "LIMITED".equals(e.detail().get("outcome"))).count();
        assertEquals(1, limits, "被限这件事记一条，不是每一次都记");

        clock.now = clock.now.plusSeconds(wait);
        assertEquals(200, perform(send(busy, groupMessage("过了一会儿"))).getStatus());
    }

    // 抓的故障：时间线上看不出是谁用哪把令牌发的；或者令牌、完整号码、正文全文进了时间线与日志。
    @Test
    void everySendIsRecordedWithoutTheTokenTheNumberOrTheText() throws Exception {
        ConfigUiAccount admin = accounts.role(member("subject-r", "值班管理员").id(), ConfigUiAccount.Role.ADMIN);
        String token = tokens.issue(admin.id(), "告警转发脚本", List.of(PushApiProvider.SEND), null).plaintext();
        String text = "机房温度过高-正文全文-不该被记下";
        assertEquals(200, perform(send(token, groupMessage(text))).getStatus());
        onebotAnswer = params -> JSONObject.of("code", -1, "message", "群 " + GROUP_NUMBER + " 不存在");
        assertEquals(502, perform(send(token, groupMessage(text))).getStatus());
        onebotAnswer = params -> JSONObject.of("code", -1, "message", "等回包超时", Sender.LocalDelivery.DELIVERY_UNKNOWN, true);
        assertEquals(200, perform(send(token, groupMessage(text))).getStatus());

        assertEquals(List.of(TimelineEventType.PUSH_SENT, TimelineEventType.PUSH_FAILED, TimelineEventType.PUSH_UNCERTAIN),
                timeline.stream().map(TimelineEvent::type).toList());
        String tokenId = tokens.match(token).orElseThrow().id();
        for (TimelineEvent event : timeline) {
            assertTrue(event.text().contains("告警转发脚本") && event.text().contains("值班管理员"), event.text());
            assertEquals("告警转发脚本", event.detail().get("token"));
            assertEquals(tokenId, event.detail().get("tokenId"));
            assertEquals(admin.id(), event.detail().get("account"));
            assertEquals(ONEBOT, event.detail().get("platform"));
            assertEquals("GROUP", event.detail().get("targetType"));
            assertEquals("98…21", event.detail().get("target"));
            assertEquals("群 98…21", event.channel());
            assertEquals(String.valueOf(text.codePointCount(0, text.length())), event.detail().get("length"));
            assertNull(event.streamer(), "不挂主播：成员的时间线里看不到");
            String all = event.toString();
            assertFalse(all.contains(token), "令牌值不进记录");
            assertFalse(all.contains(GROUP_NUMBER), "完整号码不进记录: " + all);
            assertFalse(all.contains("正文全文"), "正文不进记录");
        }
        assertEquals("m-1", timeline.get(0).detail().get("id"));
        assertTrue(timeline.get(1).detail().get("reason").contains("98…21"));
        String logged = String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertTrue(logged.contains("告警转发脚本"), "日志里也写明是哪把令牌");
        assertFalse(logged.contains(token), "令牌值不进日志");
        assertFalse(logged.contains("legacy-push-secret"));
    }

    // 抓的故障：超大的请求体被收下、发了出去，或者把服务端拖进了 500。
    @Test
    void anOversizedBodyIsRefusedByTheBase() throws Exception {
        String admin = issue(ConfigUiAccount.LOCAL_ID, PushApiProvider.SEND);
        String huge = "{\"platform\":\"qq-onebot\",\"targetType\":\"GROUP\",\"target\":\"987654321\",\"content\":\""
                + "长".repeat(100_000) + "\"}";
        MockHttpServletResponse response = perform(send(admin, huge));
        assertError(response, 400, "bad_request");
        assertTrue(message(response).contains("太大"), message(response));
        assertTrue(delivered.isEmpty());
    }

    // 抓的故障：没带令牌、令牌被吊销之后，推送接口另有一套判法，照样能发。
    @Test
    void missingOrRevokedTokensFollowTheBase() throws Exception {
        assertEquals(401, perform(post(API + "messages").contentType(MediaType.APPLICATION_JSON)
                .content(groupMessage("x"))).getStatus());
        String admin = issue(ConfigUiAccount.LOCAL_ID, PushApiProvider.SEND);
        tokens.revoke(tokens.match(admin).orElseThrow().id());
        assertError(perform(send(admin, groupMessage("x"))), 401, "unauthenticated");
        assertError(perform(send("nvb_not-a-real-token", groupMessage("x"))), 401, "unauthenticated");
        assertTrue(delivered.isEmpty());
    }

    @Test
    void numbersAreMaskedToTheirEnds() {
        assertEquals("98…21", PushApiProvider.mask(987654321L));
        assertEquals("12…45", PushApiProvider.mask(12345L));
        assertEquals("…", PushApiProvider.mask(1234L));
        assertEquals("…", PushApiProvider.mask(3L));
    }

    private ConfigUiAccount member(String subject, String name) {
        var account = accounts.register(new OidcClient.Identity("https://accounts.example.com/tenant", subject, name, null),
                Instant.now());
        return accounts.status(account.id(), ConfigUiAccount.Status.ENABLED);
    }

    private String issue(String accountId, String... scopes) {
        return tokens.issue(accountId, "推送脚本", List.of(scopes), null).plaintext();
    }

    private static MockHttpServletRequest console(ConfigUiAccount account) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ConfigUiAccess.ACCOUNT, account);
        return request;
    }

    private static String groupMessage(String content) {
        return JSONObject.of("platform", ONEBOT, "targetType", "GROUP", "target", GROUP_NUMBER, "content", content)
                .toJSONString();
    }

    private static MockHttpServletRequestBuilder send(String token, String body) {
        return post(API + "messages").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RequestPostProcessor peer(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(builder).andReturn().getResponse();
    }

    private static TimelineEvent only(List<TimelineEvent> events) {
        assertEquals(1, events.size(), events.toString());
        return events.get(0);
    }

    private static JSONObject data(MockHttpServletResponse response) throws Exception {
        JSONObject json = JSONObject.parseObject(response.getContentAsString());
        assertEquals(true, json.get("ok"), response.getContentAsString());
        return json.getJSONObject("data");
    }

    private static String message(MockHttpServletResponse response) throws Exception {
        return JSONObject.parseObject(response.getContentAsString()).getJSONObject("error").getString("message");
    }

    private static void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        JSONObject json = JSONObject.parseObject(response.getContentAsString());
        assertEquals(false, json.get("ok"), response.getContentAsString());
        assertEquals(code, json.getJSONObject("error").getString("code"), response.getContentAsString());
    }
}
