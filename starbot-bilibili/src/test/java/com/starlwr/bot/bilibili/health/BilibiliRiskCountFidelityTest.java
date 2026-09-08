package com.starlwr.bot.bilibili.health;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.DataPackType;
import com.starlwr.bot.bilibili.protocol.BilibiliPacketCodec;
import com.starlwr.bot.bilibili.service.BilibiliApiSupport;
import com.starlwr.bot.bilibili.service.BilibiliConnectGate;
import com.starlwr.bot.bilibili.service.BilibiliEventParser;
import com.starlwr.bot.bilibili.service.BilibiliGiftService;
import com.starlwr.bot.bilibili.service.BilibiliGuardReconciler;
import com.starlwr.bot.bilibili.service.BilibiliLiveRoomConnector;
import com.starlwr.bot.bilibili.service.BilibiliLiveStateGate;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.service.LiveDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * 风控计数的真值测试
 * <p>
 * 这些计数此前混着两种语义：一部分类目<b>逐次</b>写入指标，另一部分只在第 1、10、100…
 * 次写入一条，而健康页对两者用同一个 {@code count()} 去读。于是后者读出来的是
 * <b>写入次数</b>而不是发生次数——25 次解析失败读出 2。
 * 阈值都建在这把尺上，尺本身混着两种单位，阈值就没有意义。
 * <p>
 * 这里钉住三件事：计数是逐次真值、每类有条数上限且溢出可读、
 * 三个此前恒 0 的缺口（非法 JSON、解压预算爆、包长异常）确实接上了记账。
 */
@DisplayName("风控计数逐次真值")
class BilibiliRiskCountFidelityTest {
    private static final Duration HOUR = Duration.ofHours(1);

    /**
     * 期望的每类保留上限。上限对照喂 {@code CAP + 10} 条，读数应为上限本身、溢出 10
     */
    private static final int CAP = 2000;

    private static final long ROOM_ID = 47731877194803L;

    private static final long STREAMER_UID = 3000003L;

    private BilibiliRiskMetrics metrics;

    private StarBotBilibiliProperties properties;

    @BeforeEach
    void setUp() {
        metrics = new BilibiliRiskMetrics();
        properties = new StarBotBilibiliProperties();
    }

    @Test
    @DisplayName("同名解析失败 25 次读 25，不是写入次数 2")
    void sameNameParseFailuresCountEveryOccurrence() {
        BilibiliEventParser parser = newParser(metrics);
        LiveStreamerInfo source = new LiveStreamerInfo(STREAMER_UID, "测试主播", ROOM_ID);

        // live_time 不是数字，解析必抛；同一个 cmd 连喂 25 条
        JSONObject data = JSONObject.of("cmd", "LIVE", "live_time", "not-a-number");
        for (int i = 0; i < 25; i++) {
            parser.parseMessage(data, source);
        }

        assertEquals(25, metrics.count(BilibiliRiskMetrics.Kind.PARSE_FAILURE, HOUR),
                "解析失败要按发生次数读，量级去重只该影响文本样本");
    }

    @Test
    @DisplayName("operation=5 的负载不是 JSON 时记一次解析失败")
    void invalidJsonNoticeIsCountedAsParseFailure() {
        BilibiliLiveRoomConnector connector = newConnector(metrics);

        receive(connector, BilibiliPacketCodec.encode(DataPackType.NOTICE, "this-is-not-json"));

        assertEquals(1, metrics.count(BilibiliRiskMetrics.Kind.PARSE_FAILURE, HOUR),
                "非法 JSON 此前只有一条 debug 日志，整类静默丢失");
    }

    @Test
    @DisplayName("解压预算爆掉时记一次数据包异常")
    void budgetBlownIsCountedAsPacketAnomaly() {
        properties.getLive().setMaxDecompressedBytes(8);
        BilibiliLiveRoomConnector connector = newConnector(metrics);

        receive(connector, zlibPacket(BilibiliPacketCodec.encode(DataPackType.NOTICE, "{\"cmd\":\"LIVE\"}")));

        assertEquals(1, packetAnomalies(), "解压预算爆掉是整批消息消失，必须记账");
    }

