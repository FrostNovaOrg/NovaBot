package org.frostnova.nova.bilibili.util;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.exception.NetworkException;
import org.frostnova.nova.bilibili.exception.RiskCooldownException;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.model.Cookies;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 只发一次的表单 POST
 * <p>
 * 给发出去就收不回的写接口用（往直播间发弹幕）。重试次数在这里配成 3：照只读接口的口径连试的话，
 * 每一格都会多出两次请求。
 */
@DisplayName("只发一次的表单请求")
class BilibiliApiPostOnceTest {
    private static final String URL = "https://api.live.bilibili.com/msg/send";

    private HttpUtil http;

    private BilibiliApiUtil api;

    private final List<Map<String, Object>> forms = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        http = mock(HttpUtil.class);
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getNetwork().setApiRetryMaxTimes(3);
        properties.getNetwork().setApiRetryInterval(0);
        api = new BilibiliApiUtil(http, properties, mock(BilibiliRiskMetrics.class));
        api.setCookies(new Cookies("sess-value", "jct-value", "buvid"));
    }

    @SuppressWarnings("unchecked")
    private void answer(Object... responses) {
        var stub = when(http.postJsonAsForm(anyString(), any(), any()));
        for (Object response : responses) {
            if (response instanceof RuntimeException e) {
                stub = stub.thenAnswer(invocation -> {
                    forms.add(Map.copyOf((Map<String, Object>) invocation.getArgument(2)));
                    throw e;
                });
            } else {
                stub = stub.thenAnswer(invocation -> {
                    forms.add(Map.copyOf((Map<String, Object>) invocation.getArgument(2)));
                    return response;
                });
            }
        }
    }

    private static HttpClientErrorException http412() {
        return HttpClientErrorException.create(HttpStatus.PRECONDITION_FAILED, "blocked",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    /**
     * 抓的用户故障：请求超时了程序自己再发一遍，平台其实已经收到第一条，直播间里出现两条一样的弹幕
     */
    @Test
    @DisplayName("网络异常只发一次，不重发，交回「结果不明」")
    void networkFailureIsNotRetried() {
        answer(new ResourceAccessException("read timed out"));

        assertThrows(NetworkException.class, () -> api.postFormOnce(URL, Map.of("msg", "你好")));

        verify(http, times(1)).postJsonAsForm(anyString(), any(), any());
    }

    /**
     * 抓的用户故障：弹幕被屏蔽词拦下时平台回 code 0，只给 data 的话这一句就丢了，界面上显示「已发出」
     */
    @Test
    @DisplayName("code 为 0 也把 message 整份交回；跨站校验口令由工具从本账号凭据里填")
    void envelopeAndCsrfAreKept() {
        answer(JSONObject.parseObject("{\"code\":0,\"data\":[],\"message\":\"f\",\"msg\":\"f\"}"));

        JSONObject response = api.postFormOnce(URL, Map.of("msg", "你好", "roomid", 1));

        assertEquals("f", response.getString("message"));
        assertEquals(1, forms.size());
        assertEquals("jct-value", forms.get(0).get("csrf"));
        assertEquals("jct-value", forms.get(0).get("csrf_token"));
        assertEquals("你好", forms.get(0).get("msg"));
    }

    /**
     * 抓的用户故障：平台回了风控码，程序转头又发，越发越被拦；或者冷却期里照样出网
     */
    @Test
    @DisplayName("风控码与 HTTP 412 让这个接口进冷却，冷却期内不再出网；业务码不重发")
    void blockedResponsesCoolTheEndpoint() {
        answer(JSONObject.parseObject("{\"code\":-412,\"message\":\"请求被拦截\"}"));

        JSONObject response = api.postFormOnce(URL, Map.of("msg", "一"));
        assertEquals(-412, response.getIntValue("code"));
        RiskCooldownException cooling = assertThrows(RiskCooldownException.class,
                () -> api.postFormOnce(URL, Map.of("msg", "二")));
        assertTrue(cooling.getMessage().contains("分钟后再试"), cooling.getMessage());
        verify(http, times(1)).postJsonAsForm(anyString(), any(), any());

        HttpUtil blockedHttp = mock(HttpUtil.class);
        when(blockedHttp.postJsonAsForm(anyString(), any(), any())).thenThrow(http412());
        BilibiliApiUtil other = new BilibiliApiUtil(blockedHttp, new NovaBilibiliProperties(),
                mock(BilibiliRiskMetrics.class));
        assertThrows(RiskCooldownException.class, () -> other.postFormOnce(URL, Map.of("msg", "三")));
        assertThrows(RiskCooldownException.class, () -> other.postFormOnce(URL, Map.of("msg", "四")));
        verify(blockedHttp, times(1)).postJsonAsForm(anyString(), any(), any());
    }

    /**
     * 抓的用户故障：没登录的账号把空口令连同请求一起发出，或者把别的账号的口令填了进去
     */
    @Test
    @DisplayName("没有凭据时口令是空串，不借别处的")
    void emptyCsrfWithoutCredentials() {
        answer(JSONObject.parseObject("{\"code\":-101,\"message\":\"账号未登录\"}"));
        api.setCookies(new Cookies());

        JSONObject response = api.postFormOnce(URL, null);

        assertEquals(-101, response.getIntValue("code"));
        assertEquals("", forms.get(0).get("csrf"));
        assertFalse(forms.get(0).containsKey("msg"));
    }
}
