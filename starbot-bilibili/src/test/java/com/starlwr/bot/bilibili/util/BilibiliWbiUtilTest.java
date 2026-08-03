package com.starlwr.bot.bilibili.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WBI 接口签名")
class BilibiliWbiUtilTest {
    /**
     * 以下 img_key、sub_key 与预期结果取自哔哩哔哩接口文档中的公开示例，用于校验算法实现是否正确
     */
    private static final String IMG_KEY = "7cd084941338484aae1ad9425b84077c";
    private static final String SUB_KEY = "4932caff0ff746eab6f01bf08b70ac45";
    private static final String EXPECTED_MIXIN_KEY = "ea1db124af3c7062474693fa704f4ff8";

    @Test
    @DisplayName("混淆密钥与文档示例一致")
    void mixinKeyMatchesReference() {
        assertEquals(EXPECTED_MIXIN_KEY, BilibiliWbiUtil.mixinKey(IMG_KEY, SUB_KEY));
    }

    @Test
    @DisplayName("签名结果与文档示例一致")
    void signMatchesReference() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("foo", "114");
        params.put("bar", "514");
        params.put("zab", 1919810);

        String query = BilibiliWbiUtil.sign(params, IMG_KEY, SUB_KEY, 1702204169L);

        assertEquals("?bar=514&foo=114&wts=1702204169&zab=1919810&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4", query);
    }

    @Test
    @DisplayName("参数按键名字典序参与签名")
    void paramsAreSorted() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("zzz", 1);
        params.put("aaa", 2);

        String query = BilibiliWbiUtil.sign(params, IMG_KEY, SUB_KEY, 1L);

        assertTrue(query.startsWith("?aaa=2&wts=1&zzz=1&w_rid="), "实际结果: " + query);
    }

    @Test
    @DisplayName("需要编码的参数在签名串与查询串中保持一致")
    void encodedParamsStayConsistent() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyword", "测试 关键词");

        String query = BilibiliWbiUtil.sign(params, IMG_KEY, SUB_KEY, 1L);

        // 参与签名的内容必须与实际发出的查询串一致，空格需编码为 %20
        assertTrue(query.contains("keyword=%E6%B5%8B%E8%AF%95%20%E5%85%B3%E9%94%AE%E8%AF%8D"), "实际结果: " + query);
    }

    @Test
    @DisplayName("从密钥地址中提取密钥")
    void extractKey() {
        assertEquals("7cd084941338484aae1ad9425b84077c",
                BilibiliWbiUtil.extractKey("https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png"));
        assertEquals("", BilibiliWbiUtil.extractKey(null));
        assertEquals("", BilibiliWbiUtil.extractKey(""));
        assertEquals("", BilibiliWbiUtil.extractKey("没有斜杠和扩展名"));
    }
}
