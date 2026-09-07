package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.LiveGap;
import com.starlwr.bot.core.model.UserScore;
import com.starlwr.bot.core.util.FaceUrlCodec;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 默认直播数据服务实现
 */
@Slf4j
@Service
public class DefaultLiveDataService implements LiveDataService {
    private final StarBotCoreProperties properties;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private JSONObject cache = new JSONObject();

    @Autowired
    public DefaultLiveDataService(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 上一个进程最后一次落盘的时刻，启动读文件时取下来
     * <p>
     * 必须在这里存一份：自动保存会不断改写文件里的那个值，几十秒后就问不出上次停在哪了，
     * 而崩溃恢复与停机缺口都要拿它当水位线。
     */
    private Long loadedLastSaveTime;

    /**
     * 上一个进程是不是正常退出的，与水位线同一刻读下来
     * <p>
     * 必须在这里存一份，理由与水位线同一个、而且更急：{@link #readWatermark} 读完当场就把
     * 文件里那一项改写成「否」，晚一步再问，答的是本次而不是上次。
     */
    private Boolean loadedCleanShutdown;

    /**
     * 加载直播数据
     */
    @Order(-10000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        if (properties.getLive().isSaveLiveData()) {
            String liveDataPath = properties.getLive().getLiveDataPath();
            log.info("开始从 {} 中加载直播数据", liveDataPath);
            try {
                cache = JSONObject.parseObject(Files.readString(Path.of(liveDataPath)));
            } catch (NoSuchFileException e) {
                log.warn("直播数据文件 {} 不存在, 建立新文件", liveDataPath);
            } catch (Exception e) {
                log.error("读取直播数据 {} 异常", liveDataPath, e);
            }
            log.info("直播数据加载完成");
            readWatermark();
            autoSave();
        }
    }

    /**
     * 读出上次落盘的水位线，并立刻把「本次是否正常退出」置为否
     * <p>
     * 立刻改写文件是有意的：这一项要到下一次自动保存才会被刷新，
     * 而进程刚起来那几十秒里崩掉的话，文件里还留着上次的「正常退出」，
     * 于是一次真崩溃会被报成一次正常停机。多写一次几 KB 的文件换掉这个误报。
     */
    private void readWatermark() {
        loadedLastSaveTime = cache.getLong(KEY_LAST_SAVE_TIME);
        // 取 Boolean 而不是 booleanValue：文件里根本没有这一项时要答「不知道」，
        // 答 false 就成了「上次崩过」——凭一个缺省值指认一次崩溃
        loadedCleanShutdown = cache.getBoolean(KEY_CLEAN_SHUTDOWN);

        if (loadedLastSaveTime == null) {
            // 4.3.0 之前的数据文件没有这一项，属正常
            log.info("直播数据里没有上次落盘时刻, 本次不做崩溃恢复与缺口计算");
        } else {
            log.info("上次落盘于 {}, 上次退出{}", localTime(loadedLastSaveTime),
                    loadedCleanShutdown == null ? "情况未知（数据文件里没有这一项）"
                            : loadedCleanShutdown ? "正常" : "异常（崩溃或被强杀）");
        }

        if (cache.isEmpty()) {
            return;
        }

        saveNow(false);
    }

    /**
     * 立刻把本场数据落盘
     * <p>
     * 三个调用点共用：启动改写、自动保存、退出收尾。抽出来是为了让测试能精确地
     * 模拟「崩溃前最后一次自动保存」——那是 {@code kill -9} 之后文件里唯一剩下的东西。
     * @param cleanShutdown 是否为正常退出时的收尾保存
     */
    void saveNow(boolean cleanShutdown) {
        String liveDataPath = properties.getLive().getLiveDataPath();
        try {
            Files.writeString(Path.of(liveDataPath), snapshot(cleanShutdown));
        } catch (Exception e) {
            log.error("保存直播数据至 {} 异常", liveDataPath, e);
        }
    }

    /**
     * 保存直播数据
     */
    @Order(0)
    @EventListener(ContextClosedEvent.class)
    public void onContextClosedEvent() {
        // 先停掉自动保存，避免与此处的收尾保存同时写同一个文件
        scheduler.shutdownNow();

        if (cache.isEmpty()) {
            return;
        }

        if (properties.getLive().isSaveLiveData()) {
            String liveDataPath = properties.getLive().getLiveDataPath();
            log.info("开始保存直播数据至 {}", liveDataPath);
            saveNow(true);
            log.info("直播数据已保存至 {}", liveDataPath);
        }
    }

    public void autoSave() {
        int interval = properties.getLive().getAutoSaveLiveDataInterval();

        scheduler.scheduleWithFixedDelay(() -> {
            Thread.currentThread().setName("auto-save-data");
            saveNow(false);
        }, interval, interval, TimeUnit.SECONDS);
    }

    /**
     * 生成缓存的 JSON 快照
     * <p>
     * 统计指标随直播间消息高频写入，序列化遍历期间若结构变化会直接抛异常，
     * 故拿锁序列化；文件写入在锁外进行，避免磁盘慢时阻塞指标写入。
     * <p>
     * 落盘时刻在这里盖章而不是由调用方传：它必须与这一份快照的内容严格对应，
     * 否则「采集到哪儿为止」就成了一个近似值。
     * @param cleanShutdown 本次是否为正常退出时的收尾保存
     */
    private String snapshot(boolean cleanShutdown) {
        synchronized (metricLock) {
            cache.put(KEY_LAST_SAVE_TIME, System.currentTimeMillis());
            cache.put(KEY_CLEAN_SHUTDOWN, cleanShutdown);
            return cache.toJSONString();
        }
    }

    // ================ 采集水位线与停机缺口 ================

    /**
     * 最后一次落盘时刻的存放键
     */
    private static final String KEY_LAST_SAVE_TIME = "LastSaveTime";

    /**
     * 上次是否正常退出的存放键
     */
    private static final String KEY_CLEAN_SHUTDOWN = "CleanShutdown";

    /**
     * 停机区间列表的存放键
     */
    private static final String KEY_DOWNTIMES = "Downtimes";

    /**
     * 停机记录的保留时长。留 30 天：月度统计要算得出「这个月有多少时间没在采」，
     * 再往前的场次早已归档，归档里带着当时算好的缺口
     */
    private static final long DOWNTIME_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000;

    /**
     * 时刻的本机时区表示。运维日志里的时刻一律用本机时区，
     * 混着 UTC 会让人对着两行日志算时差
     */
    private static String localTime(long millis) {
        return java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Override
    public Optional<Long> getLastSaveTime() {
        return Optional.ofNullable(loadedLastSaveTime);
    }

    @Override
    public Optional<Boolean> wasCleanShutdown() {
        return Optional.ofNullable(loadedCleanShutdown);
    }

    @Override
    public void recordDowntime(long from, long to, @NonNull LiveGap.Reason reason) {
        if (to <= from) {
            return;
        }

        synchronized (metricLock) {
            JSONArray downtimes = cache.getJSONArray(KEY_DOWNTIMES);
            if (downtimes == null) {
                downtimes = new JSONArray();
                cache.put(KEY_DOWNTIMES, downtimes);
            }

            long expiry = System.currentTimeMillis() - DOWNTIME_RETENTION_MILLIS;
            downtimes.removeIf(entry -> !(entry instanceof JSONObject json) || json.getLongValue("to") < expiry);

            JSONObject entry = new JSONObject();
            entry.put("from", from);
            entry.put("to", to);
            // 存枚举名而不是中文说明：说明是给人看的、改得动，键值是数据文件的一部分、改不得
            entry.put(FIELD_REASON, reason.name());
            downtimes.add(entry);
        }

        log.info("已记录一段停机: {} ~ {}, 共 {} 秒, 成因 {}",
                localTime(from), localTime(to), (to - from) / 1000, reason.getDescription());
    }

    @Override
    public List<LiveGap> downtimeIntervals(long from, long to) {
        if (to <= from) {
            return List.of();
        }

        List<LiveGap> clipped = new ArrayList<>();
        synchronized (metricLock) {
            JSONArray downtimes = cache.getJSONArray(KEY_DOWNTIMES);
            if (downtimes == null || downtimes.isEmpty()) {
                return List.of();
            }

            for (int i = 0; i < downtimes.size(); i++) {
                JSONObject entry = downtimes.getJSONObject(i);
                if (entry == null) {
                    continue;
                }
                // 只留交集，跨越开播时刻的那一段停机里，开播之前那一截不属于本场
                new LiveGap(entry.getLongValue("from"), entry.getLongValue("to"), reasonOf(entry))
                        .overlap(from, to).ifPresent(clipped::add);
            }
        }

        // 进程要么在跑要么没在跑，全局停机区间天然不重叠；仍走一遍合并是为了排序，
        // 顺带兜住数据文件被外部改坏、真出现两段重叠的那一天
        return LiveGap.merge(List.of(clipped));
    }

    /**
     * 缺口成因的存放字段
     */
    private static final String FIELD_REASON = "reason";

    /**
     * 读出一条缺口记录的成因
     * <p>
     * 认不出来一律 {@link LiveGap.Reason#UNKNOWN}：本项是 5.1 才加的，早先落盘的记录没有它，
     * 而<b>「不知道」比猜一个成因诚实</b>——猜出来的「维护」会让人以为这段空白已经有人解释过了。
     */
    private static LiveGap.Reason reasonOf(JSONObject entry) {
        String name = entry.getString(FIELD_REASON);
        if (name == null) {
            return LiveGap.Reason.UNKNOWN;
        }
        try {
            return LiveGap.Reason.valueOf(name);
        } catch (IllegalArgumentException e) {
            log.warn("直播数据里的缺口成因 {} 不认得, 按原因未定处理", name);
            return LiveGap.Reason.UNKNOWN;
        }
    }

    /**
     * 单个直播间断线区间的存放键
     * <p>
     * 与全局停机分开存：停机是进程层面的，一份就够；断线是每个房间各自的事。
     */
    private static final String KEY_ROOM_OUTAGES = "RoomOutages:";

    @Override
    public void recordRoomOutage(@NonNull String platform, @NonNull Long uid, long from, long to,
                                  @NonNull LiveGap.Reason reason) {
        if (to <= from) {
            return;
        }

        synchronized (metricLock) {
            JSONObject byRoom = cache.getJSONObject(KEY_ROOM_OUTAGES + platform);
            if (byRoom == null) {
                byRoom = new JSONObject();
                cache.put(KEY_ROOM_OUTAGES + platform, byRoom);
            }
            JSONArray outages = byRoom.getJSONArray(String.valueOf(uid));
            if (outages == null) {
                outages = new JSONArray();
                byRoom.put(String.valueOf(uid), outages);
            }

            // 保留窗口与停机记录一致：更早的场次早已归档，归档里带着当时算好的缺口
            long expiry = System.currentTimeMillis() - DOWNTIME_RETENTION_MILLIS;
            outages.removeIf(entry -> !(entry instanceof JSONObject json) || json.getLongValue("to") < expiry);

            JSONObject entry = new JSONObject();
            entry.put("from", from);
            entry.put("to", to);
            // 存枚举名而不是中文说明，理由与停机那边同一条
            entry.put(FIELD_REASON, reason.name());
            outages.add(entry);
        }

        log.debug("已记录直播间 {} 的一段断线: {} ~ {}, 共 {} 秒, 成因 {}",
                uid, localTime(from), localTime(to), (to - from) / 1000, reason.getDescription());
    }

    /**
     * 读出一条断线记录的成因
     * <p>
     * 与停机那边的 {@link #reasonOf} 判法不同：<b>缺字段读回 {@link LiveGap.Reason#STREAM_LOSS}
     * 而不是 UNKNOWN</b>——成因字段出现之前的断线记录全由断线重连那条路写入，
     * 那时落的就是断流，读回断流是还原事实而非猜测。认不出的名字仍按未定处理，
     * 与停机同一取舍。
     */
    private static LiveGap.Reason outageReasonOf(JSONObject entry) {
        String name = entry.getString(FIELD_REASON);
        if (name == null) {
            return LiveGap.Reason.STREAM_LOSS;
        }
        try {
            return LiveGap.Reason.valueOf(name);
        } catch (IllegalArgumentException e) {
            log.warn("直播数据里的断线成因 {} 不认得, 按原因未定处理", name);
            return LiveGap.Reason.UNKNOWN;
        }
    }

    /**
     * 查询某个直播间与给定区间重叠的断线区间
     * <p>
     * ⚠️ <b>先合并再返回。</b> 断线区间之间可能互相重叠（一次断线还没恢复又记了一次，
     * 或者重连过程中记了几段），把每段的交集直接摆出来会让上层<b>把同一秒数两遍</b>，
     * 算出比整场时长还大的缺口。全局停机那一侧不会重叠——进程要么在跑要么没在跑；
     * 这里不同。
     */
    @Override
    public List<LiveGap> roomOutageIntervals(@NonNull String platform, @NonNull Long uid, long from, long to) {
        if (to <= from) {
            return List.of();
        }

        List<LiveGap> clipped = new ArrayList<>();
        synchronized (metricLock) {
            JSONArray outages = Optional.ofNullable(cache.getJSONObject(KEY_ROOM_OUTAGES + platform))
                    .map(byRoom -> byRoom.getJSONArray(String.valueOf(uid)))
                    .orElse(null);
            if (outages == null || outages.isEmpty()) {
                return List.of();
            }

            for (int i = 0; i < outages.size(); i++) {
                JSONObject entry = outages.getJSONObject(i);
                if (entry == null) {
                    continue;
                }
                new LiveGap(entry.getLongValue("from"), entry.getLongValue("to"), outageReasonOf(entry))
                        .overlap(from, to).ifPresent(clipped::add);
            }
        }

        return LiveGap.merge(List.of(clipped));
    }

    // ================ 直播间状态 ================

    /**
     * 获取直播间状态
     *
     * @param platform 直播平台
     * @param uid      UID
     * @return 直播间状态，true：已开播，false：未开播
     */
    @Override
    public Optional<Boolean> getLiveStatus(@NonNull String platform, @NonNull Long uid) {
        String key = "LiveStatus:" + platform;
        return Optional.ofNullable(cache.getJSONObject(key)).map(data -> data.getBoolean(String.valueOf(uid)));
    }

    /**
     * 设置直播间状态
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param status   直播间状态，true：已开播，false：未开播
     */
    @Override
    public void setLiveStatus(@NonNull String platform, @NonNull Long uid, boolean status) {
        String key = "LiveStatus:" + platform;
        cache.putIfAbsent(key, new JSONObject());
        cache.getJSONObject(key).put(String.valueOf(uid), status);
    }

    // ================ 直播开始时间 ================

    /**
     * 获取最近一场直播开始时间戳
     *
     * @param platform 直播平台
     * @param uid      UID
     * @return 最近一场直播开始时间戳
     */
    @Override
    public Optional<Long> getLiveStartTime(@NonNull String platform, @NonNull Long uid) {
        String key = "LiveStartTime:" + platform;
        return Optional.ofNullable(cache.getJSONObject(key)).map(data -> data.getLong(String.valueOf(uid)));
    }

    /**
     * 设置最近一场直播开始时间戳
     *
     * @param platform  直播平台
     * @param uid       UID
     * @param startTime 最近一场直播开始时间戳
     */
    @Override
    public void setLiveStartTime(@NonNull String platform, @NonNull Long uid, long startTime) {
        String key = "LiveStartTime:" + platform;
        cache.putIfAbsent(key, new JSONObject());
        cache.getJSONObject(key).put(String.valueOf(uid), startTime);
    }

    // ================ 直播结束时间 ================

    /**
     * 获取最近一场直播结束时间戳
     *
     * @param platform 直播平台
     * @param uid      UID
     * @return 最近一场直播结束时间戳
     */
    @Override
    public Optional<Long> getLiveEndTime(@NonNull String platform, @NonNull Long uid) {
        String key = "LiveEndTime:" + platform;
        return Optional.ofNullable(cache.getJSONObject(key)).map(data -> data.getLong(String.valueOf(uid)));
    }

    /**
     * 设置最近一场直播结束时间戳
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param endTime  最近一场直播结束时间戳
     */
    @Override
    public void setLiveEndTime(@NonNull String platform, @NonNull Long uid, long endTime) {
        String key = "LiveEndTime:" + platform;
        cache.putIfAbsent(key, new JSONObject());
        cache.getJSONObject(key).put(String.valueOf(uid), endTime);
    }

    /**
     * 删除最近一场直播结束时间戳
     *
     * @param platform 直播平台
     * @param uid      UID
     */
    @Override
    public void deleteLiveEndTime(@NonNull String platform, @NonNull Long uid) {
        String key = "LiveEndTime:" + platform;
        Optional.ofNullable(cache.getJSONObject(key)).ifPresent(data -> data.remove(String.valueOf(uid)));
    }

    // ================ 本场直播统计指标 ================

    /**
     * 独立用户集合的容量上限，防止超大直播间把用户列表撑到不可收拾
     */
    private static final int METRIC_USER_LIMIT = 100_000;

    /**
     * 已就用户数上限告警过的「主播 + 指标」，用于每种组合只警告一次
     * <p>
     * 达到上限后每条弹幕都会走到该分支，不去重会瞬间刷屏
     */
    private final Set<String> userLimitWarned = ConcurrentHashMap.newKeySet();

    /**
     * 统计指标的写锁
     * <p>
     * 指标随直播间消息高频写入（弹幕高峰可达每秒数百条），与自动保存的序列化并发时
     * 会让 fastjson2 在遍历中途遇到结构变化。开关播状态等低频写入维持原状不加锁。
     */
    private final Object metricLock = new Object();

    /**
     * 累加本场直播的统计指标
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param metric   指标名
     * @param delta    增量
     */
    @Override
    public void incrementLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric, double delta) {
        synchronized (metricLock) {
            JSONObject metrics = metricsOf(platform, uid);
            metrics.put(metric, metrics.getDoubleValue(metric) + delta);
        }
    }

    /**
     * 直接设定本场直播的统计指标
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param metric   指标名
     * @param value    指标值
     */
    @Override
    public void setLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric, double value) {
        synchronized (metricLock) {
            metricsOf(platform, uid).put(metric, value);
        }
    }

