package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.analytics.LiveDetail;
import com.starlwr.bot.core.analytics.LiveHighlightFinder;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.DanmuRecord;
import com.starlwr.bot.core.model.LiveGap;
import com.starlwr.bot.core.model.RoomInfoSnapshot;
import com.starlwr.bot.core.model.SeriesPeak;
import com.starlwr.bot.core.model.UserScore;
import jakarta.annotation.PostConstruct;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 每场直播的全量明细留档
 * <p>
 * <b>场次归档留的是「这一场发生过」，本留档留的是「这一场的原始数据」。</b>
 * 曲线、排行榜、词频表、弹幕原文此前全都只活在本场数据里，
 * 画完那张报告图就随下一次开播清零——不是算不出来，是当时就没留。
 * <p>
 * <b>一场一个目录</b>，而不是像场次归档那样一行一条：明细动辄几十上百 KB，
 * 塞进同一个追加式文件会让「读某一场」变成扫全表；分目录之后，
 * 想看哪一场就只读那一场，也方便整场删除与整场导出。
 * <p>
 * 目录里两份，分开的理由是<b>写入时机不同</b>：
 * <ul>
 *     <li>{@code danmu.jsonl} —— 弹幕原文，<b>直播过程中逐条追加</b>。
 *         追加写没有读改写周期，程序崩在中途也只丢最后一条，
 *         而攒在内存里等下播一起写，崩一次就是整场原文没了。</li>
 *     <li>{@code detail.json} —— 曲线、排行、词频、高能、标题、缺口，<b>下播时整份落盘</b>。
 *         这些在直播中一直在变，只有下播那一刻的值才是「这一场的」。</li>
 * </ul>
 * <p>
 * 同目录另有 {@code events.jsonl}：非弹幕事件的逐条流水，与弹幕原文同一把写锁、
 * 同一道封存闸、同一条数上限；失败只记日志。一行 JSON 以 {@code at}、{@code t} 打头，
 * 其余字段按调用方插入顺序附上。
 * <p>
 * <b>{@code detail.json} 的存在同时是「本场已封存」的标记</b>：它落盘之后，
 * 这一场的弹幕原文不再接收新的追加。没有这道闸的话，下播到次日开播之间的零星弹幕
 * 会继续追加进已归档的那一场——那些弹幕在统计里本就该被丢弃（开播清零时一并丢），
 * 而留档这一侧不丢，结果是<b>原文条数比场次里的弹幕数多出一截，且看不出多在哪</b>。
 * <p>
 * ⚠️ <b>保留期默认永久</b>（{@code starbot.core.live.detail-retention-days} 为 0）：
 * 明细是唯一一份原始数据，删掉之后<b>连报告图都重画不出来</b>。
 * 要设上限得先想清楚「过期的那一场在界面上怎么表示」——
 * 一行点开是 404 与「这一场从来没有过报告」在使用者眼里长得一样。
 */
@Slf4j
@Service
public class LiveDetailArchive {
    /**
     * 留档根目录名，与场次归档同目录下的一个子目录
     */
    private static final String DIRECTORY_NAME = "details";

    /**
     * 明细本体的文件名
     */
    private static final String DETAIL_FILE = "detail.json";

    /**
     * 弹幕原文的文件名
     */
    private static final String DANMU_FILE = "danmu.jsonl";
    private static final String EVENT_FILE = "events.jsonl";

    /**
     * 允许的平台名形状
     * <p>
     * 平台名会成为目录名的一部分，
     * 而查看明细那一支是从<b>请求路径</b>里取它的。白名单里容不下一个点号，
     * 路径穿越连拼都拼不出来。
     */
    private static final Pattern PLATFORM = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /**
     * 一场最多留多少条弹幕原文
     * <p>
     * 一场 12 小时、每秒 5 条是 216000 条，此为其上界再留余量。
     * 到顶之后不再收录并记一条日志，<b>而不是继续写</b>：
     * 无上界的追加写在异常场次（如没有正确下播、一直在收）下会把磁盘写满，
     * 而磁盘写满会连累的是整个程序，不只是这一份留档。
     */
    private static final int DANMU_LIMIT = 500_000;

