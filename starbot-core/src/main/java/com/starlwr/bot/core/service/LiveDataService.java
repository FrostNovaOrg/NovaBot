package com.starlwr.bot.core.service;

import com.starlwr.bot.core.model.LiveGap;
import com.starlwr.bot.core.model.UserScore;
import lombok.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 直播数据服务接口
 */
public interface LiveDataService {
    /**
     * 获取直播间状态
     * @param platform 直播平台
     * @param uid UID
     * @return 直播间状态，true：已开播，false：未开播
     */
    Optional<Boolean> getLiveStatus(@NonNull String platform, @NonNull Long uid);

    /**
     * 设置直播间状态
     * @param platform 直播平台
     * @param uid UID
     * @param status 直播间状态，true：已开播，false：未开播
     */
    void setLiveStatus(@NonNull String platform, @NonNull Long uid, boolean status);

    /**
     * 获取最近一场直播开始时间戳
     * @param platform 直播平台
     * @param uid UID
     * @return 最近一场直播开始时间戳
     */
    Optional<Long> getLiveStartTime(@NonNull String platform, @NonNull Long uid);

    /**
     * 设置最近一场直播开始时间戳
     * @param platform 直播平台
     * @param uid UID
     * @param startTime 最近一场直播开始时间戳
     */
    void setLiveStartTime(@NonNull String platform, @NonNull Long uid, long startTime);

    /**
     * 获取<b>上一个进程</b>最后一次把本场数据落盘的时刻
     * <p>
     * 这是「采集到哪儿为止」的水位线：这个时刻之后收到的消息，随进程一起没了。
     * 崩溃恢复用它当未闭合场次的结束时刻，停机缺口用它当缺口的起点。
     * <p>
     * 取的是<b>启动时读到的那个值</b>，不是当前进程正在写的值——后者会被
     * 自动保存不断刷新，几十秒后就问不出上次停在哪了。
     * @return 上次落盘时刻（毫秒），不落盘或文件里没有这一项时为空
     */
    Optional<Long> getLastSaveTime();

    /**
     * <b>上一个进程</b>是不是正常退出的
     * <p>
     * 与 {@link #getLastSaveTime} 同一批读出来的水位线信息，一起回答「这段空白是怎么来的」：
     * 正常退出的那一段是计划内维护，崩溃或被强杀的那一段是重启。<b>两者对主播的含义不同</b>——
     * 后者是该有人去查的，前者不是。
     * <p>
     * 取的同样是<b>启动时读到的那个值</b>：进程一起来就会把这一项改写成「否」，
     * 几十秒后再问，答的是本次而不是上次。
     * @return 上次正常退出为 true，崩溃或被强杀为 false；数据文件里没有这一项、
     *         或本实现根本不记录时为空——<b>空不读成 false</b>，那会把「不知道」说成「崩过」
     */
    default Optional<Boolean> wasCleanShutdown() {
        return Optional.empty();
    }

    /**
     * 记一段停机（未采集）区间
     * <p>
     * 按<b>全局区间</b>存而不是按主播存：停机是进程层面的事，同时监听 10 个主播时
     * 10 场直播共享同一段缺口，各自的报告只需截取与自己场次重叠的部分。
     * @param from 起始时刻（毫秒，含）
     * @param to 结束时刻（毫秒，含）
     * @param reason 成因，推不出来时传 {@link LiveGap.Reason#UNKNOWN}
     */
    void recordDowntime(long from, long to, @NonNull LiveGap.Reason reason);

    /**
     * 记一段成因不明的停机区间
     * <p>
     * 落成 {@link LiveGap.Reason#UNKNOWN} 而不是挑一个看起来最像的成因：
     * 报告上「原因未定」是一句真话，「维护」在这里会是一句编出来的话。
     * @param from 起始时刻（毫秒，含）
     * @param to 结束时刻（毫秒，含）
     */
    default void recordDowntime(long from, long to) {
        recordDowntime(from, to, LiveGap.Reason.UNKNOWN);
    }

