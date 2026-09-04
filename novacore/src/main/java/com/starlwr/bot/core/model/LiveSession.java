package com.starlwr.bot.core.model;

import com.starlwr.bot.core.enums.LiveEndReason;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一场直播的归档记录
 * <p>
 * <b>这是运营分析的唯一数据源。</b>累计数据只是个没有时间维度的标量，
 * 从「累计弹幕 455」推不出「上周播了几场」「本月比上月如何」——
 * 有了逐场流水，周月统计不过是按时间区间聚合；反过来则无解。
 * <p>
 * 指标以「名称 → 取值」的形式原样存放，不做字段化：指标名由各平台自行定义，
 * 核心并不知道有哪些，写死字段会让新增一个指标就要改一次归档格式。
 * <p>
 * 结束原因与标题轨迹则相反，它们是<b>所有平台共有的场次属性</b>而不是某个平台的指标，
 * 而且都不是数值，塞进 {@code metrics} 只会让那张表同时装两种东西。
 *
 * @param platform 直播平台
 * @param uid 主播 UID
 * @param uname 主播昵称，记录当时的值
 * @param roomId 直播间号
 * @param startTime 开播时刻（毫秒）
 * @param endTime 下播时刻（毫秒）
 * @param durationSeconds 时长（秒）
 * @param metrics 各项统计指标
 * @param userCounts 各计分表的独立参与人数
 * @param endReason 结束原因，用于把被平台切断的场次与正常场次区分开
 * @param titles 本场的标题与分区轨迹，首条为开播时的初始值
 * @param maintenanceGapSeconds 本场之内因<b>程序停机</b>而没有采集的秒数
 * @param userSets 各计分表的参与者 uid 名单（冻结项 F5）。与 {@code userCounts} <b>同源</b>——
 *                 后者就是前者的 size，归档时会断言两者一致。老记录没有这一项，读成空表
 * @param roomOutageSeconds 本场之内因<b>这个直播间自己断线重连</b>而没有采集的秒数。
 *                          ⚠️ <b>与 {@code maintenanceGapSeconds} 分开存，不相加</b>：
 *                          两者成因不同（单房断线 vs 整个程序停机），而且程序停机期间
 *                          所有房间都在断，两段会重叠，<b>相加就是重复计数</b>。
 *                          合成一个数还会重演「两个口径混成一个数」那类错误
 * @param peaks 各条时间序列在本场之内的<b>派生峰值</b>：指标名 → 峰值与出现时刻。
 *              与 {@code metrics} 的分工是「多少」与「最多时是多少」——
 *              后者从累计值里推不出来，而序列本身下次开播就清零，
 *              所以它必须在下播那一刻算出来跟着场次走。
 *              <b>本版之前归档的场次没有这一项，读成空表</b>：那时候的序列已经没了，
 *              补不出来，界面上该显示「—」而不是 0
 */
