package com.starlwr.bot.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.analytics.LiveDetail;
import com.starlwr.bot.core.analytics.LiveMetricCatalog;
import com.starlwr.bot.core.analytics.LiveSessionAnalytics;
import com.starlwr.bot.core.config.ui.ConfigUiController;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.model.LiveSession;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.model.StreamerSnapshot;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveDetailArchive;
import com.starlwr.bot.core.service.LiveReportRedrawer;
import com.starlwr.bot.core.service.LiveSessionArchive;
import com.starlwr.bot.core.service.StreamerDirectory;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.StreamerSnapshotArchive;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 主播数据接口
 * <p>
 * 「主播」页与「主播详情」的数据来源。与运营分析（{@link AnalyticsController}）的分工是：
 * 那一支按周期把<b>全部</b>场次聚合成趋势，这一支答的是<b>某一位主播</b>现在什么样、
 * 最近播得怎么样、每一场分别发生了什么。
 * <p>
 * <b>名单取自推送配置而不是内存里的数据源</b>，理由见 {@link StreamerDirectory}：
 * 停用的主播在内存里根本不存在，问内存要名单会让「停用」看起来像「删除」。
 * <p>
 * <b>路径里不写平台名</b>，与报告版式那两支同理：主播是核心界面上的一件东西。
 * 平台作为路径变量出现，那是在说「这一位主播属于哪个平台」，不是把界面绑到某个平台上。
 */
