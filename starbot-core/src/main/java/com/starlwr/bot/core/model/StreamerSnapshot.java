package com.starlwr.bot.core.model;

import java.util.Map;

/**
 * 某一时刻的主播基础数据快照
 * <p>
 * <b>补的是两场直播之间的空白。</b>场次归档只在下播时写一条，于是所有数据都只存在于
 * 直播期间——「这周涨了多少粉」这种问题因此答不出来，能答的只有「每场开播时的粉丝数是多少」。
 * 一周没播就是一片空白，而粉丝数在没播的日子里照样在变。
 * <p>
 * 指标同样以「名称 → 取值」原样存放，理由与 {@link LiveSession} 相同：
 * 各平台能拿到的基础数据并不一致，核心不该替它们规定字段。
 *
 * @param platform 直播平台
 * @param uid 主播 UID
 * @param uname 主播昵称，记录当时的值
 * @param at 采样时刻（毫秒）
 * @param metrics 各项基础数据
 */
public record StreamerSnapshot(
        String platform,
        Long uid,
        String uname,
        long at,
        Map<String, Double> metrics
) {
    /**
     * 取某项数据，缺失时为 0
     */
    public double metric(String name) {
        Double value = metrics == null ? null : metrics.get(name);
        return value == null ? 0 : value;
    }

    /**
     * 是否含有某项数据
     * <p>
     * 与「取值为 0」不是一回事：接口拉不到时该项根本不该参与计算，
     * 而当作 0 会让涨幅凭空多出一个断崖。
     */
    public boolean has(String name) {
        return metrics != null && metrics.containsKey(name);
    }
}
