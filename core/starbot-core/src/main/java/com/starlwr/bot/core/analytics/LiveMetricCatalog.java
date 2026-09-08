package com.starlwr.bot.core.analytics;

import java.util.List;

/**
 * 直播指标说明的扩展点
 * <p>
 * 归档里的指标是 {@code danmu_count} 这样的裸键，**核心并不知道它叫什么、能不能相加**。
 * 这两件事都由产生该指标的平台插件来说明：核心负责按周期聚合，插件负责解释指标。
 * <p>
 * <b>「能不能相加」不是格式问题，是对不对的问题。</b>把「开播时的粉丝数」在一个月里
 * 累加十次，得到的数字既不是月初粉丝数也不是月末粉丝数，纯属无中生有；
 * 而运营分析的用途正是据此做判断，一个看似合理的错数比没有数更糟。
 * 因此聚合只处理明确声明为可累加的指标，其余一律不出现在统计里。
 * <p>
 * 没有对应平台的实现时，统计只展示场次与时长——这两项核心自己就算得出，且永远正确。
 */
public interface LiveMetricCatalog {
    /**
     * 本说明适用的直播平台
     * @return 平台名，如 bilibili
     */
    String platform();

    /**
     * 可参与周期统计的指标，按界面展示顺序排列
     * @return 指标说明列表
     */
    List<Metric> metrics();

    /**
     * 基础数据快照里那些指标的说明，按界面展示顺序排列
     * <p>
     * 快照记的是「开播那一刻的粉丝数」这类<b>存量</b>，由平台插件按固定间隔采样留档，
     * 与某一场直播无关。核心同样不知道各平台会采什么，因此换人话这件事也归插件说。
     * <p>
     * <b>这里的指标一律不进可累加集</b>，复用 {@link Metric} 只是为了共用「键、名、单位、
     * 是不是钱」这四样说明——存量相加就是上面那个无中生有的数，理由与 {@link #metrics()}
     * 那一段完全相同。两边的键因此不许重合：同一个键既算得又算不得，看的人无从分辨。
     * <p>
     * 不实现时回空表，界面照旧原样显示裸键——<b>裸键不许藏起来</b>：藏起来之后，
     * 插件新采了一项指标的那一天，屏幕上不会有任何变化。
     * @return 快照指标说明列表，缺省为空
     */
    default List<Metric> snapshotMetrics() {
        return List.of();
    }

    /**
     * 一项指标的说明
     * @param key 指标键，与归档中的键一致
     * @param name 中文名
     * @param unit 单位，无单位时为空串
     * @param money 是否为金额。金额保留两位小数，其余取整——
     *              「12.00 条弹幕」和「23 元」一样让人别扭
     */
    record Metric(String key, String name, String unit, boolean money) {
        /**
         * 计数类指标
         */
        public static Metric count(String key, String name, String unit) {
            return new Metric(key, name, unit, false);
        }

        /**
         * 金额类指标，单位固定为元
         */
        public static Metric money(String key, String name) {
            return new Metric(key, name, "元", true);
        }
    }
}