    /**
     * 查询与给定区间重叠的停机区间，按时间先后排列
     * <p>
     * 只给重叠的那一部分：一段跨越开播时刻的停机，开播之前那一截不属于本场。
     * <p>
     * <b>报告要的是区间不是总数</b>：曲线上得把这几段画成斜纹，概览里得按成因分栏，
     * 两件事都做不到只知道「一共缺了多久」。
     * @param from 区间起始（毫秒，含）
     * @param to 区间结束（毫秒，不含）
     * @return 已裁剪到 {@code [from, to)} 之内的区间，互不重叠；没有交集时为空表
     */
    List<LiveGap> downtimeIntervals(long from, long to);

    /**
     * 查询与给定区间重叠的停机总时长
     * <p>
     * <b>由 {@link #downtimeIntervals} 求和得出，实现不要单独覆盖。</b>
     * 「一共缺了多久」与「缺在哪几段」是同一件事的两种问法，各算各的迟早会答出
     * 两个对不上的数——报告上就成了「说缺了 12 分钟，却一段斜纹都没画」。
     * @param from 区间起始（毫秒，含）
     * @param to 区间结束（毫秒，不含）
     * @return 重叠的停机总毫秒数，没有交集时为 0
     */
    default long downtimeWithin(long from, long to) {
        return LiveGap.totalMillis(downtimeIntervals(from, to));
    }

    /**
     * 记一段<b>单个直播间</b>的断线区间
     * <p>
     * ⚠️ <b>与 {@link #recordDowntime} 是两回事，别合并。</b>
     * 那一个是<b>进程层面</b>的停机，全局一份、所有主播共享；
     * 这一个是<b>某个房间自己</b>断线重连，别的房间可能一直好好的。
     * <p>
     * 两者<b>必然重叠</b>——程序停机期间每个房间都是断的——所以
     * <b>相加会重复计数</b>，报告里必须分开表述。
     * <p>
     * 默认空实现：不记录的实现照旧返回 0，行为与加这一项之前完全一致。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param from 起始时刻（毫秒，含）
     * @param to 结束时刻（毫秒，含）
     */
    default void recordRoomOutage(@NonNull String platform, @NonNull Long uid, long from, long to) {
    }

    /**
     * 查询某个直播间与给定区间重叠的断线区间，按时间先后排列
     * <p>
     * 只给重叠的那一部分，理由同 {@link #downtimeIntervals}：跨越开播时刻的那一段，
     * 开播之前那一截不属于本场。成因一律 {@link LiveGap.Reason#STREAM_LOSS}。
     * <p>
     * ⚠️ <b>返回的区间必须互不重叠</b>（一次断线尚未恢复又记了一次是实际会发生的），
     * 实现须先合并再返回，否则同一秒会被数两遍。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param from 区间起始（毫秒，含）
     * @param to 区间结束（毫秒，不含）
     * @return 已裁剪并合并的断线区间，没有交集或未记录时为空表
     */
    default List<LiveGap> roomOutageIntervals(@NonNull String platform, @NonNull Long uid, long from, long to) {
        return List.of();
    }

    /**
     * 查询某个直播间与给定区间重叠的断线总时长
     * <p>
     * <b>由 {@link #roomOutageIntervals} 求和得出，实现不要单独覆盖</b>，理由同 {@link #downtimeWithin}：
     * 只覆盖总数不覆盖区间，报告会说得出「断线 2 分 3 秒」却在曲线上画不出那一段。
     * @return 重叠的断线总毫秒数，没有交集或未记录时为 0
     */
    default long roomOutageWithin(@NonNull String platform, @NonNull Long uid, long from, long to) {
        return LiveGap.totalMillis(roomOutageIntervals(platform, uid, from, to));
    }

    /**
     * 获取最近一场直播结束时间戳
     * @param platform 直播平台
     * @param uid UID
     * @return 最近一场直播结束时间戳
     */
    Optional<Long> getLiveEndTime(@NonNull String platform, @NonNull Long uid);

