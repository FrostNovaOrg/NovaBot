package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.live.BilibiliPaidGiftEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliRandomGiftEvent;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.service.BilibiliEventParserTest.PbWriter;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.event.live.NovaBaseLiveEvent;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 一条 {@code SEND_GIFT_V2} 带几个礼物块（一次开多个盲盒、开出几种礼物）时，从收包到入账的全程
 * <p>
 * 报文走真实的连接器解码与解析，发布出去的事件逐件交给真实的统计聚合器入账；
 * 取值全部合成，不含真实观众。
 */
@DisplayName("一条礼物消息带几个礼物块")
class BilibiliGiftV2MultiBlockTest {
    private static final String PLATFORM = "bilibili";

    private static final long START = 1_700_000_000_000L;

    @TempDir
    Path dir;

    private BilibiliConnectorHarness harness;

    private DefaultLiveDataService liveDataService;

    private LiveDetailArchive details;

    private BilibiliLiveStatsAggregator aggregator;

    @BeforeEach
    void setUp() {
        BilibiliEventParser parser = new BilibiliEventParser(new NovaBilibiliProperties(),
                mock(BilibiliGiftService.class), mock(BilibiliApiSupport.class),
                new BilibiliGuardReconciler(event -> { }, mock(ScheduledExecutorService.class), Duration.ZERO),
                new BilibiliRiskMetrics());
        harness = new BilibiliConnectorHarness(parser);

        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        liveDataService = new DefaultLiveDataService(properties);
        details = new LiveDetailArchive(properties);
        aggregator = new BilibiliLiveStatsAggregator(liveDataService, details);
        liveDataService.setLiveStartTime(PLATFORM, BilibiliConnectorHarness.STREAMER_UID, START);
    }

    /** 盒子：一种盲盒，盒价 9 元 */
    private static PbWriter box() {
        return new PbWriter()
                .varint(1, 144)
                .varint(2, 90001)
                .str(3, "测试盲盒")
                .str(5, "爆出")
                .varint(6, 9000);
    }

    /** 一个礼物块，单价与实扣按千分之一元 */
    private static PbWriter block(long id, String name, int num, int price, int totalCoin, long ts) {
        return new PbWriter()
                .varint(1, id)
                .str(2, name)
                .varint(3, num)
                .varint(6, price)
                .varint(7, totalCoin)
                .str(8, "gold")
                .varint(10, ts);
    }

    private static String body(PbWriter message) {
        return "{\"cmd\":\"SEND_GIFT_V2\",\"data\":{\"dmscore\":3,\"pb\":\"" + message.base64() + "\"}}";
    }

    /** 送礼人放在前头，与样本报文的顺序一致：1 uid、2 昵称，再是 9 盒子与 10 礼物块 */
    private static PbWriter sender() {
        return new PbWriter().varint(1, 7001).str(2, "观众甲");
    }

    /**
     * 连接器发布出去的事件逐件交给聚合器，按类型分派，与容器里的监听器一致
     */
    private List<NovaBaseLiveEvent> deliver() {
        List<NovaBaseLiveEvent> events = new ArrayList<>();
        for (Object published : harness.publishedEvents()) {
            if (published instanceof BilibiliRandomGiftEvent box) {
                aggregator.onRandomGift(box);
            } else if (published instanceof BilibiliPaidGiftEvent gift) {
                aggregator.onPaidGift(gift);
            }
            if (published instanceof NovaBaseLiveEvent event) {
                events.add(event);
            }
        }
        return events;
    }

    private double metric(String name) {
        return liveDataService.getLiveMetric(PLATFORM, BilibiliConnectorHarness.STREAMER_UID, name);
    }

    /**
     * 逐问各自捕获、末尾汇总，一问红不许短路其余问
     */
    private static void tally(List<String> reds, String question, Runnable check) {
        try {
            check.run();
        } catch (AssertionError | RuntimeException e) {
            reds.add(question + " " + e.getMessage());
        }
    }