public record LiveSession(
        String platform,
        Long uid,
        String uname,
        Long roomId,
        long startTime,
        long endTime,
        long durationSeconds,
        Map<String, Double> metrics,
        Map<String, Integer> userCounts,
        LiveEndReason endReason,
        List<RoomInfoSnapshot> titles,
        long maintenanceGapSeconds,
        Map<String, List<Long>> userSets,
        long roomOutageSeconds,
        Map<String, SeriesPeak> peaks
) {
    /**
     * 按无峰值构造
     * <p>
     * 供峰值这一项之前就存在的调用方与测试继续使用，<b>也是读取老归档记录时走的那一个</b>——
     * 老记录里本来就没有峰值。
     */
    public LiveSession(String platform, Long uid, String uname, Long roomId, long startTime, long endTime,
                       long durationSeconds, Map<String, Double> metrics, Map<String, Integer> userCounts,
                       LiveEndReason endReason, List<RoomInfoSnapshot> titles, long maintenanceGapSeconds,
                       Map<String, List<Long>> userSets, long roomOutageSeconds) {
        this(platform, uid, uname, roomId, startTime, endTime, durationSeconds, metrics, userCounts,
                endReason, titles, maintenanceGapSeconds, userSets, roomOutageSeconds, Map.of());
    }

    /**
     * 按正常结束、无标题记录、无停机缺口构造
     * <p>
     * 绝大多数场次都是这种情况，另有 4.3.0 之前归档的历史记录也没有这几项。
     */
    public LiveSession(String platform, Long uid, String uname, Long roomId, long startTime, long endTime,
                       long durationSeconds, Map<String, Double> metrics, Map<String, Integer> userCounts) {
        this(platform, uid, uname, roomId, startTime, endTime, durationSeconds, metrics, userCounts,
                LiveEndReason.NORMAL, List.of(), 0);
    }

    /**
     * 按无停机缺口构造，供只关心结束原因与标题轨迹的调用方与既有测试使用
     */
    public LiveSession(String platform, Long uid, String uname, Long roomId, long startTime, long endTime,
                       long durationSeconds, Map<String, Double> metrics, Map<String, Integer> userCounts,
                       LiveEndReason endReason, List<RoomInfoSnapshot> titles) {
        this(platform, uid, uname, roomId, startTime, endTime, durationSeconds, metrics, userCounts,
                endReason, titles, 0);
    }

    /**
     * 按无名单、无单房断线构造
     * <p>
     * 供 4.3.x 之前就存在的调用方与测试继续使用，<b>也是读取老归档记录时走的那一个</b>——
     * 老记录里本来就没有这两项。
     */
    public LiveSession(String platform, Long uid, String uname, Long roomId, long startTime, long endTime,
                       long durationSeconds, Map<String, Double> metrics, Map<String, Integer> userCounts,
                       LiveEndReason endReason, List<RoomInfoSnapshot> titles, long maintenanceGapSeconds) {
        this(platform, uid, uname, roomId, startTime, endTime, durationSeconds, metrics, userCounts,
                endReason, titles, maintenanceGapSeconds, Map.of(), 0, Map.of());
    }

    /**
     * 取某项指标，缺失时为 0
     */
    public double metric(String name) {
        Double value = metrics == null ? null : metrics.get(name);
        return value == null ? 0 : value;
    }

    /**
     * 取某个计分表的参与人数，缺失时为 0
     */
    public int userCount(String name) {
        Integer value = userCounts == null ? null : userCounts.get(name);
        return value == null ? 0 : value;
    }

    /**
     * 取某个计分表的参与者名单，缺失时为空表
     * <p>
     * ⚠️ <b>空表有两种含义，本方法分不开</b>：一是这一场真的没人参与，
     * 二是这条记录来自还没有 F5 的年代。要区分请用 {@link #hasUserSets()}。
     */
    public List<Long> userSet(String name) {
        List<Long> value = userSets == null ? null : userSets.get(name);
        return value == null ? List.of() : value;
    }

    /**
     * 这条记录是否带名单
     * <p>
     * <b>用来把「这场没人」和「那时候还没这功能」分开。</b>
     * 分析侧若不做这个区分，历史场次会显示成「零观众」，
     * 与停机缺口那一项读成 0 是同一类误读——<b>0 是「不知道」不是「我保证没有」</b>。
     */
    public boolean hasUserSets() {
        return userSets != null && !userSets.isEmpty();
    }

    /**
     * 取某条序列的峰值，没有这一项时为空
     * <p>
     * ⚠️ <b>空有两种含义，本方法分不开</b>：一是这一场那条序列一个点都没有，
     * 二是这条记录来自还没有峰值的年代。要区分请用 {@link #hasPeaks()}——
     * 与名单那一项同一条道理：<b>把「不知道」显示成 0，读的人会当成「最高只有 0」</b>。
     * @param metric 指标名
     * @return 峰值与出现时刻
     */
    public Optional<SeriesPeak> peak(String metric) {
        return Optional.ofNullable(peaks == null ? null : peaks.get(metric));
    }

    /**
     * 这条记录是否带峰值
     * <p>
     * <b>用来把「这一场没有互动」和「那时候还没这功能」分开。</b>
     * 场次表上前者该显示 0，后者该显示「—」。
     */
    public boolean hasPeaks() {
        return peaks != null && !peaks.isEmpty();
    }

    /**
     * 名单长度与人数容许的偏差
     * <p>
     * 归档时人数与名单是<b>两次独立调用</b>取的：各自持锁，但彼此之间没有原子性。
     * 两次之间恰好又来一个人，size 与名单长度就会差 1。
     * <p>
     * <b>所以自校验的绊线不能写成严格相等</b>——竞态既然存在且已记档，
     * 严格相等会在正常竞态上误报，而<b>误报会把人训练成忽略告警</b>，
     * 那比没有告警更糟。
     */
    private static final int USER_SET_TOLERANCE = 1;

    /**
     * 名单长度与人数的偏差是否已经大到该当缺陷查
     * <p>
     * 两者本该同源（后者就是前者的 size），所以这是一条<b>免费的自校验绊线</b>：
     * 差得太多就说明落盘漏了。但判读要放宽到 ±{@value #USER_SET_TOLERANCE}，理由见
     * {@link #USER_SET_TOLERANCE}。
     * <p>
     * ⚠️ <b>没有名单的老记录一律不报</b>：那时候本来就不写名单，
     * 拿「名单 0 条 vs 人数 33」去报缺陷，报的是一个不存在的问题。
     * @param name 计分表名
     * @return true 表示偏差超出容差，值得查
     */
    public boolean userSetSuspicious(String name) {
        if (!hasUserSets()) {
            return false;
        }
        return Math.abs(userCount(name) - userSet(name).size()) > USER_SET_TOLERANCE;
    }

    /**
     * 本场是否有任何一种采集缺口（程序停机或单房断线）
     */
    public boolean hasGap() {
        return maintenanceGapSeconds > 0 || roomOutageSeconds > 0;
    }

    /**
     * 本场是否被平台中断
     * <p>
     * <b>时长、营收、互动都因此不可比。</b>做趋势分析时应当把这类场次单独标出，
     * 而不是让它在图上表现为「这天状态很差」。
     */
    public boolean interrupted() {
        return endReason != null && endReason != LiveEndReason.NORMAL;
    }

    /**
     * 本场标题实际改动的次数，首条记录是初始标题而不是一次改动
     */
    public int titleChangeCount() {
        return titles == null ? 0 : Math.max(0, titles.size() - 1);
    }

    /**
     * 本场是否有没采到的时段
     * <p>
     * 有缺口时各项计数<b>只是下界</b>。缺口期间到达的弹幕与礼物没有任何实例在接收，
     * 事后也无法补——平台不提供回溯。
     */
    public boolean hasMaintenanceGap() {
        return maintenanceGapSeconds > 0;
    }
}
