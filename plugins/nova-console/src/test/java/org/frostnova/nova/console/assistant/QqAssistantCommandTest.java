package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.assistant.AssistantClient;
import org.frostnova.nova.core.assistant.AssistantChartRenderer;
import org.frostnova.nova.core.assistant.AssistantKeyStore;
import org.frostnova.nova.core.assistant.AssistantLocalStore;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.assistant.AssistantUsageStore;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandDispatcher;
import org.frostnova.nova.core.command.CommandFollowUp;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.command.NovaCommand;
import org.frostnova.nova.core.command.builtin.DisableCommand;
import org.frostnova.nova.core.command.builtin.EnableCommand;
import org.frostnova.nova.core.command.builtin.MenuCommand;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.event.remote.NovaRemoteMessageEvent;
import org.frostnova.nova.core.model.DanmuRecord;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.StreamerNames;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.report.analytics.BilibiliLiveMetricCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.frostnova.nova.core.enums.PushTargetType.FRIEND;
import static org.frostnova.nova.core.enums.PushTargetType.GROUP;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QqAssistantCommandTest {
    private static final String QQ = "qq-test";
    private static final String BILIBILI = "bilibili";
    private static final long ROOM = 30003L;
    private static final long ASKER = 2000000002L;
    private static final long OTHER = 2000000003L;
    private static final long FIRST = 10001L;
    private static final long SECOND = 10002L;
    private static final long START = 1794000000000L;
    private static final String LABEL = "（由人工智能生成）";

    @TempDir Path directory;
    private final List<PushUser> users = new ArrayList<>();
    private final MutableClock clock = new MutableClock();
    private final BlockingQueue<Message> replies = new LinkedBlockingQueue<>();
    private final List<JSONObject> requests = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<JSONObject> modelReplies = new ConcurrentLinkedQueue<>();
    private Runnable modelWait = () -> { };
    private AssistantProperties properties;
    private AssistantClient client;
    private AssistantLocalStore local;
    private AssistantAskService service;
    private AssistantChartRenderer chartRenderer;
    private AssistantQueryService queries;
    private LiveSessionArchive archive;
    private LiveDetailArchive details;
    private QqAssistantCommand command;
    private CommandDispatcher dispatcher;
    private CommandSettingsService settings;
    private RestTemplate template;

    @BeforeEach
    void setUp() {
        var core = new NovaCoreProperties();
        core.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        settings = new CommandSettingsService(new NovaStateStore(core));
        var data = mock(AbstractDataSource.class);
        when(data.getAllUsers()).thenReturn(users);
        when(data.getUsers(BILIBILI)).thenReturn(users);
        when(data.getUser(eq(BILIBILI), anyLong())).thenAnswer(invocation -> users.stream()
                .filter(user -> user.getUid().equals(invocation.getArgument(1))).findFirst());
        var sender = mock(NovaMessageSender.class);
        doAnswer(invocation -> replies.add(invocation.getArgument(0))).when(sender).send(any());

        properties = new AssistantProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://model.example/v1");
        template = new RestTemplate();
        MockRestServiceServer.bindTo(template).build().expect(ExpectedCount.manyTimes(), anything()).andRespond(request -> {
            requests.add(JSON.parseObject(((MockClientHttpRequest) request).getBodyAsString()));
            modelWait.run();
            JSONObject reply = modelReplies.poll();
            return withSuccess(JSONObject.of("choices", List.of(JSONObject.of("message",
                    reply == null ? text("没有这份数据") : reply)), "usage",
                    JSONObject.of("prompt_tokens", 100, "completion_tokens", 10)).toJSONString(),
                    MediaType.APPLICATION_JSON).createResponse(request);
        });
        client = newClient();
        client.validateAndSaveKey("operator", "test-model-secret");
        client.acknowledge("operator");
        requests.clear();

        var catalogs = provider(List.<LiveMetricCatalog>of(new BilibiliLiveMetricCatalog()));
        archive = new LiveSessionArchive(core);
        details = new LiveDetailArchive(core);
        queries = spy(new AssistantQueryService(null, archive, details,
                mock(LiveDataService.class), core, catalogs, clock));
        local = new AssistantLocalStore(directory, clock);
        chartRenderer = mock(AssistantChartRenderer.class);
        service = new AssistantAskService(queries, client, properties, local, clock, () -> chartRenderer);
        command = new QqAssistantCommand(service, client, local, data, StreamerNames.none(),
                new RevenueVisibilityService(new NovaStateStore(core)), settings, sender, clock);
        var dispatcherProvider = mockDispatcherProvider();
        dispatcher = new CommandDispatcher(provider(List.of(command, new MenuCommand(dispatcherProvider, settings),
                new EnableCommand(dispatcherProvider, settings), new DisableCommand(dispatcherProvider, settings))),
                provider(List.<CommandFollowUp>of(command)), settings, data, sender, core, TimelineWriter.NONE, clock);
        when(dispatcherProvider.getIfAvailable()).thenReturn(dispatcher);
    }

    @AfterEach
    void close() {
        if (command != null) command.close();
        if (service != null) service.close();
    }

    @Test
    @DisplayName("旧群通道未开问答，@ 机器人也不能触发模型")
    void groupChannelDefaultsOff() throws Exception {
        add(FIRST, "测试主播甲", channel(GROUP, ROOM, null, null));
        group(ASKER, "问 这个月心动盲盒多少个");
        assertReply("还没开智能助手问答", "推送页");
        assertTrue(requests.isEmpty());
    }

    @Test
    @DisplayName("空问应直接给三条例子，不能选择主播、请求模型或扣次数")
    void emptyCommandsGiveExamplesWithoutUsingQuota() throws Exception {
        add(FIRST, "测试主播甲", channel(GROUP, ROOM, true, false));
        add(SECOND, "测试主播乙", channel(GROUP, ROOM, true, false));
        for (String commandName : List.of("问", "问数")) {
            group(ASKER, commandName);
            assertReply("1–300 字", "问 这个月心动盲盒收了多少个", "问 最近 30 天各钟点平均在线", "问 上周送礼榜前十");
        }
        assertTrue(requests.isEmpty());
        assertTrue(qqCounts().isEmpty());
    }

    @Test
    @DisplayName("群里的问数经过通用汇总查到真实夹具的盲盒个数，再发回原群")
    void answersMonthlyBoxCountFromArchivedEvents() throws Exception {
        var target = channel(GROUP, ROOM, true, false);
        add(FIRST, "测试主播甲", target);
        archive.append(new LiveSession(BILIBILI, FIRST, "测试主播甲", 1L, START, START + 10000, 10,
                Map.of("box_count", 12d), Map.of()));
        details.appendEvent(BILIBILI, FIRST, START, START + 1, "box",
                Map.of("bn", "心动盲盒", "n", 7, "val", 129.50));
        details.appendEvent(BILIBILI, FIRST, START, START + 2, "box",
                Map.of("bn", "心动盲盒", "n", 5, "val", 21.50));
        modelReplies.add(call("aggregate", JSONObject.of("kinds", List.of("box"), "from", "2026-11-01", "to", "2026-11-16",
                "metrics", List.of("sum:quantity"),
                "filters", List.of(JSONObject.of("field", "name", "op", "eq", "value", "心动盲盒")))));
        modelWait = () -> {
            if (requests.size() != 2) return;
            JSONObject data = toolResults(requests.get(1)).get("aggregate");
            modelReplies.add(text("这个月心动盲盒 " + data.getJSONArray("totals").getIntValue(0)
                    + " 个（" + data.getString("source") + "）。"));
        };
        group(ASKER, "问 这个月心动盲盒多少个");
        Message reply = assertReply("心动盲盒 12 个", "场次事件明细", LABEL);
        assertEquals(GROUP, reply.getType());
        assertEquals(ROOM, reply.getNum());
        assertSame(target, reply.getChannel());
    }

    @Test
    @DisplayName("点名同群未开问答的主播时，不能查到该主播或误用另一位")
    void rejectsDisabledStreamerByNameAndUid() throws Exception {
        add(FIRST, "星星", channel(GROUP, ROOM, true, null));
        add(SECOND, "小星星", channel(GROUP, ROOM, false, null));
        group(ASKER, "问 小星星昨天播了多久");
        assertReply("所点名的主播", "问答");
        group(ASKER, "问 " + SECOND + " 昨天播了多久");
        assertReply("所点名的主播", "问答");
        assertTrue(requests.isEmpty());

        modelReplies.add(call("sessions", JSONObject.of("days", 7, "limit", 1)));
        group(ASKER, "问数 星星昨天播了多久");
        assertReply(LABEL);
        verify(queries).sessions(BILIBILI, FIRST, 7, 1);
        verify(queries, never()).sessions(eq(BILIBILI), eq(SECOND), anyInt(), anyInt());
    }

    @Test
    @DisplayName("多位主播等使用者回序号，并在执行时重验所选通道")
    void resumesNumberedSelectionAndRechecksRevocation() throws Exception {
        var second = channel(GROUP, ROOM, true, null);
        add(FIRST, "测试主播甲", channel(GROUP, ROOM, true, null));
        add(SECOND, "测试主播乙", second);
        group(ASKER, "问 最近播了几场");
        assertReply("回序号", "1. 测试主播甲", "2. 测试主播乙");
        receive(GROUP, ROOM, OTHER, "2", false);
        assertTrue(replies.isEmpty());
        assertTrue(requests.isEmpty());

        group(ASKER, "问 1");
        assertReply("没有可接着问");
        assertTrue(requests.isEmpty());

        modelReplies.add(call("sessions", JSONObject.of("days", 7, "limit", 1)));
        receive(GROUP, ROOM, ASKER, "2", false);
        assertReply(LABEL);
        verify(queries).sessions(BILIBILI, SECOND, 7, 1);
        assertEquals("最近播了几场", userPayload(requests.get(0)).getString("question"));
        awaitIdle(GROUP, ROOM);

        group(ASKER, "问 还有几场");
        assertReply("回序号");
        users.get(1).getTargets().remove(second);
        receive(GROUP, ROOM, ASKER, "2", false);
        assertReply("所选主播", "问答");
        verify(queries, never()).sessions(eq(BILIBILI), eq(FIRST), anyInt(), anyInt());
    }

    @Test
    @DisplayName("关闭金额的群，各工具结果和模型最终回复都不能给出流水")
    void hiddenAmountsAreAbsentFromAllToolsAndTheAnswer() throws Exception {
        add(FIRST, "测试主播甲", channel(GROUP, ROOM, true, false));
        properties.setMaxToolCalls(12);
        JSONObject metrics = JSONObject.of("gift_paid", 576.42, "box_profit", 576.42, "danmu_count", 32);
        var summary = JSONObject.of("revenue", 576.42, "metrics", metrics,
                "peaks", JSONObject.of("gift_value", JSONObject.of("value", 576.42)));
        doReturn(JSONObject.of("source", "场次归档", "items", List.of(summary)))
                .when(queries).sessions(BILIBILI, FIRST, 7, 1);
        doReturn(JSONObject.of("source", "场次归档", "current", summary,
                "versusMedian", JSONObject.of("revenue", JSONObject.of("difference", 576.42))))
                .when(queries).session(BILIBILI, FIRST, START);
        doReturn(JSONObject.of("source", "主播页", "insights", JSONObject.of("previous", summary)))
                .when(queries).insights(BILIBILI, FIRST, 7);
        doReturn(JSONObject.of("source", "主播页", "trend", JSONObject.of("buckets", List.of(summary),
                "metrics", JSONArray.of(JSONObject.of("key", "gift_paid", "money", true), JSONObject.of("key", "danmu_count", "money", false)))))
                .when(queries).trend(BILIBILI, FIRST, "day", 7);
        doReturn(JSONObject.of("source", "快照", "fans", JSONObject.of("change", 32)))
                .when(queries).fans(BILIBILI, FIRST, 7);
        doReturn(JSONObject.of("source", "弹幕", "total", 32))
                .when(queries).danmu(BILIBILI, FIRST, START, START, START + 60000, 30);

        modelReplies.add(JSONObject.of("tool_calls", List.of(
                function("sessions", JSONObject.of("days", 7, "limit", 1)),
                function("session", JSONObject.of("start", START)),
                function("curve", JSONObject.of("start", START, "metric", "gift_paid")),
                function("danmu", JSONObject.of("start", START, "fromAt", START, "toAt", START + 60000)),
                function("revenue", JSONObject.of("start", START)),
                function("insights", JSONObject.of("days", 7)),
                function("trend", JSONObject.of("period", "day", "days", 7)),
                function("fans", JSONObject.of("days", 7)),
                function("aggregate", JSONObject.of("kinds", List.of("gift"), "from", "2026-11-01",
                        "to", "2026-11-15", "metrics", List.of("sum:amount"))))));
        modelReplies.add(text("这场流水为 576.42 元"));
        modelReplies.add(text("这场流水为 576.42 元"));
        group(ASKER, "问 这场流水和互动怎样");
        String response = assertReply("不显示金额", LABEL).getContent();
        assertFalse(response.contains("576.42"));
        var tools = toolResults(requests.get(1));
        assertEquals(9, tools.size());
        assertFalse(tools.toString().contains("576.42"), tools::toString);
        assertFalse(tools.toString().contains("gift_paid"), tools::toString);
        assertTrue(tools.toString().contains("danmu_count"));
        for (String name : List.of("curve", "revenue", "aggregate"))
            assertTrue(tools.get(name).getString("reason").contains("不显示金额"), name);
        verify(queries, never()).revenue(anyString(), anyLong(), anyLong());
        verify(queries, never()).curve(anyString(), anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("金额可见的群仍只把弹幕汇总发给模型，恢复允许展示的观众昵称")
    void qqNeverSendsAudienceQuotesAndRestoresAllowedAliases() throws Exception {
        add(FIRST, "测试主播甲", channel(GROUP, ROOM, true, true));
        String quote = "这一条是测试弹幕原话";
        long viewer = 9300000021L;
        String handle = "测试观众甲";
        doReturn(JSONObject.of("source", "测试弹幕归档", "total", 2, "items", JSONArray.of(
                JSONObject.of("uid", viewer, "uname", handle, "text", quote))))
                .when(queries).danmu(BILIBILI, FIRST, START, START, START + 60000, 30);
        local.rememberNickname(local.alias(BILIBILI, viewer), handle);
        modelReplies.add(call("danmu", JSONObject.of("start", START, "fromAt", START, "toAt", START + 60000)));
        modelReplies.add(text("弹幕有 2 条。 " + local.alias(BILIBILI, viewer) + " 是此前提到的观众。"));
        group(ASKER, "问 这一段弹幕怎样");
        String answer = assertReply("弹幕有 2 条", handle, LABEL).getContent();
        assertFalse(answer.contains(quote));
        assertFalse(requests.toString().contains(quote));
        JSONObject tool = toolResults(requests.get(1)).get("danmu");
        assertFalse(tool.containsKey("audienceQuotes"));
        assertEquals(2, tool.getIntValue("total"));
    }

    @Test
    @DisplayName("群聊通用汇总保留在线采样、弹幕计数和新老人数，并拒绝隐藏的金额")
    void qqAggregateKeepsArchiveMetricsAndAppliesChannelPrivacy() throws Exception {
        add(FIRST, "测试主播甲", channel(GROUP, ROOM, true, false));
        long returning = 9300000021L, newcomer = 9300000022L;
        long start = START + Duration.ofHours(1).toMillis();
        long end = start + Duration.ofMinutes(2).toMillis();
        archive.append(new LiveSession(BILIBILI, FIRST, "测试主播甲", 1L, START, START + 1000, 1, Map.of(), Map.of()));
        archive.append(new LiveSession(BILIBILI, FIRST, "测试主播甲", 1L, start, end, 120, Map.of(), Map.of()));
        details.appendDanmu(BILIBILI, FIRST, START, new DanmuRecord(START + 1, returning, "测试观众甲",
                "合成历史弹幕", DanmuRecord.Type.DANMU, false, null));
        String quote = "合成生日祝福原文";
        details.appendDanmu(BILIBILI, FIRST, start, new DanmuRecord(start + 1, newcomer, "测试观众乙",
                quote, DanmuRecord.Type.DANMU, false, null));
        details.appendDanmu(BILIBILI, FIRST, start, new DanmuRecord(start + 2, newcomer, "测试观众乙",
                "合成生日蛋糕原文", DanmuRecord.Type.DANMU, false, null));
        details.appendEvent(BILIBILI, FIRST, start, start + 3, "gift",
                Map.of("uid", returning, "n", 1, "val", 73.25, "pay", 73.25));
        details.store(new LiveDetail(LiveDetail.VERSION, BILIBILI, FIRST, "测试主播甲", 1L, start, end, 120,
                Map.of(), Map.of(), Map.of("online_count", Map.of(start, 12d), "watched_count", Map.of(start, 30d)),
                Map.of(), Map.of(), List.of(), List.of(), List.of(), Map.of(), List.of()));

        String day = "2026-11-07";
        modelReplies.add(JSONObject.of("tool_calls", List.of(
                aggregateCall("minutes", JSONObject.of("kinds", List.of("minute"), "from", day, "to", day,
                        "metrics", List.of("avg:online", "avg:watched"))),
                aggregateCall("danmu-count", JSONObject.of("kinds", List.of("danmu"), "from", day, "to", day,
                        "metrics", List.of("count", "distinctAudience"), "filters",
                        List.of(JSONObject.of("field", "text", "op", "contains", "value", "生日")))),
                aggregateCall("audience-count", JSONObject.of("kinds", List.of("audience_activity"), "from", day, "to", day,
                        "groupBy", List.of("audienceAge"), "metrics", List.of("distinctAudience", "distinctPayingAudience"))),
                aggregateCall("audience-amount", JSONObject.of("kinds", List.of("audience_activity"), "from", day, "to", day,
                        "groupBy", List.of("audienceAge"), "metrics", List.of("sum:amount"))))));
        modelReplies.add(text("平均在线 12 人，看过 30 人。含生日的弹幕有 2 条、1 人发言。新老观众各 1 人。这里不显示金额。"));

        group(ASKER, "问 这场在线、生日弹幕、新老观众和打赏怎样");
        String reply = assertReply("在线 12 人", "看过 30 人", "2 条、1 人发言",
                "新老观众各 1 人", "不显示金额", LABEL).getContent();
        var tools = toolResults(requests.get(1));
        var minutes = tools.get("minutes");
        assertEquals(12d, minutes.getJSONArray("totals").getDoubleValue(0));
        assertEquals(30d, minutes.getJSONArray("totals").getDoubleValue(1));
        assertEquals(1, minutes.getJSONObject("missingMinutes").getIntValue("online"));
        var danmu = tools.get("danmu-count");
        assertEquals(JSONArray.of(2, 1), danmu.getJSONArray("totals"));
        assertFalse(danmu.containsKey("audienceQuotes"));
        var counts = tools.get("audience-count");
        assertEquals(JSONArray.of(JSONArray.of("new", 1, 0), JSONArray.of("returning", 1, 1)), counts.getJSONArray("rows"));
        assertEquals("2026-11-06", counts.getString("historyFrom"));
        var amount = tools.get("audience-amount");
        assertTrue(amount.getString("reason").contains("不显示金额"));
        assertNull(amount.get("rows"));
        for (String forbidden : List.of(quote, "合成生日蛋糕原文", "73.25")) {
            assertFalse(requests.toString().contains(forbidden), forbidden);
            assertFalse(reply.contains(forbidden), forbidden);
        }
    }

    @Test
    @DisplayName("QQ 个人次数跨会话累计，重启后也保留，控制台仍可提问")
    void userLimitPersistsAcrossSessionsAndLeavesConsoleQuotaAlone() throws Exception {
        properties.setDailyQuestionsPerQqUser(1);
        properties.setDailyQuestionsPerAccount(1);
        add(FIRST, "测试主播甲", channel(GROUP, ROOM, true, null), channel(FRIEND, ASKER, true, null));
        group(ASKER, "问 本月开播几场");
        assertReply(LABEL);
        receive(FRIEND, ASKER, ASKER, "问 本周呢", false);
        assertReply("你的聊天账号", "提问次数用完");
        assertEquals(1, requests.size());
        assertTrue(client.status().questionsToday().isEmpty());
        var source = local.qqSource("QQ", QQ, FRIEND, ASKER, ASKER);
        var error = assertThrows(org.frostnova.nova.core.assistant.AssistantException.class,
                () -> newClient().beginQqQuestion(source));
        assertEquals("qq_user_limit", error.reason());

        assertTrue(service.ask(BILIBILI, FIRST, "operator", JSONObject.of("question", "本场呢")).getBooleanValue("answered"));
        assertEquals(1, client.status().questionsToday().get("operator"));
    }

    @Test
    @DisplayName("会话次数用完会提示另一位发问者，隔日可再问，月度额度与控制台共用")
    void enforcesSessionLimitAndSharedMonthlyBudget() throws Exception {
        properties.setDailyQuestionsPerQqSession(1);
        add(FIRST, "测试主播甲", channel(GROUP, ROOM, true, null));
        group(ASKER, "问 本场开播了吗");
        assertReply(LABEL);
        awaitIdle(GROUP, ROOM);
        group(OTHER, "问 本周播过吗");
        assertReply("这个会话", "提问次数用完");
        assertEquals(1, requests.size());
        awaitIdle(GROUP, ROOM);

        clock.advance(Duration.ofDays(1));
        group(OTHER, "问 今天播了吗");
        assertReply(LABEL);
        awaitIdle(GROUP, ROOM);
        clock.advance(Duration.ofDays(1));
        properties.setMonthlyBudgetYuan(client.status().spentYuan());
        group(OTHER, "问 今天播了吗");
        assertReply("本月", "额度已用完");
        assertEquals("monthly_budget", service.ask(BILIBILI, FIRST, "operator",
                JSONObject.of("question", "本场呢")).getString("reason"));
        assertEquals(2, requests.size());
    }

    @Test
    @DisplayName("群聊和好友的发送记录及次数文件都不能写入发问者的 QQ 号")
    void recordsQqSourceAndPseudonymWithoutRawAskerId() throws Exception {
        add(FIRST, "测试主播甲", channel(GROUP, ROOM, true, null), channel(FRIEND, ASKER, true, null));
        group(ASKER, "问 本月播过吗");
        assertReply(LABEL);
        receive(FRIEND, ASKER, ASKER, "问 本周呢", false);
        assertReply(LABEL);
        String raw = Files.readString(directory.resolve("assistant-transmissions.jsonl"));
        List<JSONObject> records = raw.lines().map(JSON::parseObject).toList();
        assertEquals(2, records.size());
        JSONObject group = records.get(0);
        JSONObject friend = records.get(1);
        assertEquals("QQ", group.getString("entry"));
        assertEquals("QQ", friend.getString("entry"));
        assertEquals("group", group.getString("sessionType"));
        assertEquals(Long.toString(ROOM), group.getString("sessionNum"));
        assertEquals("friend", friend.getString("sessionType"));
        assertTrue(friend.getString("sessionNum").startsWith("好友-"));
        assertTrue(group.getString("account").startsWith("问者-"));
        assertEquals(group.getString("account"), friend.getString("account"));
        assertFalse(raw.contains(Long.toString(ASKER)));
        String usage = Files.readString(directory.resolve("assistant-usage.json"));
        assertFalse(usage.contains(Long.toString(ASKER)));
        assertEquals(2, JSON.parseObject(usage).getJSONObject("qqQuestions").getIntValue(group.getString("account")));
    }

    @Test
    @DisplayName("启用命令只移出禁用名单，禁用命令覆盖别名")
    void toggleCannotEnableChannelPermission() throws Exception {
        var target = channel(GROUP, ROOM, false, null);
        add(FIRST, "测试主播甲", target);
        settings.disable(QQ, GROUP, ROOM, "问");
        group(ASKER, "启用命令 问");
        assertReply("已启用", "问");
        assertFalse(settings.isDisabled(QQ, GROUP, ROOM, "问"));
        assertFalse(target.getAssistantEnabled());
        group(ASKER, "问 本周播了吗");
        assertReply("还没开智能助手问答");

        target.setAssistantEnabled(true);
        group(ASKER, "禁用命令 问");
        assertReply("已禁用", "问");
        group(ASKER, "问数 本周播了吗");
        assertReply("已关闭", "问");
        assertTrue(target.getAssistantEnabled());
        assertTrue(requests.isEmpty());
    }

    @Test
    @DisplayName("总开关或模型密钥缺失时，已配置的群里也应静默且菜单不推荐")
    void hidesCommandAndStaysSilentWithoutSwitchOrKey() throws Exception {
        add(FIRST, "测试主播甲", channel(GROUP, ROOM, true, null));
        properties.setEnabled(false);
        group(ASKER, "问 本周开播了吗");
        assertTrue(replies.isEmpty());
        receive(GROUP, ROOM, ASKER, "菜单", true);
        assertFalse(assertReply("可用命令").getContent().contains("问 〈问题〉"));

        properties.setEnabled(true);
        group(ASKER, "菜单");
        assertReply("问 〈问题〉");
        client.deleteKey();
        group(ASKER, "问数 本周开播了吗");
        assertTrue(replies.isEmpty());
        receive(GROUP, ROOM, ASKER, "菜单", true);
        assertFalse(assertReply("可用命令").getContent().contains("问 〈问题〉"));
        assertTrue(requests.isEmpty());
    }

    @Test
    @DisplayName("群聊未经呼叫与未配置好友静默，已配置好友直接提问并收到短文本")
    void honorsMentionsAndFriendWhitelistAndClipsReply() throws Exception {
        add(FIRST, "测试主播甲", channel(GROUP, ROOM, true, null), channel(FRIEND, ASKER, true, null));
        receive(GROUP, ROOM, ASKER, "问 本场怎样", false);
        receive(FRIEND, OTHER, OTHER, "问 本场怎样", false);
        assertTrue(replies.isEmpty());
        assertTrue(requests.isEmpty());
        modelReplies.add(text("{at=all}" + "直播互动很活跃😀".repeat(60)));
        receive(FRIEND, ASKER, ASKER, "问 本场怎样", false);
        Message answer = assertReply(LABEL);
        assertEquals(FRIEND, answer.getType());
        assertEquals(ASKER, answer.getNum());
        assertTrue(answer.isReply());
        assertTrue(answer.getContent().codePointCount(0, answer.getContent().length()) <= 300);
        assertFalse(answer.getContent().contains("{at=all}"));
    }

    @Test
    @DisplayName("慢问题先提示正在查，同时提问只收到忙提示而不请求模型")
    void acknowledgesSlowQuestionAndRejectsAnotherWithoutQueueing() throws Exception {
        add(FIRST, "测试主播甲", channel(GROUP, ROOM, true, null));
        var received = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        modelWait = () -> {
            received.countDown();
            try {
                assertTrue(release.await(30, TimeUnit.SECONDS), "测试未释放模型响应");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
        };
        try {
            group(ASKER, "问 本月播过吗");
            assertTrue(received.await(10, TimeUnit.SECONDS));
            receive(GROUP, ROOM, OTHER, "问 本周播过吗", true);
            assertReply("上一个问题还在查", "稍等");
            assertReply("收到", "正在查");
            assertEquals(1, requests.size());
        } finally {
            release.countDown();
        }
        assertReply(LABEL);
        awaitIdle(GROUP, ROOM);
        assertEquals(1, requests.size());
    }

    @Test
    @DisplayName("追问历史只给本会话同一发问人和主播，最多三轮并在十分钟后过期")
    void historyIsScopedAndExpires() throws Exception {
        add(FIRST, "测试主播甲", channel(GROUP, ROOM, true, null), channel(FRIEND, ASKER, true, null));
        add(SECOND, "测试主播乙", channel(GROUP, ROOM, true, null));
        for (String question : List.of("本月开播了吗", "本周开播了吗", "今天开播了吗", "昨天开播了吗", "前天开播了吗")) {
            int previous = requests.size();
            group(ASKER, "问 测试主播甲" + question);
            assertReply(LABEL);
            assertEquals(Math.min(previous, 3), lastHistory().size());
            awaitIdle(GROUP, ROOM);
        }
        assertTrue(lastHistory().getJSONObject(0).getString("question").contains("本周"));
        group(OTHER, "问 测试主播甲今天怎么样");
        assertReply(LABEL);
        assertTrue(lastHistory().isEmpty());
        awaitIdle(GROUP, ROOM);
        group(ASKER, "问 测试主播乙今天怎么样");
        assertReply(LABEL);
        assertTrue(lastHistory().isEmpty());
        awaitIdle(GROUP, ROOM);
        receive(FRIEND, ASKER, ASKER, "问 测试主播甲今天怎么样", false);
        assertReply(LABEL);
        assertTrue(lastHistory().isEmpty());
        clock.advance(Duration.ofMinutes(10));
        group(ASKER, "问 测试主播甲今天怎么样");
        assertReply(LABEL);
        assertTrue(lastHistory().isEmpty());
    }

    @Test
    @DisplayName("编号续问不能借用同群其他人的选项或把选项带去另一个聊天或主播")
    void numberedFollowUpsStayWithTheirAskerChatAndStreamer() throws Exception {
        add(FIRST, "测试主播甲", channel(GROUP, ROOM, true, false), channel(FRIEND, ASKER, true, false));
        add(SECOND, "测试主播乙", channel(GROUP, ROOM, true, false));
        modelReplies.add(text("周六在线更高。\n1. 周六那场的弹幕有多少？\n2. 周六那场的开播时间是什么？"));
        group(ASKER, "问 测试主播甲最近在线怎么样");
        assertReply("1. 周六那场的弹幕", "2. 周六那场的开播时间", LABEL);
        awaitIdle(GROUP, ROOM);

        modelReplies.add(text("周日在线更高。\n1. 周日那场的弹幕有多少？"));
        group(OTHER, "问 测试主播乙最近在线怎么样");
        assertReply("1. 周日那场的弹幕", LABEL);
        awaitIdle(GROUP, ROOM);

        receive(FRIEND, ASKER, ASKER, "问 1", false);
        assertReply("没有可接着问");
        assertEquals(2, requests.size());

        modelReplies.add(call("sessions", JSONObject.of("days", 7, "limit", 1)));
        group(ASKER, "问 2");
        assertReply(LABEL);
        assertEquals("周六那场的开播时间是什么？", userPayload(requests.get(2)).getString("question"));
        assertEquals("测试主播甲最近在线怎么样", lastHistory().getJSONObject(0).getString("question"));
        verify(queries).sessions(BILIBILI, FIRST, 7, 1);
        verify(queries, never()).sessions(eq(BILIBILI), eq(SECOND), anyInt(), anyInt());
        awaitIdle(GROUP, ROOM);

        group(ASKER, "问 1");
        assertReply("没有可接着问");
        assertEquals(4, requests.size());
    }

    @Test
    @DisplayName("选项换了发问人或金额可见设置、改问其他主播或已满十分钟时都不能调用模型或扣次数")
    void invalidNumberedFollowUpsUseNoModelOrQuota() throws Exception {
        var target = channel(GROUP, ROOM, true, true);
        add(FIRST, "测试主播甲", target);
        add(SECOND, "测试主播乙", channel(GROUP, ROOM, true, false));
        String options = "收入是否包含上舰？\n1. 含上舰的收入是多少？\n2. 不含上舰的收入是多少？";
        modelReplies.add(text(options));
        group(ASKER, "问 测试主播甲收入多少");
        assertReply("收入是否包含上舰", "1. 含上舰", "2. 不含上舰");
        awaitIdle(GROUP, ROOM);

        group(OTHER, "问 1");
        assertReply("没有可接着问");
        target.setRevenueVisible(false);
        group(ASKER, "问 1");
        assertReply("没有可接着问");
        assertEquals(1, requests.size());

        target.setRevenueVisible(true);
        modelReplies.add(text(options));
        group(ASKER, "问 测试主播甲收入多少");
        assertReply("1. 含上舰");
        awaitIdle(GROUP, ROOM);
        group(ASKER, "问 测试主播乙昨天播了吗");
        assertReply(LABEL);
        awaitIdle(GROUP, ROOM);
        group(ASKER, "问 1");
        assertReply("没有可接着问");
        assertEquals(3, requests.size());

        modelReplies.add(text(options));
        group(ASKER, "问 测试主播甲收入多少");
        assertReply("1. 含上舰");
        awaitIdle(GROUP, ROOM);
        clock.advance(Duration.ofMinutes(10).minusSeconds(4));
        group(ASKER, "问 1");
        assertReply("没有可接着问");
        assertEquals(4, requests.size());
        var source = local.qqSource("QQ", QQ, GROUP, ROOM, ASKER);
        assertEquals(Map.of(source.account(), 4, source.sessionKey(), 4), qqCounts());
    }

    @Test
    @DisplayName("三百字的回复应保留完整的下一问，选中后发送显示的那条")
    void numberedFollowUpFitsInQqReply() throws Exception {
        add(FIRST, "测试主播甲", channel(GROUP, ROOM, true, false));
        String question = "周六那场的弹幕有多少？";
        modelReplies.add(text("按：上周、在线人数\n" + "在线较高。".repeat(65) + "\n1. " + question));
        group(ASKER, "问 上周在线怎么样");
        String reply = assertReply("按：上周、在线人数", "1. " + question, LABEL).getContent();
        assertEquals(300, reply.codePointCount(0, reply.length()));
        awaitIdle(GROUP, ROOM);

        group(ASKER, "问 1");
        assertReply(LABEL);
        assertEquals(question, userPayload(requests.get(1)).getString("question"));
    }

    @Test
    @DisplayName("追问沿用原会话的结果编号，文字先到，换发问人或超过十分钟时无法画旧数据")
    void chartFollowUpSendsTextThenImageAndCannotUseOtherOrExpiredResults() throws Exception {
        add(FIRST, "测试主播甲", channel(GROUP, ROOM, true, false));
        when(chartRenderer.render(any())).thenReturn(Optional.of("QUJD"));
        doReturn(JSONObject.of("source", "场次归档", "items", JSONArray.of(JSONObject.of("startTime", START, "messages", 52))))
                .when(queries).sessions(BILIBILI, FIRST, 7, 1);
        var chart = call("draw_chart", JSONObject.of("resultNumber", 1, "type", "bar", "xColumn", "startTime",
                "yColumns", List.of("messages"), "title", "场次弹幕数"));

        modelReplies.add(call("sessions", JSONObject.of("days", 7, "limit", 1)));
        modelReplies.add(text("查到了这一场。"));
        group(ASKER, "问 最近的场次");
        assertReply("查到了", LABEL);
        awaitIdle(GROUP, ROOM);
        verifyNoInteractions(chartRenderer);

        modelReplies.add(chart);
        modelReplies.add(text("场次弹幕见图。"));
        group(ASKER, "问 用刚才的数据画图");
        var first = assertReply("见图", LABEL);
        var image = assertReply("{image_base64=QUJD}");
        assertSame(first, image.getPrevious());
        assertEquals(ROOM, image.getNum());
        var previous = userPayload(requests.get(2)).getJSONArray("previousResults").getJSONObject(0);
        assertEquals(1, previous.getIntValue("resultNumber"));
        assertTrue(previous.getJSONArray("chartColumns").contains("messages"));
        verify(chartRenderer).render(argThat(spec -> spec.rows().get(0).values().get(0).number().intValueExact() == 52));
        awaitIdle(GROUP, ROOM);

        modelReplies.add(chart);
        modelReplies.add(text("找不到数据。"));
        group(OTHER, "问 用刚才的数据画图");
        assertReply("图没出来", "结果编号");
        assertFalse(userPayload(requests.get(4)).containsKey("previousResults"));
        awaitIdle(GROUP, ROOM);

        clock.advance(Duration.ofMinutes(10));
        modelReplies.add(chart);
        modelReplies.add(text("找不到数据。"));
        group(ASKER, "问 再用刚才的数据画图");
        var missing = assertReply("图没出来", "结果编号");
        assertNull(missing.getNext());
        assertFalse(userPayload(requests.get(6)).containsKey("previousResults"));
        verifyNoMoreInteractions(chartRenderer);
    }

    @Test
    @DisplayName("关掉金额权限后，上一问的金额结果不能通过图表发出")
    void chartFollowUpCannotReuseAmountsAfterRevocation() throws Exception {
        var target = channel(GROUP, ROOM, true, true);
        add(FIRST, "测试主播甲", target);
        doReturn(JSONObject.of("source", "场次归档", "items", JSONArray.of(JSONObject.of("startTime", START, "revenue", 615.75))))
                .when(queries).sessions(BILIBILI, FIRST, 7, 1);
        modelReplies.add(call("sessions", JSONObject.of("days", 7, "limit", 1)));
        modelReplies.add(text("已有场次数据。"));
        group(ASKER, "问 最近场次");
        assertReply("已有", LABEL);
        awaitIdle(GROUP, ROOM);

        target.setRevenueVisible(false);
        modelReplies.add(call("draw_chart", JSONObject.of("resultNumber", 1, "type", "bar", "xColumn", "startTime",
                "yColumns", List.of("revenue"), "title", "流水")));
        modelReplies.add(text("无法提供该图。"));
        group(ASKER, "问 把之前那份查数画出来");
        var response = assertReply("图没出来");
        assertNull(response.getNext());
        assertFalse(userPayload(requests.get(2)).containsKey("previousResults"));
        assertFalse(requests.get(3).toJSONString().contains("615.75"));
        verifyNoInteractions(chartRenderer);
    }

    private AssistantClient newClient() {
        return new AssistantClient(properties, new AssistantKeyStore(directory), new AssistantUsageStore(directory, clock), template);
    }

    private void add(long uid, String name, PushTarget... targets) {
        var user = new PushUser();
        user.setPlatform(BILIBILI);
        user.setUid(uid);
        user.setUname(name);
        user.setEnabled(true);
        user.setTargets(new ArrayList<>(List.of(targets)));
        for (PushTarget target : targets) target.setUser(user);
        users.add(user);
    }

    private static PushTarget channel(PushTargetType type, long num, Boolean enabled, Boolean revenue) {
        var target = new PushTarget();
        target.setPlatform(QQ);
        target.setType(type);
        target.setNum(num);
        target.setEnabled(true);
        target.setAssistantEnabled(enabled);
        target.setRevenueVisible(revenue);
        return target;
    }

    private void group(long actor, String text) {
        clock.advance(Duration.ofSeconds(4));
        receive(GROUP, ROOM, actor, text, true);
    }

    private void receive(PushTargetType type, long num, long actor, String text, boolean mention) {
        dispatcher.onRemoteMessage(new NovaRemoteMessageEvent(QQ, type == GROUP ? "group" : "private",
                num, actor, text, "admin", mention));
    }

    private Message assertReply(String... words) throws InterruptedException {
        Message message = replies.poll(18, TimeUnit.SECONDS);
        assertNotNull(message, "会话没有收到回复");
        assertEquals(QQ, message.getPlatform());
        assertTrue(message.isReply());
        for (String word : words) assertTrue(message.getContent().contains(word), message::getContent);
        return message;
    }

    private void awaitIdle(PushTargetType type, long num) throws InterruptedException {
        var context = new CommandContext(QQ, type, num, ASKER, "问", List.of(), "");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (command.beforeCooldown(context) != null) {
            assertTrue(System.nanoTime() < deadline, "问题结束后会话仍处于忙状态");
            Thread.sleep(10);
        }
    }

    private JSONArray lastHistory() { return userPayload(requests.get(requests.size() - 1)).getJSONArray("history"); }

    private JSONObject qqCounts() throws Exception {
        return JSON.parseObject(Files.readString(directory.resolve("assistant-usage.json"))).getJSONObject("qqQuestions");
    }

    private static JSONObject userPayload(JSONObject request) {
        return JSON.parseObject(request.getJSONArray("messages").getJSONObject(1).getString("content"));
    }

    private static java.util.Map<String, JSONObject> toolResults(JSONObject request) {
        var results = new java.util.LinkedHashMap<String, JSONObject>();
        for (Object item : request.getJSONArray("messages")) {
            var message = (JSONObject) item;
            if ("tool".equals(message.getString("role")))
                results.put(message.getString("tool_call_id"), JSON.parseObject(message.getString("content")));
        }
        return results;
    }

    private static JSONObject call(String name, JSONObject args) {
        return JSONObject.of("tool_calls", List.of(function(name, args)));
    }

    private static JSONObject aggregateCall(String id, JSONObject args) {
        var call = function("aggregate", args);
        call.put("id", id);
        return call;
    }

    private static JSONObject function(String name, JSONObject args) {
        return JSONObject.of("type", "function", "id", name, "function",
                JSONObject.of("name", name, "arguments", args.toJSONString()));
    }

    private static JSONObject text(String reply) { return JSONObject.of("content", reply); }

    private static <T> ObjectProvider<T> provider(List<T> items) {
        return new ObjectProvider<>() {
            @Override public Iterator<T> iterator() { return items.iterator(); }
            @Override public java.util.stream.Stream<T> orderedStream() { return items.stream(); }
        };
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CommandDispatcher> mockDispatcherProvider() { return mock(ObjectProvider.class); }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-16T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }
}
