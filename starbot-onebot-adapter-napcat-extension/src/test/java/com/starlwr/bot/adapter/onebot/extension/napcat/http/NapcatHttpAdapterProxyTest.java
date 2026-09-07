package com.starlwr.bot.adapter.onebot.extension.napcat.http;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.exception.OneBotApiException;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.util.HttpUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NapCat 扩展接口的代理：一次调用最终打成什么样的 HTTP 请求
 *
 * <h2>为什么这把尺量的是请求本身</h2>
 * 这个代理没有自己的返回值可言，它整个存在就是「把一次方法调用翻译成一次 HTTP 往返」。
 * 因此判据全部落在<b>翻译的结果</b>上：打给谁（地址）、带什么（请求头与请求体）、
 * 从回来的报文里取哪一段、错误码怎么变成异常。
 *
 * <h2>已知的怪脾气，本尺一并钉住</h2>
 * 下面几条是<b>现状</b>而非「应该如此」，重写不许顺手改掉：
 * <ul>
 *     <li>回来的报文里缺 {@code retcode} 时抛 {@link NullPointerException} 而不是接口异常——
 *         装箱比较踩空，错处离原因很远</li>
 *     <li>{@code data} 缺席时安静地回 {@code null}，调用方拿到的是空手</li>
 *     <li>请求根本没回来（HTTP 层回 {@code null}）时同样是 {@link NullPointerException}</li>
 * </ul>
 */
@DisplayName("NapCat 扩展接口代理")
class NapcatHttpAdapterProxyTest {
    private HttpUtil http;

    private NapcatHttpAdapterProxy handler;

    private NapcatHttpAdapter adapter;

    private final OneBotSender sender = sender();

    private static OneBotSender sender() {
        OneBotSender sender = new OneBotSender();
        sender.setName("napcat-qq");
        sender.setOneBotAddress("127.0.0.1");
        sender.setOneBotHttpPort(3000);
        sender.setOneBotHttpToken("secret");
        return sender;
    }

    @BeforeEach
    void setUp() {
        http = mock(HttpUtil.class);
        handler = new NapcatHttpAdapterProxy(http);
        adapter = (NapcatHttpAdapter) Proxy.newProxyInstance(
                NapcatHttpAdapter.class.getClassLoader(),
                new Class[]{NapcatHttpAdapter.class},
                handler);
    }

    /**
     * 让 NapCat 对任意请求回一段固定的原文
     */
    private void replyWith(String json) {
        when(http.postJson(anyString(), anyMap(), any())).thenReturn(JSON.parseObject(json));
    }

    @Test
    @DisplayName("两支接口各打各的地址, 地址由平台的主机与端口拼出")
    void callsTheAnnotatedUrls() {
        replyWith("{\"retcode\":0,\"data\":{}}");

        adapter.getGroupAtAllRemain(sender, new JSONObject());
        adapter.setGroupTodo(sender, new JSONObject());

        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(http, times(2)).postJson(urls.capture(), anyMap(), any());

        assertEquals("http://127.0.0.1:3000/get_group_at_all_remain", urls.getAllValues().get(0));
        assertEquals("http://127.0.0.1:3000/set_group_todo", urls.getAllValues().get(1));
    }

    @Test
    @DisplayName("请求头带本平台的 OneBot Token")
    void carriesTheSenderToken() {
        replyWith("{\"retcode\":0,\"data\":{}}");

        adapter.getGroupAtAllRemain(sender, new JSONObject());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers =
                ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
        verify(http).postJson(anyString(), headers.capture(), any());

        assertEquals(1, headers.getValue().size());
        assertEquals("Bearer secret", headers.getValue().get("Authorization"));
    }

    @Test
    @DisplayName("请求体就是调用方给的那一份, 原样送出不加工")
    void passesParamsThrough() {
        replyWith("{\"retcode\":0,\"data\":{}}");
        JSONObject params = new JSONObject();
        params.put("group_id", 700100200L);

        adapter.setGroupTodo(sender, params);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(http).postJson(anyString(), anyMap(), body.capture());

        assertSame(params, body.getValue());
        assertEquals("{\"group_id\":700100200}", ((JSONObject) body.getValue()).toJSONString());
    }

    @Test
    @DisplayName("只把 data 一段交给调用方, 外层的 retcode 不外传")
    void returnsOnlyTheDataSection() {
        replyWith("{\"status\":\"ok\",\"retcode\":0,\"data\":{\"can_at_all\":true,\"remain_at_all_count_for_group\":9}}");

        JSONObject data = adapter.getGroupAtAllRemain(sender, new JSONObject());

        assertEquals("{\"can_at_all\":true,\"remain_at_all_count_for_group\":9}", data.toJSONString());
    }

    @Test
    @DisplayName("⚠️ data 缺席时回 null, 不抛异常")
    void missingDataYieldsNull() {
        replyWith("{\"status\":\"ok\",\"retcode\":0}");

        assertNull(adapter.getGroupAtAllRemain(sender, new JSONObject()));
    }

    @Test
    @DisplayName("retcode 非 0 抛接口异常, 异常里带得回接口名、请求参数、错误码与信息")
    void nonZeroRetcodeThrows() {
        replyWith("{\"retcode\":1200,\"message\":\"group not found\"}");
        JSONObject params = new JSONObject();
        params.put("group_id", 1L);

        OneBotApiException thrown = assertThrows(OneBotApiException.class,
                () -> adapter.setGroupTodo(sender, params));

        assertEquals("/set_group_todo", thrown.getApi());
        assertSame(params, thrown.getParams());
        assertEquals(1200, thrown.getCode());
        assertEquals("group not found", thrown.getMsg());
        assertEquals("OneBot API 请求异常, 接口: /set_group_todo, 请求参数: {\"group_id\":1}, 错误码: 1200, 信息: group not found",
                thrown.getMessage());
    }

    @Test
    @DisplayName("⚠️ 报文缺 retcode 时抛空指针, 不是接口异常")
    void missingRetcodeThrowsNullPointer() {
        replyWith("{\"status\":\"ok\",\"data\":{}}");

        assertThrows(NullPointerException.class, () -> adapter.getGroupAtAllRemain(sender, new JSONObject()));
    }

    @Test
    @DisplayName("⚠️ 请求没回来时抛空指针")
    void nullResponseThrowsNullPointer() {
        when(http.postJson(anyString(), anyMap(), any())).thenReturn(null);

        assertThrows(NullPointerException.class, () -> adapter.getGroupAtAllRemain(sender, new JSONObject()));
    }

    @Test
    @DisplayName("没标注解的方法一律拒绝, 不会当成接口打出去")
    void unannotatedMethodIsRejected() throws Exception {
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> handler.invoke(adapter, Runnable.class.getMethod("run"), null));

        assertTrue(thrown.getMessage().startsWith("不支持的方法 "));
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("equals 按同一个对象判, hashCode 与 toString 不打接口")
    void objectMethodsStayLocal() {
        NapcatHttpAdapter another = (NapcatHttpAdapter) Proxy.newProxyInstance(
                NapcatHttpAdapter.class.getClassLoader(),
                new Class[]{NapcatHttpAdapter.class},
                handler);

        // 不用 assertEquals：它先比引用再调 equals，同一个对象时根本不会走进被测的那段
        assertTrue(adapter.equals(adapter));
        assertFalse(adapter.equals(another));
        assertEquals(System.identityHashCode(adapter), adapter.hashCode());
        assertEquals(handler.toString(), adapter.toString());
        verifyNoInteractions(http);
    }
}
