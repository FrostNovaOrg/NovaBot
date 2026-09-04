package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.util.StringUtil;

/**
 * 告警三路各自「配好了没有」
 * <p>
 * 设置页药丸、首页 {@code /api/status} 的 {@code alerts}、以及邮件通道能不能发，
 * 问的是同一件事。判定只留这一份：两处各写各的话，首页催人去配、点「发一条测试」却仍走发送。
 * <p>
 * 前端那一份在 {@code alert-model.js} 的 {@code mailAlertConfigured}，
 * 两处都只认收件与 SMTP 主机这两栏，缺一即未配。
 */
public final class AlertReadiness {
    private AlertReadiness() {
    }

    /**
     * 邮件这一路配好了没有
     * <p>
     * 口径与设置页药丸相同：收件邮箱与 SMTP 主机都有才算。
     * 只填了收件、没填主机会发出去失败，那种「看起来配了其实发不走」
     * 与没配在出事那天长得一样。
     * @param defaultTo 收件邮箱
     * @param smtpHost SMTP 主机
     * @return 两栏都有时为 true
     */
    public static boolean mailConfigured(String defaultTo, String smtpHost) {
        return StringUtil.isNotBlank(defaultTo) && StringUtil.isNotBlank(smtpHost);
    }
}
