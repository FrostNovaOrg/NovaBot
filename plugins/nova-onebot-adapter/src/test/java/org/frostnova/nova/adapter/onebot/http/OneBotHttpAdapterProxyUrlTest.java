package org.frostnova.nova.adapter.onebot.http;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * one-bot-address 里写协议头时，一次接口调用实际打到哪里
 *
 * <h2>抓的用户故障</h2>
 * NapCat 放在另一台机器上、前面挡着 TLS（反代或自身开了 https 端口）时，把地址写成
 * {@code https://主机} 是最自然的写法。适配器若只认明文，会照样拿 http:// 去连加密端口：
 * 连不上时看着像「服务没起」，真连上时访问令牌与消息全程明文走在两台机器之间。
 *
 * <h2>为什么量的是发出去的请求</h2>
 * 加密与否不在任何返回值里，只在「这次打到了哪个地址」里。体检、推送与连接测试走的
 * 都是同一个代理，这里钉住代理拼出的地址，就等于钉住了它们全部。
 */
@DisplayName("OneBot 地址带协议头时的实际请求地址")
class OneBotHttpAdapterProxyUrlTest {

    /**
     * 拼地址那一处的日志名。按名字取而不是按类取：这把尺要在还没有那一处的树上先跑红，
     * 按类取的话那时连编译都过不了，红就轮不到断言来报。
     */
    private static final String ASSEMBLY_LOGGER_NAME =
            "org.frostnova.nova.adapter.onebot.config.OneBotEndpointAddresses";

    private HttpUtil http;

    private OneBotHttpAdapter adapter;

    private ListAppender<ILoggingEvent> assemblyLog;

    private ch.qos.logback.classic.Logger assemblyLogger;

    @BeforeEach
    void setUp() {
        http = mock(HttpUtil.class);
        when(http.postJson(anyString(), anyMap(), any())).thenReturn(JSON.parseObject("{\"retcode\":0,\"data\":{}}"));
        adapter = (OneBotHttpAdapter) Proxy.newProxyInstance(
                OneBotHttpAdapter.class.getClassLoader(),
                new Class[]{OneBotHttpAdapter.class},
                new OneBotHttpAdapterProxy(http, new OneBotConnectionState()));

        assemblyLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ASSEMBLY_LOGGER_NAME);
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
    @DisplayName("只写主机、http://主机、ws://主机：照旧明文 http")
    void plainFormsStayPlain() {
        assertEquals("http://127.0.0.1:3000/get_version_info",
                urlLastCalledBy(senderTo("明文-只写主机", "127.0.0.1")));
        assertEquals("http://example.internal:3000/get_version_info",
                urlLastCalledBy(senderTo("明文-http头", "http://example.internal")));
        assertEquals("http://example.internal:3000/get_version_info",
                urlLastCalledBy(senderTo("明文-ws头", "ws://example.internal")));
    }

    @Test
    @DisplayName("https://主机 与 wss://主机：HTTP 请求改走 https，协议头不分大小写")
    void secureFormsUseHttps() {
        assertEquals("https://example.internal:3000/get_version_info",
                urlLastCalledBy(senderTo("加密-https头", "https://example.internal")));
        assertEquals("https://example.internal:3000/get_version_info",
                urlLastCalledBy(senderTo("加密-wss头", "wss://example.internal")));
        assertEquals("https://example.internal:3000/get_version_info",
                urlLastCalledBy(senderTo("加密-大写头", "HTTPS://example.internal")));
    }

    @Test
    @DisplayName("地址里带路径：不去猜，照原样拼，并打一句该怎么写")
    void pathInAddressKeepsPlainAssemblyAndSaysHowToWriteIt() {
        assertEquals("http://example.internal/onebot:3000/get_version_info",
                urlLastCalledBy(senderTo("怪写法-带路径", "example.internal/onebot")));

        assertEquals(1, warnLines().size(), "该打一句错，且只打一句");
        String warn = warnLines().get(0);
        assertTrue(warn.contains("怪写法-带路径"), "要点名是哪个机器人: " + warn);
        assertTrue(warn.contains("example.internal/onebot"), "要带上原值: " + warn);
        assertTrue(warn.contains("https://"), "要说清加密时该怎么写: " + warn);
    }

    @Test
    @DisplayName("地址里带端口：不去猜，照原样拼，并打一句该怎么写")
    void portInAddressKeepsPlainAssemblyAndSaysHowToWriteIt() {
        assertEquals("http://example.internal:3000:3000/get_version_info",
                urlLastCalledBy(senderTo("怪写法-带端口", "example.internal:3000")));

        assertEquals(1, warnLines().size(), "该打一句错，且只打一句");
        String warn = warnLines().get(0);
        assertTrue(warn.contains("怪写法-带端口"), "要点名是哪个机器人: " + warn);
        assertTrue(warn.contains("example.internal:3000"), "要带上原值: " + warn);
        assertTrue(warn.contains("https://"), "要说清加密时该怎么写: " + warn);
    }

    @Test
    @DisplayName("别的协议头（ftp://）：不去猜，照原样拼，并打一句该怎么写")
    void unknownSchemeKeepsPlainAssemblyAndSaysHowToWriteIt() {
        assertEquals("http://ftp://example.internal:3000/get_version_info",
                urlLastCalledBy(senderTo("怪写法-别的协议头", "ftp://example.internal")));

        assertEquals(1, warnLines().size(), "该打一句错，且只打一句");
        String warn = warnLines().get(0);
        assertTrue(warn.contains("怪写法-别的协议头"), "要点名是哪个机器人: " + warn);
        assertTrue(warn.contains("ftp://example.internal"), "要带上原值: " + warn);
        assertTrue(warn.contains("https://"), "要说清加密时该怎么写: " + warn);
    }

    @Test
    @DisplayName("同一个机器人的同一个怪写法，提醒只占一句")
    void unrecognizedFormIsWarnedOnlyOnce() {
        OneBotSender sender = senderTo("反复保存", "example.internal:3000");
        urlLastCalledBy(sender);
        urlLastCalledBy(sender);

        assertEquals(1, warnLines().size(), "拼地址发生在每次接口调用上，同一句不许按调用次数刷: " + warnLines());
    }

    private static OneBotSender senderTo(String name, String address) {
        OneBotSender sender = new OneBotSender();
        sender.setName(name);
        sender.setOneBotAddress(address);
        sender.setOneBotHttpPort(3000);
        sender.setOneBotHttpToken("secret");
        return sender;
    }

    /**
     * 让这位机器人做一次 getVersionInfo，把代理实际请求的地址取回来。
     * 同一个 mock 上前面几格已经打过若干次，取的是最后那一次。
     */
    private String urlLastCalledBy(OneBotSender sender) {
        adapter.getVersionInfo(sender, new JSONObject());
        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(http, atLeastOnce()).postJson(urls.capture(), anyMap(), any());
        return urls.getAllValues().get(urls.getAllValues().size() - 1);
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