    private final StarBotCoreProperties properties;

    /**
     * 写锁。多个直播间可能同时下播，各写各的目录，但建目录与过期清理这两步会撞
     */
    private final Object writeLock = new Object();

    /**
     * 各场已留档的弹幕条数，按文件路径计
     * <p>
     * 只服务于上限判定。<b>它不是弹幕条数的权威出处</b>——那一份是落盘的 JSONL 本身，
     * {@link #readDanmu} 数出来的才作数。两处若不一致，以文件为准：
     * 这份计数在进程重启后从文件重建，本就是文件的一个影子。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> danmuCounts =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public LiveDetailArchive(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 追加一条弹幕原文
     * <p>
     * <b>失败只记日志，绝不向上抛。</b>调用点在直播间消息线程上，
     * 留档写不进去不该连累事件分发本身。
     * <p>
     * 本场已经封存（{@code detail.json} 已落盘）时直接跳过，理由见类注释。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param startTime 本场开播时刻（毫秒），与场次归档里的 {@code startTime} 同值
     * @param record 一条弹幕原文
     */
    public void appendDanmu(@NonNull String platform, @NonNull Long uid, long startTime,
                            @NonNull DanmuRecord record) {
        Optional<Path> dir = directory(platform, uid, startTime);
        if (dir.isEmpty()) {
            return;
        }

        Path path = dir.get().resolve(DANMU_FILE);
        synchronized (writeLock) {
            if (Files.exists(dir.get().resolve(DETAIL_FILE))) {
                log.debug("{} 的这一场已封存, 不再收录弹幕原文", uid);
                return;
            }

            // 条数记在内存里，而不是每条都去数一遍文件行数：那是每条弹幕一次全文件扫描，
            // 一场几万条就是几亿行的读——上限本是防磁盘写满的，别让它自己成为性能事故。
            // 首次遇到这一场时数一遍（进程中途重启后接着数），此后只加一
            long count = danmuCounts.computeIfAbsent(path.toString(), key -> new AtomicLong(countLines(path))).get();
            if (count >= DANMU_LIMIT) {
                if (count == DANMU_LIMIT) {
                    log.warn("主播 {} 本场弹幕原文已达上限 {} 条, 后续不再留档", uid, DANMU_LIMIT);
                    danmuCounts.get(path.toString()).incrementAndGet();
                }
                return;
            }

            try {
                Files.createDirectories(dir.get());
                Files.writeString(path, toJson(record).toJSONString() + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                danmuCounts.get(path.toString()).incrementAndGet();
            } catch (IOException e) {
                log.debug("留档弹幕原文失败: {}", e.getMessage());
            }
        }
    }

    /** 追加一条事件流水。失败只记日志；本场已封存时直接跳过。 */
    public void appendEvent(@NonNull String platform, @NonNull Long uid, long startTime, long at,
                            @NonNull String type, @NonNull Map<String, Object> fields) {
        if (fields.containsKey("at") || fields.containsKey("t")) {
            throw new IllegalArgumentException("fields must not contain at or t");
        }
        Optional<Path> dir = directory(platform, uid, startTime);
        if (dir.isEmpty()) {
            return;
        }
        Path path = dir.get().resolve(EVENT_FILE);
        synchronized (writeLock) {
            if (Files.exists(dir.get().resolve(DETAIL_FILE))) {
                log.debug("{} 的这一场已封存, 不再收录事件流水", uid);
                return;
            }
            long count = danmuCounts.computeIfAbsent(path.toString(), key -> new AtomicLong(countLines(path))).get();
            if (count >= DANMU_LIMIT) {
                if (count == DANMU_LIMIT) {
                    log.warn("主播 {} 本场事件流水已达上限 {} 条, 后续不再留档", uid, DANMU_LIMIT);
                    danmuCounts.get(path.toString()).incrementAndGet();
                }
                return;
            }
            try {
                JSONObject json = new JSONObject();
                json.put("at", at);
                json.put("t", type);
                fields.forEach(json::put);
                Files.createDirectories(dir.get());
                Files.writeString(path, json.toJSONString() + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                danmuCounts.get(path.toString()).incrementAndGet();
            } catch (IOException e) {
                log.debug("留档事件流水失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 整份留下这一场的明细
     * <p>
     * <b>失败只记日志，绝不向上抛</b>，理由同场次归档：调用点在下播事件里。
     * <p>
     * 先写临时文件再原子改名：直接往目标文件上写的话，写到一半被读到的是半份 JSON，
     * 而<b>一份解析失败的明细与「这一场没有明细」在读取方眼里长得一样</b>。
     * @param detail 本场明细
     */
    public void store(@NonNull LiveDetail detail) {
        Optional<Path> dir = directory(detail.platform(), detail.uid(), detail.startTime());
        if (dir.isEmpty()) {
            log.warn("平台名 {} 不能作为目录名, 本场明细不留档", detail.platform());
            return;
        }

        synchronized (writeLock) {
            try {
                Files.createDirectories(dir.get());
                Path temp = Files.createTempFile(dir.get(), "detail-", ".part");
                Files.writeString(temp, toJson(detail).toJSONString(), StandardCharsets.UTF_8);
                Files.move(temp, dir.get().resolve(DETAIL_FILE),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                log.info("已留档 {} 的一场直播明细: {} 条序列, {} 张排行", detail.uname(),
                        detail.series() == null ? 0 : detail.series().size(),
                        detail.rankings() == null ? 0 : detail.rankings().size());
            } catch (IOException e) {
                log.error("留档直播明细失败, 该场的报告将无法重新绘制", e);
            }
        }
    }

    /**
     * 读一场的明细
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param startTime 本场开播时刻（毫秒）
     * @return 明细，没留下过或读不出来时为空
     */
    public Optional<LiveDetail> read(@NonNull String platform, @NonNull Long uid, long startTime) {
        Optional<Path> dir = directory(platform, uid, startTime);
        if (dir.isEmpty()) {
            return Optional.empty();
        }

        Path path = dir.get().resolve(DETAIL_FILE);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(parse(JSON.parseObject(Files.readString(path, StandardCharsets.UTF_8))));
        } catch (Exception e) {
            log.error("读取直播明细失败", e);
            return Optional.empty();
        }
    }

    /**
     * 读一场的弹幕原文
     * <p>
     * ⚠️ <b>整份读进内存</b>：一场十万条约十几 MB，调用点只有「重画报告」与人工导出两处，
     * 都不是热路径。真要按时间段取时再加一支带区间的读法，不要让这一支变成流式而调用方不知道。
     * <p>
     * 坏行跳过而不是整场判死：原文是一次性的，这一场丢了再也补不回来。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param startTime 本场开播时刻（毫秒）
     * @return 按落盘顺序排列的弹幕原文，没留下过时为空表
     */
    public List<DanmuRecord> readDanmu(@NonNull String platform, @NonNull Long uid, long startTime) {
        Optional<Path> dir = directory(platform, uid, startTime);
        if (dir.isEmpty()) {
            return List.of();
        }

        List<DanmuRecord> result = new ArrayList<>();
        try (Stream<String> lines = Files.lines(dir.get().resolve(DANMU_FILE), StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                DanmuRecord record = parseDanmu(line);
                if (record != null) {
                    result.add(record);
                }
            });
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            log.error("读取弹幕原文失败", e);
            return List.of();
        }
        return result;
    }

    /** 读一场的事件流水。文件不存在回空列表；坏行跳过。 */
    public List<Map<String, Object>> readEvents(@NonNull String platform, @NonNull Long uid, long startTime) {
        Optional<Path> dir = directory(platform, uid, startTime);
        if (dir.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        try (Stream<String> lines = Files.lines(dir.get().resolve(EVENT_FILE), StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                try {
                    JSONObject json = JSON.parseObject(line);
                    if (json != null) {
                        result.add(json);
                    }
                } catch (Exception e) {
                    log.debug("跳过事件流水里无法解析的一行: {}", e.getMessage());
                }
            });
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            log.error("读取事件流水失败", e);
            return List.of();
        }
        return result;
    }

    /**
     * 这一场有没有留下明细
     * <p>
     * 场次列表据此决定哪一行点得开：报告图可能已过期删掉，只要明细还在就重画得出来。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param startTime 本场开播时刻（毫秒）
     * @return 是否有明细
     */
    public boolean has(@NonNull String platform, @NonNull Long uid, long startTime) {
        return directory(platform, uid, startTime)
                .map(dir -> dir.resolve(DETAIL_FILE))
                .filter(Files::isRegularFile)
                .isPresent();
    }

    /**
     * 启动时清一次过期明细
     * <p>
     * 清理<b>只挂在启动上</b>：
     * 挂到落盘上的话，一场开播时刻已在保留窗口外的直播会把自己刚写的明细当场删掉。
     * 默认永久保留，因此这一趟默认什么都不做。
     */
    @PostConstruct
    public void purgeOnStartup() {
        purgeExpired();
    }

    /**
     * 删掉过期的明细
     * <p>
     * 保留天数为 0 时什么都不做——默认就是 0，<b>明细默认永久保留</b>。
     * 按<b>开播时刻</b>判过期而不是按文件修改时间：后者会被一次备份还原、一次目录整体拷贝
     * 全部刷新成「今天」，于是保留期形同虚设，而这件事不会有任何地方报错。
     */
    void purgeExpired() {
        int days = properties.getLive().getDetailRetentionDays();
        if (days <= 0) {
            return;
        }

        long deadline = System.currentTimeMillis() - days * 86_400_000L;
        Path root = root();
        if (!Files.isDirectory(root)) {
            return;
        }

        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                Long start = startTimeOf(dir.getFileName().toString());
                if (start == null || start >= deadline) {
                    continue;
                }
                deleteRecursively(dir);
                log.info("已删除过期的直播明细: {}", dir.getFileName());
            }
        } catch (IOException e) {
            log.error("清理过期直播明细失败", e);
        }
    }

    /**
     * 从目录名末段取开播时刻，认不出时为空
     * <p>
     * 认不出的目录一律不动：这个目录下可能有别人放的东西，
     * <b>清理程序删掉自己不认识的东西，比留着不该留的更糟</b>。
     */
    private Long startTimeOf(String name) {
        int index = name.lastIndexOf('-');
        if (index < 0) {
            return null;
        }
        try {
            return Long.parseLong(name.substring(index + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                Files.deleteIfExists(entry);
            }
        }
        Files.deleteIfExists(dir);
    }

    /**
     * 数一份文件有多少行，文件不在或读不出时为 0
     */
    private long countLines(Path path) {
        if (!Files.isRegularFile(path)) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines.count();
        } catch (IOException e) {
            log.debug("数弹幕原文行数失败: {}", e.getMessage());
            return 0;
        }
    }

    // ================ 序列化 ================
    // 手写而不是靠反射自动转：这份 JSON 是给「后续开发」读的接口面，
    // 字段名与嵌套形状必须是一次显式的决定。自动转的话，
    // 改一个字段名就悄悄改了留档格式，而已经落盘的那些一份都改不了。

    private JSONObject toJson(LiveDetail detail) {
        JSONObject json = new JSONObject();
        json.put("version", detail.version());
        json.put("platform", detail.platform());
        json.put("uid", detail.uid());
        json.put("uname", detail.uname());
        json.put("roomId", detail.roomId());
        json.put("startTime", detail.startTime());
        json.put("endTime", detail.endTime());
        json.put("durationSeconds", detail.durationSeconds());
        json.put("metrics", detail.metrics() == null ? new JSONObject() : new JSONObject(detail.metrics()));
        json.put("userCounts", detail.userCounts() == null ? new JSONObject() : new JSONObject(detail.userCounts()));

        JSONObject series = new JSONObject();
        if (detail.series() != null) {
            detail.series().forEach((metric, buckets) -> {
                JSONObject one = new JSONObject();
                buckets.forEach((at, value) -> one.put(String.valueOf(at), value));
                series.put(metric, one);
            });
        }
        json.put("series", series);

        JSONObject rankings = new JSONObject();
        if (detail.rankings() != null) {
            detail.rankings().forEach((metric, users) -> {
                JSONArray one = new JSONArray();
                for (UserScore user : users) {
                    JSONObject entry = new JSONObject();
                    entry.put("uid", user.userUid());
                    entry.put("uname", user.userName());
                    entry.put("face", user.userFace());
                    entry.put("score", user.score());
                    one.add(entry);
                }
                rankings.put(metric, one);
            });
        }
        json.put("rankings", rankings);

        json.put("words", detail.words() == null ? new JSONObject() : new JSONObject(detail.words()));

        JSONArray highlights = new JSONArray();
        if (detail.highlights() != null) {
            for (LiveHighlightFinder.Highlight highlight : detail.highlights()) {
                JSONObject entry = new JSONObject();
                entry.put("at", highlight.at());
                entry.put("value", highlight.value());
                entry.put("ratio", highlight.ratio());
                highlights.add(entry);
            }
        }
        json.put("highlights", highlights);

        JSONArray titles = new JSONArray();
        if (detail.titles() != null) {
            for (RoomInfoSnapshot title : detail.titles()) {
                JSONObject entry = new JSONObject();
                entry.put("at", title.at());
                entry.put("title", title.title());
                entry.put("area", title.area());
                titles.add(entry);
            }
        }
        json.put("titles", titles);

        JSONArray gaps = new JSONArray();
        if (detail.gaps() != null) {
            for (LiveGap gap : detail.gaps()) {
                JSONObject entry = new JSONObject();
                entry.put("from", gap.from());
                entry.put("to", gap.to());
                entry.put("reason", gap.reason() == null ? LiveGap.Reason.UNKNOWN.name() : gap.reason().name());
                gaps.add(entry);
            }
        }
        json.put("gaps", gaps);

        JSONObject peaks = new JSONObject();
        if (detail.peaks() != null) {
            detail.peaks().forEach((metric, peak) -> {
                JSONObject entry = new JSONObject();
                entry.put("at", peak.at());
                entry.put("value", peak.value());
                peaks.put(metric, entry);
            });
        }
        json.put("peaks", peaks);

        return json;
    }

    private JSONObject toJson(DanmuRecord record) {
        JSONObject json = new JSONObject();
        json.put("at", record.at());
        json.put("uid", record.uid());
        json.put("uname", record.uname());
        json.put("text", record.text());
        json.put("type", record.type() == null ? DanmuRecord.Type.DANMU.name() : record.type().name());
        return json;
    }

    private LiveDetail parse(JSONObject json) {
        if (json == null) {
            return null;
        }

        Map<String, Double> metrics = new LinkedHashMap<>();
        JSONObject rawMetrics = json.getJSONObject("metrics");
        if (rawMetrics != null) {
            rawMetrics.forEach((key, value) -> metrics.put(key, ((Number) value).doubleValue()));
        }

        Map<String, Integer> userCounts = new LinkedHashMap<>();
        JSONObject rawCounts = json.getJSONObject("userCounts");
        if (rawCounts != null) {
            rawCounts.forEach((key, value) -> userCounts.put(key, ((Number) value).intValue()));
        }

        Map<String, Map<Long, Double>> series = new LinkedHashMap<>();
        JSONObject rawSeries = json.getJSONObject("series");
        if (rawSeries != null) {
            for (String metric : rawSeries.keySet()) {
                JSONObject buckets = rawSeries.getJSONObject(metric);
                if (buckets == null) {
                    continue;
                }
                // TreeMap：曲线的横轴是时间，读出来就该是按时间递增的，与 getLiveSeries 同形
                Map<Long, Double> one = new TreeMap<>();
                for (String at : buckets.keySet()) {
                    try {
                        one.put(Long.parseLong(at), buckets.getDoubleValue(at));
                    } catch (NumberFormatException e) {
                        log.debug("跳过明细序列中的非法时间格: {}", at);
                    }
                }
                series.put(metric, one);
            }
        }

        Map<String, List<UserScore>> rankings = new LinkedHashMap<>();
        JSONObject rawRankings = json.getJSONObject("rankings");
        if (rawRankings != null) {
            for (String metric : rawRankings.keySet()) {
                JSONArray users = rawRankings.getJSONArray(metric);
                if (users == null) {
                    continue;
                }
                List<UserScore> one = new ArrayList<>(users.size());
                for (int i = 0; i < users.size(); i++) {
                    JSONObject entry = users.getJSONObject(i);
                    if (entry == null) {
                        continue;
                    }
                    one.add(new UserScore(entry.getLong("uid"), entry.getString("uname"),
                            entry.getString("face"), entry.getDoubleValue("score")));
                }
                rankings.put(metric, one);
            }
        }

        Map<String, Integer> words = new LinkedHashMap<>();
        JSONObject rawWords = json.getJSONObject("words");
        if (rawWords != null) {
            rawWords.forEach((key, value) -> words.put(key, ((Number) value).intValue()));
        }

        List<LiveHighlightFinder.Highlight> highlights = new ArrayList<>();
        JSONArray rawHighlights = json.getJSONArray("highlights");
        if (rawHighlights != null) {
            for (int i = 0; i < rawHighlights.size(); i++) {
                JSONObject entry = rawHighlights.getJSONObject(i);
                if (entry != null) {
                    highlights.add(new LiveHighlightFinder.Highlight(
                            entry.getLongValue("at"), entry.getDoubleValue("value"), entry.getDoubleValue("ratio")));
                }
            }
        }

        List<RoomInfoSnapshot> titles = new ArrayList<>();
        JSONArray rawTitles = json.getJSONArray("titles");
        if (rawTitles != null) {
            for (int i = 0; i < rawTitles.size(); i++) {
                JSONObject entry = rawTitles.getJSONObject(i);
                if (entry != null) {
                    titles.add(new RoomInfoSnapshot(entry.getLongValue("at"),
                            entry.getString("title"), entry.getString("area")));
                }
            }
        }

        List<LiveGap> gaps = new ArrayList<>();
        JSONArray rawGaps = json.getJSONArray("gaps");
        if (rawGaps != null) {
            for (int i = 0; i < rawGaps.size(); i++) {
                JSONObject entry = rawGaps.getJSONObject(i);
                if (entry != null) {
                    gaps.add(new LiveGap(entry.getLongValue("from"), entry.getLongValue("to"),
                            parseReason(entry.getString("reason"))));
                }
            }
        }

        Map<String, SeriesPeak> peaks = new LinkedHashMap<>();
        JSONObject rawPeaks = json.getJSONObject("peaks");
        if (rawPeaks != null) {
            for (String metric : rawPeaks.keySet()) {
                JSONObject entry = rawPeaks.getJSONObject(metric);
                if (entry != null) {
                    peaks.put(metric, new SeriesPeak(entry.getLongValue("at"), entry.getDoubleValue("value")));
                }
            }
        }

        return new LiveDetail(
                json.getIntValue("version"),
                json.getString("platform"),
                json.getLong("uid"),
                json.getString("uname"),
                json.getLong("roomId"),
                json.getLongValue("startTime"),
                json.getLongValue("endTime"),
                json.getLongValue("durationSeconds"),
                metrics, userCounts, series, rankings, words, highlights, titles, gaps, peaks);
    }

    /**
     * 解析缺口成因，认不出的一律当作「原因未定」
     * <p>
     * 与场次归档把认不出的结束原因当作「正常」相反，这里退到 {@code UNKNOWN}：
     * 那边编一个「正常」出来只会少报一次事故，这边编一个「维护」出来
     * 是<b>替这段空白编了一个成因</b>，而「不知道为什么没采到」才是该有人去查的那件。
     */
    private LiveGap.Reason parseReason(String name) {
        if (name == null || name.isBlank()) {
            return LiveGap.Reason.UNKNOWN;
        }
        try {
            return LiveGap.Reason.valueOf(name);
        } catch (IllegalArgumentException e) {
            return LiveGap.Reason.UNKNOWN;
        }
    }

    private DanmuRecord parseDanmu(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JSONObject json = JSON.parseObject(line);
            DanmuRecord.Type type;
            try {
                type = DanmuRecord.Type.valueOf(json.getString("type"));
            } catch (Exception e) {
                type = DanmuRecord.Type.DANMU;
            }
            return new DanmuRecord(json.getLongValue("at"), json.getLong("uid"),
                    json.getString("uname"), json.getString("text"), type);
        } catch (Exception e) {
            log.debug("跳过弹幕原文里无法解析的一行: {}", e.getMessage());
            return null;
        }
    }

    // ================ 路径 ================

    /**
     * 一场明细的目录，平台名不合形状时为空
     */
    private Optional<Path> directory(String platform, Long uid, long startTime) {
        if (platform == null || uid == null || !PLATFORM.matcher(platform).matches()) {
            return Optional.empty();
        }
        return Optional.of(root().resolve(platform + "-" + uid + "-" + startTime));
    }

    /**
     * 留档根目录，与场次归档同目录下的 {@code details/}
     */
    private Path root() {
        Path liveData = Path.of(properties.getLive().getLiveDataPath());
        Path parent = liveData.getParent();
        return parent == null ? Path.of(DIRECTORY_NAME) : parent.resolve(DIRECTORY_NAME);
    }

    /**
     * 已留档的场次，按开播时刻升序
     * <p>
     * 供人工导出与「这台机器上还留着哪几场」用。<b>目录名是唯一的出处</b>——
     * 逐个打开 {@code detail.json} 去读里面的 {@code startTime} 也行，
     * 但那样一次列举就要读几百个文件，而两处若不一致，问题恰恰出在写目录名那一步。
     * @return 平台、uid 与开播时刻
     */
    public List<Archived> list() {
        Path root = root();
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        List<Archived> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                if (!Files.isRegularFile(dir.resolve(DETAIL_FILE))) {
                    continue;
                }
                String name = dir.getFileName().toString();
                int last = name.lastIndexOf('-');
                int middle = last <= 0 ? -1 : name.lastIndexOf('-', last - 1);
                if (middle <= 0) {
                    continue;
                }
                try {
                    result.add(new Archived(name.substring(0, middle),
                            Long.parseLong(name.substring(middle + 1, last)),
                            Long.parseLong(name.substring(last + 1))));
                } catch (NumberFormatException e) {
                    log.debug("跳过认不出的明细目录: {}", name);
                }
            }
        } catch (IOException e) {
            log.error("列举直播明细失败", e);
            return List.of();
        }

        result.sort(Comparator.comparingLong(Archived::startTime));
        return result;
    }

    /**
     * 一场已留档的明细
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param startTime 开播时刻（毫秒）
     */
    public record Archived(String platform, long uid, long startTime) {
    }
}
