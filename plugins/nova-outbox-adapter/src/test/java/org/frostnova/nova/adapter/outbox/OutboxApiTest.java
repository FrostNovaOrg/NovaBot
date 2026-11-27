package org.frostnova.nova.adapter.outbox;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.ServletContext;
import org.frostnova.nova.adapter.outbox.OutboxRecord.Streamer;
import org.frostnova.nova.core.api.*;
import org.frostnova.nova.core.alert.AlertChannel;
import org.frostnova.nova.core.alert.AlertService;
import org.frostnova.nova.core.alert.HealthAlertMonitor;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplier;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcClient;
import org.frostnova.nova.core.model.Message.PushKind;
import org.frostnova.nova.core.health.HealthProbe;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.web.SecurityHeadersFilter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.frostnova.nova.adapter.outbox.OutboxRecord.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class OutboxApiTest {
    private static final String CONTEXT = "/robot";
    private static final String API = CONTEXT + "/nova/api/v1/outbox/";
    private static final String READ = "outbox:read";
    private static final String DELIVER = "outbox:deliver";
    private static final Streamer OWNER = new Streamer("live", 101L, "青山");
    private static final Streamer OTHER = new Streamer("other", 101L, "远山");
    private static final String SENT_BODY = "{\"claimNumber\":1,\"outcome\":\"SENT\"}";
    private static final String PICKUP_ALERT = "novabot.adapter.outbox.pickup-alert-enabled";
    private static final String PICKUP_MINUTES = "novabot.adapter.outbox.pickup-alert-minutes";

    @TempDir Path directory;
    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    private final NovaCoreProperties properties = new NovaCoreProperties();
    private OutboxService service;
    private OutboxConversations conversations;
    private long conversation;
    private ConfigUiAccountStore accounts;
    private NovaApiTokenStore tokens;
    private ConfigUiAccount member;
    private String device;
    private MockMvc mvc;

    @BeforeEach void start() {
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        var servlet = new MockServletContext();
        servlet.setContextPath(CONTEXT);
        context.registerBean(ServletContext.class, () -> servlet);
        context.registerBean(NovaCoreProperties.class, () -> properties);
        context.registerBean(NovaSenderService.class, () -> new NovaSenderService(properties));
        context.registerBean(TimelineWriter.class, () -> event -> {});
        context.register(OutboxPluginAutoConfiguration.class);
        context.refresh();
        service = context.getBean(OutboxService.class);
        conversations = context.getBean(OutboxConversations.class);
        conversation = conversations.create("早班", "日间通知", 0, 0).id();
        var state = new NovaStateStore(properties);
        accounts = new ConfigUiAccountStore(state);
        tokens = new NovaApiTokenStore(state);
        var account = accounts.register(new OidcClient.Identity("https://accounts.example.org", "device-owner",
                "成员", null), Instant.now());
        accounts.status(account.id(), ConfigUiAccount.Status.ENABLED);
        member = accounts.streamers(account.id(), List.of("live:101"));
        device = issue(member.id(), READ, DELIVER);
        useRegistry(new NovaApiRegistry(context.getBeanProvider(NovaApiProvider.class).orderedStream().toList()));
    }

    @AfterEach void close() { context.close(); }

    private void useRegistry(NovaApiRegistry registry) {
        var filter = new NovaApiFilter(tokens, accounts, () -> true,
                registry, new NovaApiThrottle(), () -> List.of("live", "other"), List::of);
        mvc = MockMvcBuilders.standaloneSetup()
                .addFilters(new SecurityHeadersFilter(List.of("/nova")), filter).build();
    }

    private String issue(String account, String... scopes) {
        return tokens.issue(account, "日间设备", List.of(scopes), null).plaintext();
    }

    private OutboxRecord put(Streamer streamer, String text) {
        return service.enqueue(conversation, streamer, PushKind.LIVE_ON, text).get(0);
    }

    private MockHttpServletResponse call(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.contextPath(CONTEXT).header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse read(String path) throws Exception {
        return call(device, get(API + path));
    }

    private MockHttpServletResponse submit(long id, String action, String body) throws Exception {
        return call(device, post(API + "messages/" + id + "/" + action).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static JSONObject data(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus());
        var json = JSONObject.parseObject(response.getContentAsString());
        assertTrue(json.getBooleanValue("ok"));
        return json.getJSONObject("data");
    }

    private static String error(MockHttpServletResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        var json = JSONObject.parseObject(response.getContentAsString());
        assertEquals(false, json.get("ok"));
        assertEquals(code, json.getJSONObject("error").getString("code"));
        return json.getJSONObject("error").getString("message");
    }

    @Test @DisplayName("程序接口的推送只发得了机器人通道，经外发发不进外发箱；或外发被列成也能发好友，发出去才被拒")
    void thePushGroupDeliversIntoAnOutboxConversation() throws Exception {
        var senders = context.getBean(NovaSenderService.class);
        assertEquals(Set.of(org.frostnova.nova.core.enums.PushTargetType.GROUP),
                senders.targetTypes(OutboxPluginAutoConfiguration.PLATFORM));
        var beans = new StaticListableBeanFactory();
        beans.addBean("senders", senders);
        beans.addBean("timeline", (TimelineWriter) event -> {});
        beans.addBean("sender", new org.frostnova.nova.core.sender.NovaMessageSender(
                mock(org.frostnova.nova.core.util.HttpUtil.class), senders,
                new org.frostnova.nova.core.health.PushActivityRecorder(TimelineWriter.NONE),
                new org.frostnova.nova.core.sender.PushGate(properties), TimelineWriter.NONE,
                new org.frostnova.nova.core.service.AtAllQuotaService(properties),
                beans.getBeanProvider(org.frostnova.nova.core.sender.AtAllPermissionResolver.class),
                new org.frostnova.nova.core.sender.FirstPushTipService(new NovaStateStore(properties))));
        var push = new org.frostnova.nova.core.api.push.PushApiProvider(beans.getBeanProvider(NovaSenderService.class),
                beans.getBeanProvider(org.frostnova.nova.core.sender.NovaMessageSender.class),
                beans.getBeanProvider(TimelineWriter.class));
        useRegistry(new NovaApiRegistry(List.of(context.getBean(OutboxApiProvider.class), push)));
        String admin = tokens.issue(ConfigUiAccount.LOCAL_ID, "推送脚本", List.of("push:send", READ), null).plaintext();

        var sent = data(call(admin, post(CONTEXT + "/nova/api/v1/push/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"outbox\",\"targetType\":\"GROUP\",\"target\":\"" + conversation
                        + "\",\"content\":\"接口发来的通知\"}")));
        assertEquals("SENT", sent.getString("delivery"));
        var listed = data(call(admin, get(API + "messages"))).getJSONArray("items");
        assertEquals(1, listed.size());
        assertEquals(sent.getString("id"), listed.getJSONObject(0).getString("id"));
        assertEquals("接口发来的通知", listed.getJSONObject(0).getJSONArray("segments").getJSONObject(0).getString("text"));
        assertTrue(data(call(device, get(API + "messages"))).getJSONArray("items").isEmpty(),
                "不挂主播的通知只有管理员看得见");

        error(call(admin, post(CONTEXT + "/nova/api/v1/push/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"outbox\",\"targetType\":\"FRIEND\",\"target\":\"" + conversation
                        + "\",\"content\":\"发给好友\"}")), 400, "bad_request");
        assertEquals(1, service.listForDevice(PENDING, 0, 10, streamer -> true).size());
    }

    @Test @DisplayName("取件设备停了一晚却没人收到提醒，或通知过期后明明没人来取却报恢复")
    void alertsOnceAndWaitsForPickupAfterExpiry() throws Exception {
        var watch = new PickupWatch();
        assertTrue(watch.settings.applyAndTrack(Map.of(PICKUP_ALERT, "true")).isEmpty());
        put(OWNER, "私有通知正文");
        watch.elapse(4);
        assertTrue(watch.mail.messages.isEmpty());
        watch.elapse(1);
        assertEquals(1, watch.mail.messages.size());
        var alert = watch.mail.messages.get(0);
        for (String word : List.of("1 条", "5 分钟", "早班", "启动后还没有来取过", "外发箱")) {
            assertTrue(alert.contains(word), alert);
        }
        assertFalse(alert.contains("私有通知正文"));
        assertFalse(alert.contains(device));
        assertEquals(HealthStatus.Level.DEGRADED, watch.probe().checkForConsole().level());
        assertEquals(true, watch.summary().get("pickupAlert"));

        put(OWNER, "又一条通知");
        watch.elapse(15);
        assertTrue(service.list(PENDING, 0, 10, streamer -> true).isEmpty());
        assertEquals(1, watch.mail.messages.size());
        assertEquals(HealthStatus.Level.DEGRADED, watch.probe().checkForConsole().level());
        assertEquals(true, watch.summary().get("pickupAlert"));

        assertTrue(data(read("messages")).getJSONArray("items").isEmpty());
        watch.monitor.check();
        watch.monitor.check();
        assertEquals(2, watch.mail.messages.size());
        assertTrue(watch.mail.messages.get(1).contains("恢复"));
        assertEquals(HealthStatus.Level.OK, watch.probe().checkForConsole().level());
        assertEquals(false, watch.summary().get("pickupAlert"));
        assertEquals(List.of("DEGRADED", "OK"), watch.events.stream()
                .filter(event -> event.type() == TimelineEventType.PROBE_CHANGED)
                .map(event -> event.detail().get("to")).toList());
    }

    @Test @DisplayName("管理员刚把通知重新放回待发，取件设备明明在线却收到久未取走的告警")
    void waitsFromWhenNoticeWasRequeued() throws Exception {
        var watch = new PickupWatch();
        watch.settings.applyAndTrack(Map.of(PICKUP_ALERT, "true"));
        long id = put(OWNER, "早班通知").id();
        watch.elapse(1);
        long claimNumber = data(submit(id, "claim", "")).getLongValue("claimNumber");
        assertEquals("FAILED", data(submit(id, "receipt", JSONObject.of("claimNumber", claimNumber,
                "outcome", "FAILED").toJSONString())).getString("status"));
        watch.elapse(6);

        assertEquals(true, new OutboxRecordController(service).requeue(id).get("success"));
        assertEquals(id, data(read("messages")).getJSONArray("items").getJSONObject(0).getLongValue("id"));
        watch.monitor.check();
        assertTrue(watch.mail.messages.isEmpty(), watch.mail.messages::toString);
        assertEquals(false, watch.summary().get("pickupAlert"));

        watch.elapse(4);
        assertTrue(watch.mail.messages.isEmpty(), watch.mail.messages::toString);
        watch.elapse(1);
        assertEquals(1, watch.mail.messages.size());
        var alert = watch.mail.messages.get(0);
        assertTrue(alert.contains("已等 5 分钟"), alert);
        assertEquals(true, watch.summary().get("pickupAlert"));
    }

    @Test @DisplayName("还没开告警就收到通知，或把门槛改成两分钟后仍按原来的时长等")
    void appliesSettingsImmediatelyAndSilencesWhenDisabled() {
        var watch = new PickupWatch();
        put(OWNER, "早班通知");
        watch.elapse(3);
        assertTrue(watch.mail.messages.isEmpty());
        assertTrue(watch.probes().stream().noneMatch(HealthProbe::active));
        assertFalse(watch.settings.validateValues(Map.of(PICKUP_MINUTES, "0")).isEmpty());
        assertFalse(watch.settings.validateValues(Map.of(PICKUP_MINUTES, "61")).isEmpty());
        assertFalse(watch.settings.validateValues(Map.of(PICKUP_MINUTES, "两分钟")).isEmpty());
        assertFalse(watch.settings.validateValues(Map.of(PICKUP_ALERT, "稍后")).isEmpty());
        assertTrue(watch.settings.validateValues(Map.of(PICKUP_MINUTES, "60")).isEmpty());
        watch.settings.applyAndTrack(Map.of(PICKUP_ALERT, "true"));
        watch.monitor.check();
        assertTrue(watch.mail.messages.isEmpty());
        assertTrue(watch.settings.applyAndTrack(Map.of(PICKUP_MINUTES, "2")).isEmpty());
        assertEquals(true, watch.summary().get("pickupAlert"));
        watch.monitor.check();
        assertEquals(1, watch.mail.messages.size());
        watch.settings.applyAndTrack(Map.of(PICKUP_ALERT, "false"));
        assertFalse(watch.probe().active());
        assertEquals(false, watch.summary().get("pickupAlert"));
        // 关闭、重新开启发生在同一轮检查间隔内。
        watch.settings.applyAndTrack(Map.of(PICKUP_ALERT, "true"));
        watch.monitor.check();
        assertEquals(2, watch.mail.messages.size());
        assertTrue(watch.mail.messages.get(1).contains("异常"));
        watch.settings.applyAndTrack(Map.of(PICKUP_ALERT, "false"));
        watch.elapse(2);
        assertEquals(2, watch.mail.messages.size());
    }

    @Test @DisplayName("设备刚回来列待发就报恢复，但排队很久的通知其实还没取完")
    void recoversWhenOldPendingMessagesAreClaimed() throws Exception {
        var watch = new PickupWatch();
        watch.settings.applyAndTrack(Map.of(PICKUP_ALERT, "true"));
        long first = put(OWNER, "先到的").id();
        watch.elapse(4);
        data(submit(first, "claim", ""));
        long second = put(OWNER, "后到的").id();
        long third = put(OWNER, "也在等").id();
        watch.elapse(1);
        assertTrue(watch.mail.messages.isEmpty());
        watch.elapse(4);
        assertEquals(1, watch.mail.messages.size());
        assertTrue(watch.mail.messages.get(0).contains("2 条"));
        assertTrue(watch.mail.messages.get(0).contains("最近一次取件"));

        data(read("messages"));
        data(submit(second, "claim", ""));
        watch.monitor.check();
        assertEquals(1, watch.mail.messages.size());
        data(submit(third, "claim", ""));
        watch.monitor.check();
        assertEquals(2, watch.mail.messages.size());
        assertTrue(watch.mail.messages.get(1).contains("恢复"));
    }

    @Test @DisplayName("普通查询冒充取件，或列空箱和认领没有更新控制台的最近取件时刻")
    void onlyPickupRequestsUpdateTheTimestamp() throws Exception {
        var watch = new PickupWatch();
        long id = put(OWNER, "早班通知").id();
        data(read("messages/" + id));
        data(read("messages?status=SENT"));
        data(read("conversations"));
        error(submit(Long.MAX_VALUE, "claim", ""), 404, "not_found");
        assertNull(watch.summary().get("lastPickupAt"));
        data(read("messages"));
        assertEquals(watch.now.toString(), watch.summary().get("lastPickupAt"));
        watch.elapse(1);
        data(submit(id, "claim", ""));
        assertEquals(watch.now.toString(), watch.summary().get("lastPickupAt"));
        watch.elapse(1);
        data(submit(id, "receipt", SENT_BODY));
        assertNotEquals(watch.now.toString(), watch.summary().get("lastPickupAt"));
        assertTrue(data(read("messages?status=PENDING")).getJSONArray("items").isEmpty());
        assertEquals(watch.now.toString(), watch.summary().get("lastPickupAt"));
    }

    @Test @DisplayName("外发会话停用或限量后，还收到这条通知等待过久的告警")
    void ignoresDisabledConversationsAndLimitedRecords() {
        var watch = new PickupWatch();
        watch.settings.applyAndTrack(Map.of(PICKUP_ALERT, "true"));
        var limited = conversations.create("限量会话", "", 0, 1);
        long claimed = service.enqueue(limited.id(), OWNER, PushKind.LIVE_ON, "一条入箱").get(0).id();
        service.claim(claimed, "设备");
        assertEquals(LIMITED, service.enqueue(limited.id(), OWNER, PushKind.LIVE_ON, "不入箱").get(0).status());
        put(OWNER, "停用前的通知");
        conversations.disable(conversation);
        watch.elapse(5);
        assertEquals(1L, ((Map<?, ?>) watch.summary().get("counts")).get(PENDING));
        assertEquals(false, watch.summary().get("pickupAlert"));
        assertTrue(watch.mail.messages.isEmpty());
    }

    @Test @DisplayName("告警落进没人取的外发会话，或跳过这个收件人时其他通道也没收到")
    void skipsOnlyTheAlertRouteBackToTheOutbox() throws Exception {
        var watch = new PickupWatch();
        watch.qq.platform = "outbox";
        watch.settings.applyAndTrack(Map.of(PICKUP_ALERT, "true"));
        put(OWNER, "早班通知");
        watch.elapse(5);
        assertEquals(1, watch.mail.messages.size());
        assertTrue(watch.qq.messages.isEmpty());
        data(read("messages"));
        service.voidPending(service.list(PENDING, 0, 1, streamer -> true).get(0).id());
        watch.monitor.check();
        assertEquals(2, watch.mail.messages.size());
        assertTrue(watch.qq.messages.isEmpty());
        watch.qq.platform = "qq";
        put(OWNER, "午班通知");
        watch.elapse(5);
        assertEquals(1, watch.qq.messages.size());
        assertEquals(3, watch.mail.messages.size());
    }

    @Test @DisplayName("重启后漏掉之前积压的待发，或没有告警收件人时首页和时间线也看不出故障")
    void restartStillDetectsPersistedBacklogWithoutConfiguredRecipients() throws Exception {
        var watch = new PickupWatch();
        put(OWNER, "上一次运行时入箱");
        data(read("messages"));
        watch.elapse(9);
        var restart = new AnnotationConfigApplicationContext();
        try (restart) {
            restart.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "outbox", Map.of(PICKUP_ALERT, "true")));
            restart.registerBean(NovaCoreProperties.class, () -> properties);
            restart.registerBean(NovaSenderService.class, () -> new NovaSenderService(properties));
            restart.registerBean(TimelineWriter.class, () -> watch.events::add);
            restart.register(OutboxPluginAutoConfiguration.class);
            restart.refresh();
            var restartedService = restart.getBean(OutboxService.class);
            ReflectionTestUtils.setField(restartedService, "clock", watch.clock);
            restartedService.maintain();
            // 待发在启动时已等候过久，第一次定时告警检查前刚好过期。
            watch.now = watch.now.plus(Duration.ofMinutes(2));
            restartedService.maintain();
            var noChannels = new StaticListableBeanFactory().getBeanProvider(AlertChannel.class);
            new HealthAlertMonitor(restart.getBeanProvider(HealthProbe.class),
                    new AlertService(properties, noChannels, watch.events::add), watch.events::add).check();
            var restartedProbe = restart.getBean(HealthProbe.class);
            assertEquals(HealthStatus.Level.DEGRADED, restartedProbe.checkForConsole().level());
            assertTrue(restartedProbe.checkForConsole().summary().contains("启动后还没有来取过"));
            assertNull(new OutboxRecordController(restartedService).summary().get("lastPickupAt"));
            assertTrue(watch.events.stream().anyMatch(event -> event.type() == TimelineEventType.PROBE_CHANGED));
        }
    }

    private final class PickupWatch {
        private Instant now = Instant.parse("2026-01-12T10:00:00Z");
        private final Clock clock = mock(Clock.class);
        private final List<TimelineEvent> events = new ArrayList<>();
        private final CaptureChannel mail = new CaptureChannel("mail");
        private final CaptureChannel qq = new CaptureChannel("qq");
        private final RuntimeConfigurationApplier settings = new RuntimeConfigurationApplier(properties,
                context.getBeanProvider(RuntimeConfigurationApplierContributor.class), TimelineWriter.NONE);
        private final HealthAlertMonitor monitor;

        private PickupWatch() {
            when(clock.getZone()).thenReturn(ZoneOffset.UTC);
            when(clock.instant()).thenAnswer(call -> now);
            when(clock.millis()).thenAnswer(call -> now.toEpochMilli());
            ReflectionTestUtils.setField(service, "clock", clock);
            var channels = new StaticListableBeanFactory(Map.of("mail", mail, "qq", qq));
            monitor = new HealthAlertMonitor(context.getBeanProvider(HealthProbe.class),
                    new AlertService(properties, channels.getBeanProvider(AlertChannel.class), events::add), events::add);
        }

        private List<HealthProbe> probes() { return context.getBeanProvider(HealthProbe.class).orderedStream().toList(); }
        private HealthProbe probe() { return context.getBean(HealthProbe.class); }
        private Map<String, Object> summary() { return new OutboxRecordController(service).summary(); }

        private void elapse(int minutes) {
            now = now.plus(Duration.ofMinutes(minutes));
            service.maintain();
            monitor.check();
        }
    }

    private static final class CaptureChannel implements AlertChannel {
        private final String id;
        private final List<String> messages = new ArrayList<>();
        private String platform;

        private CaptureChannel(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public String name() { return id; }
        @Override public boolean isAvailable() { return true; }
        @Override public void send(String subject, String content) { messages.add(subject + "\n" + content); }
        @Override public boolean routesTo(String target) { return target.equals(platform); }
    }

    @Test @DisplayName("取件设备无法按通知段顺序取回完整大图，或停用会话后积压通知发不出去")
    void pickUpAndReportAfterConversationDisabled() throws Exception {
        var pixels = new BufferedImage(2048, 1366, BufferedImage.TYPE_3BYTE_BGR);
        new Random(43).nextBytes(((DataBufferByte) pixels.getRaster().getDataBuffer()).getData());
        var encoded = new ByteArrayOutputStream();
        ImageIO.write(pixels, "png", encoded);
        byte[] image = encoded.toByteArray();
        assertTrue(image.length >= 8 * 1024 * 1024);
        var record = put(OWNER, "{at=all}开播{image_base64=" + Base64.getEncoder().encodeToString(image)
                + "}{image_url=https://media.example.org/p.png}");
        conversations.disable(conversation);
        var directoryPage = data(read("conversations"));
        assertTrue(directoryPage.containsKey("next"));
        assertNull(directoryPage.get("next"));
        var destination = directoryPage.getJSONArray("items").getJSONObject(0);
        assertEquals(conversation, destination.getLongValue("id"));
        assertEquals("早班", destination.getString("name"));
        assertEquals("日间通知", destination.getString("remark"));
        assertEquals(false, destination.get("enabled"));

        var message = data(read("messages")).getJSONArray("items").getJSONObject(0);
        assertEquals(message, data(read("messages/" + record.id())));
        assertEquals(record.id(), message.getLongValue("id"));
        assertEquals("PENDING", message.getString("status"));
        assertEquals(record.createdAt().toString(), message.getString("createdAt"));
        assertEquals(record.expiresAt().toString(), message.getString("expiresAt"));
        assertEquals(conversation, message.getJSONObject("conversation").getLongValue("id"));
        assertEquals("早班", message.getJSONObject("conversation").getString("name"));
        assertEquals(JSONObject.of("platform", "live", "uid", 101, "name", "青山"), message.getJSONObject("streamer"));
        assertEquals("LIVE_ON", message.getString("kind"));
        assertTrue(message.getBooleanValue("atAll"));
        var segments = message.getJSONArray("segments");
        assertEquals(3, segments.size());
        assertEquals(JSONObject.of("type", "TEXT", "text", "开播"), segments.getJSONObject(0));
        assertEquals(JSONObject.of("type", "IMAGE_URL", "url", "https://media.example.org/p.png"), segments.getJSONObject(2));
        var picture = segments.getJSONObject(1);
        assertEquals("IMAGE_BYTES", picture.getString("type"));
        assertEquals(1, picture.getIntValue("index"));
        assertEquals(image.length, picture.getLongValue("byteCount"));

        var claim = data(submit(record.id(), "claim", ""));
        long claimNumber = claim.getLongValue("claimNumber");
        assertTrue(claimNumber > 0);
        assertEquals("SENDING", claim.getJSONObject("message").getString("status"));
        assertEquals("日间设备", service.list(SENDING, 0, 1, OWNER::equals).get(0).claimant());
        var response = call(device, get(picture.getString("url")));
        assertEquals(200, response.getStatus());
        assertEquals("image/png", response.getContentType());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertArrayEquals(image, response.getContentAsByteArray());
        for (String index : List.of("0", "2", "3", "-1", "2147483648")) {
            error(read("messages/" + record.id() + "/images/" + index), 404, "not_found");
        }
        var result = data(submit(record.id(), "receipt", JSONObject.of("claimNumber", claimNumber,
                "outcome", "SENT", "note", "送达").toJSONString()));
        assertEquals(record.id(), result.getLongValue("id"));
        assertEquals("SENT", result.getString("status"));
        assertTrue(data(read("messages")).getJSONArray("items").isEmpty());
        assertEquals(record.id(), data(read("messages?status=SENT")).getJSONArray("items").getJSONObject(0).getLongValue("id"));
    }

    @Test @DisplayName("一张图片文件丢了，所有通知都取不到了")
    void missingImageStillAllowsPickup() throws Exception {
        long id = put(OWNER, "开播{image_base64=AQID}").id();
        long otherId = put(OWNER, "稍后开播").id();
        var images = context.getBean(OutboxDatabase.class).images;
        Files.move(images.resolve(id + "-1.bin"), directory.resolve("moved.bin"));

        var items = data(read("messages")).getJSONArray("items").toList(JSONObject.class);
        assertEquals(List.of(id, otherId), items.stream().map(item -> item.getLong("id")).toList());
        var message = items.get(0);
        var picture = message.getJSONArray("segments").getJSONObject(1);
        assertEquals("IMAGE_BYTES", picture.getString("type"));
        assertEquals(1, picture.get("index"));
        assertTrue(picture.containsKey("byteCount"));
        assertNull(picture.get("byteCount"));
        assertEquals(true, picture.get("unavailable"));
        assertEquals(message, data(read("messages/" + id)));

        var claim = data(submit(id, "claim", ""));
        long claimNumber = claim.getLongValue("claimNumber");
        assertTrue(claimNumber > 0);
        assertEquals(message.getJSONArray("segments"), claim.getJSONObject("message").getJSONArray("segments"));
        var missing = call(device, get(picture.getString("url")));
        assertEquals(error(read("messages/" + id + "/images/0"), 404, "not_found"),
                error(missing, 404, "not_found"));
        assertEquals("FAILED", data(submit(id, "receipt", JSONObject.of("claimNumber", claimNumber,
                "outcome", "FAILED").toJSONString())).getString("status"));
    }

    @Test @DisplayName("成员能按编号读取、认领或修改别人的通知，或从错误信息判断通知存在")
    void memberCannotReadOrChangeHiddenMessages() throws Exception {
        var hidden = put(OTHER, "{image_base64=AQID}");
        var unassigned = put(new Streamer(null, null, null), "无主播");
        assertTrue(data(read("messages")).getJSONArray("items").isEmpty());
        long missing = 999;
        for (long id : List.of(hidden.id(), unassigned.id())) {
            for (String suffix : List.of("", "/images/0")) {
                var absent = read("messages/" + missing + suffix);
                error(absent, 404, "not_found");
                var refused = read("messages/" + id + suffix);
                error(refused, 404, "not_found");
                assertEquals(absent.getContentAsString(), refused.getContentAsString());
            }
            for (String action : List.of("claim", "receipt")) {
                var absent = submit(missing, action, SENT_BODY);
                error(absent, 404, "not_found");
                var refused = submit(id, action, SENT_BODY);
                error(refused, 404, "not_found");
                assertEquals(absent.getContentAsString(), refused.getContentAsString());
            }
        }
        assertEquals(2, service.list(PENDING, 0, 2, streamer -> true).size());
        service.claim(hidden.id(), "别的设备");
        error(submit(hidden.id(), "receipt", SENT_BODY), 404, "not_found");
        assertEquals(hidden.id(), service.list(SENDING, 0, 1, OTHER::equals).get(0).id());
        device = issue(ConfigUiAccount.LOCAL_ID, READ);
        assertEquals(unassigned.id(), data(read("messages?status=PENDING")).getJSONArray("items").getJSONObject(0).getLongValue("id"));
        assertEquals(hidden.id(), data(read("messages/" + hidden.id())).getLongValue("id"));
    }

    @Test @DisplayName("只读令牌把通知标成已发，或令牌能执行控制台的管理动作")
    void scopesAndAdministrativeActions() throws Exception {
        long id = put(OWNER, "{image_base64=AQID}").id();
        String path = "messages/" + id;
        device = issue(member.id(), READ);
        for (String action : List.of("claim", "receipt")) error(submit(id, action, SENT_BODY), 403, "insufficient_scope");
        device = issue(member.id(), DELIVER);
        for (String endpoint : List.of("messages", path, path + "/images/0", "conversations")) {
            error(read(endpoint), 403, "insufficient_scope");
        }
        assertEquals("SENDING", data(submit(id, "claim", "")).getJSONObject("message").getString("status"));
        device = issue(ConfigUiAccount.LOCAL_ID, READ, DELIVER);
        for (String action : List.of("requeue", "void")) error(submit(id, action, ""), 404, "not_found");
    }

    @Test @DisplayName("账号停用或令牌吊销后仍能领取待发通知")
    void revokedOrDisabledIsUnauthenticated() throws Exception {
        long id = put(OWNER, "通知").id();
        assertEquals(200, read("messages").getStatus());
        tokens.revoke(tokens.match(device).orElseThrow().id());
        error(submit(id, "claim", ""), 401, "unauthenticated");
        device = issue(member.id(), READ, DELIVER);
        accounts.status(member.id(), ConfigUiAccount.Status.DISABLED);
        error(submit(id, "claim", ""), 401, "unauthenticated");
        assertEquals(id, service.list(PENDING, 0, 1, OWNER::equals).get(0).id());
    }

    @Test @DisplayName("两台设备都能认领同一条通知，或旧设备的迟报覆盖了新认领")
    void claimsCompeteAndLateResultsCannotOverwrite() throws Exception {
        long id = put(OWNER, "通知").id();
        assertTrue(error(submit(id, "receipt", "{\"claimNumber\":0,\"outcome\":\"SENT\"}"),
                409, "conflict").contains("待发"));
        var pool = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        try {
            var claims = new ArrayList<Callable<MockHttpServletResponse>>();
            String secondDevice = tokens.issue(member.id(), "晚班设备", List.of(DELIVER), null).plaintext();
            for (String token : List.of(device, secondDevice)) {
                claims.add(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return call(token, post(API + "messages/" + id + "/claim"));
                });
            }
            var responses = pool.invokeAll(claims, 30, TimeUnit.SECONDS);
            var first = responses.get(0).get();
            var second = responses.get(1).get();
            assertEquals(Set.of(200, 409), Set.of(first.getStatus(), second.getStatus()));
            assertTrue(error(first.getStatus() == 409 ? first : second, 409, "conflict").contains("发送中"));
        } finally { pool.shutdownNow(); }
        assertEquals("FAILED", data(submit(id, "receipt", "{\"claimNumber\":1,\"outcome\":\"FAILED\"}")).getString("status"));
        service.requeue(id);
        assertEquals(2, data(submit(id, "claim", "")).getLongValue("claimNumber"));
        error(submit(id, "receipt", SENT_BODY), 409, "conflict");
        assertEquals("SENDING", data(read("messages/" + id)).getString("status"));
        assertEquals("SKIPPED", data(submit(id, "receipt", "{\"claimNumber\":2,\"outcome\":\"SKIPPED\"}")).getString("status"));
    }

    @Test @DisplayName("通知已经发出，仅因回报晚了就一直显示结果不明")
    void acceptsReceiptAfterClaimTimeout() throws Exception {
        var earlier = new OutboxService(context.getBean(OutboxDatabase.class), new OutboxSettings(),
                Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-16)), event -> {});
        long id = earlier.enqueue(conversation, OWNER, PushKind.DYNAMIC, "动态通知").get(0).id();
        long claimNumber = earlier.claim(id, "日间设备").claimNumber();

        assertEquals("UNKNOWN", data(read("messages/" + id)).getString("status"));
        String receipt = JSONObject.of("claimNumber", claimNumber, "outcome", "SENT", "note", "已送达").toJSONString();
        assertEquals("SENT", data(submit(id, "receipt", receipt)).getString("status"));
        assertEquals("SENT", data(read("messages/" + id)).getString("status"));
        assertEquals("已送达", service.get(id, OWNER::equals).note());
        error(submit(id, "receipt", receipt), 409, "conflict");
    }

    @Test @DisplayName("设备重新领走已发、过期或作废的通知")
    void closedMessagesCannotBeClaimed() throws Exception {
        var past = new OutboxService(context.getBean(OutboxDatabase.class), new OutboxSettings(),
                Clock.offset(Clock.systemUTC(), Duration.ofHours(-2)), event -> {});
        long expired = past.enqueue(conversation, OWNER, PushKind.LIVE_ON, "昨天的通知").get(0).id();
        assertTrue(error(submit(expired, "claim", ""), 409, "conflict").contains("过期"));
        long cancelled = service.voidPending(put(OWNER, "取消").id()).id();
        assertTrue(error(submit(cancelled, "claim", ""), 409, "conflict").contains("作废"));
        long sent = put(OWNER, "送达").id();
        service.receipt(sent, service.claim(sent, "别的设备").claimNumber(), SENT, null);
        assertTrue(error(submit(sent, "claim", ""), 409, "conflict").contains("已发"));
    }

    @Test @DisplayName("设备提交了无效结果、过长说明或损坏的正文，却改变了外发状态")
    void validatesReceiptBeforeChangingState() throws Exception {
        long id = service.claim(put(OWNER, "通知").id(), "设备").id();
        String note = "🌻".repeat(200);
        for (String malformed : List.of("broken", "[1]", "{}", "{\"outcome\":\"SENT\"}",
                "{\"claimNumber\":1.5,\"outcome\":\"SENT\"}", "{\"claimNumber\":1,\"outcome\":\"PENDING\"}",
                "{\"claimNumber\":1,\"outcome\":\"SENT\",\"note\":123}",
                JSONObject.of("claimNumber", 1, "outcome", "SENT", "note", note + "长").toJSONString())) {
            error(submit(id, "receipt", malformed), 400, "bad_request");
        }
        assertEquals("SENDING", data(read("messages/" + id)).getString("status"));
        assertEquals("SENT", data(submit(id, "receipt", JSONObject.of("claimNumber", 1, "outcome", "SENT",
                "note", note).toJSONString())).getString("status"));
        assertEquals(note, service.list(SENT, 0, 1, OWNER::equals).get(0).note());
    }

    @Test @DisplayName("翻页时有新通知入箱，设备漏取或重复取走同一条")
    void cursorAdvancesAcrossNewAndHiddenMessages() throws Exception {
        long first = put(OWNER, "第一条").id();
        put(OTHER, "别人的通知");
        long second = put(OWNER, "第二条").id();
        var page = data(read("messages?limit=1"));
        assertEquals(first, page.getJSONArray("items").getJSONObject(0).getLongValue("id"));
        assertEquals(Long.toString(first), page.get("next"));
        long third = put(OWNER, "第三条").id();
        var last = data(read("messages?cursor=" + page.getString("next") + "&limit=2"));
        assertEquals(List.of(second, third), last.getJSONArray("items").toList(JSONObject.class).stream()
                .map(row -> row.getLong("id")).toList());
        assertTrue(last.containsKey("next"));
        assertNull(last.get("next"));
    }

    @Test @DisplayName("页长或游标写错时设备收到错误的一页，或一次取走了过多记录")
    void boundsAndDefaultsForPolling() throws Exception {
        for (String query : List.of("limit=0", "limit=-1", "limit=101", "limit=no", "limit=1.5",
                "cursor=-1", "cursor=no", "status=invalid")) {
            error(read("messages?" + query), 400, "bad_request");
        }
        service.enqueue(conversation, OWNER, PushKind.LIVE_ON, ("一{next}").repeat(21));
        assertEquals(20, data(read("messages")).getJSONArray("items").size());
        assertEquals(21, data(read("messages?limit=100")).getJSONArray("items").size());
    }

    @Test @DisplayName("卸掉外发箱后签发页仍能勾外发范围，设备也还能访问旧地址")
    void routesAndScopesBelongToThePlugin() throws Exception {
        var installed = new NovaApiRegistry(context.getBeanProvider(NovaApiProvider.class).orderedStream().toList());
        assertEquals(List.of(DELIVER, READ), installed.scopes().stream().map(NovaApiScope::id).toList());
        var absent = new NovaApiRegistry(List.of());
        assertTrue(absent.scopes().isEmpty());
        useRegistry(absent);
        for (String endpoint : List.of("messages", "messages/1", "messages/1/images/0", "conversations"))
            error(read(endpoint), 404, "not_found");
        for (String action : List.of("claim", "receipt")) error(submit(1, action, ""), 404, "not_found");
        assertEquals(200, call(device, get(CONTEXT + "/nova/api/v1/me")).getStatus());
    }
}
