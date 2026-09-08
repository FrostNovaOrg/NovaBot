package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.analytics.LiveDetail;
import com.starlwr.bot.core.analytics.LiveHighlightFinder;
import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.model.DanmuRecord;
import com.starlwr.bot.core.model.LiveGap;
import com.starlwr.bot.core.model.RoomInfoSnapshot;
import com.starlwr.bot.core.model.SeriesPeak;
import com.starlwr.bot.core.model.UserScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 每场明细留档
 * <p>
 * 这份留档是<b>唯一一份原始数据</b>：曲线、排行、词频、弹幕原文在本场数据里下次开播就清零，
 * 报告图又只是缓存。因此本组用例最要紧的一条是<b>往返不丢东西</b>——
 * 写下去多少条，读回来还是多少条，一条都不能少。
 * <p>
 * 「少了」这件事在生产里是看不出来的：一份少了一半排行榜的明细，
 * 重画出来的报告仍然像模像样，只是榜短了一截，而没有人记得那一场原本有多长。
 */
@DisplayName("每场明细留档")
class LiveDetailArchiveTest {
    private static final String PLATFORM = "bilibili";

    /** ⚠️ 保留段假值：位数远超平台真实取值范围，与任何真人天然不重叠 */
    private static final long UID = 19604318752096L;

    private static final long START = 1_700_000_000_000L;

    private static final long MINUTE = 60_000L;

    /** 称重用的弹幕条数：四小时、平均每秒不到 1.5 条，是一场热闹但不异常的直播 */
    private static final int DANMU_COUNT = 20_000;

    @TempDir
    Path dir;

    private NovaCoreProperties properties;

