package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.assistant.AssistantChartRenderer;
import org.frostnova.nova.core.assistant.AssistantClient;
import org.frostnova.nova.core.assistant.AssistantKeyStore;
import org.frostnova.nova.core.assistant.AssistantLocalStore;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.assistant.AssistantUsageStore;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.StreamerDirectory;
import org.frostnova.nova.core.service.StreamerNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.frostnova.nova.core.enums.PushTargetType.GROUP;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssistantHistoryTest {
    private static final String PLATFORM = "bilibili";
    private static final long STREAMER = 10001L;
    private static final long START = 1794000000000L;
    private static final String ANSWER = "查到了相关场次。";

    @TempDir Path directory;
    private final MutableClock clock = new MutableClock();
    private final List<JSONObject> requests = new CopyOnWriteArrayList<>();
    private final Queue<JSONObject> modelReplies = new ConcurrentLinkedQueue<>();
    private final BlockingQueue<Message> qqReplies = new LinkedBlockingQueue<>();
    private volatile JSONObject usage = JSONObject.of("prompt_tokens", 100, "completion_tokens", 10);
    private final AssistantProperties properties = new AssistantProperties();
    private final AssistantQueryService queries = mock(AssistantQueryService.class);
    private final AssistantChartRenderer renderer = mock(AssistantChartRenderer.class);
    private final AbstractDataSource data = mock(AbstractDataSource.class);
    private AssistantLocalStore local;
    private AssistantAskService service;
    private QqAssistantCommand command;

    @BeforeEach void setUp() {
        properties.setEnabled(true);
        properties.setBaseUrl("https://model.example/v1");
        var template = new RestTemplate();
        MockRestServiceServer.bindTo(template).build().expect(manyTimes(), anything()).andRespond(request -> {
            requests.add(JSON.parseObject(((MockClientHttpRequest) request).getBodyAsString()));
            JSONObject reply = modelReplies.poll();
            return withSuccess(JSONObject.of("choices", List.of(JSONObject.of("message",
                    reply == null ? JSONObject.of("content", ANSWER) : reply)), "usage", usage).toJSONString(),
                    MediaType.APPLICATION_JSON).createResponse(request);
        });
        var client = new AssistantClient(properties, new AssistantKeyStore(directory),
                new AssistantUsageStore(directory, clock), template);
        client.validateAndSaveKey("operator", "fixture-assistant-key");
        client.acknowledge("operator");
        requests.clear();
        local = new AssistantLocalStore(directory, clock);
        service = new AssistantAskService(queries, client, properties, local, clock, () -> renderer);

        var target = new PushTarget();
        target.setPlatform("qq-test");
        target.setType(GROUP);
        target.setNum(30003L);
        target.setEnabled(true);
        target.setRevenueVisible(true);
        target.setAssistantEnabled(true);
        var user = new PushUser();
        user.setPlatform(PLATFORM);
        user.setUid(STREAMER);
        user.setUname("测试主播");
        user.setEnabled(true);
        user.setTargets(List.of(target));
        when(data.getUsers(PLATFORM)).thenReturn(List.of(user));
        when(data.getUser(PLATFORM, STREAMER)).thenReturn(Optional.of(user));
        var sender = mock(NovaMessageSender.class);
        doAnswer(invocation -> qqReplies.add(invocation.getArgument(0))).when(sender).send(any());
        command = new QqAssistantCommand(service, client, local, data, StreamerNames.none(),
                new RevenueVisibilityService(null), mock(CommandSettingsService.class), sender, clock);
    }

    @AfterEach void close() {
        if (command != null) command.close();
        if (service != null) service.close();
    }

    // 追问时，助手忘了三轮以前或十分钟以前问过的内容。
    @Test void sixthQqQuestionIncludesFiveTurns() throws InterruptedException {
        for (int i = 1; i <= 6; i++) {
            askQq("最近的开播情况，第" + i + "问");
            clock.advance(Duration.ofSeconds(40));
        }
        assertEquals(5, userPayload(5).getJSONArray("history").size());
    }

    // 追问时，助手忘了三轮以前或十分钟以前问过的内容。
    @Test void qqHistoryStillIncludesAnAnswerFromTwentyMinutesAgo() throws InterruptedException {
        String first = "上周开播了吗";
        askQq(first);
        clock.advance(Duration.ofMinutes(18));
        askQq("这周开播了吗");
        clock.advance(Duration.ofMinutes(1));
        askQq("昨天开播了吗");
        clock.advance(Duration.ofMinutes(1));
        askQq("跟上周相比呢");

        var history = userPayload(3).getJSONArray("history");
        assertEquals(3, history.size());
        assertEquals(first, history.getJSONObject(0).getString("question"));
        assertTrue(history.getJSONObject(0).getString("answerForHistory").contains("相关场次"));
    }

    // 隔了十几分钟，让助手把之前查到的数据画成图，它只能重查。
    @Test void qqCanDrawFromResultsFoundFifteenMinutesAgo() throws InterruptedException {
        queueSessionQuery();
        askQq("最近的场次有哪些");
        clock.advance(Duration.ofMinutes(15));
        when(renderer.render(any())).thenReturn(Optional.of("QUJD"));
        modelReplies.add(toolCall("draw_chart", JSONObject.of("resultNumber", 1, "type", "bar", "xColumn", "startTime",
                "yColumns", List.of("messages"), "title", "场次弹幕数")));

        askQq("把刚才查到的弹幕数画成图");
        var previous = userPayload(2).getJSONArray("previousResults");
        assertNotNull(previous, "模型应当收到上一问的结果编号");
        assertEquals(1, previous.getJSONObject(0).getIntValue("resultNumber"));
        assertTrue(previous.getJSONObject(0).getJSONArray("chartColumns").contains("messages"));
        verify(renderer).render(argThat(chart -> chart.rows().get(0).values().get(0).number().intValueExact() == 52));
        verify(queries).sessions(PLATFORM, STREAMER, 7, 1);
        Message image = qqReplies.poll(18, TimeUnit.SECONDS);
        assertNotNull(image, "会话没有收到图");
        assertTrue(image.getContent().contains("{image_base64="));
    }

    @Test void qqDropsTextAndResultsTogetherWhenATurnExpires() throws InterruptedException {
        queueSessionQuery();
        askQq("最近的场次有哪些");
        clock.advance(Duration.ofMinutes(20));
        queueSessionQuery();
        askQq("这些场次的弹幕多吗");
        clock.advance(Duration.ofMinutes(15));
        askQq("哪些数据还能用");

        var payload = userPayload(4);
        assertEquals(1, payload.getJSONArray("history").size());
        assertEquals("这些场次的弹幕多吗", payload.getJSONArray("history").getJSONObject(0).getString("question"));
        assertEquals(1, payload.getJSONArray("previousResults").size());
        assertEquals(2, payload.getJSONArray("previousResults").getJSONObject(0).getIntValue("resultNumber"));
    }

    @Test void qqKeepsTextAndResultsForOnlyTheLatestTenTurns() throws InterruptedException {
        for (int i = 1; i <= 11; i++) {
            queueSessionQuery();
            askQq("直播场次，第" + i + "问");
        }
        askQq("用前面的数据比较");

        var payload = userPayload(22);
        assertEquals(List.of(2, 3, 4, 5, 6, 7, 8, 9, 10, 11), payload.getJSONArray("previousResults")
                .toJavaList(JSONObject.class).stream().map(result -> result.getIntValue("resultNumber")).toList());
        assertEquals(IntStream.rangeClosed(2, 11).mapToObj(i -> "直播场次，第" + i + "问").toList(),
                payload.getJSONArray("history").toJavaList(JSONObject.class).stream()
                        .map(turn -> turn.getString("question")).toList());
    }

    // 几次没答出来的提问把之前的对话挤掉。
    @Test void qqKeepsAnAnsweredTurnAfterTenUnansweredQueries() throws InterruptedException {
        String first = "最近有哪些直播场次";
        queueSessionQuery();
        askQq(first);
        for (int i = 1; i <= 10; i++) {
            queueSessionQuery();
            modelReplies.add(JSONObject.of("content", ""));
            askQq("再查一次场次，第" + i + "问", "空响应");
        }
        verify(queries, times(11)).sessions(PLATFORM, STREAMER, 7, 1);

        int next = requests.size();
        askQq("你之前回答了什么");

        var history = userPayload(next).getJSONArray("history");
        assertEquals(1, history.size());
        assertEquals(first, history.getJSONObject(0).getString("question"));
        assertTrue(history.getJSONObject(0).getString("answerForHistory").contains("相关场次"));
    }

    // 控制台追问到第四轮就报错。
    @Test void consoleRejectsElevenTurnsWithTheNewLimit() throws Exception {
        askConsole(Collections.nCopies(11, JSONObject.of("question", "旧问题", "answerForHistory", "旧回答")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("最多 10 轮")));
        assertTrue(requests.isEmpty());
    }

    @Test void consoleSendsTenTurnsAndClipsLongAnswersByCharacter() throws Exception {
        String retained = "好😀".repeat(500);
        askConsole(Collections.nCopies(10, JSONObject.of("question", "旧问题", "answerForHistory", retained + "答".repeat(500))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answered").value(true));

        var history = userPayload(0).getJSONArray("history");
        assertEquals(10, history.size());
        for (JSONObject turn : history.toJavaList(JSONObject.class)) assertEquals(retained, turn.getString("answerForHistory"));
    }

    // 一条带大段空白的回答让下一次追问报错。
    @Test void consoleKeepsTheAnswerAfterLeadingWhitespace() throws Exception {
        String answer = "查到了这些场次。";
        askConsole(List.of(JSONObject.of("question", "旧问题", "answerForHistory", " \n".repeat(500) + answer + "\t ")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answered").value(true));

        var history = userPayload(0).getJSONArray("history");
        assertEquals(1, history.size());
        assertTrue(history.getJSONObject(0).getString("answerForHistory").contains(answer),
                "送给模型的历史回答应保留正文");
    }

    @Test void consoleStillRejectsAnswersOverTheExistingSizeLimit() throws Exception {
        askConsole(List.of(JSONObject.of("question", "旧问题", "answerForHistory", "答".repeat(16001))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("代号版回答")));
        assertTrue(requests.isEmpty());
    }

    // 命中缓存的重复内容挤掉查数次数，助手查一两次就被迫作答。
    @Test void cachedInputLeavesToolsAvailableOnTheFourthCall() {
        usage = JSONObject.of("prompt_tokens", 10000, "completion_tokens", 10,
                "prompt_tokens_details", JSONObject.of("cached_tokens", 9000));
        assertFourthCallToolChoice("auto", 3);
    }

    @Test void missingCacheUsageCountsAllTokens() {
        usage = JSONObject.of("prompt_tokens", 10000, "completion_tokens", 10);
        assertFourthCallToolChoice("none", 2);
    }

    @Test void zeroUncachedPriceCountsAllTokens() {
        properties.setPriceInputYuanPerMillion(BigDecimal.ZERO);
        usage = JSONObject.of("prompt_tokens", 10000, "completion_tokens", 10,
                "prompt_cache_hit_tokens", 9000, "prompt_cache_miss_tokens", 1000);
        assertFourthCallToolChoice("none", 2);
    }

    private void assertFourthCallToolChoice(String expected, int queriesRun) {
        for (int i = 0; i < 3; i++) queueSessionQuery();
        assertTrue(service.ask(PLATFORM, STREAMER, "operator", JSONObject.of("question", "最近有哪些场次"))
                .getBooleanValue("answered"));
        assertEquals(4, requests.size());
        assertEquals(expected, requests.get(3).getString("tool_choice"));
        verify(queries, times(queriesRun)).sessions(PLATFORM, STREAMER, 7, 1);
    }

    private ResultActions askConsole(List<JSONObject> history) throws Exception {
        return MockMvcBuilders.standaloneSetup(new AssistantAskController(service, local, data,
                        mock(StreamerDirectory.class), StreamerNames.none(), mock(LiveSessionArchive.class))).build()
                .perform(post("/config/api/streamers/{platform}/{uid}/assistant/ask", PLATFORM, STREAMER)
                        .requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(clock.instant()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSONObject.of("question", "再看一下", "history", history).toJSONString()));
    }

    private void askQq(String question) throws InterruptedException {
        askQq(question, "相关场次");
    }

    private void askQq(String question, String expectedReply) throws InterruptedException {
        var context = new CommandContext("qq-test", GROUP, 30003L, 2000000002L, "问", List.of(question), "问 " + question);
        assertEquals(CommandReply.none(), command.execute(context));
        Message reply = qqReplies.poll(18, TimeUnit.SECONDS);
        assertNotNull(reply, "会话没有收到回复");
        assertTrue(reply.getContent().contains(expectedReply), reply::getContent);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (command.beforeCooldown(context) != null) {
            assertTrue(System.nanoTime() < deadline, "问题结束后会话仍处于忙状态");
            Thread.sleep(10);
        }
    }

    private JSONObject userPayload(int index) {
        return JSON.parseObject(requests.get(index).getJSONArray("messages").getJSONObject(1).getString("content"));
    }

    private void queueSessionQuery() {
        when(queries.sessions(PLATFORM, STREAMER, 7, 1)).thenAnswer(invocation -> JSONObject.of("source", "场次归档",
                "items", List.of(JSONObject.of("startTime", START, "messages", 52))));
        modelReplies.add(toolCall("sessions", JSONObject.of("days", 7, "limit", 1)));
    }

    private static JSONObject toolCall(String name, JSONObject arguments) {
        return JSONObject.of("tool_calls", List.of(JSONObject.of("type", "function", "id", name,
                "function", JSONObject.of("name", name, "arguments", arguments.toJSONString()))));
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-16T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }
}
