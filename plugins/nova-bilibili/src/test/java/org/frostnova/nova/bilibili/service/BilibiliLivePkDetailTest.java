package org.frostnova.nova.bilibili.service;

import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.live.BilibiliPaidGiftEvent;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.protocol.NovaEventMapper;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.event.live.NovaBaseLiveEvent;
import org.frostnova.nova.core.model.GiftInfo;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.event.EventListener;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * 直播明细里 PK 开打与结算两类事件行
 * <p>
 * 走「报文 → 解析 → 聚合 → 明细留档」整条道：报文照真报文的形状造（只用占位号），
 * 断言只看 events.jsonl 里落下的行，不绑任何中间件的字段名。
 * <p>
 * 去重、本房站位、结果码这几件事都是用户会碰到的故障：记重了、把对手的票当成自己的、
 * 按票数推胜负——每一格都对着那句话写。
 */
@DisplayName("直播明细里的 PK 开打与结算")
class BilibiliLivePkDetailTest {

    private static final String PLATFORM = "bilibili";

    /** 本房：房间号 1001、主播 uid 10001（占位号） */
    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(10001L, "主播A", 1001L);

    private static final long START = 1_700_000_000_000L;

    /** 计划开始与结束，照真报文的秒级时刻写法 */
    private static final long PLAN_START = 1_700_000_000L;

    private static final long PLAN_END = 1_700_000_300L;

    @TempDir
    Path dir;

    private DefaultLiveDataService liveDataService;

    private LiveDetailArchive details;

    private BilibiliLiveStatsAggregator aggregator;