    private LiveDetailArchive archive;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        archive = new LiveDetailArchive(properties);
    }

    @Test
    @DisplayName("① 往返：曲线点数、排行条数、弹幕条数、词频项数写前读后相等")
    void roundTripKeepsEveryCount() {
        for (int i = 0; i < 40; i++) {
            archive.appendDanmu(PLATFORM, UID, START, danmu(START + i * 1_000L, "第" + i + "句"));
        }
        LiveDetail written = detail(120, 57, 34);
        archive.store(written);

        LiveDetail read = archive.read(PLATFORM, UID, START).orElseThrow();

        assertAll(
                () -> assertEquals(120, read.series("danmu_count").size(), "曲线点数"),
                () -> assertEquals(57, read.ranking("danmu_users").size(), "排行条数"),
                () -> assertEquals(34, read.words().size(), "词频项数"),
                () -> assertEquals(40, archive.readDanmu(PLATFORM, UID, START).size(), "弹幕原文条数"),
                () -> assertEquals(written.series("danmu_count"), read.series("danmu_count"), "曲线逐点同值"),
                () -> assertEquals(written.ranking("danmu_users"), read.ranking("danmu_users"), "排行逐条同值"),
                () -> assertEquals(written.words(), read.words(), "词频逐项同值"),
                () -> assertEquals(written.peaks(), read.peaks(), "峰值同值"),
                () -> assertEquals(written.highlights(), read.highlights(), "高能时刻同值"),
                () -> assertEquals(written.titles(), read.titles(), "标题轨迹同值"),
                () -> assertEquals(written.gaps(), read.gaps(), "缺口区间同值"));
    }

    @Test
    @DisplayName("① 排行榜留的是全量，不是报告图上画的前几名")
    void rankingKeepsTheLongTail() {
        archive.store(detail(3, 200, 5));

        List<UserScore> ranking = archive.read(PLATFORM, UID, START).orElseThrow().ranking("danmu_users");

        assertEquals(200, ranking.size(),
                "报告图上只画前几名是版面所限；留档只留前几名, 第 30 名往后的人就永久没有了");
        assertEquals(0.0, ranking.get(199).score(), "长尾那一端的分数也要原样留着");
    }

    @Test
    @DisplayName("弹幕原文逐条落盘：时刻、uid、昵称、原文、类型五项都在")
    void danmuKeepsEveryField() {
        archive.appendDanmu(PLATFORM, UID, START,
                new DanmuRecord(START + MINUTE, 19338207415562L, "观众甲", "好听", DanmuRecord.Type.DANMU));

        DanmuRecord read = archive.readDanmu(PLATFORM, UID, START).get(0);

        assertAll(
                () -> assertEquals(START + MINUTE, read.at()),
                () -> assertEquals(19338207415562L, read.uid()),
                () -> assertEquals("观众甲", read.uname()),
                () -> assertEquals("好听", read.text()),
                () -> assertEquals(DanmuRecord.Type.DANMU, read.type()));
    }

    @Test
    @DisplayName("事件流水：写读往返、封存后不写、缺场次空表")
    void eventStreamWriteReadSealAndMissing() {
        List<String> red = new ArrayList<>();
        Map<String, Object> gift = new LinkedHashMap<>();
        gift.put("gid", 31036);
        gift.put("gn", "小花花");
        gift.put("n", 2);
        gift.put("val", 100);
        Map<String, Object> follow = new LinkedHashMap<>();
        follow.put("uid", 19338207415562L);
        follow.put("un", "观众甲");
        long giftAt = START + MINUTE;
        long followAt = START + 2 * MINUTE;

        try {
            archive.appendEvent(PLATFORM, UID, START, giftAt, "gift", gift);
            archive.appendEvent(PLATFORM, UID, START, followAt, "follow", follow);
            List<Map<String, Object>> events = archive.readEvents(PLATFORM, UID, START);
            assertEquals(2, events.size(), "① 条数");
            assertEventRoundTrip(events.get(0), giftAt, "gift", gift);
            assertEventRoundTrip(events.get(1), followAt, "follow", follow);
            Path path = dir.resolve("details").resolve(PLATFORM + "-" + UID + "-" + START).resolve("events.jsonl");
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            assertEquals(2, lines.size(), "① JSON 行数");
            for (String line : lines) {
                JSONObject json = JSON.parseObject(line);
                List<String> keys = new ArrayList<>(json.keySet());
                assertEquals("at", keys.get(0), line);
                assertEquals("t", keys.get(1), line);
            }
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            archive.store(detail(1, 1, 1));
            archive.appendEvent(PLATFORM, UID, START, START + 10 * MINUTE, "gift", gift);
            assertEquals(2, archive.readEvents(PLATFORM, UID, START).size(), "② 封存后仍 2 条");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            List<Map<String, Object>> missing = archive.readEvents(PLATFORM, UID, START + 1);
            assertTrue(missing.isEmpty(), "③ 缺场次应空");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }
        try {
            Map<String, Object> withAt = new LinkedHashMap<>(gift);
            withAt.put("at", 1L);
            assertThrows(IllegalArgumentException.class,
                    () -> archive.appendEvent(PLATFORM, UID, START, START, "gift", withAt),
                    "④ 含 at");
            Map<String, Object> withT = new LinkedHashMap<>(gift);
            withT.put("t", "x");
            assertThrows(IllegalArgumentException.class,
                    () -> archive.appendEvent(PLATFORM, UID, START, START, "gift", withT),
                    "④ 含 t");
            assertEquals(2, archive.readEvents(PLATFORM, UID, START).size(), "④ 拒收后仍 2 条");
        } catch (Throwable t) {
            red.add("④ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("付费留言不计入弹幕密度，文字与表情弹幕计入")
    void onlyDanmuAndEmojiCountAsDanmu() {
        assertAll(
                () -> assertTrue(danmu(START, "话").countsAsDanmu()),
                () -> assertTrue(new DanmuRecord(START, 1L, "甲", "笑", DanmuRecord.Type.EMOJI).countsAsDanmu()),
                () -> assertFalse(new DanmuRecord(START, 1L, "甲", "谢谢", DanmuRecord.Type.SUPER_CHAT).countsAsDanmu(),
                        "把一条 30 元的付费留言算进弹幕密度, 它在曲线上就等价于一句「哈哈」"));
    }

    @Test
    @DisplayName("明细落盘即封存：下播之后的零星弹幕不再追加进这一场")
    void sealedAfterStore() {
        archive.appendDanmu(PLATFORM, UID, START, danmu(START, "播中"));
        archive.store(detail(1, 1, 1));

        archive.appendDanmu(PLATFORM, UID, START, danmu(START + 10 * MINUTE, "下播之后"));

        assertEquals(1, archive.readDanmu(PLATFORM, UID, START).size(),
                "这些弹幕在统计里本就该被丢弃（开播清零时一并丢）, 留档这一侧不拦的话, "
                        + "原文条数会比场次里的弹幕数多出一截, 而看不出多在哪");
    }

    @Test
    @DisplayName("开播时刻差一毫秒就是另一场，不许糊到同一个目录里")
    void startTimeIsTheKey() {
        archive.appendDanmu(PLATFORM, UID, START, danmu(START, "这一场"));

        assertTrue(archive.readDanmu(PLATFORM, UID, START + 1).isEmpty());
    }

    @Test
    @DisplayName("没留过的那一场读出来是空，而不是报错")
    void missingIsEmpty() {
        assertAll(
                () -> assertFalse(archive.has(PLATFORM, UID, START)),
                () -> assertTrue(archive.read(PLATFORM, UID, START).isEmpty()),
                () -> assertTrue(archive.readDanmu(PLATFORM, UID, START).isEmpty()));
    }

    @Test
    @DisplayName("平台名撬不开目录：带斜杠或点号的一律不落盘")
    void platformNameCannotEscape() {
        archive.store(detail(1, 1, 1, "../../etc"));
        archive.appendDanmu("../../etc", UID, START, danmu(START, "话"));

        assertAll(
                () -> assertFalse(archive.has("../../etc", UID, START)),
                () -> assertTrue(Files.notExists(dir.resolve("details")) || isEmptyDirectory(dir.resolve("details")),
                        "平台名是从请求路径里来的, 它一旦能拼进目录名, 就能拼出目录外"));
    }

    @Test
    @DisplayName("坏掉的一行跳过，其余原文照旧读得出来")
    void oneBadLineDoesNotKillTheRest() throws IOException {
        archive.appendDanmu(PLATFORM, UID, START, danmu(START, "第一句"));
        Path path = dir.resolve("details").resolve(PLATFORM + "-" + UID + "-" + START).resolve("danmu.jsonl");
        Files.writeString(path, "{这不是 JSON" + System.lineSeparator(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        archive.appendDanmu(PLATFORM, UID, START, danmu(START + 1000, "第三句"));

        List<DanmuRecord> read = archive.readDanmu(PLATFORM, UID, START);

        assertEquals(List.of("第一句", "第三句"), read.stream().map(DanmuRecord::text).toList(),
                "原文是一次性的, 这一场丢了再也补不回来——坏一行不能连累整场");
    }

    @Test
    @DisplayName("保留期默认为 0：明细永久留着，清理跑过也一场不删")
    void retentionDefaultsToForever() {
        archive.store(detail(1, 1, 1));

        archive.purgeExpired();

        assertEquals(0, properties.getLive().getDetailRetentionDays(), "默认永久保留");
        assertTrue(archive.has(PLATFORM, UID, START));
    }

    @Test
    @DisplayName("设了保留期就按开播时刻删，没到期的与认不出名字的都不动")
    void purgeByStartTime() throws IOException {
        long old = System.currentTimeMillis() - 30L * 86_400_000L;
        long fresh = System.currentTimeMillis() - 86_400_000L;
        properties.getLive().setDetailRetentionDays(7);
        archive.store(detail(1, 1, 1, PLATFORM, old));
        archive.store(detail(1, 1, 1, PLATFORM, fresh));
        Path stranger = Files.createDirectories(dir.resolve("details").resolve("有人放的东西"));
        // 落盘那一步不清理，因此这一场此刻必须还在——不先断这一句的话，
        // 下面的「该删」会绿在另一条判法上：它压根没被写进去过
        assertTrue(archive.has(PLATFORM, UID, old), "清理之前它得先真的在");

        archive.purgeExpired();

        assertAll(
                () -> assertFalse(archive.has(PLATFORM, UID, old), "30 天前那一场该删"),
                () -> assertTrue(archive.has(PLATFORM, UID, fresh), "昨天那一场没到期"),
                () -> assertTrue(Files.isDirectory(stranger),
                        "清理程序删掉自己不认识的东西, 比留着不该留的更糟"));
    }

    @Test
    @DisplayName("清理挂在启动上：一台不再开播的机器也会把过期明细清掉")
    void purgesOnStartup() {
        // 时刻取一次记在局部里：两处各调一次 currentTimeMillis 的话，
        // 存的与查的会是两个毫秒、两个目录名，而这条用例照样绿
        long old = System.currentTimeMillis() - 30L * 86_400_000L;
        properties.getLive().setDetailRetentionDays(7);
        archive.store(detail(1, 1, 1, PLATFORM, old));

        archive.purgeOnStartup();

        assertFalse(archive.has(PLATFORM, UID, old),
                "清理只挂在落盘上的话, 「保留 N 天」在一台已经停播的机器上就是一句空话");
    }

    @Test
    @DisplayName("落盘那一步不清理：开播时刻已在窗口外的一场，不会把自己刚写的明细当场删掉")
    void storeDoesNotPurgeWhatItJustWrote() {
        properties.getLive().setDetailRetentionDays(7);

        archive.store(detail(1, 1, 1, PLATFORM, START));

        assertTrue(archive.has(PLATFORM, UID, START),
                "写与删同在一次调用里的话, 删掉的理由还看起来完全正当");
    }

    @Test
    @DisplayName("列举已留档的场次，按开播时刻升序")
    void listsArchivedSessions() {
        archive.store(detail(1, 1, 1, PLATFORM, START + 2 * MINUTE));
        archive.store(detail(1, 1, 1, PLATFORM, START));

        List<LiveDetailArchive.Archived> list = archive.list();

        assertEquals(List.of(START, START + 2 * MINUTE), list.stream().map(LiveDetailArchive.Archived::startTime).toList());
        assertEquals(PLATFORM, list.get(0).platform());
        assertEquals(UID, list.get(0).uid());
    }

    @Test
    @DisplayName("只追加过弹幕、还没落明细的那一场不算已留档")
    void danmuAloneIsNotArchived() {
        archive.appendDanmu(PLATFORM, UID, START, danmu(START, "播到一半"));

        assertFalse(archive.has(PLATFORM, UID, START),
                "还在播的那一场点开只会看到半份数据, 而半份数据与完整的一份在界面上长得一样");
    }

    @Test
    @DisplayName("一场四小时直播的明细占多少盘：量出来，别估")
    void oneSessionCostsThisMuchDisk() throws IOException {
        for (int i = 0; i < DANMU_COUNT; i++) {
            archive.appendDanmu(PLATFORM, UID, START, new DanmuRecord(
                    START + i * 700L, 19_000_000_000_000L + i % 900, "观众" + i % 900,
                    "这一句是第" + i + "条弹幕", DanmuRecord.Type.DANMU));
        }
        archive.store(fourHourDetail());

        Path dir = this.dir.resolve("details").resolve(PLATFORM + "-" + UID + "-" + START);
        long detailBytes = Files.size(dir.resolve("detail.json"));
        long danmuBytes = Files.size(dir.resolve("danmu.jsonl"));
        JSONObject json = JSON.parseObject(Files.readString(dir.resolve("detail.json"), StandardCharsets.UTF_8));
        // 各段单独再序列化一次来称重：合计比文件总字节略小，差在外层的键名与逗号
        System.out.println("明细字节数（4 小时 / 6 条曲线 × 240 点 / 4 张排行 × 500 名 / 800 个词 / "
                + DANMU_COUNT + " 条弹幕原文）:"
                + " detail.json=" + detailBytes
                + " 其中曲线=" + weigh(json, "series")
                + " 排行=" + weigh(json, "rankings")
                + " 词频=" + weigh(json, "words")
                + "; danmu.jsonl=" + danmuBytes
                + "; 合计=" + (detailBytes + danmuBytes));

        assertTrue(detailBytes + danmuBytes < 8L * 1024 * 1024,
                "「默认永久保留」这个决定是建立在单场量级之上的: 单场若真到几十 MB, "
                        + "永久保留就是替使用者决定了把盘写满, 而这件事要到写满那天才看得见");
    }

    @Test
    @DisplayName("detail.json 顶层键集钉死")
    void detailFileKeySetIsPinned() throws Exception {
        // 这份 JSON 的顶层键名同样是外部工具直接消费的接口面。
        // 手写序列化挡得住反射带来的悄悄变形，挡不住「顺手再加一个键」——
        // 这一格把现状钉死：多一个键、少一个键都红。
        archive.store(detail(1, 1, 1));

        JSONObject json = JSON.parseObject(Files.readString(dir.resolve("details")
                .resolve(PLATFORM + "-" + UID + "-" + START).resolve("detail.json"), StandardCharsets.UTF_8));

        List<String> red = new ArrayList<>();
        try {
            assertEquals(Set.of("version", "platform", "uid", "uname", "roomId", "startTime", "endTime",
                    "durationSeconds", "metrics", "userCounts", "series", "rankings", "words",
                    "highlights", "titles", "gaps", "peaks"), json.keySet(), "① 顶层键集");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertEquals(Set.of("uid", "uname", "face", "score"),
                    json.getJSONObject("rankings").getJSONArray("danmu_users").getJSONObject(0).keySet(),
                    "② 榜项键集（名次由数组下标给出，不落键）");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 一段单独序列化之后的字节数
     */
    private int weigh(JSONObject json, String key) {
        return json.getJSONObject(key).toJSONString().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 一场四小时直播：六条曲线、四张排行、八百个词
     * <p>
     * 形状取自哔哩哔哩下播报告实际会画的那几项，条数取偏满的一侧——
     * 量出来的数是拿去决定保留期的，量一场冷清的直播等于没量。
     */
    private LiveDetail fourHourDetail() {
        Map<String, Map<Long, Double>> series = new LinkedHashMap<>();
        for (String metric : List.of("interaction_count", "danmu_count", "gift_count",
                "super_chat_count", "guard_count", "watched_count")) {
            Map<Long, Double> one = new TreeMap<>();
            for (int i = 0; i < 240; i++) {
                one.put(START + i * MINUTE, (double) (i * 37 % 211));
            }
            series.put(metric, one);
        }

        Map<String, List<UserScore>> rankings = new LinkedHashMap<>();
        Map<String, Integer> userCounts = new LinkedHashMap<>();
        for (String metric : List.of("danmu_users", "gift_users", "super_chat_users", "guard_users")) {
            List<UserScore> one = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                // uid 一律取保留段假值；头像地址按真实那一路的长度取，短了会把排行称轻
                one.add(new UserScore(19_000_000_000_000L + i, "观众" + i,
                        "https://example.invalid/face/" + (19_000_000_000_000L + i) + ".jpg", 500 - i));
            }
            rankings.put(metric, one);
            userCounts.put(metric, one.size());
        }

        Map<String, Integer> words = new LinkedHashMap<>();
        for (int i = 0; i < 800; i++) {
            words.put("词" + i, 800 - i);
        }

        Map<String, SeriesPeak> peaks = new LinkedHashMap<>();
        series.forEach((metric, buckets) -> peaks.put(metric, new SeriesPeak(START, 210)));

        return new LiveDetail(LiveDetail.VERSION, PLATFORM, UID, "主播甲", 20002L,
                START, START + 240 * MINUTE, 14400,
                Map.of("danmu_count", 20000.0), userCounts, series, rankings, words,
                List.of(new LiveHighlightFinder.Highlight(START + 42 * MINUTE, 211, 4.25)),
                List.of(new RoomInfoSnapshot(START, "开播时的标题", "虚拟主播")),
                List.of(), peaks);
    }

    private boolean isEmptyDirectory(Path path) throws IOException {
        try (var entries = Files.list(path)) {
            return entries.findAny().isEmpty();
        }
    }

    private DanmuRecord danmu(long at, String text) {
        return new DanmuRecord(at, 19338207415562L, "观众甲", text, DanmuRecord.Type.DANMU);
    }

    private void assertEventRoundTrip(Map<String, Object> event, long at, String type, Map<String, Object> fields) {
        assertEquals(at, ((Number) event.get("at")).longValue(), "at");
        assertEquals(type, event.get("t"), "t");
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            Object actual = event.get(entry.getKey());
            Object expected = entry.getValue();
            if (expected instanceof Number && actual instanceof Number) {
                assertEquals(((Number) expected).longValue(), ((Number) actual).longValue(), entry.getKey());
            } else {
                assertEquals(String.valueOf(expected), String.valueOf(actual), entry.getKey());
            }
        }
    }

    private LiveDetail detail(int seriesPoints, int rankingRows, int wordCount) {
        return detail(seriesPoints, rankingRows, wordCount, PLATFORM, START);
    }

    private LiveDetail detail(int seriesPoints, int rankingRows, int wordCount, String platform) {
        return detail(seriesPoints, rankingRows, wordCount, platform, START);
    }

    private LiveDetail detail(int seriesPoints, int rankingRows, int wordCount, String platform, long start) {
        Map<Long, Double> danmuSeries = new TreeMap<>();
        for (int i = 0; i < seriesPoints; i++) {
            danmuSeries.put(start + i * MINUTE, (double) (i % 17));
        }

        List<UserScore> ranking = new ArrayList<>();
        for (int i = 0; i < rankingRows; i++) {
            // uid 一律取保留段假值，与真实取值范围不重叠
            ranking.add(new UserScore(19_000_000_000_000L + i, "观众" + i, "face-" + i, rankingRows - 1 - i));
        }

        Map<String, Integer> words = new LinkedHashMap<>();
        for (int i = 0; i < wordCount; i++) {
            words.put("词" + i, wordCount - i);
        }

        return new LiveDetail(LiveDetail.VERSION, platform, UID, "主播甲", 20002L,
                start, start + 120 * MINUTE, 7200,
                Map.of("danmu_count", 1384.0),
                Map.of("danmu_users", rankingRows),
                Map.of("danmu_count", danmuSeries),
                Map.of("danmu_users", ranking),
                words,
                List.of(new LiveHighlightFinder.Highlight(start + 42 * MINUTE, 34, 4.25)),
                List.of(new RoomInfoSnapshot(start, "开播时的标题", "虚拟主播"),
                        new RoomInfoSnapshot(start + 42 * MINUTE, "改过一次的标题", "虚拟主播")),
                List.of(new LiveGap(start + 10 * MINUTE, start + 12 * MINUTE, LiveGap.Reason.RESTART)),
                Map.of("danmu_count", new SeriesPeak(start + 42 * MINUTE, 34)));
    }
}
