package com.starlwr.bot.core.timeline;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件时间线存储测试
 * <p>
 * 时间线是「昨晚为什么没推」的唯一结构化答案，且<b>过期就删</b>——
 * 保留期算错一天，删掉的那一天再也回不来。因此边界逐个钉死。
 */
@DisplayName("事件时间线存储")
class TimelineStoreTest {
    @TempDir
    Path dir;

    private StarBotCoreProperties properties;

    private TimelineStore store;

    @BeforeEach
    void setUp() {
        properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        store = new TimelineStore(properties);
        store.load();
    }

    @Test
    @DisplayName("没有任何记录时应返回空表而非报错")
    void emptyWhenNothingRecorded() {
        assertTrue(store.days().isEmpty());
        assertTrue(store.recent().isEmpty());
        assertEquals(0, query(null).matched());
    }

    @Test
    @DisplayName("写下的事件应能原样读回, 含可空四项与补充键值")
    void roundTrip() {
        Instant at = Instant.now();
        store.record(TimelineEvent.of(TimelineEventType.PUSH_FAILED, TimelineEvent.Level.ERROR)
                .at(at)
                .streamer("测试主播")
                .channel("群 12345")
                .text("推送失败：群号不存在")
                .detail("platform", "qq-onebot")
                .build());

        List<TimelineEvent> found = query(null).events();

        assertEquals(1, found.size());
        TimelineEvent event = found.get(0);
        assertEquals(at.toEpochMilli(), event.at());
        assertEquals(TimelineEventType.PUSH_FAILED, event.type());
        assertEquals(TimelineEvent.Level.ERROR, event.level());
        assertEquals("测试主播", event.streamer());
        assertEquals("群 12345", event.channel());
        assertEquals("推送失败：群号不存在", event.text());
        assertEquals(Map.of("platform", "qq-onebot"), event.detail());
    }

    @Test
    @DisplayName("可空四项留空时读回仍是空, 不会变成空串")
    void keepsNullsNull() {
        store.record(TimelineEvent.of(TimelineEventType.PROBE_CHANGED, TimelineEvent.Level.INFO).build());

        TimelineEvent event = query(null).events().get(0);

        assertNull(event.streamer(), "空与空串在按主播筛选时不是一回事");
        assertNull(event.channel());
        assertNull(event.text());
        assertTrue(event.detail().isEmpty());
    }

    @Test
    @DisplayName("跨日的事件应落进各自那一天的文件")
    void splitsByDay() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        store.record(event(today, "今天这条"));
        store.record(event(yesterday, "昨天这条"));
        store.record(event(yesterday, "昨天第二条"));

        assertTrue(Files.exists(file(today)), "今天的文件应存在");
        assertTrue(Files.exists(file(yesterday)), "昨天的文件应存在");
        assertEquals(1, lineCount(file(today)));
        assertEquals(2, lineCount(file(yesterday)));

        // 各日条数：最近的在前
        assertEquals(List.of(new TimelineStore.Day(today, 1), new TimelineStore.Day(yesterday, 2)),
                store.days());

