package com.starlwr.bot.adapter.onebot.security;

import com.starlwr.bot.core.util.SecureToken;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 推送接口 Token 存储，维护「接口路径 -> 期望 Token」的映射
 * <p>
 * 未显式配置 Token 的推送平台会在启动时自动生成一个高强度随机 Token。由于 StarBot 核心
 * 是通过本机回环地址调用自身推送接口的，自动生成的 Token 会同步注册给核心，对默认部署完全透明；
 * 而外部调用方若要接入，则必须在配置文件中显式设置 Token，从而杜绝「零配置即裸奔」的情况。
 * <p>
 * 令牌的生成、恒定时间比对与强度判定统一由 {@link SecureToken} 提供。
 */
@Slf4j
public class PushApiTokenStore {
    /**
     * Token 最小长度，低于此长度视为弱 Token
     */
    public static final int MIN_TOKEN_LENGTH = SecureToken.MIN_LENGTH;

    /**
     * 接口路径 -> 期望 Token
     */
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    /**
     * 注册某个推送接口的 Token
     * @param path 接口完整路径，例如 /onebot/send
     * @param token 期望的 Token
     */
    public void register(String path, String token) {
        tokens.put(path, token);
    }

    /**
     * 判断某个路径是否为受保护的推送接口
     * @param path 接口路径
     * @return 是否受保护
     */
    public boolean isProtected(String path) {
        return tokens.containsKey(path);
    }

    /**
     * 校验请求携带的 Token 是否与接口期望的 Token 一致
     * @param path 接口路径
     * @param presented 请求携带的 Token，可为 null
     * @return 是否校验通过
     */
    public boolean verify(String path, String presented) {
        return SecureToken.verify(tokens.get(path), presented);
    }

    /**
     * 生成一个高强度随机 Token
     * @return 生成的 Token
     */
    public static String generate() {
        return SecureToken.generate();
    }

    /**
     * 判断 Token 是否过弱
     * @param token 待判断的 Token
     * @return 是否过弱
     */
    public static boolean isWeak(String token) {
        return SecureToken.isWeak(token);
    }

    /**
     * 生成 Token 指纹，用于在日志中标识 Token 而不泄露其本身
     * @param token Token
     * @return 指纹字符串
     */
    public static String fingerprint(String token) {
        return SecureToken.fingerprint(token);
    }
}
