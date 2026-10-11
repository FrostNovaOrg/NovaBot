package org.frostnova.nova.bilibili.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.exception.RequestFailedException;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("网页历史弹幕响应校验")
class BilibiliApiUtilDanmuHistoryTest {

    @Test
    @DisplayName("缺少 room 时查询失败，异常不含响应原文")
    void missingRoomFailsQuery() {
        // 用户故障：平台移除或改名 room 后，真断流被空表当成房间安静。
        JSONObject data = new JSONObject();
        data.put("admin", "private-response-body");

        RequestFailedException error = assertThrows(RequestFailedException.class,
                () -> apiReturning(data).getLiveRoomLatestDanmus(1L));
        assertEquals("历史弹幕响应缺少有效的 room 数组", error.getMessage());
    }

    @Test
    @DisplayName("data 为 null 时查询失败")
    void nullDataFailsQuery() {
        // 用户故障：平台移除 data 后，空对象被当成房间安静，断流不再告警。
        assertThrows(RequestFailedException.class,
                () -> apiReturning(null).getLiveRoomLatestDanmus(1L));
    }

    @Test
    @DisplayName("room 为空数组时返回空表")
    void emptyRoomRemainsQuiet() {
        // 用户故障：房间确实没人说话时，空数组被误当查询失败而触发断流告警。
        JSONObject data = new JSONObject();
        data.put("room", new JSONArray());

        assertTrue(apiReturning(data).getLiveRoomLatestDanmus(1L).isEmpty());
    }

    @Test
    @DisplayName("room 不是数组时查询失败，异常不含响应原文")
    void nonArrayRoomFailsQuery() {
        // 用户故障：平台改变 room 类型后，真断流被误当安静，或响应原文泄露到报错中。
        for (Object room : new Object[]{null, new JSONObject(), "private-response-body", 1, true}) {
            JSONObject data = new JSONObject();
            data.put("room", room);

            RequestFailedException error = assertThrows(RequestFailedException.class,
                    () -> apiReturning(data).getLiveRoomLatestDanmus(1L));
            assertEquals("历史弹幕响应缺少有效的 room 数组", error.getMessage());
        }
    }

    private BilibiliApiUtil apiReturning(JSONObject data) {
        JSONObject response = new JSONObject();
        response.put("code", 0);
        response.put("data", data);
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenReturn(response);
        return new BilibiliApiUtil(http, new NovaBilibiliProperties(), new BilibiliRiskMetrics());
    }
}
