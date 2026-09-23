package org.frostnova.nova.core.config.ui.auth;

import java.time.Duration;

/**
 * 锁定提示里要说的分钟数。
 * <p>
 * 向上取整，不足一分钟按一分钟。刚锁上时往往还剩十四分五十九秒，
 * 向下取整会说成十四分钟，人照这个数等完再试仍会被锁大约一分钟。
 */
public final class LockoutMinutes {
    private LockoutMinutes() {
    }

    /**
     * @param remaining 还要等多久
     * @return 提示里的分钟数，至少为 1
     */
    public static long toShow(Duration remaining) {
        long whole = remaining.toMinutes();
        long minutes = remaining.compareTo(Duration.ofMinutes(whole)) > 0 ? whole + 1 : whole;
        return Math.max(1, minutes);
    }
}
