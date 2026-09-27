package org.frostnova.nova.adapter.onebot.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * one-bot-address 写 IPv6 字面量时的拼法与提醒
 *
 * <h2>抓的用户故障</h2>
 * 机器人与 NapCat 同机、走 ::1 时，地址照浏览器的写法填 {@code [::1]} 最自然。
 * 判「带了端口」要是见了冒号就算，写对的 {@code [::1]} 会被误报一句并不存在的
 * 「带了端口」；要加密的 {@code https://[::1]} 更会被赶到「照原样拼」那支，
 * 拼成 {@code http://https://[::1]:3000}，加密连不上。不带方括号的 {@code ::1}
 * 本来就拼不出能用的地址，提醒里只说「带了端口」，照着看不出来该怎么改。
 *
 * <h2>为什么只量拼出的串与提醒句</h2>
 * HTTP、Websocket、代理与连接测试拼的都是这一处：这里拼对了、提醒说对了，
 * 它们就全对。不去真连一个 IPv6 上的服务——量的是拼地址，不是握手。
 */
@DisplayName("IPv6 方括号地址的拼法与提醒")
class OneBotEndpointAddressesIpv6Test {

    private ListAppender<ILoggingEvent> assemblyLog;

    private ch.qos.logback.classic.Logger assemblyLogger;

    @BeforeEach
    void setUp() {
        assemblyLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OneBotEndpointAddresses.class);
        assemblyLog = new ListAppender<>();
        assemblyLog.start();
        assemblyLogger.addAppender(assemblyLog);
    }

    @AfterEach
    void tearDown() {
        assemblyLogger.detachAppender(assemblyLog);
        assemblyLog.stop();
    }

    @Test
    @DisplayName("[::1]、[fe80::1]：方括号里的冒号是地址自己的，不算带端口")
    void bracketedIpv6IsNotMistakenForAPort() {
        assertEquals("http://[::1]:3000", OneBotEndpointAddresses.httpBaseUrl("IPv6-裸写", "[::1]", 3000),
                "写对的 [::1] 该照常拼，与改前照原样拼出的是同一个串");
        assertEquals("ws://[fe80::1]:3001", OneBotEndpointAddresses.websocketUrl("IPv6-链路本地", "[fe80::1]", 3001),
                "链路本地地址一样只算主机");
        assertEquals(0, warnLines().size(), "写对的方括号地址不该报「带了端口」: " + warnLines());
    }

    @Test
    @DisplayName("https://[::1]、wss://[::1]：走加密，不再被赶到照原样拼那支")
    void bracketedIpv6WithSecureSchemeEncrypts() {
        assertEquals("https://[::1]:3000", OneBotEndpointAddresses.httpBaseUrl("IPv6-https头", "https://[::1]", 3000),
                "https://[::1] 的 HTTP 地址该是 https");
        assertEquals("wss://[::1]:3001", OneBotEndpointAddresses.websocketUrl("IPv6-wss头", "wss://[::1]", 3001),
                "wss://[::1] 的 Websocket 地址该是 wss");
        assertEquals(0, warnLines().size(), "写对的加密地址不该报「带了端口」: " + warnLines());
    }

    @Test
    @DisplayName("[::1]:3000：方括号后面的端口照旧算，提醒里看得出该怎么改")
    void portAfterTheBracketsStillCounts() {
        assertEquals("http://[::1]:3000:3000", OneBotEndpointAddresses.httpBaseUrl("IPv6-带端口", "[::1]:3000", 3000),
                "方括号后面紧跟的端口照旧按老写法照原样拼");
        assertEquals(1, warnLines().size(), "该打一句错，且只打一句: " + warnLines());
        String warn = warnLines().get(0);
        assertTrue(warn.contains("[::1]:3000"), "要带上原值: " + warn);
        assertTrue(warn.contains("方括号"), "要教一句 IPv6 地址怎么写: " + warn);
    }

    @Test
    @DisplayName("不带方括号的 ::1：照旧提醒，但提醒里看得出该怎么改")
    void bareIpv6IsToldHowToWriteIt() {
        // 平台名里不许带「方括号」三个字：那句提醒会点名平台，名字里有它断言就恒真了
        assertEquals("http://::1:3000", OneBotEndpointAddresses.httpBaseUrl("IPv6-裸冒号", "::1", 3000),
                "没写方括号的 IPv6 拼不出能用的地址，照旧按老写法照原样拼");
        assertEquals(1, warnLines().size(), "该打一句错，且只打一句: " + warnLines());
        String warn = warnLines().get(0);
        assertTrue(warn.contains("::1"), "要带上原值: " + warn);
        assertTrue(warn.contains("方括号"), "要教一句 IPv6 地址要加方括号: " + warn);
    }

    /**
     * 拼地址那一处打过的警告行。收集器这把锁先抄一份再逐条看
     */
    private List<String> warnLines() {
        synchronized (assemblyLog) {
            List<String> lines = new ArrayList<>();
            for (ILoggingEvent event : assemblyLog.list) {
                if (event.getLevel() == Level.WARN) {
                    lines.add(event.getFormattedMessage());
                }
            }
            return lines;
        }
    }
}
