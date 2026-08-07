package com.starlwr.bot.bilibili.util;

import com.starlwr.bot.bilibili.model.WebSign;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收到 -352 时是否值得重算签名
 * <p>
 * 这条规则决定「限流时要不要再多打一次请求」，钉住它是为了防止有人把门槛调低——
 * 门槛越低越接近「重试到成功」，而那正是限流想拦的行为。
 */
@DisplayName("风控重试的签名年龄判定")
class BilibiliApiUtilSignRetryTest {
    private static final Instant NOW = Instant.parse("2026-08-07T10:00:00Z");

    private WebSign signGeneratedSecondsAgo(long seconds) {
        WebSign sign = new WebSign();
        sign.setGeneratedAt(NOW.minusSeconds(seconds));
        return sign;
    }

    @Test
    @DisplayName("密钥够旧才值得重算")
    void oldEnough() {
        assertTrue(BilibiliApiUtil.signOldEnoughToRetry(signGeneratedSecondsAgo(900), NOW), "整 900 秒应放行");
        assertTrue(BilibiliApiUtil.signOldEnoughToRetry(signGeneratedSecondsAgo(3600), NOW));
    }

    @Test
    @DisplayName("刚换过的密钥不重算，重算出来多半还是同一份")
    void tooFresh() {
        assertFalse(BilibiliApiUtil.signOldEnoughToRetry(signGeneratedSecondsAgo(899), NOW), "差一秒也不放行");
        assertFalse(BilibiliApiUtil.signOldEnoughToRetry(signGeneratedSecondsAgo(0), NOW));
    }

    @Test
    @DisplayName("没有签名或没有生成时刻时不重算")
    void missingSign() {
        assertFalse(BilibiliApiUtil.signOldEnoughToRetry(null, NOW));
        assertFalse(BilibiliApiUtil.signOldEnoughToRetry(new WebSign(), NOW),
                "生成时刻为空说明来路不明，不要据此决定多打一次请求");
    }
}
