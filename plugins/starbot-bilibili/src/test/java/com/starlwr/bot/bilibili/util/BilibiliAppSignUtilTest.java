package com.starlwr.bot.bilibili.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * APP 接口签名测试
 * <p>
 * 以官方文档给出的示例参数与密钥核对签名结果，确保实现与服务端一致——
 * 签名算错的表现是接口一律返回鉴权失败，靠联调排查代价很高。
 */
@DisplayName("APP 接口签名")
class BilibiliAppSignUtilTest {
    @Test
    @DisplayName("应与文档示例的签名结果一致")
    void matchesOfficialExample() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", 114514);
        params.put("str", "1919810");
        params.put("test", "いいよ，こいよ");

        Map<String, Object> signed = BilibiliAppSignUtil.sign(params, "1d8b6e7d45233436", "560c52ccd288fed045859ed18bffd973");

        // 期望值由文档给出的 Python 例程对同一组参数实算得出，非人工推测
        assertEquals("01479cf20504d865519ac50f33ba3a7d", signed.get("sign"));
    }

    @Test
    @DisplayName("参数应按名称排序且补入 appkey")
    void sortsParamsAndAddsAppKey() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ts", 0);
        params.put("auth_code", "abc");
        params.put("local_id", 0);

        Map<String, Object> signed = BilibiliAppSignUtil.signWithTvKey(params);

        assertEquals(List.of("appkey", "auth_code", "local_id", "ts", "sign"), List.copyOf(signed.keySet()));
        assertEquals(BilibiliAppSignUtil.TV_APP_KEY, signed.get("appkey"));
    }

    @Test
    @DisplayName("不应修改传入的参数表")
    void doesNotMutateInput() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ts", 0);

        BilibiliAppSignUtil.signWithTvKey(params);

        assertEquals(1, params.size());
        assertFalse(params.containsKey("sign"));
    }
}