        assertEquals(List.of("今天这条"), texts(query(today)));
        assertEquals(List.of("昨天第二条", "昨天这条"), texts(query(yesterday)));
    }

    @Test
    @DisplayName("保留 14 天时, 15 天的记录应删掉最早那一天, 余 14 天")
    void purgesBeyondRetention() {
        LocalDate today = LocalDate.now();
        for (int back = 0; back < 15; back++) {
            store.record(event(today.minusDays(back), "第 " + back + " 天前"));
        }
        assertEquals(15, store.days().size(), "清理前应有 15 天");

        store.purgeExpired();

        assertEquals(14, store.days().size(), "保留天数含当天, 14 天即今天与此前 13 天");
        assertEquals(today, store.days().get(0).date());
        assertEquals(today.minusDays(13), store.days().get(store.days().size() - 1).date());
        assertFalse(Files.exists(file(today.minusDays(14))), "第 14 天前那一天的文件应已删除");
        assertTrue(Files.exists(file(today.minusDays(13))), "第 13 天前那一天应留着");
    }

    @Test
    @DisplayName("保留天数设为 0 时一天都不删")
    void keepsEverythingWhenRetentionDisabled() {
        properties.getTimeline().setRetentionDays(0);
        LocalDate today = LocalDate.now();
        store.record(event(today.minusDays(400), "很久以前"));
        store.record(event(today, "今天"));

        store.purgeExpired();

        assertEquals(2, store.days().size(), "配 0 是「不要自动删」, 不是「全删」");
    }

    @Test
    @DisplayName("上一次运行留下的过期文件也应清掉, 而不只是本进程写过的那些天")
    void purgesFilesLeftByPreviousRun() throws IOException {
        LocalDate stale = LocalDate.now().minusDays(30);
        Files.createDirectories(dir.resolve("timeline"));
        Files.writeString(file(stale), "{\"at\":1,\"type\":\"PUSH_SENT\",\"level\":\"info\"}\n",
                StandardCharsets.UTF_8);

        store.purgeExpired();

        assertFalse(Files.exists(file(stale)));
    }

    @Test
    @DisplayName("只看问题应滤掉 info, 留下 warn 与 error")
    void filtersProblemsOnly() {
        store.record(leveled(TimelineEvent.Level.INFO, "正常一条"));
        store.record(leveled(TimelineEvent.Level.WARN, "警告一条"));
        store.record(leveled(TimelineEvent.Level.ERROR, "故障一条"));

        TimelineStore.Result result = store.query(
                new TimelineStore.Filter(null, true, null, null, null, null, 0));

        assertEquals(List.of("故障一条", "警告一条"), texts(result));
    }

    @Test
    @DisplayName("按类型筛选应只留那一类")
    void filtersByType() {
        store.record(typed(TimelineEventType.PUSH_MUTED, "静音丢的"));
        store.record(typed(TimelineEventType.PUSH_SENT, "推出去的"));

        TimelineStore.Result result = store.query(new TimelineStore.Filter(
                null, false, TimelineEventType.PUSH_MUTED, null, null, null, 0));

        assertEquals(List.of("静音丢的"), texts(result));
    }

    @Test
    @DisplayName("按主播与通道筛选应全等匹配")
    void filtersByStreamerAndChannel() {
        store.record(TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO)
                .streamer("甲主播").channel("群 111").text("甲的").build());
        store.record(TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO)
                .streamer("乙主播").channel("群 222").text("乙的").build());

        assertEquals(List.of("甲的"), texts(store.query(
                new TimelineStore.Filter(null, false, null, "甲主播", null, null, 0))));
        assertEquals(List.of("乙的"), texts(store.query(
                new TimelineStore.Filter(null, false, null, null, "群 222", null, 0))));
        assertEquals(List.of(), texts(store.query(
                new TimelineStore.Filter(null, false, null, "甲主播", "群 222", null, 0))),
                "两项都给时应同时满足");
    }

    @Test
    @DisplayName("关键词应搜到正文、主播、通道与补充信息")
    void searchesTextStreamerChannelAndDetail() {
        store.record(TimelineEvent.of(TimelineEventType.PUSH_FAILED, TimelineEvent.Level.ERROR)
                .streamer("甲主播").channel("群 111").text("推送失败：群号不存在")
                .detail("platform", "qq-onebot").build());
        store.record(TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO)
                .streamer("乙主播").channel("群 222").text("已推送：开播了").build());

        assertEquals(1, search("群号不存在"), "正文");
        assertEquals(1, search("甲主播"), "主播");
        assertEquals(1, search("群 111"), "通道");
        assertEquals(1, search("qq-onebot"), "补充信息");
        assertEquals(2, search("群"), "两条的通道里都有「群」");
        assertEquals(0, search("并不存在的词"));
    }

    @Test
    @DisplayName("超过条数上限时应如实报出命中总数与被截断")
    void reportsTruncation() {
        for (int i = 0; i < 5; i++) {
            store.record(leveled(TimelineEvent.Level.INFO, "第 " + i + " 条"));
        }

        TimelineStore.Result result = store.query(
                new TimelineStore.Filter(null, false, null, null, null, null, 2));

        assertEquals(2, result.events().size());
        assertEquals(5, result.matched(), "截断了也要说清一共命中多少");
        assertTrue(result.truncated());
    }

    @Test
    @DisplayName("重启后应从磁盘重建索引: 各日条数与最近若干条都在")
    void rebuildsIndexOnStartup() {
        LocalDate today = LocalDate.now();
        store.record(event(today, "重启前这条"));
        store.record(event(today.minusDays(1), "昨天那条"));

        TimelineStore restarted = new TimelineStore(properties);
        restarted.load();

        assertEquals(List.of(new TimelineStore.Day(today, 1), new TimelineStore.Day(today.minusDays(1), 1)),
                restarted.days());
        assertEquals(List.of("重启前这条", "昨天那条"),
                restarted.recent().stream().map(TimelineEvent::text).toList(),
                "最近若干条按时间倒序, 且跨日");
    }

    @Test
    @DisplayName("最近若干条有容量上限, 满了丢最旧的")
    void recentIsBounded() {
        int overflow = TimelineStore.RECENT_CAPACITY + 5;
        for (int i = 0; i < overflow; i++) {
            store.record(leveled(TimelineEvent.Level.INFO, "第 " + i + " 条"));
        }

        List<TimelineEvent> recent = store.recent();

        assertEquals(TimelineStore.RECENT_CAPACITY, recent.size());
        assertEquals("第 " + (overflow - 1) + " 条", recent.get(0).text());
        assertEquals("第 5 条", recent.get(recent.size() - 1).text());
    }

    @Test
    @DisplayName("坏行与认不出的类型应跳过, 不让整份时间线不可用")
    void skipsBadLines() throws IOException {
        store.record(event(LocalDate.now(), "好的那条"));
        Files.writeString(file(LocalDate.now()),
                "这不是 JSON\n{\"at\":1,\"type\":\"来自未来的类型\",\"level\":\"info\"}\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertEquals(List.of("好的那条"), texts(query(null)));
    }

    // —— 以下为夹具 ——

    private TimelineStore.Result query(LocalDate date) {
        return store.query(new TimelineStore.Filter(date, false, null, null, null, null, 0));
    }

    private int search(String keyword) {
        return store.query(new TimelineStore.Filter(null, false, null, null, null, keyword, 0)).matched();
    }

    private List<String> texts(TimelineStore.Result result) {
        return result.events().stream().map(TimelineEvent::text).toList();
    }

    private TimelineEvent event(LocalDate day, String text) {
        return TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO)
                .at(day.atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant())
                .text(text)
                .build();
    }

    private TimelineEvent leveled(TimelineEvent.Level level, String text) {
        return TimelineEvent.of(TimelineEventType.PROBE_CHANGED, level).text(text).build();
    }

    private TimelineEvent typed(TimelineEventType type, String text) {
        return TimelineEvent.of(type, TimelineEvent.Level.INFO).text(text).build();
    }

    private Path file(LocalDate day) {
        return dir.resolve("timeline").resolve(day + ".jsonl");
    }

    private long lineCount(Path path) {
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