    @Test
    @DisplayName("解压失败与包长异常同样记账，且分得出是哪一种")
    void decompressFailureAndBadLengthAreCountedSeparately() {
        BilibiliLiveRoomConnector connector = newConnector(metrics);

        // 声称是 zlib 压缩，负载却是随手写的几个字节，解压必抛
        byte[] corrupted = ByteBuffer.allocate(BilibiliPacketCodec.HEADER_LENGTH + 4)
                .putInt(BilibiliPacketCodec.HEADER_LENGTH + 4)
                .putShort((short) BilibiliPacketCodec.HEADER_LENGTH)
                .putShort((short) 2)
                .putInt(DataPackType.NOTICE.getCode())
                .putInt(1)
                .put(new byte[]{1, 2, 3, 4})
                .array();
        receive(connector, corrupted);
        assertEquals(1, packetAnomalies());
        assertEquals("decompress-failed",
                metrics.lastDetail(BilibiliRiskMetrics.Kind.PACKET_CORRUPT).orElse("").split("\\s+")[0]);

        // 整包长度字段声称 4 字节，比头部还短
        byte[] badLength = ByteBuffer.allocate(BilibiliPacketCodec.HEADER_LENGTH)
                .putInt(4)
                .putShort((short) BilibiliPacketCodec.HEADER_LENGTH)
                .putShort((short) 0)
                .putInt(DataPackType.NOTICE.getCode())
                .putInt(1)
                .array();
        receive(connector, badLength);
        assertEquals(2, packetAnomalies());
        assertEquals("bad-length",
                metrics.lastDetail(BilibiliRiskMetrics.Kind.PACKET_CORRUPT).orElse("").split("\\s+")[0]);
    }

    @Test
    @DisplayName("阴性对照：正常包不产生任何解析失败或数据包异常")
    void normalPacketsRaiseNothing() {
        BilibiliLiveRoomConnector connector = newConnector(metrics);
        byte[] inner = BilibiliPacketCodec.encode(DataPackType.NOTICE, "{\"cmd\":\"LIVE\"}");

        for (int i = 0; i < 5; i++) {
            receive(connector, inner);
            receive(connector, zlibPacket(inner));
        }

        assertEquals(0, metrics.count(BilibiliRiskMetrics.Kind.PARSE_FAILURE, HOUR));
        assertEquals(0, packetAnomalies());
        assertEquals(0, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_VER, HOUR));
    }

    @Test
    @DisplayName("上限对照：喂上限＋10 条，读数为上限，溢出计 10")
    void countStopsAtCapAndOverflowIsReadable() {
        for (int i = 0; i < CAP + 10; i++) {
            metrics.record(BilibiliRiskMetrics.Kind.CODE_352, null);
        }

        assertEquals(CAP, metrics.count(BilibiliRiskMetrics.Kind.CODE_352, HOUR),
                "每类要有条数上限，否则长期运行会无限增长");
        assertEquals(10, metrics.overflow(BilibiliRiskMetrics.Kind.CODE_352),
                "被上限挤掉的条数必须单独可读，否则「恰好顶格」与「二十万次」读出来一样");
    }

    // ================ 夹具 ================

    private long packetAnomalies() {
        return metrics.count(BilibiliRiskMetrics.Kind.PACKET_CORRUPT, HOUR);
    }

    private BilibiliEventParser newParser(BilibiliRiskMetrics riskMetrics) {
        return new BilibiliEventParser(properties, mock(BilibiliGiftService.class),
                mock(BilibiliApiSupport.class), mock(BilibiliGuardReconciler.class), riskMetrics);
    }

    /**
     * 一个只接得住「收到一段字节」的连接器：解析器与指标是真对象，其余依赖打桩。
     * 计数要看的是真实的解码与记账路径，把解析器换成 mock 就把要测的那一半打了桩
     */
    private BilibiliLiveRoomConnector newConnector(BilibiliRiskMetrics riskMetrics) {
        return new BilibiliLiveRoomConnector(
                new LiveStreamerInfo(STREAMER_UID, "测试主播", ROOM_ID),
                mock(BilibiliApiUtil.class),
                newParser(riskMetrics),
                properties,
                mock(ApplicationEventPublisher.class),
                mock(TaskScheduler.class),
                mock(WebSocketClient.class),
                mock(BilibiliLiveStateGate.class),
                mock(BilibiliConnectGate.class),
                riskMetrics,
                mock(BilibiliDisconnectDigest.class),
                mock(LiveDataService.class));
    }

    private static void receive(BilibiliLiveRoomConnector connector, byte[] payload) {
        try {
            connector.handleMessage(mock(WebSocketSession.class), new BinaryMessage(payload));
        } catch (Exception e) {
            throw new IllegalStateException("喂包时连接器抛了异常", e);
        }
    }

    /**
     * 把一段字节包成一个 zlib 压缩的数据包
     */
    private static byte[] zlibPacket(byte[] inner) {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(inner);
        } catch (Exception e) {
            throw new IllegalStateException("压缩夹具数据失败", e);
        }

        byte[] body = compressed.toByteArray();
        return ByteBuffer.allocate(BilibiliPacketCodec.HEADER_LENGTH + body.length)
                .putInt(BilibiliPacketCodec.HEADER_LENGTH + body.length)
                .putShort((short) BilibiliPacketCodec.HEADER_LENGTH)
                .putShort((short) 2)
                .putInt(DataPackType.NOTICE.getCode())
                .putInt(1)
                .put(body)
                .array();
    }
}
