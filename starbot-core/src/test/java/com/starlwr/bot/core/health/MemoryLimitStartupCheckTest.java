package com.starlwr.bot.core.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.starlwr.bot.core.health.MemoryLimitStartupCheck.advise;
import static com.starlwr.bot.core.health.MemoryLimitStartupCheck.parseSize;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 内存上限自检测试
 * <p>
 * 用 2026-08-10 那次生产故障的真实数字做基准用例：
 * {@code -Xmx512m + MaxMetaspaceSize=192m}，单元里 {@code MemoryHigh=768M} / {@code MemoryMax=1G}，
 * 实测稳态峰值 896M。当时六项健康探针全绿，而进程一直在被节流。
 */
@DisplayName("内存上限自检")
class MemoryLimitStartupCheckTest {
    private static final long M = 1024L * 1024;

    /** 生产实际的 JVM 设置 */
    private static final long XMX = 512 * M;

    private static final long METASPACE = 192 * M;

    @Test
    @DisplayName("⚠️ 复现那次故障：MemoryHigh=768M 配 -Xmx512m + 元空间 192m，必须报出来")
    void catchesTheRealWorldMisconfiguration() {
        Optional<String> advice = advise(768 * M, 1024 * M, XMX, METASPACE);

        assertTrue(advice.isPresent(), "512+192+150=854M 装不进 768M，这正是那次线上故障");
        assertTrue(advice.get().contains("MemoryHigh"), advice.get());
    }

    @Test
    @DisplayName("抬到 920M 之后不再报——这是裁决 #8 定的新模板值")
    void acceptsTheFixedTemplateValue() {
        assertTrue(advise(920 * M, 1024 * M, XMX, METASPACE).isEmpty());
    }

    @Test
    @DisplayName("建议里要说清 MemoryHigh 是节流而不是警戒线")
    void adviceExplainsWhatMemoryHighActuallyDoes() {
        String advice = advise(768 * M, 1024 * M, XMX, METASPACE).orElseThrow();

        // 「配得低一点」听起来像保守做法，不说清是节流，下一个人还会照样配
        assertTrue(advice.contains("节流"), advice);
        assertTrue(advice.contains("memory.events"), "应给出确认是否正在被节流的办法: " + advice);
    }

    @Test
    @DisplayName("硬上限不够时报的是另一件事：会被 SIGKILL，不是变慢")
    void hardLimitFailureIsADifferentProblem() {
        String advice = advise(null, 700 * M, XMX, METASPACE).orElseThrow();

        assertTrue(advice.contains("MemoryMax"), advice);
        assertTrue(advice.contains("SIGKILL"), "软硬上限的后果不同，建议不能混为一谈: " + advice);
    }

    @Test
    @DisplayName("硬上限不够时优先报硬上限, 它更严重")
    void hardLimitOutranksSoftLimit() {
        String advice = advise(600 * M, 700 * M, XMX, METASPACE).orElseThrow();

        assertTrue(advice.contains("MemoryMax"), "两个都不够时应先说会被杀掉的那个: " + advice);
    }

    @Test
    @DisplayName("两边都够就不说话")
    void staysSilentWhenLimitsAreEnough() {
        assertTrue(advise(2048 * M, 4096 * M, XMX, METASPACE).isEmpty());
    }

    @Test
    @DisplayName("没有设上限（cgroup 写 max）时不判定")
    void staysSilentWhenUnlimited() {
        assertTrue(advise(null, null, XMX, METASPACE).isEmpty());
    }

    @Test
    @DisplayName("未设 MaxMetaspaceSize 时按 0 计, 不假装能算准")
    void treatsMissingMetaspaceAsZero() {
        // 512 + 0 + 150 = 662M，装得进 768M
        assertTrue(advise(768 * M, 1024 * M, XMX, null).isEmpty());
    }

    @Test
    @DisplayName("边界：正好等于所需不报，差一个字节就报")
    void boundaryIsExact() {
        long needed = XMX + METASPACE + MemoryLimitStartupCheck.SLACK_BYTES;

        assertTrue(advise(needed, null, XMX, METASPACE).isEmpty());
        assertTrue(advise(needed - 1, null, XMX, METASPACE).isPresent());
    }

    @Test
    @DisplayName("解析 192m / 1G / 纯字节三种写法")
    void parsesSizeSuffixes() {
        assertEquals(192 * M, parseSize("192m"));
        assertEquals(192 * M, parseSize("192M"));
        assertEquals(1024 * M, parseSize("1G"));
        assertEquals(1024L, parseSize("1k"));
        assertEquals(201326592L, parseSize("201326592"));
    }

    @Test
    @DisplayName("解析不了就返回空, 不抛异常——自检绝不能拖垮启动")
    void parseFailureIsSilent() {
        assertNull(parseSize("不是数字"));
        assertNull(parseSize(""));
        assertNull(parseSize("m"));
    }
}
