package com.starlwr.bot.core.converter;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 日志级别转换器测试
 * <p>
 * 这个转换器缺席时的后果不是「日志级别没生效」，而是<b>程序直接进入安全模式</b>——
 * 推送整个停摆。用例因此既覆盖能不能转，也覆盖填错时会不会安静地蒙混过去。
 */
@DisplayName("日志级别转换")
class LogLevelConverterTest {
    private LogLevelConverter converter;

    @BeforeEach
    void setUp() {
        converter = new LogLevelConverter();
    }

    @Test
    @DisplayName("各级别都应能转换")
    void convertsAllLevels() {
        assertEquals(Level.TRACE, converter.convert("TRACE"));
        assertEquals(Level.DEBUG, converter.convert("DEBUG"));
        assertEquals(Level.INFO, converter.convert("INFO"));
        assertEquals(Level.WARN, converter.convert("WARN"));
        assertEquals(Level.ERROR, converter.convert("ERROR"));
        assertEquals(Level.OFF, converter.convert("OFF"));
        assertEquals(Level.ALL, converter.convert("ALL"));
    }

    @Test
    @DisplayName("大小写与首尾空格都应容忍")
    void toleratesCaseAndWhitespace() {
        assertEquals(Level.DEBUG, converter.convert("debug"));
        assertEquals(Level.INFO, converter.convert("  Info  "));
    }

    @Test
    @DisplayName("留空表示不设置，应返回空而不是某个默认级别")
    void blankMeansUnset() {
        assertNull(converter.convert(""));
        assertNull(converter.convert("   "));
    }

    @Test
    @DisplayName("拼错时应报错，而不是像 Level.toLevel 那样悄悄退回 DEBUG")
    void rejectsUnknownLevel() {
        // Level.toLevel("IFNO") 会返回 DEBUG，使用者只会发现日志忽然暴增却不知原因
        assertEquals(Level.DEBUG, Level.toLevel("IFNO"), "这正是不能直接用 toLevel 的理由");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> converter.convert("IFNO"));
        assertEquals(true, e.getMessage().contains("IFNO"), "报错要指出是哪个值不对");
    }
}