@Slf4j
@StarBotComponent
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/streamers")
@ConditionalOnProperty(name = "starbot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class StreamerController {
    /**
     * 列表里那条小折线画几天
     * <p>
     * 七天恰好是一个作息周期：少于七天看不出「周末播得多」这类规律，多于七天在一行里挤不下。
     */
    static final int SERIES_DAYS = 7;

    /**
     * 场次分页默认每页几条
     */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final int MAX_PAGE_SIZE = 200;

    /**
     * 趋势默认返回多少个周期，与运营分析同值——同一份数据在两个页面上该看到一样多
     */
    private static final int DEFAULT_TREND_LIMIT = 26;

    private static final int MAX_TREND_LIMIT = 120;

    private final AbstractDataSource dataSource;

    private final StreamerDirectory directory;

    private final LiveDataService liveDataService;

    private final LiveSessionArchive archive;

    private final StreamerSnapshotArchive snapshots;

    /**
     * 每场明细。控制台回看报告图时按它现画
     */
    private final LiveDetailArchive details;

    /**
     * 各平台插件提供的指标说明，取法与运营分析一致（插件的 Bean 定义延迟注册）
     */
    private final ObjectProvider<LiveMetricCatalog> catalogs;

    /**
     * 各平台插件提供的报告重绘实现，取法同上
     */
    private final ObjectProvider<LiveReportRedrawer> redrawers;

    private final LiveSessionAnalytics analytics = new LiveSessionAnalytics();

    private final ZoneId zone = ZoneId.systemDefault();

    @Autowired
    public StreamerController(AbstractDataSource dataSource,
                              StreamerDirectory directory,
                              LiveDataService liveDataService,
                              LiveSessionArchive archive,
                              StreamerSnapshotArchive snapshots,
                              LiveDetailArchive details,
                              ObjectProvider<LiveMetricCatalog> catalogs,
                              ObjectProvider<LiveReportRedrawer> redrawers) {
        this.dataSource = dataSource;
        this.directory = directory;
        this.liveDataService = liveDataService;
        this.archive = archive;
        this.snapshots = snapshots;
        this.details = details;
        this.catalogs = catalogs;
        this.redrawers = redrawers;
    }

    /**
     * 主播列表
     * <p>
     * 每位一行：是谁、现在什么状态、最近七天播了多少，以及一条按日的小折线。
     * @return 主播清单
     */
    @GetMapping
    public JSONObject streamers() {
        JSONObject result = new JSONObject();
        result.put("success", true);

        Map<String, PushUser> loaded = loadedUsers();
        List<StreamerDirectory.Entry> entries = roster(loaded);
        List<LiveSession> all = archive.find(0, Long.MAX_VALUE);
        Map<String, List<LiveSession>> byStreamer = groupByStreamer(all);

        LocalDate today = LocalDate.now(zone);
        LocalDate windowStart = today.minusDays(SERIES_DAYS - 1L);

        JSONArray items = new JSONArray();
        for (StreamerDirectory.Entry entry : entries) {
            String key = key(entry.platform(), entry.uid());
            List<LiveSession> sessions = byStreamer.getOrDefault(key, List.of());

            JSONObject item = identity(entry.platform(), entry.uid(), loaded.get(key), sessions);
            putStatus(item, entry.platform(), entry.uid(), entry.enabled(), entry.pushing());
            item.put("summary", summary(sessions, windowStart, today));
            item.put("series", series(sessions, windowStart));
            items.add(item);
        }

        result.put("streamers", items);
        result.put("total", items.size());
        result.put("days", SERIES_DAYS);
        // 状态闭集由接口给出，界面不必自己写一张会漏项的表——与时间线那支同一条理由
        result.put("statuses", statuses());
        // ⚠️ 上面的名单只圈了「推送配置里的」。从配置里删掉的主播，他过去的场次仍在归档里，
        // 运营分析那一页照样列得出来。两处圈法不同时，只报一个数会让人以为这就是全部，
        // 因此把差集本身点名报出来——不是报个数，是报「是谁」
        result.put("notConfigured", notConfigured(entries, byStreamer));

        return result;
    }

    /**
     * 主播详情
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param period 趋势周期，week 或 month
     * @param page 场次页码，从 1 起
     * @param size 场次每页条数
     * @return 概况、场次与趋势
     */
    @GetMapping("/{platform}/{uid}")
    public ResponseEntity<JSONObject> streamer(@PathVariable String platform,
                                               @PathVariable Long uid,
                                               @RequestParam(defaultValue = "week") String period,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "0") int size) {
        String key = key(platform, uid);
        Map<String, PushUser> loaded = loadedUsers();
        List<LiveSession> sessions = groupByStreamer(archive.find(0, Long.MAX_VALUE))
                .getOrDefault(key, List.of());
        Optional<StreamerDirectory.Entry> entry = roster(loaded).stream()
                .filter(item -> key.equals(key(item.platform(), item.uid())))
                .findFirst();

        if (entry.isEmpty() && sessions.isEmpty()) {
            // 既不在配置里、也没有任何一场归档，那就是真没这位主播。
            // 回一份空壳会让「uid 打错一位」看起来像「这位主播还没播过」
            JSONObject missing = new JSONObject();
            missing.put("success", false);
            missing.put("message", "没有这位主播的记录：他既不在推送配置里，也没有归档过的场次");
            return ResponseEntity.status(404).body(missing);
        }

        JSONObject result = identity(platform, uid, loaded.get(key), sessions);
        result.put("success", true);
        putStatus(result, platform, uid,
                entry.map(StreamerDirectory.Entry::enabled).orElse(false),
                entry.map(StreamerDirectory.Entry::pushing).orElse(false));
        // 不在配置里的那位，上面的状态是按「已停用」给的。多给一位真话：他压根没在册
        result.put("configured", entry.isPresent());

        result.put("overview", overview(platform, uid, sessions));
        result.put("sessions", sessionPage(platform, uid, sessions, page, size));
        result.put("trend", trend(sessions, period));

        return ResponseEntity.ok(result);
    }

    /**
     * 某一场的报告图
     * <p>
     * 控制台回看时按当场明细现画。<b>没有图不是错误</b>——那一场可能压根没配下播报告，
     * 也可能画失败改发了文字版，还可能是启用明细留档之前的老场次，所以回的是 404 加一句人话，
     * 而不是 500 或者一张空图。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param start 本场开播时刻（毫秒），与场次列表里的 startTime 同值
     * @return PNG 图片
     */
    @GetMapping("/{platform}/{uid}/sessions/{start}/report")
    public ResponseEntity<?> report(@PathVariable String platform,
                                    @PathVariable Long uid,
                                    @PathVariable long start) {
        Optional<byte[]> image = redraw(platform, uid, start);

        if (image.isEmpty()) {
            JSONObject missing = new JSONObject();
            missing.put("success", false);
            missing.put("message", "这一场没有报告图，也没有留下明细数据，重新绘制不出来："
                    + "可能没开下播报告，也可能这一场早于「每场留明细」这个功能");
            return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON).body(missing);
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(image.get());
    }

    /**
     * 从明细现画一场的报告
     * <p>
     * 现画出来的图<b>不另存副本</b>：推送当时已经把图内联发出去了，控制台回看再画一次。
     * 重画一次的代价是几百毫秒，比另存一份成品图划算。
     * <p>
     * 没有对应平台的重画实现时为空——那时它与「没有明细」在结果上一样，都是 404。
     * <b>但两者的日志不同</b>：一个是「这台机器没装这个平台的插件」，一个是「这一场没数据」。
     */
    private Optional<byte[]> redraw(String platform, Long uid, long start) {
        Optional<LiveDetail> detail = details.read(platform, uid, start);
        if (detail.isEmpty()) {
            return Optional.empty();
        }

        Optional<LiveReportRedrawer> redrawer = redrawers.orderedStream()
                .filter(one -> platform.equals(one.platform()))
                .findFirst();
        if (redrawer.isEmpty()) {
            log.debug("没有 {} 平台的报告重绘实现, 这一场看不到报告图", platform);
            return Optional.empty();
        }

        long t = System.nanoTime();
        Optional<byte[]> image = redrawer.get().redraw(detail.get());
        log.info("报告重画耗时 {} ms", (System.nanoTime() - t) / 1_000_000L);
        return image;
    }

    /**
     * 是谁：uid、昵称、头像、直播间号
     * <p>
     * 昵称与头像优先取内存里那份（加载时向平台补全过），<b>取不到时退回最近一场归档里的昵称</b>——
     * 停用的主播不在内存里，只显示一串 uid 的话，使用者认不出那是谁，也就无从决定要不要恢复。
     */
    private JSONObject identity(String platform, Long uid, PushUser loadedUser, List<LiveSession> sessions) {
        JSONObject item = new JSONObject();
        item.put("platform", platform);
        item.put("uid", uid);

        String uname = loadedUser == null ? null : loadedUser.getUname();
        Long roomId = loadedUser == null ? null : loadedUser.getRoomId();
        if ((uname == null || uname.isBlank()) && !sessions.isEmpty()) {
            LiveSession latest = sessions.get(sessions.size() - 1);
            uname = latest.uname();
            if (roomId == null) {
                roomId = latest.roomId();
            }
        }

        item.put("uname", uname);
        item.put("face", loadedUser == null ? null : loadedUser.getFace());
        item.put("roomId", roomId);
        return item;
    }

    /**
     * 状态四项，以及算出它的那三个事实
     * <p>
     * <b>三个事实一并给出，不只给结论。</b>状态是一个闭集里的一项，一行只能显示一个，
     * 而「没配通道」与「正在播」可以同时成立——只给结论的话，界面上要么丢掉在播那一点，
     * 要么得自己再算一遍，而自己算的那一份迟早与这里分叉。
     * <p>
     * 优先级：已停用 &gt; 只采集不推送 &gt; 在播 &gt; 未开播。停用的排最前，是因为停用之后
     * 根本不再采集，此时的直播状态是上次留下的陈值；「只采集不推送」排在「在播」之前，
     * 是因为它是一项配置事实，看得见才改得动，而在播与否由旁边的圆点表示。
     */
    private void putStatus(JSONObject item, String platform, Long uid, boolean enabled, boolean pushing) {
        boolean living = enabled && liveDataService.getLiveStatus(platform, uid).orElse(false);

        Status status;
        if (!enabled) {
            status = Status.DISABLED;
        } else if (!pushing) {
            status = Status.COLLECT_ONLY;
        } else if (living) {
            status = Status.LIVE;
        } else {
            status = Status.OFFLINE;
        }

        item.put("status", status.name());
        item.put("statusText", status.getText());
        item.put("enabled", enabled);
        item.put("pushing", pushing);
        item.put("living", living);
        // 开播时刻可能没记上（例如程序在别人已经开播之后才起来），此时给 null，
        // 界面那一侧就不写「已播 N 小时」——编一个开始时刻出来，那个时长会一直是错的
        item.put("since", living ? liveDataService.getLiveStartTime(platform, uid).orElse(null) : null);
    }

    /**
     * 七天汇总：几场、多久、最近一场是什么时候
     * <p>
     * 「最近一场」不受七天窗口限制：一位一个月没播的主播，界面上该写出他上次播是什么时候，
     * 而不是因为不在窗口里就显示成从来没播过。
     */
    private JSONObject summary(List<LiveSession> sessions, LocalDate windowStart, LocalDate today) {
        int count = 0;
        long duration = 0;
        Long lastStart = null;

        for (LiveSession session : sessions) {
            LocalDate day = dateOf(session.startTime());
            if (!day.isBefore(windowStart) && !day.isAfter(today)) {
                count++;
                duration += session.durationSeconds();
            }
            if (lastStart == null || session.startTime() > lastStart) {
                lastStart = session.startTime();
            }
        }

        JSONObject json = new JSONObject();
        json.put("days", SERIES_DAYS);
        json.put("sessions", count);
        json.put("durationSeconds", duration);
        json.put("lastStart", lastStart);
        return json;
    }

    /**
     * 七天折线，按日一点
     * <p>
     * <b>没播的那一天给 0 而不是跳过</b>：跳过会让折线看起来比实际连贯，
     * 而「哪几天没播」本身就是要看的东西。与运营分析里「空周期也要出现」同一条规矩。
     * <p>
     * 一个点上同时给场次数与时长，是因为这条小折线画哪一项由界面定：
     * 只给其中一项的话，另一项要么缺，要么由界面拿别处的数拼——那两个数迟早对不上。
     */
    private JSONArray series(List<LiveSession> sessions, LocalDate windowStart) {
        Map<LocalDate, int[]> counts = new LinkedHashMap<>();
        Map<LocalDate, Long> durations = new LinkedHashMap<>();
        for (int i = 0; i < SERIES_DAYS; i++) {
            LocalDate day = windowStart.plusDays(i);
            counts.put(day, new int[]{0});
            durations.put(day, 0L);
        }

        for (LiveSession session : sessions) {
            LocalDate day = dateOf(session.startTime());
            int[] count = counts.get(day);
            if (count == null) {
                continue;
            }
            count[0]++;
            durations.merge(day, session.durationSeconds(), Long::sum);
        }

        JSONArray points = new JSONArray();
        counts.forEach((day, count) -> {
            JSONObject point = new JSONObject();
            point.put("date", day.toString());
            point.put("sessions", count[0]);
            point.put("durationSeconds", durations.get(day));
            points.add(point);
        });
        return points;
    }

    /**
     * 概况：累计场次与时长，以及最近一次采到的基础数据
     * <p>
     * <b>基础数据原样给出、不挑字段。</b>粉丝数在哔哩哔哩叫 {@code fans}，
     * 而核心并不知道各平台会采什么——把某个平台的键名写进核心，装第二个平台时
     * 那一栏要么空着要么显示错东西。这与指标说明那套（{@link LiveMetricCatalog}）同一条道理。
     * <p>
     * 那些裸键叫什么，另起一栏 {@code snapshotMetrics} 由平台插件自报（同一个扩展点）。
     * 值与说明分两栏给：<b>目录里没有的键界面要原样显示出来</b>，两栏并成一栏的话，
     * 插件新采了一项指标而目录还没跟上的那一天，那一项会在屏幕上直接消失。
     */
    private JSONObject overview(String platform, Long uid, List<LiveSession> sessions) {
        long duration = 0;
        Long firstStart = null;
        Long lastStart = null;
        for (LiveSession session : sessions) {
            duration += session.durationSeconds();
            if (firstStart == null || session.startTime() < firstStart) {
                firstStart = session.startTime();
            }
            if (lastStart == null || session.startTime() > lastStart) {
                lastStart = session.startTime();
            }
        }

        JSONObject json = new JSONObject();
        json.put("sessions", sessions.size());
        json.put("durationSeconds", duration);
        // 一场都没有时不写 0：「场均 0 分钟」读起来像「每场都秒退」，而真相是没有数据
        json.put("averageDurationSeconds", sessions.isEmpty() ? null : duration / sessions.size());
        json.put("firstStart", firstStart);
        json.put("lastStart", lastStart);

        JSONObject snapshot = null;
        Optional<StreamerSnapshot> latest = snapshots.latestBefore(platform, uid, System.currentTimeMillis());
        if (latest.isPresent()) {
            snapshot = new JSONObject();
            snapshot.put("at", latest.get().at());
            snapshot.put("metrics", new JSONObject(latest.get().metrics()));
        }
        // 从来没采到过时给 null，不给一个空对象：空对象在界面上会渲成「粉丝 0」
        json.put("snapshot", snapshot);
        // 说明照目录给全，不按本次采到了哪几项过滤：这一栏答的是「这个平台的指标都叫什么」，
        // 而不是「这次采到了什么」。按采样过滤的话，采漏一项与目录漏一项在界面上长得一样
        json.put("snapshotMetrics", describe(snapshotMetricsOf(platform)));

        return json;
    }

    /**
     * 场次分页，最近的在前
     */
    private JSONObject sessionPage(String platform, Long uid, List<LiveSession> sessions, int page, int size) {
        List<LiveSession> ordered = new ArrayList<>(sessions);
        // 归档按开播时间升序，而场次表要的是最近几场，倒过来
        Collections.reverse(ordered);

        int effectiveSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int pages = ordered.isEmpty() ? 0 : (ordered.size() + effectiveSize - 1) / effectiveSize;
        // 页码越界夹回来而不是回空表：空表与「这一页恰好没有场次」在界面上长得一样
        int effectivePage = Math.max(1, Math.min(page, Math.max(1, pages)));

        int from = Math.min((effectivePage - 1) * effectiveSize, ordered.size());
        int to = Math.min(from + effectiveSize, ordered.size());

        JSONArray items = new JSONArray();
        for (LiveSession session : ordered.subList(from, to)) {
            JSONObject item = LiveSessionJson.of(session);
            // 这一行点不点得开，问明细而不是照「配没配下播报告」推断：
            // 配了也可能画失败改发了文字版，那一场就是没有图。
            // 有明细就能现画，没有明细就点不开
            item.put("hasReport", details.has(platform, uid, session.startTime()));
            items.add(item);
        }

        JSONObject json = new JSONObject();
        json.put("total", ordered.size());
        json.put("page", effectivePage);
        json.put("size", effectiveSize);
        json.put("pages", pages);
        json.put("items", items);
        json.put("metrics", describe(metricsOf(sessions)));
        return json;
    }

    /**
     * 趋势，沿运营分析的周/月口径
     */
    private JSONObject trend(List<LiveSession> sessions, String period) {
        LiveSessionAnalytics.Period parsed = "month".equalsIgnoreCase(period)
                ? LiveSessionAnalytics.Period.MONTH
                : LiveSessionAnalytics.Period.WEEK;

        List<LiveMetricCatalog.Metric> metrics = metricsOf(sessions);
        Set<String> keys = new LinkedHashSet<>();
        metrics.forEach(metric -> keys.add(metric.key()));

        List<LiveSessionAnalytics.Bucket> buckets = analytics.aggregate(sessions, parsed, keys);
        int dropped = Math.max(0, buckets.size() - DEFAULT_TREND_LIMIT);
        if (dropped > 0) {
            buckets = buckets.subList(buckets.size() - DEFAULT_TREND_LIMIT, buckets.size());
        }

        JSONArray items = new JSONArray();
        for (LiveSessionAnalytics.Bucket bucket : buckets) {
            JSONObject item = new JSONObject();
            item.put("label", bucket.label());
            item.put("start", bucket.start());
            item.put("end", bucket.end());
            item.put("sessions", bucket.sessions());
            item.put("durationSeconds", bucket.durationSeconds());
            item.put("metrics", bucket.metrics());
            item.put("userTimes", bucket.userTimes());
            items.add(item);
        }

        JSONObject json = new JSONObject();
        json.put("period", parsed.name().toLowerCase(java.util.Locale.ROOT));
        json.put("buckets", items);
        // 截断了就得说，否则界面看起来像是「一共就这些数据」
        json.put("droppedPeriods", dropped);
        json.put("limit", Math.min(DEFAULT_TREND_LIMIT, MAX_TREND_LIMIT));
        json.put("metrics", describe(metrics));
        json.put("metricsKnown", !metrics.isEmpty());
        return json;
    }

    /**
     * 归档里有、推送配置里没有的那些主播
     * <p>
     * 报名字不报个数：一个「另有 3 位」的数字，跑完这一趟就再也找不回来是谁了。
     */
    private JSONArray notConfigured(List<StreamerDirectory.Entry> entries, Map<String, List<LiveSession>> byStreamer) {
        Set<String> known = new LinkedHashSet<>();
        entries.forEach(entry -> known.add(key(entry.platform(), entry.uid())));

        JSONArray items = new JSONArray();
        byStreamer.forEach((key, sessions) -> {
            if (known.contains(key) || sessions.isEmpty()) {
                return;
            }
            LiveSession latest = sessions.get(sessions.size() - 1);
            JSONObject item = new JSONObject();
            item.put("platform", latest.platform());
            item.put("uid", latest.uid());
            item.put("uname", latest.uname());
            item.put("sessions", sessions.size());
            items.add(item);
        });
        return items;
    }

    /**
     * 在册名单：推送配置文件里的，加上内存里有而文件里没有的那些
     * <p>
     * <b>为什么要并两处。</b>文件那一份管的是「已停用」——停用的主播在内存里根本不存在。
     * 内存那一份管的是「用的不是 JSON 数据源」：换一种数据源实现（或者配置文件被挪走）时，
     * 文件是空的，而机器人明明正监听着几位主播，主播页却一行都没有。
     * <p>
     * 内存里那些的状态照 {@code PushUser} 的 {@code enabled} 与 {@code targets} 现算。
     * 它们必然是启用的——数据源在加载时就把停用的剔掉了。
     */
    private List<StreamerDirectory.Entry> roster(Map<String, PushUser> loaded) {
        List<StreamerDirectory.Entry> entries = new ArrayList<>(directory.entries());
        Set<String> known = new LinkedHashSet<>();
        entries.forEach(entry -> known.add(key(entry.platform(), entry.uid())));

        loaded.forEach((key, user) -> {
            if (known.contains(key) || user.getPlatform() == null || user.getUid() == null) {
                return;
            }
            entries.add(new StreamerDirectory.Entry(user.getPlatform(), user.getUid(),
                    !Boolean.FALSE.equals(user.getEnabled()), pushing(user)));
        });

        return entries;
    }

    /**
     * 这位加载着的主播会不会真的推出去
     * <p>
     * 与配置文件那一侧同一条规矩：<b>只数通道数是不够的</b>，一个通道里的通知全关掉，
     * 与没有通道的效果完全相同。数据源加载时已经把停用的通道与消息剔掉了，
     * 所以这里剩下的都是启用的，数一数还剩不剩东西即可。
     */
    private boolean pushing(PushUser user) {
        return user.getTargets() != null && user.getTargets().stream()
                .anyMatch(target -> target.getMessages() != null && !target.getMessages().isEmpty());
    }

    /**
     * 内存里加载着的推送用户，按「平台 + uid」索引
     */
    private Map<String, PushUser> loadedUsers() {
        Map<String, PushUser> map = new LinkedHashMap<>();
        dataSource.getAllUsers().forEach(user -> map.put(key(user.getPlatform(), user.getUid()), user));
        return map;
    }

    /**
     * 按「平台 + uid」把归档分组，各组内仍按开播时间升序
     */
    private Map<String, List<LiveSession>> groupByStreamer(List<LiveSession> sessions) {
        Map<String, List<LiveSession>> map = new LinkedHashMap<>();
        for (LiveSession session : sessions) {
            map.computeIfAbsent(key(session.platform(), session.uid()), key -> new ArrayList<>()).add(session);
        }
        return map;
    }

    /**
     * 所选场次涉及平台的可累加指标，取法与运营分析一致
     */
    private List<LiveMetricCatalog.Metric> metricsOf(List<LiveSession> sessions) {
        Set<String> platforms = new LinkedHashSet<>();
        sessions.forEach(session -> platforms.add(session.platform()));

        List<LiveMetricCatalog.Metric> result = new ArrayList<>();
        for (String platform : platforms) {
            catalogs.orderedStream()
                    .filter(catalog -> catalog.platform().equals(platform))
                    .findFirst()
                    .map(LiveMetricCatalog::metrics)
                    .ifPresentOrElse(result::addAll,
                            () -> log.debug("未找到平台 {} 的指标说明, 该平台的场次只统计场次数与时长", platform));
        }
        return result;
    }

    /**
     * 这个平台的快照指标说明
     * <p>
     * 与 {@link #metricsOf(List)} 那一条的取法有意不同：那边一个平台只取第一份目录，
     * 这边把该平台<b>所有</b>实现的都收下。快照说明是纯说明、不参与任何计算，
     * 平台插件与报告插件各说自己那几项是常态；只取第一份的话，后装的那一份说的名字
     * 永远不会出现在屏幕上，而两处的代码看起来都对。
     * <p>
     * 键重不重复由目录那一侧的判据守着（见各实现的用例），这里不去重——去重要挑一份留下，
     * 而挑哪一份是个界面替插件做的决定。
     */
    private List<LiveMetricCatalog.Metric> snapshotMetricsOf(String platform) {
        return catalogs.orderedStream()
                .filter(catalog -> catalog.platform().equals(platform))
                .map(LiveMetricCatalog::snapshotMetrics)
                .flatMap(List::stream)
                .toList();
    }

    private JSONArray describe(List<LiveMetricCatalog.Metric> metrics) {
        JSONArray items = new JSONArray();
        for (LiveMetricCatalog.Metric metric : metrics) {
            JSONObject item = new JSONObject();
            item.put("key", metric.key());
            item.put("name", metric.name());
            item.put("unit", metric.unit());
            item.put("money", metric.money());
            items.add(item);
        }
        return items;
    }

    /**
     * 状态闭集
     */
    private JSONArray statuses() {
        JSONArray items = new JSONArray();
        for (Status status : Status.values()) {
            JSONObject item = new JSONObject();
            item.put("name", status.name());
            item.put("text", status.getText());
            items.add(item);
        }
        return items;
    }

    private LocalDate dateOf(long millis) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
    }

    private String key(String platform, Long uid) {
        return platform + "#" + uid;
    }

    /**
     * 主播在列表上的状态
     * <p>
     * 闭集，且由接口连同数据一起下发（见 {@code statuses()}）——界面自己抄一份的话，
     * 漏掉的那一项只有真出现过那种状态的人才看得见。
     */
    @Getter
    public enum Status {
        /**
         * 正在播
         */
        LIVE("在播"),

        /**
         * 在监听，此刻没播
         */
        OFFLINE("未开播"),

        /**
         * 没有任何一条启用的推送消息：照常采集、出场次与报告，但一条消息都不发
         */
        COLLECT_ONLY("只采集不推送"),

        /**
         * 停用：留在配置里，不采集也不推送
         */
        DISABLED("已停用");

        private final String text;

        Status(String text) {
            this.text = text;
        }
    }
}
