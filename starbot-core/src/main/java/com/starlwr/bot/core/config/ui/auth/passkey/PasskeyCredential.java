package com.starlwr.bot.core.config.ui.auth.passkey;

import java.time.Instant;

/**
 * 一把已登记的通行密钥
 * <p>
 * 存的是<b>公钥</b>——私钥从头到尾没离开过使用者的设备，这正是通行密钥与口令的根本区别：
 * 这台机器上存的东西即使整份泄漏，也没有人能拿它登进来。
 *
 * @param id 凭据 ID，base64url。它由认证器给出，是这把钥匙在浏览器那一侧的名字
 * @param name 使用者起的名字，最长见 {@link PasskeyService#MAX_NAME_LENGTH}
 * @param publicKey X.509 编码的公钥，Base64。存编码而不是存 COSE 原样：
 *                  登录时要的是一个 {@link java.security.PublicKey}，
 *                  存 COSE 就得每次登录都把那段解析再跑一遍——那段解析读的是外来字节，
 *                  跑的次数越少越好
 * @param algorithm COSE 算法标识，决定验签算法与密钥工厂
 * @param signCount 上一次见到的签名计数器
 * @param createdAt 登记时刻
 * @param lastUsedAt 上次用它登录的时刻，从未用过时为 null
 */
public record PasskeyCredential(String id, String name, String publicKey, int algorithm,
                                long signCount, Instant createdAt, Instant lastUsedAt) {
    /**
     * 记下这一次成功登录
     * @param newSignCount 认证器这次报的计数器
     * @param now 当前时刻
     * @return 更新后的凭据
     */
    PasskeyCredential used(long newSignCount, Instant now) {
        return new PasskeyCredential(id, name, publicKey, algorithm, newSignCount, createdAt, now);
    }
}
