package org.frostnova.nova.core.datasource;

/**
 * 同时监控的主播数上限
 * <p>
 * 单个实例最多同时监控 {@value #MAX_STREAMERS} 位主播，这是产品上限，不是可调参数。
 */
public final class MonitorLimit {
    /**
     * 同时监控的主播数上限
     */
    public static final int MAX_STREAMERS = 10;

    private MonitorLimit() {
    }
}
