package org.frostnova.nova.core.alert;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Webhook 请求头的值不进报错与日志
 *
 * <h2>抓的是哪件用户故障</h2>
 * Authorization 一类的请求头，值本身就是密钥。往设置页里粘值时把行尾一起带上是常事：
 * 头值里混进回车换行或别的控制字符时，HTTP 客户端会拒发，而拒发的报错原文里带着
 * <b>整串头值</b>。这条起因链会进测试回话、时间线详情与工程日志——用户把日志贴出去求助，
 * 密钥就从那里漏。
 *
 * <h2>三格分别钉住什么</h2>
 * 第一格钉发送前那一查：坏头值不发请求，往外那句写头名、不写值。
 * 第二格钉起因链剥头值那一手：头值本身合法、别的失败把头值带进原文时，起因链里也找不到它。
 * 第三格是对照：合法的头照原样发出去，一字不差、不改写。
 */
@DisplayName("Webhook 请求头的值不进报错")
class WebhookHeaderValueLeakTest {

    /**
     * 明显是假的密钥串（只在测试里出现）。够长、自成一段，
     * 查片段时不会与栈迹里的类名撞上
     */
    private static final String SECRET = "FAKE-secret-Aa12Bb34Cc56";

    private static final String HEADER = "Authorization";

    private static final String URL = "https://example.invalid/push";

    /**
     * 查「任何一段」时的片段长：8 字起的片段一律不许有。
     * 再短的片段会与栈迹里的类名、方法名撞车，量到的就不是泄漏了
     */
    private static final int SEGMENT = 8;

    private NovaCoreProperties properties;

    private HttpUtil http;

    private WebhookAlertChannel channel;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        http = mock(HttpUtil.class);
        channel = new WebhookAlertChannel(properties, http);
        // 成败只看状态码，默认桩成 200；不桩的话 mock 返回 0，会被判成非 2xx
        when(http.postForStatus(anyString(), anyMap(), any(), any())).thenReturn(200);
    }

    /**
     * 抓的故障①：粘贴时多带一个回车，告警发不出去，报错却带着完整的密钥。
     * 要求：不发请求就按失败交出去；往外那句写明是哪个请求头、不写值；
     * 整条起因链每一层的 message、toString() 与打出来的栈迹文本里都找不到密钥的任何一段。
     */
    @Test
    @DisplayName("头值里带换行：不发请求，报错写明是哪个请求头，整条起因链不带密钥")
    void newlineInHeaderValueFailsBeforeSending() {
        properties.getAlert().setWebhookUrl(URL);
        properties.getAlert().getWebhookHeaders().put(HEADER, SECRET + "\r\n");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> channel.send("标题", "内容"));

        verify(http, never()).postForStatus(anyString(), anyMap(), any(), any());
        verify(http, never()).getForStatus(any(), anyMap(), any());
        assertTrue(error.getMessage().contains(HEADER),
                "往外那句要写明是哪个请求头:\n" + error.getMessage());
        assertNoTrace("报错起因链", error);
    }

    /**
     * 抓的故障②（第二道保险）：头值本身合法，但别的失败把头值带进了异常原文。
     * 发送前那一查对它管不着，靠的是剥起因链时把头值按字面换掉。
     */
    @Test
    @DisplayName("起因链里带头值：头值合法，失败原文带着它，起因链里也找不到")
    void causeChainCarriesNoHeaderValueEither() {
        properties.getAlert().setWebhookUrl(URL);
        properties.getAlert().getWebhookHeaders().put(HEADER, SECRET);
        // 阳性对照：注入的原文确实带着头值——真栈的拒发原文就是这么写的
        IOException deepest = new IOException("read failed, header was " + SECRET);
        ResourceAccessException raw = new ResourceAccessException(
                "I/O error on POST request: invalid header value \"" + SECRET + "\"", deepest);
        assertTrue(raw.getMessage().contains(SECRET) && deepest.getMessage().contains(SECRET),
                "注入没注入到头值，说明这把尺子量错了地方");
        when(http.postForStatus(anyString(), anyMap(), any(), any())).thenThrow(raw);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> channel.send("标题", "内容"));

        assertNotNull(error.getCause(), "起因链不该被整个丢掉:\n" + error);
        assertNoTrace("起因链", error);
    }

    /**
     * 对照格：合法的请求头照原样交给 HttpUtil，值一字不差、不去空白、不改大小写
     */
    @Test
    @DisplayName("正常请求头（对照）：原样发出去，值一字不差")
    void normalHeadersGoOutUnchanged() {
        properties.getAlert().setWebhookUrl(URL);
        properties.getAlert().getWebhookHeaders().put("Authorization", "Bearer token-123");
        properties.getAlert().getWebhookHeaders().put("X-Custom-Header", "  keep  spaces  ");

        channel.send("标题", "内容");

        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.captor();
        verify(http).postForStatus(anyString(), headers.capture(), any(), any());
        assertEquals("Bearer token-123", headers.getValue().get("Authorization"));
        assertEquals("  keep  spaces  ", headers.getValue().get("X-Custom-Header"));
    }

    /**
     * 起因链每一层的 message、toString() 与整条栈迹文本里都不许有密钥的任何一段
     */
    private static void assertNoTrace(String where, Throwable chain) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = chain; current != null && seen.add(current); current = current.getCause()) {
            assertNoSegment(where + " 的 message", current.getMessage());
            assertNoSegment(where + " 的 toString()", current.toString());
        }
        assertNoSegment(where + " 的栈迹文本", stackTraceText(chain));
    }

    private static void assertNoSegment(String where, String text) {
        if (text == null) {
            return;
        }
        assertFalse(text.contains(SECRET), leakMessage(where, "整串", 0, text));
        for (int i = 0; i + SEGMENT <= SECRET.length(); i++) {
            assertFalse(text.contains(SECRET.substring(i, i + SEGMENT)),
                    leakMessage(where, "第 " + i + " 字起 " + SEGMENT + " 字", i, text));
        }
    }

    /**
     * 断言失败的消息里也别把整串密钥打出来：只报长度、前两字与露出的位置，
     * 印出来的印文先把密钥换成掩码
     */
    private static String leakMessage(String where, String what, int at, String text) {
        return where + " 露出请求头的值（长度 " + SECRET.length()
                + "，前两字 " + SECRET.substring(0, 2) + "，露出的是" + what + "，位置 " + at + "）:\n" + masked(text);
    }

    private static String masked(String text) {
        String out = text.replace(SECRET, "***");
        for (int i = 0; i + SEGMENT <= SECRET.length(); i++) {
            out = out.replace(SECRET.substring(i, i + SEGMENT), "***");
        }
        return out;
    }

    /**
     * 把整条起因链按生产的印法打出来，返回落盘会长的样子
     */
    private static String stackTraceText(Throwable chain) {
        StringWriter sink = new StringWriter();
        chain.printStackTrace(new PrintWriter(sink));
        return sink.toString();
    }
}