    /**
     * 以取最大值的方式更新本场直播的统计指标
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param metric   指标名
     * @param value    候选值
     */
    @Override
    public void maxLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric, double value) {
        synchronized (metricLock) {
            JSONObject metrics = metricsOf(platform, uid);
            metrics.put(metric, Math.max(metrics.getDoubleValue(metric), value));
        }
    }

    /**
     * 获取本场直播的统计指标
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param metric   指标名
     * @return 指标值，未记录时为 0
     */
    @Override
    public double getLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        synchronized (metricLock) {
            return Optional.ofNullable(cache.getJSONObject("LiveMetric:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .map(metrics -> metrics.getDoubleValue(metric))
                    .orElse(0.0);
        }
    }

    /**
     * 累加某个用户在本场直播的得分
     * <p>
     * 以「用户 UID → 得分」的映射而非数组存储，含判重的写入是 O(1)。
     * 独立人数即该映射的大小，因此计分与计人数共用同一份数据。
     *
     * @param platform 直播平台
     * @param uid      主播 UID
     * @param metric   指标名
     * @param userUid  用户 UID
     * @param delta    增量
     */
    @Override
    public void incrementLiveUserMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                        @NonNull Long userUid, double delta) {
        synchronized (metricLock) {
            String key = "LiveMetricUser:" + platform;
            cache.putIfAbsent(key, new JSONObject());
            JSONObject byUid = cache.getJSONObject(key);
            byUid.putIfAbsent(String.valueOf(uid), new JSONObject());
            JSONObject byMetric = byUid.getJSONObject(String.valueOf(uid));
            byMetric.putIfAbsent(metric, new JSONObject());
            JSONObject users = byMetric.getJSONObject(metric);

            String userKey = String.valueOf(userUid);
            Double current = users.getDouble(userKey);
            if (current == null) {
                // 已达上限时不再收录新用户，但已在表内的继续累加：
                // 丢弃新用户只影响长尾，而中断已有用户的累加会让其数据凭空变小
                if (users.size() >= METRIC_USER_LIMIT) {
                    if (userLimitWarned.add(uid + ":" + metric)) {
                        log.warn("主播 {} 的 {} 计分表已达 {} 人上限, 后续新用户不再收录, 排行榜与人数统计会偏小",
                                uid, metric, METRIC_USER_LIMIT);
                    }
                    return;
                }
                users.put(userKey, delta);
            } else {
                users.put(userKey, current + delta);
            }
        }
    }

    /**
     * 获取某个用户在本场直播的得分
     *
     * @param platform 直播平台
     * @param uid      主播 UID
     * @param metric   指标名
     * @param userUid  用户 UID
     * @return 得分，未记录时为 0
     */
    @Override
    public double getLiveUserMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                    @NonNull Long userUid) {
        synchronized (metricLock) {
            return Optional.ofNullable(users(platform, uid, metric))
                    .map(users -> users.getDoubleValue(String.valueOf(userUid)))
                    .orElse(0.0);
        }
    }

    /**
     * 获取本场直播某项指标的用户排行
     *
     * @param platform 直播平台
     * @param uid      主播 UID
     * @param metric   指标名
     * @param limit    取前多少名
     * @return 按得分降序排列的用户
     */
    /**
     * 取得本场全部指标的快照，供并入累计存储
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 指标名到取值的映射
     */
    public java.util.Map<String, Double> liveMetrics(@NonNull String platform, @NonNull Long uid) {
        synchronized (metricLock) {
            JSONObject metrics = Optional.ofNullable(cache.getJSONObject("LiveMetric:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .orElse(null);
            if (metrics == null) {
                return java.util.Map.of();
            }

            java.util.Map<String, Double> result = new java.util.HashMap<>();
            for (String metric : metrics.keySet()) {
                result.put(metric, metrics.getDoubleValue(metric));
            }
            return result;
        }
    }

    /**
     * 取得本场全部用户计分表的快照，供并入累计存储
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 指标名到「用户 UID → 得分」的映射
     */
    public java.util.Map<String, java.util.Map<Long, Double>> liveUserMetrics(@NonNull String platform, @NonNull Long uid) {
        synchronized (metricLock) {
            JSONObject byMetric = Optional.ofNullable(cache.getJSONObject("LiveMetricUser:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .orElse(null);
            if (byMetric == null) {
                return java.util.Map.of();
            }

            java.util.Map<String, java.util.Map<Long, Double>> result = new java.util.HashMap<>();
            for (String metric : byMetric.keySet()) {
                JSONObject users = byMetric.getJSONObject(metric);
                if (users == null) {
                    continue;
                }
                java.util.Map<Long, Double> scores = new java.util.HashMap<>();
                for (String userKey : users.keySet()) {
                    try {
                        scores.put(Long.parseLong(userKey), users.getDoubleValue(userKey));
                    } catch (NumberFormatException ignored) {
                        // 非法用户键跳过即可，不必让整次并入失败
                    }
                }
                result.put(metric, scores);
            }
            return result;
        }
    }

    /**
     * 获取本场直播的全部统计指标
     *
     * @param platform 直播平台
     * @param uid      主播 UID
     * @return 指标名到取值的映射
     */
    @Override
    public Map<String, Double> getLiveMetrics(@NonNull String platform, @NonNull Long uid) {
        return liveMetrics(platform, uid);
    }

    /**
     * 获取本场直播各计分表的参与人数
     *
     * @param platform 直播平台
     * @param uid      主播 UID
     * @return 指标名到独立人数的映射
     */
    @Override
    public Map<String, Integer> getLiveMetricUserCounts(@NonNull String platform, @NonNull Long uid) {
        synchronized (metricLock) {
            JSONObject byMetric = Optional.ofNullable(cache.getJSONObject("LiveMetricUser:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .orElse(null);
            if (byMetric == null) {
                return Map.of();
            }

            Map<String, Integer> result = new HashMap<>();
            for (String metric : byMetric.keySet()) {
                JSONObject users = byMetric.getJSONObject(metric);
                result.put(metric, users == null ? 0 : users.size());
            }
            return result;
        }
    }

    /**
     * 获取本场直播各计分表的参与者名单（F5）
     * <p>
     * 与 {@link #getLiveMetricUserCounts} 读的是同一份数据、同一把锁，
     * <b>所以两者必然对得上</b>——归档时会断言这一点，对不上说明落盘漏了。
     * <p>
     * 名单里是<b>原始 uid</b>，隐私边界见接口文档。
     * @return 指标名到参与者 uid 列表的映射
     */
    @Override
    public Map<String, List<Long>> getLiveMetricUserSets(@NonNull String platform, @NonNull Long uid) {
        synchronized (metricLock) {
            JSONObject byMetric = Optional.ofNullable(cache.getJSONObject("LiveMetricUser:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .orElse(null);
            if (byMetric == null) {
                return Map.of();
            }

            Map<String, List<Long>> result = new HashMap<>();
            for (String metric : byMetric.keySet()) {
                JSONObject users = byMetric.getJSONObject(metric);
                if (users == null) {
                    result.put(metric, List.of());
                    continue;
                }
                List<Long> uids = new ArrayList<>(users.size());
                for (String userUid : users.keySet()) {
                    try {
                        uids.add(Long.parseLong(userUid));
                    } catch (NumberFormatException e) {
                        // 计分表的键理论上都是 uid 字符串；真出现非数字键时跳过这一个，
                        // 不能让一条脏数据把整场名单丢掉——名单丢了就再也补不回来
                        log.warn("计分表 {} 里有非数字的用户键, 已跳过: {}", metric, userUid);
                    }
                }
                result.put(metric, uids);
            }
            return result;
        }
    }

    /**
     * 取得本场记录到的用户昵称快照，供并入累计存储
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 用户 UID 到昵称的映射
     */
    public java.util.Map<Long, String> liveUserNames(@NonNull String platform, @NonNull Long uid) {
        synchronized (metricLock) {
            JSONObject names = Optional.ofNullable(cache.getJSONObject("LiveUserName:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .orElse(null);
            if (names == null) {
                return java.util.Map.of();
            }

            java.util.Map<Long, String> result = new java.util.HashMap<>();
            for (String userKey : names.keySet()) {
                try {
                    result.put(Long.parseLong(userKey), names.getString(userKey));
                } catch (NumberFormatException ignored) {
                    // 同上
                }
            }
            return result;
        }
    }

    /**
     * 取得本场记录到的用户头像快照，供并入累计存储
     * <p>
     * <b>返回的是存储态（压缩形态），不是可直接下载的地址。</b>它只用于原样搬运到
     * 累计存储，两边保持同一种形态，读取时统一由 {@link FaceUrlCodec#expand} 还原。
     * 若要拿去下载图片，必须先自行还原。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 用户 UID 到头像存储态的映射
     */
    public java.util.Map<Long, String> liveUserFaces(@NonNull String platform, @NonNull Long uid) {
        synchronized (metricLock) {
            JSONObject faces = Optional.ofNullable(cache.getJSONObject("LiveUserFace:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .orElse(null);
            if (faces == null) {
                return java.util.Map.of();
            }

            java.util.Map<Long, String> result = new java.util.HashMap<>();
            for (String userKey : faces.keySet()) {
                try {
                    result.put(Long.parseLong(userKey), faces.getString(userKey));
                } catch (NumberFormatException ignored) {
                    // 同上
                }
            }
            return result;
        }
    }

    /**
     * 记录用户昵称
     *
     * @param platform 直播平台
     * @param uid      主播 UID
     * @param userUid  用户 UID
     * @param userName 用户昵称
     */
    @Override
    public void recordLiveUserName(@NonNull String platform, @NonNull Long uid, @NonNull Long userUid, String userName) {
        if (userName == null || userName.isBlank()) {
            return;
        }

        synchronized (metricLock) {
            String key = "LiveUserName:" + platform;
            cache.putIfAbsent(key, new JSONObject());
            JSONObject byUid = cache.getJSONObject(key);
            byUid.putIfAbsent(String.valueOf(uid), new JSONObject());
            JSONObject names = byUid.getJSONObject(String.valueOf(uid));

            String userKey = String.valueOf(userUid);
            // 与计分表同样的容量约束：昵称表只为已计分的用户服务，不应比它更大
            if (names.containsKey(userKey) || names.size() < METRIC_USER_LIMIT) {
                names.put(userKey, userName);
            }
        }
    }

    /**
     * 获取某个用户在本场排行中的名次
     *
     * @param platform 直播平台
     * @param uid      主播 UID
     * @param metric   指标名
     * @param userUid  用户 UID
     * @return 名次，从 1 开始；未上榜时为 0
     */
    @Override
    public int getLiveUserRank(@NonNull String platform, @NonNull Long uid, @NonNull String metric, @NonNull Long userUid) {
        synchronized (metricLock) {
            JSONObject users = users(platform, uid, metric);
            String userKey = String.valueOf(userUid);
            if (users == null || !users.containsKey(userKey)) {
                return 0;
            }

            // 数一数有多少人分数比他高即可，不必把整张榜排序。
            // 同分并列取较优名次：并列第一时两人都显示第 1，而不是一个第 1 一个第 2
            double score = users.getDoubleValue(userKey);
            int higher = 0;
            for (String key : users.keySet()) {
                if (!key.equals(userKey) && users.getDoubleValue(key) > score) {
                    higher++;
                }
            }
            return higher + 1;
        }
    }

    /**
     * 记录用户头像地址
     *
     * @param platform 直播平台
     * @param uid      主播 UID
     * @param userUid  用户 UID
     * @param userFace 用户头像地址
     */
    @Override
    public void recordLiveUserFace(@NonNull String platform, @NonNull Long uid, @NonNull Long userUid, String userFace) {
        if (userFace == null || userFace.isBlank()) {
            return;
        }

        synchronized (metricLock) {
            String key = "LiveUserFace:" + platform;
            cache.putIfAbsent(key, new JSONObject());
            JSONObject byUid = cache.getJSONObject(key);
            byUid.putIfAbsent(String.valueOf(uid), new JSONObject());
            JSONObject faces = byUid.getJSONObject(String.valueOf(uid));

            String userKey = String.valueOf(userUid);
            // 与昵称表同样的容量约束：头像表只为已计分的用户服务，不该比计分表更大
            if (faces.containsKey(userKey) || faces.size() < METRIC_USER_LIMIT) {
                // 存压缩形态，读取时还原，详见 FaceUrlCodec
                faces.put(userKey, FaceUrlCodec.compact(userFace));
            }
        }
    }

    @Override
    public List<UserScore> getLiveUserRanking(@NonNull String platform, @NonNull Long uid, @NonNull String metric, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        synchronized (metricLock) {
            JSONObject users = users(platform, uid, metric);
            if (users == null) {
                return List.of();
            }

            // JSON 结构不像 Redis 的 zset 那样自带排序，只能取出后在内存里排。
            // 单直播间的用户数有上限，这个开销可以接受，且排行榜只在下播与查询时才取
            JSONObject names = Optional.ofNullable(cache.getJSONObject("LiveUserName:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .orElseGet(JSONObject::new);
            JSONObject faces = Optional.ofNullable(cache.getJSONObject("LiveUserFace:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .orElseGet(JSONObject::new);

            List<UserScore> scores = new ArrayList<>(users.size());
            for (String userKey : users.keySet()) {
                try {
                    scores.add(new UserScore(Long.parseLong(userKey), names.getString(userKey),
                            FaceUrlCodec.expand(faces.getString(userKey)), users.getDoubleValue(userKey)));
                } catch (NumberFormatException e) {
                    // 手工编辑数据文件等情况下可能混入非法键，跳过即可，不必让整个排行榜失败
                    log.debug("跳过计分表中的非法用户键: {}", userKey);
                }
            }

            scores.sort(Comparator.comparingDouble(UserScore::score).reversed());
            return scores.size() <= limit ? scores : List.copyOf(scores.subList(0, limit));
        }
    }

    /**
     * 取出某项指标的用户计分表，调用方需持有 {@link #metricLock}
     */
    private JSONObject users(String platform, Long uid, String metric) {
        return Optional.ofNullable(cache.getJSONObject("LiveMetricUser:" + platform))
                .map(data -> data.getJSONObject(String.valueOf(uid)))
                .map(byMetric -> byMetric.getJSONObject(metric))
                .orElse(null);
    }

    /**
     * 获取参与某项互动的独立用户数
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param metric   指标名
     * @return 独立用户数，未记录时为 0
     */
    @Override
    public int getLiveMetricUserCount(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        synchronized (metricLock) {
            return Optional.ofNullable(cache.getJSONObject("LiveMetricUser:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .map(byMetric -> byMetric.getJSONObject(metric))
                    .map(JSONObject::size)
                    .orElse(0);
        }
    }

    /**
     * 取出（必要时创建）指定主播的指标容器，调用方需持有 {@link #metricLock}
     */
    private JSONObject metricsOf(String platform, Long uid) {
        String key = "LiveMetric:" + platform;
        cache.putIfAbsent(key, new JSONObject());
        JSONObject byUid = cache.getJSONObject(key);
        byUid.putIfAbsent(String.valueOf(uid), new JSONObject());
        return byUid.getJSONObject(String.valueOf(uid));
    }

    /**
     * 词频表的词汇量上限，超过后不再收录新词（已收录的词仍会累计）
     */
    private static final int WORD_FREQUENCY_LIMIT = 5_000;

    /**
     * 累计本场直播的词频
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param word     词语
     */
    @Override
    public void incrementLiveWordFrequency(@NonNull String platform, @NonNull Long uid, @NonNull String word) {
        synchronized (metricLock) {
            String key = "LiveWordFrequency:" + platform;
            cache.putIfAbsent(key, new JSONObject());
            JSONObject byUid = cache.getJSONObject(key);
            byUid.putIfAbsent(String.valueOf(uid), new JSONObject());
            JSONObject words = byUid.getJSONObject(String.valueOf(uid));

            Integer current = words.getInteger(word);
            if (current == null && words.size() >= WORD_FREQUENCY_LIMIT) {
                return;
            }
            words.put(word, current == null ? 1 : current + 1);
        }
    }

    /**
     * 获取本场直播的词频表
     *
     * @param platform 直播平台
     * @param uid      UID
     * @return 词语到出现次数的映射，未记录时为空表
     */
    @Override
    public Map<String, Integer> getLiveWordFrequencies(@NonNull String platform, @NonNull Long uid) {
        synchronized (metricLock) {
            JSONObject words = Optional.ofNullable(cache.getJSONObject("LiveWordFrequency:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .orElse(null);
            if (words == null) {
                return Map.of();
            }

            Map<String, Integer> result = new HashMap<>();
            for (String word : words.keySet()) {
                Integer count = words.getInteger(word);
                if (count != null) {
                    result.put(word, count);
                }
            }
            return result;
        }
    }

    // ================ 时间序列（互动曲线） ================

    /**
     * 单项指标最多保留的时间格数
     * <p>
     * 一分钟一格，1440 格即 24 小时。超长的直播（或忘记清零的异常场次）到此为止，
     * 不再收录新格；已有的格仍会继续累加，所以曲线是被截断而不是错乱。
     */
    private static final int SERIES_BUCKET_LIMIT = 1440;

    /**
     * 把一次互动计入所属的时间格
     *
     * @param platform  直播平台
     * @param uid       主播 UID
     * @param metric    指标名
     * @param timestamp 事件发生时刻（毫秒）
     * @param delta     增量
     */
    @Override
    public void incrementLiveSeries(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                    long timestamp, double delta) {
        String bucket = String.valueOf(timestamp / SERIES_BUCKET_MILLIS * SERIES_BUCKET_MILLIS);

        synchronized (metricLock) {
            String key = "LiveSeries:" + platform;
            cache.putIfAbsent(key, new JSONObject());
            JSONObject byUid = cache.getJSONObject(key);
            byUid.putIfAbsent(String.valueOf(uid), new JSONObject());
            JSONObject byMetric = byUid.getJSONObject(String.valueOf(uid));
            byMetric.putIfAbsent(metric, new JSONObject());
            JSONObject buckets = byMetric.getJSONObject(metric);

            Double current = buckets.getDouble(bucket);
            if (current == null && buckets.size() >= SERIES_BUCKET_LIMIT) {
                log.warn("主播 {} 的指标 {} 时间格数已达上限 {}, 后续时段不再收录", uid, metric, SERIES_BUCKET_LIMIT);
                return;
            }
            buckets.put(bucket, (current == null ? 0 : current) + delta);
        }
    }

    /**
     * 把一次读数计入所属的时间格，同一格内取最大值
     *
     * @param platform  直播平台
     * @param uid       主播 UID
     * @param metric    指标名
     * @param timestamp 读数时刻（毫秒）
     * @param value     读数
     */
    @Override
    public void maxLiveSeries(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                              long timestamp, double value) {
        String bucket = String.valueOf(timestamp / SERIES_BUCKET_MILLIS * SERIES_BUCKET_MILLIS);

        synchronized (metricLock) {
            String key = "LiveSeries:" + platform;
            cache.putIfAbsent(key, new JSONObject());
            JSONObject byUid = cache.getJSONObject(key);
            byUid.putIfAbsent(String.valueOf(uid), new JSONObject());
            JSONObject byMetric = byUid.getJSONObject(String.valueOf(uid));
            byMetric.putIfAbsent(metric, new JSONObject());
            JSONObject buckets = byMetric.getJSONObject(metric);

            Double current = buckets.getDouble(bucket);
            if (current == null && buckets.size() >= SERIES_BUCKET_LIMIT) {
                log.warn("主播 {} 的指标 {} 时间格数已达上限 {}, 后续时段不再收录", uid, metric, SERIES_BUCKET_LIMIT);
                return;
            }
            buckets.put(bucket, current == null ? value : Math.max(current, value));
        }
    }

    /**
     * 获取本场直播某项指标的时间序列
     *
     * @param platform 直播平台
     * @param uid      主播 UID
     * @param metric   指标名
     * @return 时间格起始时刻到增量的映射，未记录时为空表
     */
    @Override
    public Map<Long, Double> getLiveSeries(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        synchronized (metricLock) {
            JSONObject buckets = Optional.ofNullable(cache.getJSONObject("LiveSeries:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .map(byMetric -> byMetric.getJSONObject(metric))
                    .orElse(null);
            if (buckets == null) {
                return Map.of();
            }

            // 用 TreeMap 是为了让调用方拿到按时间递增的序列——曲线的横轴就是它
            Map<Long, Double> result = new TreeMap<>();
            for (String bucket : buckets.keySet()) {
                try {
                    result.put(Long.parseLong(bucket), buckets.getDoubleValue(bucket));
                } catch (NumberFormatException e) {
                    log.debug("跳过时间序列中的非法时间格: {}", bucket);
                }
            }
            return result;
        }
    }

    /**
     * 获取本场直播有哪些时间序列
     *
     * @param platform 直播平台
     * @param uid      主播 UID
     * @return 有序列记录的指标名，未记录时为空集
     */
    @Override
    public java.util.Set<String> getLiveSeriesMetrics(@NonNull String platform, @NonNull Long uid) {
        synchronized (metricLock) {
            JSONObject byMetric = Optional.ofNullable(cache.getJSONObject("LiveSeries:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .orElse(null);
            // 拷一份出去而不是把 keySet 直接给出来：那是缓存对象自己的视图，
            // 调用方遍历时另一个直播间的事件正在往里写，就是一次 ConcurrentModificationException
            return byMetric == null ? java.util.Set.of() : new java.util.LinkedHashSet<>(byMetric.keySet());
        }
    }

    // ================ 其他操作 ================

    /**
     * 重置最近一场直播数据
     * <p>
     * 在开播时被调用，清空上一场直播累计的统计指标，让新一场从零开始
     *
     * @param platform 直播平台
     * @param uid      UID
     */
    @Override
    public void resetLiveData(@NonNull String platform, @NonNull Long uid) {
        synchronized (metricLock) {
            Optional.ofNullable(cache.getJSONObject("LiveMetric:" + platform))
                    .ifPresent(data -> data.remove(String.valueOf(uid)));
            Optional.ofNullable(cache.getJSONObject("LiveMetricUser:" + platform))
                    .ifPresent(data -> data.remove(String.valueOf(uid)));
            Optional.ofNullable(cache.getJSONObject("LiveWordFrequency:" + platform))
                    .ifPresent(data -> data.remove(String.valueOf(uid)));
            Optional.ofNullable(cache.getJSONObject("LiveUserName:" + platform))
                    .ifPresent(data -> data.remove(String.valueOf(uid)));
            Optional.ofNullable(cache.getJSONObject("LiveUserFace:" + platform))
                    .ifPresent(data -> data.remove(String.valueOf(uid)));
            Optional.ofNullable(cache.getJSONObject("LiveSeries:" + platform))
                    .ifPresent(data -> data.remove(String.valueOf(uid)));
        }
    }
}