    private BilibiliEventParser parser;

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        liveDataService = new DefaultLiveDataService(properties);
        details = new LiveDetailArchive(properties);
        aggregator = new BilibiliLiveStatsAggregator(liveDataService, details);
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), START);

        // 上舰归并用的定时器在这条道上不会被触发，给个空壳即可；事件补全默认关闭，不碰接口
        parser = new BilibiliEventParser(new NovaBilibiliProperties(), mock(BilibiliGiftService.class),
                mock(BilibiliApiSupport.class),
                new BilibiliGuardReconciler(event -> {
                }, mock(ScheduledExecutorService.class), Duration.ZERO));
    }

    @Test
    @DisplayName("直播中打完一场 1v1 定时 PK，明细里什么都没留下——应恰有开打、结算各一行，本房票数与结果对；发起方与被匹配方两种站位都要对")
    void type6BattleLeavesOpenAndSettleRows() {
        List<String> red = new ArrayList<>();
        try {
            // 站位一：本房是发起方，本房胜（1892 : 661）
            List<Map<String, Object>> rows = play(
                    pkInfo(400000001L, 6, 201, PLAN_START, PLAN_END, 1001L, 10001L, PLAN_START,
                            member(1001L, 10001L, 0, 0, 0, 0),
                            member(1002L, 10002L, 0, 0, 0, 0)),
                    pkInfo(400000001L, 6, 401, PLAN_START, PLAN_END, 1001L, 10001L, 1_700_000_312L,
                            member(1001L, 10001L, 1892, 1, 1, 3),
                            member(1002L, 10002L, 661, 2, 0, 3)));
            assertEquals(2, rows.size(),
                    "① 一场 1v1 打完应恰有开打、结算各一行；什么都没留下，就是这场 PK 在明细里不存在");
            assertEquals(List.of("pk_open", "pk_settle"), types(rows), "① 两行的种类");
            Map<String, Object> open = rows.get(0);
            assertEquals(400000001L, num(open, "pk"), "① 开打行的场次号");
            assertEquals(6, num(open, "tp"), "① 开打行的 PK 类型");
            assertEquals(2, num(open, "nc"), "① 开打行的参加家数");
            assertEquals(PLAN_START, num(open, "ps"), "① 计划开始");
            assertEquals(PLAN_END, num(open, "pe"), "① 计划结束");
            assertEquals(1, ops(open).size(), "① 开打行的对手家数");
            assertEquals(1002L, num(ops(open).get(0), "r"), "① 对手房间号");
            assertEquals(10002L, num(ops(open).get(0), "u"), "① 对手主播 uid");
            Map<String, Object> settle = rows.get(1);
            assertEquals(400000001L, num(settle, "pk"), "① 结算行的场次号");
            assertEquals(6, num(settle, "tp"), "① 结算行的 PK 类型");
            assertEquals(2, num(settle, "nc"), "① 结算行的参加家数");
            assertEquals(0, num(settle, "er"), "① 正常结算不是提前结算");
            assertEquals(1_700_000_312L, num(settle, "en"), "① 实际结束时刻");
            assertEquals(1892, num(settle, "mv"), "① 本房票数——发起方站位下把对手的票当成自己的就反了");
            assertEquals(1, num(settle, "mr"), "① 本房名次");
            assertEquals(1, num(settle, "rs"), "① 本房结果：胜");
            assertEquals(1, ops(settle).size(), "① 结算行的对手家数");
            assertEquals(1002L, num(ops(settle).get(0), "r"), "① 对手房间号");
            assertEquals(10002L, num(ops(settle).get(0), "u"), "① 对手主播 uid");
            assertEquals(661, num(ops(settle).get(0), "v"), "① 对手票数");
            assertEquals(2, num(ops(settle).get(0), "k"), "① 对手名次");
        } catch (Throwable t) {
            red.add("① 发起方 " + t.getMessage());
        }
        try {
            // 站位二：本房是被匹配方，本房负（20 : 830）。发起方是对手，members 的先后也换了
            List<Map<String, Object>> rows = play(
                    pkInfo(400000002L, 6, 201, PLAN_START, PLAN_END, 1002L, 10002L, PLAN_START,
                            member(1002L, 10002L, 0, 0, 0, 0),
                            member(1001L, 10001L, 0, 0, 0, 0)),
                    pkInfo(400000002L, 6, 401, PLAN_START, PLAN_END, 1002L, 10002L, 1_700_000_312L,
                            member(1002L, 10002L, 830, 1, 1, 3),
                            member(1001L, 10001L, 20, 2, 0, 3)));
            assertEquals(2, rows.size(), "② 被匹配方站位同样应恰有开打、结算各一行");
            assertEquals(List.of("pk_open", "pk_settle"), types(rows), "② 两行的种类");
            Map<String, Object> open = rows.get(0);
            assertEquals(1, ops(open).size(), "② 开打行只列对手");
            assertEquals(1002L, num(ops(open).get(0), "r"), "② 对手房间号是发起方那家");
            assertEquals(10002L, num(ops(open).get(0), "u"), "② 对手主播 uid");
            Map<String, Object> settle = rows.get(1);
            assertEquals(20, num(settle, "mv"),
                    "② 本房票数——不能假定 members 的哪一侧是本房，按位置取就取成对手的 830 了");
            assertEquals(2, num(settle, "mr"), "② 本房名次");
            assertEquals(0, num(settle, "rs"), "② 本房结果：负");
            assertEquals(1, ops(settle).size(), "② 结算行只列对手");
            assertEquals(830, num(ops(settle).get(0), "v"), "② 对手票数");
            assertEquals(1, num(ops(settle).get(0), "k"), "② 对手名次");
            assertEquals(1002L, num(ops(settle).get(0), "r"), "② 对手房间号");
        } catch (Throwable t) {
            red.add("② 被匹配方 " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("1v1 老型 PK 平台只发对账消息、不发结算消息，结算没记下——开打与结算两行都要落，本房票数与结果对")
    void type2BattleSettlesToo() {
        List<String> red = new ArrayList<>();
        try {
            // type 2 的结算只在 PK_INFO 里；这一场本房负（3098 : 4259）
            List<Map<String, Object>> rows = play(
                    pkInfo(400000001L, 2, 201, PLAN_START, PLAN_END, 1001L, 10001L, PLAN_START,
                            member(1001L, 10001L, 0, 0, 0, 0),
                            member(1002L, 10002L, 0, 0, 0, 0)),
                    pkInfo(400000001L, 2, 401, PLAN_START, PLAN_END, 1001L, 10001L, 1_700_000_312L,
                            member(1001L, 10001L, 3098, 2, 0, 3),
                            member(1002L, 10002L, 4259, 1, 1, 3)));
            assertEquals(2, rows.size(), "① 老型 PK 一场打完应恰有开打、结算各一行；只认新式结算消息的话，这一场的结算就没了");
            assertEquals(List.of("pk_open", "pk_settle"), types(rows), "① 两行的种类");
            Map<String, Object> settle = rows.get(1);
            assertEquals(2, num(settle, "tp"), "① 结算行的 PK 类型");
            assertEquals(3098, num(settle, "mv"), "① 本房票数");
            assertEquals(2, num(settle, "mr"), "① 本房名次");
            assertEquals(0, num(settle, "rs"), "① 本房结果：负");
            assertEquals(1, ops(settle).size(), "① 对手家数");
            assertEquals(4259, num(ops(settle).get(0), "v"), "① 对手票数");
            assertEquals(1, num(ops(settle).get(0), "k"), "① 对手名次");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("多方 PK 打完名次与家数没记下——参加家数、本房名次、每个对手的名次都要落")
    void multiPartyBattleRecordsCountAndRanks() {
        List<String> red = new ArrayList<>();
        try {
            // 四方场，本房第 1 名
            List<Map<String, Object>> rows = play(
                    pkInfo(400000001L, 8, 201, PLAN_START, PLAN_END, 1002L, 10002L, PLAN_START,
                            member(1002L, 10002L, 0, 0, 0, 0),
                            member(1003L, 10003L, 0, 0, 0, 0),
                            member(1004L, 10004L, 0, 0, 0, 0),
                            member(1001L, 10001L, 0, 0, 0, 0)),
                    pkInfo(400000001L, 8, 401, PLAN_START, PLAN_END, 1002L, 10002L, 1_700_000_312L,
                            member(1002L, 10002L, 251, 3, 0, 3),
                            member(1003L, 10003L, 420, 2, 0, 3),
                            member(1004L, 10004L, 105, 4, 0, 3),
                            member(1001L, 10001L, 552, 1, 1, 3)));
            assertEquals(2, rows.size(), "① 多方场一场打完应恰有开打、结算各一行");
            Map<String, Object> open = rows.get(0);
            assertEquals(4, num(open, "nc"), "① 开打行的参加家数");
            assertEquals(3, ops(open).size(), "① 开打行的对手家数");
            Map<String, Object> settle = rows.get(1);
            assertEquals(4, num(settle, "nc"), "① 结算行的参加家数");
            assertEquals(8, num(settle, "tp"), "① 结算行的 PK 类型");
            assertEquals(1, num(settle, "mr"), "① 本房名次：第 1 名");
            assertEquals(552, num(settle, "mv"), "① 本房票数");
            assertEquals(1, num(settle, "rs"), "① 本房结果：胜");
            assertEquals(3, ops(settle).size(), "① 对手家数");
            assertEquals(List.of(3L, 2L, 4L), ranksOfOpponents(settle), "① 三个对手的名次");
            assertEquals(List.of(251L, 420L, 105L), votesOfOpponents(settle), "① 三个对手的票数");
            assertEquals(List.of(1002L, 1003L, 1004L), roomsOfOpponents(settle), "① 三个对手的房间号");
            assertEquals(List.of(10002L, 10003L, 10004L), uidsOfOpponents(settle), "① 三个对手的主播 uid");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("开打那条反复来、结算那条重发，明细里记成好几行——同一场同一阶段只记一行")
    void repeatedMessagesLeaveOneRowPerStage() {
        List<String> red = new ArrayList<>();
        try {
            String open = pkInfo(400000001L, 6, 201, PLAN_START, PLAN_END, 1001L, 10001L, PLAN_START,
                    member(1001L, 10001L, 0, 0, 0, 0),
                    member(1002L, 10002L, 0, 0, 0, 0));
            String settle = pkInfo(400000001L, 6, 401, PLAN_START, PLAN_END, 1001L, 10001L, 1_700_000_312L,
                    member(1001L, 10001L, 1892, 1, 1, 3),
                    member(1002L, 10002L, 661, 2, 0, 3));
            List<Map<String, Object>> rows = play(open, open, open, settle, settle);
            assertEquals(2, rows.size(),
                    "① 开打那条反复来、结算那条重发，同一场同一阶段只记一行；记成好几行，看明细的人会以为打了一场又一场");
            assertEquals(List.of("pk_open", "pk_settle"), types(rows), "① 两行的种类");
            assertEquals(1, rows.stream().filter(r -> "pk_open".equals(r.get("t"))).count(), "① 开打一行");
            assertEquals(1, rows.stream().filter(r -> "pk_settle".equals(r.get("t"))).count(), "① 结算一行");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            // 不同场次照记两套，别把去重做成全场只记一次。换两个没记过的场次号
            String open1 = pkInfo(400000003L, 6, 201, PLAN_START, PLAN_END, 1001L, 10001L, PLAN_START,
                    member(1001L, 10001L, 0, 0, 0, 0),
                    member(1002L, 10002L, 0, 0, 0, 0));
            String open2 = pkInfo(400000004L, 6, 201, PLAN_START, PLAN_END, 1001L, 10001L, PLAN_START,
                    member(1001L, 10001L, 0, 0, 0, 0),
                    member(1003L, 10003L, 0, 0, 0, 0));
            assertEquals(2, play(open1, open2).size(),
                    "② 相邻两场各自开打各记一行；把去重做成整场只记一次，第二场就没了");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("提前结算、0:0 却判本房胜，按票数推会记成平局——结果只读结果码")
    void earlySettleJudgedWinWithoutVotes() {
        List<String> red = new ArrayList<>();
        try {
            // 0 : 0 仍然判本房胜；按票数推会得出平局
            List<Map<String, Object>> rows = play(
                    pkInfo(400000001L, 6, 201, PLAN_START, PLAN_END, 1001L, 10001L, PLAN_START,
                            member(1001L, 10001L, 0, 0, 0, 0),
                            member(1002L, 10002L, 0, 0, 0, 0)),
                    pkInfo(400000001L, 6, 404, PLAN_START, PLAN_END, 1001L, 10001L, 1_700_000_114L,
                            member(1001L, 10001L, 0, 1, 1, 3),
                            member(1002L, 10002L, 0, 2, 0, 3)));
            assertEquals(2, rows.size(), "① 一场打完应恰有开打、结算各一行");
            Map<String, Object> settle = rows.get(1);
            assertEquals(1, num(settle, "er"), "① 提前结算要标出来");
            assertEquals(1_700_000_114L, num(settle, "en"), "① 实际结束时刻取这一条自己的时刻");
            assertEquals(0, num(settle, "mv"), "① 本房票数");
            assertEquals(1, num(settle, "rs"),
                    "① 本房结果是胜；按票数推会当成平局，可 0:0 的提前结算照样判了胜负");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("匹配失败那场（场次号 0）也记了一行——它不该占一行，同场真正的 PK 仍记开打、结算两行")
    void matchFailureIsNotRecorded() {
        List<String> red = new ArrayList<>();
        try {
            // 匹配失败只发 PK_INFO、场次号 0；它不该占一行。同场另打了一场正常的，两行都要在
            List<Map<String, Object>> rows = play(
                    pkInfo(0L, 8, 201, PLAN_START, PLAN_END, 1002L, 10002L, PLAN_START,
                            member(1002L, 10002L, 0, 0, 0, 0),
                            member(1001L, 10001L, 0, 0, 0, 0)),
                    pkInfo(0L, 8, 404, PLAN_START, PLAN_END, 1002L, 10002L, 1_700_000_114L,
                            member(1002L, 10002L, 0, 1, 1, 3),
                            member(1001L, 10001L, 0, 2, 0, 3)),
                    pkInfo(400000001L, 6, 201, PLAN_START, PLAN_END, 1001L, 10001L, PLAN_START,
                            member(1001L, 10001L, 0, 0, 0, 0),
                            member(1002L, 10002L, 0, 0, 0, 0)),
                    pkInfo(400000001L, 6, 401, PLAN_START, PLAN_END, 1001L, 10001L, 1_700_000_312L,
                            member(1001L, 10001L, 1892, 1, 1, 3),
                            member(1002L, 10002L, 661, 2, 0, 3)));
            assertEquals(2, rows.size(),
                    "① 匹配失败不该占一行，加上真正那场的开打与结算恰是两行；它占了行，明细里就多出一场没打过的 PK");
            assertEquals(List.of("pk_open", "pk_settle"), types(rows), "① 两行的种类");
            assertEquals(List.of(400000001L, 400000001L),
                    rows.stream().map(r -> num(r, "pk")).toList(),
                    "① 两行都属于真正那场，场次号 0 的一条都不该有");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("打完 PK 之后，本场礼物与收入各项读数不变")
    void pkLeavesGiftAndIncomeMetricsUntouched() {
        List<String> red = new ArrayList<>();
        try {
            aggregator.onPaidGift(new BilibiliPaidGiftEvent(STREAMER,
                    new UserInfo(10010L, "观众甲"),
                    new GiftInfo(31036L, "小花花", 5.2, 1, null), 5.2));
            Map<String, Double> before = snapshotMetrics();
            assertTrue(before.getOrDefault(BilibiliLiveMetric.GIFT_VALUE, 0.0) > 0,
                    "① 先让礼物读数动一下，这块盘才是活的；照不到活盘的话，「一动不动」是空对空");
            Map<String, List<Long>> usersBefore = snapshotMetricUsers();

            play(pkInfo(400000001L, 6, 201, PLAN_START, PLAN_END, 1001L, 10001L, PLAN_START,
                            member(1001L, 10001L, 0, 0, 0, 0),
                            member(1002L, 10002L, 0, 0, 0, 0)),
                    pkInfo(400000001L, 6, 401, PLAN_START, PLAN_END, 1001L, 10001L, 1_700_000_312L,
                            member(1001L, 10001L, 1892, 1, 1, 3),
                            member(1002L, 10002L, 661, 2, 0, 3)),
                    pkInfo(400000001L, 8, 404, PLAN_START, PLAN_END, 1002L, 10002L, 1_700_000_400L,
                            member(1001L, 10001L, 55200, 1, 1, 3),
                            member(1002L, 10002L, 25100, 3, 0, 3)));

            assertEquals(before, snapshotMetrics(),
                    "① PK 的票数不算收入也不算礼物；打完 PK 之后本场各项读数该一动不动，多出的格也不该有");
            assertEquals(usersBefore, snapshotMetricUsers(),
                    "② 按人计分的几张表同样不该被 PK 动到，更不该多出一张 PK 的表");
            assertNoMoneyNames(details.readEvents(PLATFORM, STREAMER.getUid(), START),
                    "③ PK 的落地行里连一个钱字都不该有：票数不是钱，golds 与助攻者一概不落；"
                            + "落了钱名的格就会被别的口径当收入捡走");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("对外事件输出里不该有 PK 事件")
    void pkEventStaysOutOfExternalEventOutput() {
        List<String> red = new ArrayList<>();
        try {
            for (String json : List.of(
                    pkInfo(400000001L, 6, 201, PLAN_START, PLAN_END, 1001L, 10001L, PLAN_START,
                            member(1001L, 10001L, 0, 0, 0, 0),
                            member(1002L, 10002L, 0, 0, 0, 0)),
                    pkInfo(400000001L, 6, 401, PLAN_START, PLAN_END, 1001L, 10001L, 1_700_000_312L,
                            member(1001L, 10001L, 1892, 1, 1, 3),
                            member(1002L, 10002L, 661, 2, 0, 3)))) {
                BilibiliEventParser.ParsedMessage parsed =
                        parser.parseMessage(JSON.parseObject(json), STREAMER);
                if (parsed.event().isPresent()) {
                    assertNull(NovaEventMapper.map(parsed.event().get()),
                            "① PK 事件不该被映射成对外事件，映射出来就会从事件输出那条道漏出去");
                }
            }
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    // ---------- 报文与读数 ----------

    /**
     * 报文里的一个成员。票数与 golds 都只是报文里的字节，落地时都不许带走；
     * 助攻者名单同样留在报文里不落地
     */
    private static String member(long room, long uid, long votes, int rank, int isWinner, int status) {
        return "{\"uid\":" + uid
                + ",\"room_id\":" + room
                + ",\"uname\":\"主播" + uid + "\""
                + ",\"votes\":" + votes
                + ",\"votes_text\":\"" + votes + "\""
                + ",\"golds\":" + (votes * 100)
                + ",\"rank\":" + rank
                + ",\"is_winner\":" + isWinner
                + ",\"status\":" + status
                + ",\"assist_info\":[{\"uid\":10009,\"uname\":\"助攻甲\",\"room_id\":1009}]}";
    }

    /**
     * 一条 PK_INFO。字段名与层级照真报文；uid、房间号、场次号一律占位值，
     * 不放任何真实号码。{@code biz_session_id} 在真报文里含主播 uid，样例里就是这个占位写法
     */
    private static String pkInfo(long pkId, int type, int status, long planStart, long planEnd,
                                 long initRoom, long initUid, long sentAt, String... members) {
        return "{\"cmd\":\"PK_INFO\",\"data\":{"
                + "\"audience_open\":false,"
                + "\"members\":[" + String.join(",", members) + "],"
                + "\"mill_timestamp\":" + (sentAt * 1000 + 375) + ","
                + "\"pk_basic\":{"
                + "\"biz_session_id\":\"(start_ms+init_uid)\","
                + "\"end_time\":" + planEnd + ","
                + "\"init_id\":" + initRoom + ","
                + "\"init_uid\":" + initUid + ","
                + "\"pk_id\":" + pkId + ","
                + "\"start_time\":" + planStart + ","
                + "\"status\":" + status + ","
                + "\"type\":" + type + "},"
                + "\"timestamp\":" + sentAt + "},"
                + "\"msg_id\":\"000000000000000001:1000:1000\","
                + "\"p_is_ack\":true,\"p_msg_type\":1,"
                + "\"send_time\":" + (sentAt * 1000 + 445) + "}";
    }

    /**
     * 报文进解析器，解析出的事件按 Spring 的分发口径送进聚合器，再读这一趟新落下的事件行
     * <p>
     * 分发照 {@code @EventListener} 方法的参数类型走，与把事件发进应用上下文是同一套判法：
     * 解析出什么就送什么，测试不点名任何具体事件类
     */
    private List<Map<String, Object>> play(String... fixtures) {
        List<Map<String, Object>> before = details.readEvents(PLATFORM, STREAMER.getUid(), START);
        for (String json : fixtures) {
            BilibiliEventParser.ParsedMessage parsed = parser.parseMessage(JSON.parseObject(json), STREAMER);
            parsed.event().ifPresent(this::deliver);
        }
        List<Map<String, Object>> all = details.readEvents(PLATFORM, STREAMER.getUid(), START);
        return all.subList(before.size(), all.size());
    }

    private void deliver(NovaBaseLiveEvent event) {
        for (Method method : aggregator.getClass().getMethods()) {
            if (method.isAnnotationPresent(EventListener.class)
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(event)) {
                try {
                    method.invoke(aggregator, event);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("事件送不进聚合器", e);
                }
            }
        }
    }

    /** 整块本场读数盘——连 PK 若偷偷新起一格都躲不过去 */
    private Map<String, Double> snapshotMetrics() {
        return new LinkedHashMap<>(liveDataService.getLiveMetrics(PLATFORM, STREAMER.getUid()));
    }

    /**
     * PK 的落地行里不许有钱的名字，也不许有助攻者
     * <p>
     * 连对手那一格里头一起翻，免得换个名字从嵌套里溜过去。礼物那行本来就带钱的字，
     * 所以只盘 PK 这两类行；末了再在整份明细里扫一遍，扫得出钱名才说明这把尺不瞎
     */
    private static void assertNoMoneyNames(List<Map<String, Object>> rows, String why) {
        List<String> stems = List.of("val", "pay", "gold", "assist");
        List<Map<String, Object>> pkRows = rows.stream()
                .filter(r -> "pk_open".equals(r.get("t")) || "pk_settle".equals(r.get("t")))
                .toList();
        assertTrue(!pkRows.isEmpty(), why + "；可一行 PK 都没落下来的话，这把尺等于没量");
        List<String> hits = new ArrayList<>();
        for (Map<String, Object> row : pkRows) {
            collectKeyNames(row, "", stems, hits);
        }
        assertTrue(hits.isEmpty(), why + "；不许出现的格名: " + hits);
        List<String> anywhere = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            collectKeyNames(row, "", stems, anywhere);
        }
        assertTrue(!anywhere.isEmpty(),
                "对照：礼物那行本来就该有钱的字；连整份明细里都扫不出一个钱名，说明这把尺是瞎的");
    }

    private static void collectKeyNames(Map<String, Object> node, String path,
                                        List<String> stems, List<String> hits) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String here = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            for (String stem : stems) {
                if (key.startsWith(stem)) {
                    hits.add(here);
                    break;
                }
            }
            if (entry.getValue() instanceof Map<?, ?> nested) {
                collectKeyNames((Map<String, Object>) nested, here, stems, hits);
            } else if (entry.getValue() instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> entryMap) {
                        collectKeyNames((Map<String, Object>) entryMap, here + "[]", stems, hits);
                    }
                }
            }
        }
    }

    /** 按人计分的几张表 */
    private Map<String, List<Long>> snapshotMetricUsers() {
        return new LinkedHashMap<>(liveDataService.getLiveMetricUserSets(PLATFORM, STREAMER.getUid()));
    }

    private static List<String> types(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> String.valueOf(r.get("t"))).toList();
    }

    private static long num(Map<String, Object> row, String key) {
        Object value = row.get(key);
        assertTrue(value instanceof Number, "行里 " + key + " 这一格应当是个数，拿到的却是 " + value);
        return ((Number) value).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> ops(Map<String, Object> row) {
        Object value = row.get("op");
        assertTrue(value instanceof List, "行里对手那一格应当是一串，拿到的却是 " + value);
        return (List<Map<String, Object>>) value;
    }

    private static List<Long> roomsOfOpponents(Map<String, Object> row) {
        return ops(row).stream().map(op -> num(op, "r")).toList();
    }

    private static List<Long> uidsOfOpponents(Map<String, Object> row) {
        return ops(row).stream().map(op -> num(op, "u")).toList();
    }

    private static List<Long> votesOfOpponents(Map<String, Object> row) {
        return ops(row).stream().map(op -> num(op, "v")).toList();
    }

    private static List<Long> ranksOfOpponents(Map<String, Object> row) {
        return ops(row).stream().map(op -> num(op, "k")).toList();
    }
}
