package org.frostnova.nova.core;

/**
 * 这次启动有没有在门里等过锁。
 * <p>
 * 过门之后核配置只在等过的时候做。开关关着，或一拿就拿到锁，
 * 配置文件上的差别是本进程自己写的，不当成候命期间别人改的。
 */
public final class StandbyWait {

    private StandbyWait() {
    }

    /**
     * @return 门里曾经等过锁时为 true
     */
    public static boolean actuallyWaited() {
        return SingleInstanceLock.actuallyWaited();
    }
}