    /**
     * 设置最近一场直播结束时间戳
     * @param platform 直播平台
     * @param uid UID
     * @param endTime 最近一场直播结束时间戳
     */
    void setLiveEndTime(@NonNull String platform, @NonNull Long uid, long endTime);

    /**
     * 删除最近一场直播结束时间戳
     * @param platform 直播平台
     * @param uid UID
     */
    void deleteLiveEndTime(@NonNull String platform, @NonNull Long uid);

    /**
     * 重置最近一场直播数据
     * @param platform 直播平台
     * @param uid UID
     */
    void resetLiveData(@NonNull String platform, @NonNull Long uid);

    // ================ 本场直播统计指标 ================
    // 以下均为 default 方法：LiveDataService 是可被第三方替换的扩展点，
    // 旧实现未覆盖这些方法时统计功能静默降级为「无数据」，不会因缺方法而无法启动

    /**
     * 累加本场直播的统计指标
     * @param platform 直播平台
     * @param uid UID
     * @param metric 指标名
     * @param delta 增量
     */
    default void incrementLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric, double delta) {
    }

    /**
     * 直接设定本场直播的统计指标
     * <p>
     * 用于记录**快照类**的值，如开播那一刻的粉丝数——它不是累加出来的，也不取最大。
     * 幂等，重复调用只是覆盖成同一个值。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param value 指标值
     */
    default void setLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric, double value) {
    }

    /**
     * 以取最大值的方式更新本场直播的统计指标，适用于服务端下发的单调累计值（如点赞总数）
     * @param platform 直播平台
     * @param uid UID
     * @param metric 指标名
     * @param value 候选值
     */
    default void maxLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric, double value) {
    }

    /**
     * 获取本场直播的统计指标
     * @param platform 直播平台
     * @param uid UID
     * @param metric 指标名
     * @return 指标值，未记录时为 0
     */
    default double getLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        return 0;
    }

    /**
     * 记录参与某项互动的用户，用于独立人数统计
     * <p>
     * 等价于以增量 1 调用 {@link #incrementLiveUserMetric}：独立人数就是「计分表里有几个人」。
     * 保留本方法是因为它的调用点语义更直白（只关心「有没有参与」而非「参与了多少」）。
     * @param platform 直播平台
     * @param uid UID
     * @param metric 指标名
     * @param userUid 参与用户的 UID
     */
    default void recordLiveMetricUser(@NonNull String platform, @NonNull Long uid, @NonNull String metric, @NonNull Long userUid) {
        incrementLiveUserMetric(platform, uid, metric, userUid, 1);
    }

    /**
     * 获取参与某项互动的独立用户数
     * @param platform 直播平台
     * @param uid UID
     * @param metric 指标名
     * @return 独立用户数，未记录时为 0
     */
    default int getLiveMetricUserCount(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        return 0;
    }

    // ================ 按用户计分（排行榜与个人数据） ================
    // 与上面的「本场指标」是两个维度：那边记总量，这边记「每个用户各贡献了多少」。
    // 排行榜取前 N 名，个人数据查单个用户，独立人数即计分表的大小。

    /**
     * 累加某个用户在本场直播的得分
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param userUid 用户 UID
     * @param delta 增量
     */
    default void incrementLiveUserMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                         @NonNull Long userUid, double delta) {
    }

    /**
     * 记录用户昵称，供排行榜展示
     * <p>
     * 昵称与计分分开存放：同一用户可能出现在多张计分表里，昵称只需存一份。
     * 榜单动辄数十人，绘制时逐个请求接口既慢又容易触发风控，故在事件到达时顺手记下。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param userUid 用户 UID
     * @param userName 用户昵称
     */
    default void recordLiveUserName(@NonNull String platform, @NonNull Long uid, @NonNull Long userUid, String userName) {
    }

    /**
     * 记录用户头像地址，供排行榜展示
     * <p>
     * 与昵称同理：头像地址在事件里现成带着，此时记下，绘制榜单时便不必逐个请求接口。
     * 逐个请求既慢又容易触发风控——这正是排行榜迟迟没加头像的原因。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param userUid 用户 UID
     * @param userFace 用户头像地址
     */
    default void recordLiveUserFace(@NonNull String platform, @NonNull Long uid, @NonNull Long userUid, String userFace) {
    }

    /**
     * 获取某个用户在本场直播的得分
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param userUid 用户 UID
     * @return 得分，未记录时为 0
     */
    default double getLiveUserMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                     @NonNull Long userUid) {
        return 0;
    }

    /**
     * 获取本场直播某项指标的用户排行
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param limit 取前多少名
     * @return 按得分降序排列的用户，不足时返回实际数量
     */
    default List<UserScore> getLiveUserRanking(
            @NonNull String platform, @NonNull Long uid, @NonNull String metric, int limit) {
        return List.of();
    }

    /**
     * 获取某个用户在本场排行中的名次
     * <p>
     * 单列一个方法而不让调用方自己在排行榜里找：Redis 的 zset 求名次是 O(log n)，
     * 拉一整张榜再遍历则是 O(n)，而「我排第几」恰恰是最常查的一项。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param userUid 用户 UID
     * @return 名次，从 1 开始；未上榜时为 0
     */
    default int getLiveUserRank(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                @NonNull Long userUid) {
        return 0;
    }

    /**
     * 获取本场直播的全部统计指标
     * <p>
     * 归档与分析需要「这一场都有些什么」，而不是逐个指标去问——指标名由各平台自行定义，
     * 核心并不知道有哪些。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 指标名到取值的映射，未记录时为空表
     */
    default Map<String, Double> getLiveMetrics(@NonNull String platform, @NonNull Long uid) {
        return Map.of();
    }

    /**
     * 获取本场直播各计分表的参与人数
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 指标名到独立人数的映射，未记录时为空表
     */
    default Map<String, Integer> getLiveMetricUserCounts(@NonNull String platform, @NonNull Long uid) {
        return Map.of();
    }

    /**
     * 获取本场直播各计分表的<b>参与者名单</b>（冻结项 F5）
     * <p>
     * 与 {@link #getLiveMetricUserCounts} 取的是同一份数据：那边只要 size，这边要 uid 本身。
     * <p>
     * ⚠️ <b>为什么非做不可</b>：计分表活在内存里、下一次开播就清空，
     * 而归档此前只留了 size——<b>每播一场就永久丢一场名单</b>。
     * 回流率、新客、周月去重 UV、同期群留存、沉睡预警、RFM 全都卡在这里，
     * 而且这一项<b>越晚做丢得越多</b>，所以排在所有分析工作最前面。
     * <p>
     * ⚠️ <b>返回原始 uid，不脱敏。</b> 隐私边界 2026-08-07 已定：
     * 数据由 Nova 系列统一管理、uid 不出这个生态；
     * <b>脱敏留给数据离开生态的场景，在导出层做不在采集层做</b>——
     * 采集层脱敏不可逆，存了哈希就再也换不回 uid。
     * <p>
     * ⚠️ 落盘之后 {@code sessions.jsonl} 就<b>长期持有他人的 uid</b>，与 {@code EventDebug/} 同性质。
     * 它不进仓库（{@code .gitignore} 已覆盖并过阳性对照），
     * 写测试时也<b>不许拿真实 uid 当夹具</b>——用保留段假值。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 指标名到参与者 uid 列表的映射，未记录时为空表
     */
    default Map<String, List<Long>> getLiveMetricUserSets(@NonNull String platform, @NonNull Long uid) {
        return Map.of();
    }

    // ================ 时间序列（互动曲线） ================
    // 与「本场指标」记总量、「按用户计分」记谁贡献了多少并列，这里记的是**什么时候发生的**。
    // 只服务于本场报告里的曲线图，因此随本场数据一并清零，也不并入累计。

    /**
     * 时间序列的分桶长度
     * <p>
     * 一分钟一格：一场 12 小时的直播是 720 格，画在 800px 宽的图上仍绰绰有余，
     * 而更细的粒度只会让曲线变成噪声。读写两侧都以此为准，不可各自定义。
     */
    long SERIES_BUCKET_MILLIS = 60_000L;

    /**
     * 把一次互动计入所属的时间格
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名，与本场指标同名，便于曲线与卡片对应
     * @param timestamp 事件发生时刻（毫秒）
     * @param delta 增量
     */
    default void incrementLiveSeries(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                     long timestamp, double delta) {
    }

    /**
     * 把一次读数计入所属的时间格，同一格内取最大值
     * <p>
     * <b>瞬时量必须走这里而不是 {@link #incrementLiveSeries}。</b>
     * 看过人数、高能用户数这类数字，平台每分钟要下发好几次，每次给的都是
     * 「当前是多少」而不是「又多了多少」——累加的话，一个真实值 8000 的读数
     * 在一分钟内下发 5 次就成了 40000。<b>而这个数看起来完全合理，不会有任何地方报错。</b>
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param timestamp 读数时刻（毫秒）
     * @param value 读数
     */
    default void maxLiveSeries(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                               long timestamp, double value) {
    }

    /**
     * 获取本场直播某项指标的时间序列
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @return 「时间格起始时刻（毫秒）→ 该格内的增量合计」，未记录时为空表。
     *         **没有互动的时间格不会出现在结果里**，绘图方需自行补零
     */
    default Map<Long, Double> getLiveSeries(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        return Map.of();
    }

    // ================ 累计数据 ================
    // 跨场次累计，数据量随时间无限增长，因此只有配置了外部存储（如 Redis）的实现才支持。
    // 未配置时一律返回「不支持」，由调用方明确告知使用者，不可静默返回 0——
    // 那会让人以为是数据丢了，而不是没开这个能力。

    /**
     * 当前实现是否支持累计数据
     * @return 是否支持
     */
    default boolean supportsTotalData() {
        return false;
    }

    /**
     * 把本场数据并入累计，在下播时调用
     * @param platform 直播平台
     * @param uid 主播 UID
     */
    default void mergeLiveDataIntoTotal(@NonNull String platform, @NonNull Long uid) {
    }

    /**
     * 获取累计的统计指标
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @return 指标值，不支持或未记录时为 0
     */
    default double getTotalMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        return 0;
    }

    /**
     * 获取某个用户的累计得分
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param userUid 用户 UID
     * @return 得分，不支持或未记录时为 0
     */
    default double getTotalUserMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                      @NonNull Long userUid) {
        return 0;
    }

    /**
     * 获取累计的用户排行
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param limit 取前多少名
     * @return 按得分降序排列的用户，不支持时为空
     */
    default List<UserScore> getTotalUserRanking(
            @NonNull String platform, @NonNull Long uid, @NonNull String metric, int limit) {
        return List.of();
    }

    /**
     * 获取某个用户在累计排行中的名次
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param userUid 用户 UID
     * @return 名次，从 1 开始；不支持或未上榜时为 0
     */
    default int getTotalUserRank(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                 @NonNull Long userUid) {
        return 0;
    }

    /**
     * 获取累计参与某项互动的独立用户数
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @return 独立用户数，不支持或未记录时为 0
     */
    default int getTotalMetricUserCount(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        return 0;
    }

    /**
     * 累计本场直播的词频，用于绘制弹幕词云
     * <p>
     * 存储的是分词后的词频而非弹幕原文：体积有上界，且能随直播数据一并持久化
     * @param platform 直播平台
     * @param uid UID
     * @param word 词语
     */
    default void incrementLiveWordFrequency(@NonNull String platform, @NonNull Long uid, @NonNull String word) {
    }

    /**
     * 获取本场直播的词频表
     * @param platform 直播平台
     * @param uid UID
     * @return 词语到出现次数的映射，未记录时为空表
     */
    default Map<String, Integer> getLiveWordFrequencies(@NonNull String platform, @NonNull Long uid) {
        return Map.of();
    }
}
