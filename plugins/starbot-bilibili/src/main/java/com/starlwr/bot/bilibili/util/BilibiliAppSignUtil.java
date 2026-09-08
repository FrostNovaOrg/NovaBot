package com.starlwr.bot.bilibili.util;

import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 哔哩哔哩 APP 接口签名工具
 * <p>
 * TV 端与 APP 端接口使用 appkey/appsec 签名，算法为：补入 appkey → 按参数名排序 →
 * url query 序列化 → 拼接 appsec 后取 md5（32 位小写）→ 作为 sign 参数一并提交。
 * 与 Web 端的 WBI 签名（见 {@link BilibiliWbiUtil}）是两套独立机制。
 */
@Slf4j
public final class BilibiliAppSignUtil {
    /**
     * 云视听小电视（TV 版）的 appkey 与 appsec
     * <p>
     * 该组密钥随客户端分发、早已公开，扫码登录走 TV 端接口是为了取得可续期的
     * access_token / refresh_token——Web 端扫码返回的刷新口令实测为空串。
     */
    public static final String TV_APP_KEY = "4409e2ce8ffd12b8";

    private static final String TV_APP_SEC = "59b43e04ad6965f34319062b478f83dd";

    private BilibiliAppSignUtil() {
    }

    /**
     * 用 TV 端密钥为参数签名
     * @param params 原始参数，不会被修改
     * @return 补入 appkey 与 sign 后的参数，按参数名排序
     */
    public static Map<String, Object> signWithTvKey(Map<String, Object> params) {
        return sign(params, TV_APP_KEY, TV_APP_SEC);
    }

    /**
     * 为参数签名
     * @param params 原始参数，不会被修改
     * @param appKey APP 密钥
     * @param appSec APP 密钥对应的盐
     * @return 补入 appkey 与 sign 后的参数，按参数名排序
     */
    public static Map<String, Object> sign(Map<String, Object> params, String appKey, String appSec) {
        Map<String, Object> sorted = new TreeMap<>(params);
        sorted.put("appkey", appKey);

        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }

        // 结果保持排序后的顺序，签名参数追加在尾部，与官方客户端的提交形式一致
        Map<String, Object> signed = new LinkedHashMap<>(sorted);
        signed.put("sign", md5(query + appSec));
        return signed;
    }

    /**
     * 计算 md5，输出 32 位小写十六进制
     */
    private static String md5(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                result.append(Character.forDigit((b >> 4) & 0xF, 16));
                result.append(Character.forDigit(b & 0xF, 16));
            }
            return result.toString();
        } catch (Exception e) {
            // MD5 是 JDK 必须实现的算法，走到这里说明运行环境异常
            throw new IllegalStateException("计算 APP 接口签名失败", e);
        }
    }
}
