package com.starlwr.bot.bilibili.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 哔哩哔哩 WBI 接口签名工具
 * <p>
 * 部分开放接口要求在查询参数中附带 w_rid 与 wts 两个字段，其中 w_rid 由参数本身与一个
 * 由 img_key、sub_key 重排得到的混淆密钥共同计算得出。混淆表与计算方式均为公开信息。
 */
public final class BilibiliWbiUtil {
    /**
     * 混淆密钥的字符重排表
     */
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    /**
     * 混淆密钥长度
     */
    private static final int MIXIN_KEY_LENGTH = 32;

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private BilibiliWbiUtil() {
    }

    /**
     * 为参数生成带签名的查询字符串
     * @param params 查询参数
     * @param imgKey 由导航接口获取的 img_key
     * @param subKey 由导航接口获取的 sub_key
     * @return 以 ? 开头的完整查询字符串
     */
    public static String sign(Map<String, Object> params, String imgKey, String subKey) {
        return sign(params, imgKey, subKey, System.currentTimeMillis() / 1000L);
    }

    /**
     * 为参数生成带签名的查询字符串，允许指定时间戳，便于测试
     * @param params 查询参数
     * @param imgKey 由导航接口获取的 img_key
     * @param subKey 由导航接口获取的 sub_key
     * @param timestamp 时间戳，单位：秒
     * @return 以 ? 开头的完整查询字符串
     */
    public static String sign(Map<String, Object> params, String imgKey, String subKey, long timestamp) {
        // 参数需按键名字典序排列后参与签名
        TreeMap<String, Object> sorted = new TreeMap<>(params);
        sorted.put("wts", timestamp);

        String query = sorted.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + urlEncode(entry.getValue()))
                .collect(Collectors.joining("&"));

        // 参与签名的查询串与实际发出的查询串必须完全一致，否则服务端算出的签名不会匹配
        return "?" + query + "&w_rid=" + md5(query + mixinKey(imgKey, subKey));
    }

    /**
     * 由 img_key 与 sub_key 计算混淆密钥
     * @param imgKey img_key
     * @param subKey sub_key
     * @return 混淆密钥
     */
    static String mixinKey(String imgKey, String subKey) {
        String source = imgKey + subKey;

        StringBuilder key = new StringBuilder(MIXIN_KEY_LENGTH);
        for (int i = 0; i < MIXIN_KEY_LENGTH; i++) {
            key.append(source.charAt(MIXIN_KEY_ENC_TAB[i]));
        }

        return key.toString();
    }

    /**
     * 从形如 https://i0.hdslb.com/bfs/wbi/{key}.png 的地址中提取密钥
     * @param url 密钥地址
     * @return 密钥，无法提取时返回空字符串
     */
    public static String extractKey(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }

        int slash = url.lastIndexOf('/');
        int dot = url.lastIndexOf('.');
        if (slash < 0 || dot <= slash) {
            return "";
        }

        return url.substring(slash + 1, dot);
    }

    /**
     * URL 编码，空格需编码为 %20 而非 +
     * @param value 待编码的值
     * @return 编码结果
     */
    private static String urlEncode(Object value) {
        return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 计算 MD5 摘要
     * @param input 输入
     * @return 小写十六进制摘要
     */
    private static String md5(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));

            char[] result = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                result[i * 2] = HEX_DIGITS[(digest[i] >> 4) & 0xF];
                result[i * 2 + 1] = HEX_DIGITS[digest[i] & 0xF];
            }

            return new String(result);
        } catch (Exception e) {
            throw new IllegalStateException("计算 MD5 摘要失败", e);
        }
    }
}
