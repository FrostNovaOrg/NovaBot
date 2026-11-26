package org.frostnova.nova.adapter.outbox;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.ServletContext;
import org.frostnova.nova.adapter.outbox.OutboxRecord.Streamer;
import org.frostnova.nova.core.api.*;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConsoleAllowList;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcClient;
import org.frostnova.nova.core.model.Message.PushKind;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.web.SecurityHeadersFilter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.frostnova.nova.adapter.outbox.OutboxRecord.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class OutboxApiTest {
    private static final String CONTEXT = "/robot";
    private static final String API = CONTEXT + "/nova/api/v1/outbox/";
    private static final String READ = "outbox:read";
    private static final String DELIVER = "outbox:deliver";
    private static final Streamer OWNER = new Streamer("live", 101L, "青山");
    private static final Streamer OTHER = new Streamer("other", 101L, "远山");
    private static final String SENT_BODY = "{\"claimNumber\":1,\"outcome\":\"SENT\"}";

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
        var filter = new NovaApiFilter(new ConsoleAllowList(properties.getConfigUi()), tokens, accounts, () -> true,
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
