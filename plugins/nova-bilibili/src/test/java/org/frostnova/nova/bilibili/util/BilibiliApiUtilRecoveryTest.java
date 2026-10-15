package org.frostnova.nova.bilibili.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.exception.RequestFailedException;
import org.frostnova.nova.bilibili.exception.RiskCooldownException;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BilibiliApiUtilRecoveryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transientCodeDoesNotRetry(boolean superChat) {
        // 用户故障：暂时性业务错误导致补录连续重试。
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenReturn(JSONObject.of("code", -503, "message", "private-body"));
        assertThrows(RequestFailedException.class, () -> call(http, superChat).get());
        verify(http, times(1)).getJson(anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {-352, -412, -509, -799, 22015})
    void riskCodeCoolsPathWithoutResigningOrReplay(int code) {
        // 用户故障：风控后又重算签名重发，或下一间房继续撞同一路径。
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenReturn(JSONObject.of("code", code));
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getNetwork().setApiRetryMaxTimes(3);
        BilibiliApiUtil api = new BilibiliApiUtil(http, properties, new BilibiliRiskMetrics());
        org.frostnova.nova.bilibili.model.WebSign oldSign = new org.frostnova.nova.bilibili.model.WebSign();
        oldSign.setGeneratedAt(java.time.Instant.now().minusSeconds(3600));
        org.springframework.test.util.ReflectionTestUtils.setField(api, "webSign", oldSign);
        assertThrows(RequestFailedException.class, () -> api.getLiveRoomDanmuHistoryOnce(4000004L));
        assertThrows(RiskCooldownException.class, () -> api.getLiveRoomDanmuHistoryOnce(4000006L));
        verify(http, times(1)).getJson(anyString(), any());
        // 冷却按路径共享，不应拦住另一接口。
        when(http.getJson(contains("getMessageList"), any()))
                .thenReturn(JSONObject.of("code", 0, "data", JSONObject.of("list", new JSONArray())));
        assertTrue(api.getLiveRoomSuperChatsOnce(4000004L).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedResponseFailsOnce(boolean superChat) {
        // 用户故障：回包形状错误被当作成功，或者触发重试。
        for (JSONObject response : new JSONObject[]{new JSONObject(), JSONObject.of("code", 0),
                JSONObject.of("code", 0, "data", JSONObject.of(superChat ? "list" : "room", "private-body")),
                JSONObject.of("code", 0, "data", JSONObject.of(superChat ? "list" : "room", JSONArray.of(1)))}) {
            HttpUtil http = mock(HttpUtil.class);
            when(http.getJson(anyString(), any())).thenReturn(response);
            assertThrows(RequestFailedException.class, () -> call(http, superChat).get());
            verify(http, times(1)).getJson(anyString(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void networkFailureDoesNotRetry(boolean superChat) {
        // 用户故障：任一补录接口遇到网络异常仍反复请求。
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenThrow(new IllegalStateException("private-body"));
        assertThrows(RuntimeException.class, () -> call(http, superChat).get());
        verify(http, times(1)).getJson(anyString(), any());
    }

    private Supplier<JSONArray> call(HttpUtil http, boolean superChat) {
        BilibiliApiUtil api = new BilibiliApiUtil(http, new NovaBilibiliProperties(), new BilibiliRiskMetrics());
        return () -> superChat ? api.getLiveRoomSuperChatsOnce(4000004L) : api.getLiveRoomDanmuHistoryOnce(4000004L);
    }
}
