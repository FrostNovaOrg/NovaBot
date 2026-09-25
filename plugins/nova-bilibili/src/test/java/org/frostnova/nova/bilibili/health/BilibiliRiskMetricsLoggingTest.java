package org.frostnova.nova.bilibili.health;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风控指标的调试日志：只有带样本的时候才值得占一行
 * <p>
 * 每一次风控事件都计一次数，调用方只在量级处附上一份文本样本，平时传 null。
 * 把 null 也照打，排障时打开调试日志会先被几千行「记录风控指标 …: null」淹掉，
 * 真正带上下文的那几行反而找不着。
 * <p>
 * 计数、溢出、最近一次时刻与样本都不受影响：这把尺只掐掉一行日志，
 * 捎带改到读数上就是两回事了。
 */
@DisplayName("风控指标调试日志只在有样本时打")
class BilibiliRiskMetricsLoggingTest {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    @BeforeEach
    void attach() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(BilibiliRiskMetrics.class);
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

    private List<String> rendered() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("记录风控指标"))
                .toList();
    }

    /**
     * 🔴 阳性对照：有样本时这条尺子确实看得见那一行。
     * 少了它，下面几条在「日志根本没接上」时同样是绿的。
     */
    @Test
    @DisplayName("有样本时照打一行——判据自己先看得见")
    void nonBlankDetailStillLogsOneLine() {
        new BilibiliRiskMetrics().record(BilibiliRiskMetrics.Kind.HTTP_412, "第 3 次命中");

        List<String> lines = rendered();
        assertEquals(1, lines.size(), "有样本时应恰一行, 实际: " + lines);
        assertTrue(lines.get(0).contains("HTTP 412"), "行里要看得出是哪一类: " + lines.get(0));
        assertTrue(lines.get(0).contains("第 3 次命中"), "行里要带着样本: " + lines.get(0));
    }

    @Test
    @DisplayName("detail 传 null 时不打——这正是每天几千行的那一类")
    void nullDetailLogsNothing() {
        BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
        for (int i = 0; i < 50; i++) {
            metrics.record(BilibiliRiskMetrics.Kind.CODE_509, null);
        }

        assertEquals(List.of(), rendered(),
                "只计数、没有样本的记录不占工程日志的行；50 次都打就是那股刷屏");
    }

    @Test
    @DisplayName("detail 是空白串时同样不打")
    void blankDetailLogsNothing() {
        BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
        metrics.record(BilibiliRiskMetrics.Kind.CODE_509, " ");
        metrics.record(BilibiliRiskMetrics.Kind.CODE_509, "");

        assertEquals(List.of(), rendered(), "空白串与 null 同一口径, 实际: " + rendered());
    }

    @Test
    @DisplayName("掐日志不能捎带掐掉计数与样本")
    void countsAndSamplesStayIntact() {
        BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
        metrics.record(BilibiliRiskMetrics.Kind.GAIA, null);
        metrics.record(BilibiliRiskMetrics.Kind.GAIA, null);
        metrics.record(BilibiliRiskMetrics.Kind.GAIA, "滑块");

        assertEquals(3, metrics.count(BilibiliRiskMetrics.Kind.GAIA, java.time.Duration.ofMinutes(5)),
                "三次调用就是三次计数，其中两次只计数、一次带样本");
        assertEquals("滑块", metrics.lastDetail(BilibiliRiskMetrics.Kind.GAIA).orElse(null),
                "带样本那次要留下样本");
        assertTrue(metrics.last(BilibiliRiskMetrics.Kind.GAIA).isPresent(), "发生时刻照记");

        BilibiliRiskMetrics onlyNull = new BilibiliRiskMetrics();
        onlyNull.record(BilibiliRiskMetrics.Kind.GAIA, null);
        assertTrue(onlyNull.lastDetail(BilibiliRiskMetrics.Kind.GAIA).isEmpty(),
                "只计数那次不该造出样本");
        assertFalse(rendered().isEmpty(),
                "上面那次带样本的调用本来就该打过行——这条防的是判据接错了 logger");
    }
}