    /**
     * 抓的故障：观众一次开了 5 个盲盒、开出三种礼物，平台把三种礼物放在同一条消息的三个礼物块里，
     * 程序只记最后一种——本场礼物收入、实付、盲盒个数与盈亏都少记，主播拿去和平台后台对不上
     */
    @Test
    @DisplayName("一个盒子开出三种礼物：三块各记一笔，本场礼物价值、实付、盲盒个数、盈亏都是三块之和")
    void chargesEveryOpenedGiftInOneMessage() {
        PbWriter message = sender()
                .message(9, box())
                .message(10, block(101, "开出物甲", 1, 2000, 9000, 1700000101L))
                .message(10, block(102, "开出物乙", 2, 5000, 18000, 1700000102L))
                .message(10, block(103, "开出物丙", 2, 10000, 18000, 1700000103L));

        harness.receiveBody(body(message));
        List<NovaBaseLiveEvent> events = deliver();

        List<String> reds = new ArrayList<>();
        tally(reds, "① 三块出三件", () -> assertEquals(3, events.size(), "事件数"));
        tally(reds, "② 各块各算", () -> {
            List<String> names = new ArrayList<>();
            double[] values = {2.0, 10.0, 20.0};
            double[] charged = {9.0, 18.0, 18.0};
            int[] counts = {1, 2, 2};
            for (int i = 0; i < 3; i++) {
                BilibiliRandomGiftEvent event = assertInstanceOf(BilibiliRandomGiftEvent.class, events.get(i));
                names.add(event.getGiftInfo().getName());
                assertEquals(counts[i], event.getGiftInfo().getCount(), "第 " + (i + 1) + " 块数量");
                assertEquals(values[i], event.getValue(), 0.0001, "第 " + (i + 1) + " 块开出价值");
                assertEquals(charged[i], event.getCharged(), 0.0001, "第 " + (i + 1) + " 块实扣");
                assertEquals(9.0 * counts[i], event.getPrice(), 0.0001, "第 " + (i + 1) + " 块盒价×数量");
                assertEquals("测试盲盒", event.getRandomGiftInfo().getName(), "盒子共用顶层 9 号");
                assertEquals(7001L, event.getSender().getUid(), "送礼人共用顶层");
                assertEquals((1700000101L + i) * 1000, event.getTimestamp(), "时间戳按本块取");
            }
            assertEquals(List.of("开出物甲", "开出物乙", "开出物丙"), names, "按报文顺序");
        });
        tally(reds, "③ 本场礼物价值", () -> assertEquals(32.0, metric(BilibiliLiveMetric.GIFT_VALUE), 0.0001));
        tally(reds, "③ 本场实付", () -> assertEquals(45.0, metric(BilibiliLiveMetric.GIFT_PAID), 0.0001));
        tally(reds, "③ 盲盒个数", () -> assertEquals(5.0, metric(BilibiliLiveMetric.BOX_COUNT), 0.0001));
        tally(reds, "③ 盲盒盈亏", () -> assertEquals(-13.0, metric(BilibiliLiveMetric.BOX_PROFIT), 0.0001));
        tally(reds, "④ 每场明细三行", () -> {
            List<Map<String, Object>> rows = details.readEvents(PLATFORM, BilibiliConnectorHarness.STREAMER_UID, START);
            assertEquals(List.of("开出物甲", "开出物乙", "开出物丙"), rows.stream().map(row -> row.get("gn")).toList());
        });
        System.out.printf("多块盲盒读数: 事件=%d 礼物价值=%.2f 实付=%.2f 盲盒=%.0f 盈亏=%.2f%n",
                events.size(), metric(BilibiliLiveMetric.GIFT_VALUE), metric(BilibiliLiveMetric.GIFT_PAID),
                metric(BilibiliLiveMetric.BOX_COUNT), metric(BilibiliLiveMetric.BOX_PROFIT));

        assertTrue(reds.isEmpty(), () -> reds.size() + " 问红：" + String.join("；", reds));
    }

    /**
     * 抓的故障：不带盒子的一条消息里有两块普通礼物时，只记了后一块
     */
    @Test
    @DisplayName("不带盒子的两块普通礼物各记一笔")
    void chargesEveryPlainGiftBlock() {
        PbWriter message = sender()
                .message(10, block(201, "礼物甲", 1, 1000, 1000, 1700000201L))
                .message(10, block(202, "礼物乙", 3, 500, 1500, 1700000202L));

        harness.receiveBody(body(message));
        List<NovaBaseLiveEvent> events = deliver();

        assertEquals(2, events.size(), "两块出两件");
        assertTrue(events.stream().allMatch(BilibiliPaidGiftEvent.class::isInstance), "都是付费礼物");
        assertEquals(2.5, metric(BilibiliLiveMetric.GIFT_VALUE), 0.0001, "1×1 + 0.5×3");
        assertEquals(2.5, metric(BilibiliLiveMetric.GIFT_PAID), 0.0001);
    }

    /**
     * 阴性对照，抓的故障：改成逐块入账后，平常只有一块的盲盒消息多记或少记
     */
    @Test
    @DisplayName("只有一块的盲盒消息照旧记一笔")
    void singleBlockUnchanged() {
        PbWriter message = sender()
                .message(9, box())
                .message(10, block(101, "开出物甲", 1, 2000, 9000, 1700000101L));

        harness.receiveBody(body(message));
        List<NovaBaseLiveEvent> events = deliver();

        assertEquals(1, events.size());
        BilibiliRandomGiftEvent event = assertInstanceOf(BilibiliRandomGiftEvent.class, events.get(0));
        assertEquals(2.0, event.getValue(), 0.0001);
        assertEquals(9.0, event.getCharged(), 0.0001);
        assertEquals(1.0, metric(BilibiliLiveMetric.BOX_COUNT), 0.0001);
        assertEquals(-7.0, metric(BilibiliLiveMetric.BOX_PROFIT), 0.0001);
    }
}
