package org.frostnova.nova.bilibili.util;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.exception.NetworkException;
import org.frostnova.nova.bilibili.exception.RequestFailedException;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B 站请求失败时的报错消息只留路径
 * <p>
 * 报错消息会进 WARN／ERROR 日志。用户把日志贴出去求助时，
 * 查询参数（账号 uid 等）不该跟着出去。
 */
@DisplayName("B站请求失败报错消息只留路径")
class BilibiliApiUtilErrorMessageTest {

    /** 真实关注列表地址形态：路径后直接拼查询串 */
    private static final String URL_WITH_QUERY =
            "https://api.bilibili.com/x/relation/followings?vmid=123456&pn=1";

    private static final String PATH_ONLY =
            "https://api.bilibili.com/x/relation/followings";

    private static BilibiliApiUtil failingApi() {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getNetwork().setApiRetryMaxTimes(2);
        properties.getNetwork().setApiRetryInterval(0);
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenThrow(new RuntimeException("connection refused"));
        return new BilibiliApiUtil(http, properties, mock(BilibiliRiskMetrics.class));
    }

    @Test
    @DisplayName("重试耗尽的报错消息有路径、不含查询参数")
    void retryExhaustedMessageKeepsPathOnly() {
        try {
            failingApi().requestBilibiliApi(URL_WITH_QUERY);
            fail("应当抛出 RequestFailedException");
        } catch (RequestFailedException e) {
            assertPathOnly(e.getMessage());
        }
    }

    @Test
    @DisplayName("网络异常的报错消息有路径、不含查询参数")
    void networkExceptionMessageKeepsPathOnly() {
        try {
            failingApi().requestBilibiliApi(URL_WITH_QUERY);
            fail("应当抛出 RequestFailedException");
        } catch (RequestFailedException e) {
            assertTrue(e.getCause() instanceof NetworkException,
                    "cause 应为 NetworkException，实际: " + e.getCause());
            assertPathOnly(e.getCause().getMessage());
        }
    }

    private static void assertPathOnly(String message) {
        assertTrue(message != null && message.contains(PATH_ONLY),
                "消息里应有路径 " + PATH_ONLY + "，实际: " + message);
        assertFalse(message.contains("?"),
                "消息里不该有查询参数分隔符，实际: " + message);
        assertFalse(message.contains("123456"),
                "消息里不该有参数值 123456，实际: " + message);
        assertFalse(message.contains("vmid"),
                "消息里不该有参数名 vmid，实际: " + message);
    }
}
