package org.frostnova.nova.bilibili.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 直播间原始报文调试日志不记全站下播名单
 * <p>
 * {@code STOP_LIVE_ROOM_LIST} 是平台下发的<b>全站</b>下播房间名单，每个连着的直播间各收一份，
 * 与本机关注的房间毫无关系，随后在见过表里直接丢掉。开着原始报文调试日志排障时，
 * 这一类能占掉当天六成的行，要看的那几条反而被淹了。
 * <p>
 * 只掐这一类。弹幕、对战这些排障时真要看的原文照记——把整张见过表都静音，
 * 等于把调试日志一起关掉。
 */
@DisplayName("原始报文调试日志跳过全站下播名单")
class LiveRoomRawMessageLogNoiseTest {

    private static final LiveStreamerInfo SOURCE = new LiveStreamerInfo(19805387116684L, "主播", 47731877194803L);

    private BilibiliEventParser parser;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    @BeforeEach
    void attach() {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getDebug().setLiveRoomRawMessageLog(true);
        parser = new BilibiliEventParser(properties, mock(BilibiliGiftService.class),
                mock(BilibiliApiSupport.class), mock(BilibiliGuardReconciler.class));

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(BilibiliEventParser.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    private void parseRaw(String cmd) {
        JSONObject data = new JSONObject();
        data.put("cmd", cmd);
        parser.parseMessage(data, SOURCE);
    }

    /**
     * 原始报文那一行的形状是 {@code 类型: 房间号 -> 报文}
     */
    private List<String> rawLines() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(" -> "))
                .toList();
    }

    @Test
    @DisplayName("弹幕原文照记——判据自己先看得见原始报文那一行")
    void danmakuStillLogsRawMessage() {
        parseRaw("DANMU_MSG");

        List<String> lines = rawLines();
        assertEquals(1, lines.size(), "弹幕类原文应照记, 实际: " + lines);
        assertTrue(lines.get(0).contains("DANMU_MSG"), "行里要看得出类型: " + lines.get(0));
    }

    @Test
    @DisplayName("对战类原文照记——阴性对照，不许顺手把整张见过表静音")
    void pkClassStillLogsRawMessage() {
        parseRaw("PK_BATTLE_START");

        List<String> lines = rawLines();
        assertEquals(1, lines.size(), "见过表里的对战类原文应照记, 实际: " + lines);
        assertTrue(lines.get(0).contains("PK_BATTLE_START"), "行里要看得出类型: " + lines.get(0));
    }

    @Test
    @DisplayName("全站下播名单不记——它与本机关注的房间无关")
    void stopLiveRoomListIsNotLogged() {
        for (int i = 0; i < 5; i++) {
            parseRaw("STOP_LIVE_ROOM_LIST");
        }

        assertEquals(List.of(), rawLines(),
                "全站下播名单的原文不占调试日志；5 份都记就是那股刷屏");
    }

    @Test
    @DisplayName("掐名单不许捎带掐掉解析——它在见过表里本来就该安静地丢掉")
    void stopLiveRoomListStaysKnownAndQuiet() {
        BilibiliEventParser.ParsedMessage parsed = parser.parseMessage(
                JSON.parseObject("{\"cmd\":\"STOP_LIVE_ROOM_LIST\",\"data\":{}}"), SOURCE);

        assertTrue(parsed.event().isEmpty(), "这一类不产出事件");
        assertFalse(parsed.degraded(), "它在见过表里是已知类型，不该记成未知或降级");
    }
}
