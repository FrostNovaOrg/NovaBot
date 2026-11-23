package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONReader;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.lang.BadLineNotice;
import org.frostnova.nova.core.lang.BadLineNotice.FileState;
import org.frostnova.nova.core.lang.JsonlFiles;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.RoomInfoSnapshot;
import org.frostnova.nova.core.model.SeriesPeak;
import org.frostnova.nova.core.util.DurableFiles;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 直播场次归档
 * <p>
 * 每场直播结束时追加一条 JSON 记录，供运营分析查询各时段的场次与数据。
 * <ul>
 *     <li>归档文件保存在本地</li>
 *     <li>追加写没有读改写周期，不会因为程序崩在中途而毁掉既有记录</li>
 *     <li>体量很小——每条约 500 字节，5 个主播每天 3 场跑一年也不过几 MB，
 *         而且人能直接看、直接导出</li>
 * </ul>
 */
@Slf4j
@Service
public class LiveSessionArchive {
    /**
     * 归档文件名，与直播数据同目录
     */
    private static final String FILE_NAME = "sessions.jsonl";

    private final NovaCoreProperties properties;

    /**
     * 写锁。多个直播间可能同时下播，追加写虽是原子的，但仍要避免两行交错
     */
    private final Object writeLock = new Object();

    /**
     * 各主播最近一场的昵称，连同读它时归档文件的样子
     * <p>
     * 首页与状态接口常轮询，都要问这一项；每问一次读一遍整份归档不值得。
     * 文件的路径、修改时刻与大小都没变时直接用上次的结果，变了才重读。
     */
    private volatile LatestNames latestNames;

    /**
     * 坏行的提示：主播页一开就读好几遍，同一份文件只报第一次，变了再报
     */
    private final BadLineNotice badLine = new BadLineNotice(log, "跳过归档中无法解析的一行");

    @Autowired
    public LiveSessionArchive(NovaCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 归档一场直播
     * <p>
     * 写盘失败只记日志，不影响下播通知与后续场次。
     * <p>
     * 同一主播、同一开播时刻已有一条时，<b>新的这条取代旧的</b>：断线重连续上的那一场
     * 中途下播时已归档过一次，真下播（或补档）这条才是整场。只在撞上时整份重写，
     * 平常仍是追加。重写时认不出的行照留，但按文本写回：行里解不开的字节会变成替换符。
     * @param session 场次记录
     */
    public void append(@NonNull LiveSession session) {
        String json = JSON.toJSONString(session);

        synchronized (writeLock) {
            try {
                if (replaceSameSession(session, json)) {
                    log.info("已归档 {} 的一场直播: {} 秒（续场, 取代中途下播那条）",
                            session.uname(), session.durationSeconds());
                    return;
                }
                // 上一行没写完时先补一个换行，把坏的半行隔开。
                // 否则下一场接在半行后面，两场一起解析失败，从运营统计里一起消失。
                JsonlFiles.separateTruncatedTail(path());
                Files.writeString(path(), json + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                log.info("已归档 {} 的一场直播: {} 秒", session.uname(), session.durationSeconds());
            } catch (IOException e) {
                log.error("归档直播场次失败, 该场将不会出现在运营统计中", e);
            }
        }
    }

    /**
     * 归档里已有同一场（平台、主播、开播时刻都相同）时，整份重写、用这条换掉它
     * <p>
     * 读不了或换不上时退回追加：多一条重复的场次，好过这一场整条没归档。
     * @return 是否换掉了；没有同一场、或没换成时不动文件
     */
    private boolean replaceSameSession(LiveSession session, String json) {
        List<String> lines;
        try (Stream<String> stream = JsonlFiles.lines(path())) {
            lines = stream.toList();
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException | RuntimeException e) {
            log.warn("读场次归档找同一场失败, 这一条照常追加: {}", e.toString());
            return false;
        }

        List<String> kept = new ArrayList<>(lines.size() + 1);
        boolean replaced = false;
        for (String line : lines) {
            if (sameSession(line, session)) {
                replaced = true;
            } else {
                kept.add(line);
            }
        }
        if (!replaced) {
            return false;
        }
        kept.add(json);
        try {
            DurableFiles.replace(path(), kept);
            return true;
        } catch (IOException e) {
            log.warn("重写场次归档失败, 这一条照常追加（同一场会有两条）: {}", e.toString());
            return false;
        }
    }

    /**
     * 这一行是不是同一场。解析不了的行一律不算，留在原处
     */
    private static boolean sameSession(String line, LiveSession session) {
        if (line == null || line.isBlank()) {
            return false;
        }
        try {
            JSONObject json = JSON.parseObject(line);
            return json != null
                    && session.startTime() == json.getLongValue("startTime")
                    && Objects.equals(session.uid(), json.getLong("uid"))
                    && Objects.equals(session.platform(), json.getString("platform"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 读取指定时间区间内的场次
     * <p>
     * 以**开播时刻**归属区间：一场跨零点的直播算在它开始的那一天，
     * 否则同一场会在两个统计周期里各出现半截。
     * @param from 起始时刻（毫秒，含）
     * @param to 结束时刻（毫秒，不含）
     * @return 按开播时间升序的场次
     */
    public List<LiveSession> find(long from, long to) {
        try {
            return read(from, to);
        } catch (NoSuchFileException e) {
            // 还没有任何一场被归档，空表即可
            return List.of();
        } catch (IOException | RuntimeException e) {
            // 读的途中出错（按行读把它包成 UncheckedIOException）同样按读不了处置：
            // 抛上去的话，主播页场次与趋势整页打不开
            log.error("读取直播场次归档失败", e);
            return List.of();
        }
    }

    /**
     * 逐条访问指定主播和时间窗重叠的场次。下播时刻恰在窗口起点也会访问，以便查到该时刻的事件。
     * 只读取调用方需要的名单，避免多场大房间的名单同时留在内存。
     */
    public void forEachOverlapping(String platform, long uid, long from, long to, boolean includeUserSets,
                                   int maxLineChars, Consumer<LiveSession> visit) throws IOException {
        forEachOverlappingRow(platform, uid, from, to, maxLineChars, row -> {
            LiveSession session = row.read(includeUserSets);
            if (session != null) visit.accept(session);
        });
    }

    /** 访问场次原行和时间范围，调用方可在需要名单等字段时再解析。 */
    public void forEachOverlappingRow(String platform, long uid, long from, long to, int maxLineChars,
                                      Consumer<ArchiveRow> visit) throws IOException {
        Path file = path();
        FileState state = BadLineNotice.stateOf(file);
        JSONReader.Context context = JSONFactory.createReadContext();
        try (Stream<String> lines = JsonlFiles.lines(file, maxLineChars)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                ArchiveRow row = overlappingRow(line, state, context, platform, uid, from, to);
                if (row != null) visit.accept(row);
            }
        } catch (NoSuchFileException ignored) {
            // 未产生场次归档。
        }
    }

    private ArchiveRow overlappingRow(String line, FileState state, JSONReader.Context context, String platform,
                                      long uid, long from, long to) {
        try (JSONReader reader = JSONReader.of(line, context)) {
            if (!reader.nextIfObjectStart()) return null;
            String rowPlatform = null;
            long rowUid = 0, start = 0, end = 0;
            while (!reader.nextIfObjectEnd()) {
                switch (reader.readFieldName()) {
                    case "platform" -> rowPlatform = reader.readString();
                    case "uid" -> rowUid = reader.readInt64Value();
                    case "startTime" -> start = reader.readInt64Value();
                    case "endTime" -> end = reader.readInt64Value();
                    default -> reader.skipValue();
                }
            }
            if (!reader.isEnd() || !platform.equals(rowPlatform) || rowUid != uid || start >= to || end < from)
                return null;
            return new ArchiveRow(line, start, end, state);
        } catch (RuntimeException error) {
            badLine.skipped(state, error);
            return null;
        }
    }

    public final class ArchiveRow {
        private final String line;
        private final long startTime;
        private final long endTime;
        private final FileState state;

        private ArchiveRow(String line, long startTime, long endTime, FileState state) {
            this.line = line;
            this.startTime = startTime;
            this.endTime = endTime;
            this.state = state;
        }

        public String line() { return line; }
        public long startTime() { return startTime; }
        public long endTime() { return endTime; }

        public LiveSession read(boolean includeUserSets) {
            try {
                return parse(JSON.parseObject(line), includeUserSets);
            } catch (RuntimeException error) {
                badLine.skipped(state, error);
                return null;
            }
        }
    }

    private static <T> Map<String, T> numberMap(JSONObject raw, Function<Number, T> convert) {
        Map<String, T> result = new HashMap<>();
        if (raw != null) raw.forEach((key, value) -> result.put(key, convert.apply((Number) value)));
        return result;
    }

    /**
     * 读取指定时间区间内的场次，读不了时向上抛
     */
    private List<LiveSession> read(long from, long to) throws IOException {
        List<LiveSession> result = new ArrayList<>();
        FileState state = BadLineNotice.stateOf(path());

        try (Stream<String> lines = JsonlFiles.lines(path())) {
            lines.forEach(line -> {
                LiveSession session = parse(line, state);
                if (session != null && session.startTime() >= from && session.startTime() < to) {
                    result.add(session);
                }
            });
        }

        result.sort((a, b) -> Long.compare(a.startTime(), b.startTime()));
        return result;
    }

    /**
     * 归档的总条数与时间范围，供界面显示「有多少可分析的数据」
     * @return 条数、最早与最晚的开播时刻；无数据时条数为 0
     */
    public Summary summary() {
        long count = 0;
        long earliest = Long.MAX_VALUE;
        long latest = Long.MIN_VALUE;
        FileState state = BadLineNotice.stateOf(path());

        try (Stream<String> lines = JsonlFiles.lines(path())) {
            for (String line : (Iterable<String>) lines::iterator) {
                LiveSession session = parse(line, state);
                if (session == null) {
                    continue;
                }
                count++;
                earliest = Math.min(earliest, session.startTime());
                latest = Math.max(latest, session.startTime());
            }
        } catch (NoSuchFileException e) {
            return new Summary(0, 0, 0);
        } catch (IOException | RuntimeException e) {
            log.error("统计直播场次归档失败", e);
            return new Summary(0, 0, 0);
        }

        return count == 0 ? new Summary(0, 0, 0) : new Summary(count, earliest, latest);
    }

    /**
     * 这位主播最近一场归档里记下的昵称
     * <p>
     * 「最近」按开播时刻算，与 {@link #find(long, long)} 的顺序一致。
     * 归档文件没变时不重读，只看一眼文件的修改时刻与大小。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 最近一场的昵称；没有归档场次，或那一场没记下昵称时为空
     */
    public Optional<String> latestUname(String platform, Long uid) {
        String uname = latestNames().get(new StreamerId(platform, uid));
        return uname == null || uname.isBlank() ? Optional.empty() : Optional.of(uname);
    }

    /** 有场次归档的主播，包含最近一场没有记下昵称的主播。 */
    public List<Long> streamerUids(String platform) {
        return latestNames().keySet().stream().filter(id -> platform.equals(id.platform()))
                .map(StreamerId::uid).toList();
    }

    private Map<StreamerId, String> latestNames() {
        Path path = path();
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            return Map.of();
        } catch (IOException e) {
            log.error("读取直播场次归档失败", e);
            return Map.of();
        }

        LatestNames cached = latestNames;
        if (cached != null && cached.matches(path, attributes)) {
            return cached.names();
        }

        // 读失败的这一次不记下来：文件的修改时刻与大小都没变，记下空结果的话，
        // 直到下一场归档写进来之前都答不上来
        List<LiveSession> sessions;
        try {
            sessions = read(0, Long.MAX_VALUE);
        } catch (NoSuchFileException e) {
            return Map.of();
        } catch (IOException | RuntimeException e) {
            log.error("读取直播场次归档失败", e);
            return Map.of();
        }

        Map<StreamerId, String> names = new HashMap<>();
        for (LiveSession session : sessions) {
            names.put(new StreamerId(session.platform(), session.uid()), session.uname());
        }
        latestNames = new LatestNames(path, attributes.lastModifiedTime(), attributes.size(), names);
        return names;
    }

    /**
     * 解析一行，坏行跳过而不是让整份归档不可用
     * @param state 读这一趟之前归档文件的样子，取不到时为 {@code null}
     */
    private LiveSession parse(String line, FileState state) {
        if (line == null || line.isBlank()) {
            return null;
        }

        try {
            return parse(JSON.parseObject(line), true);
        } catch (Exception e) {
            // 只记原因不记内容：一行里有观众 uid 名单
            badLine.skipped(state, e);
            return null;
        }
    }

    private LiveSession parse(JSONObject json, boolean includeUserSets) {
        Map<String, Double> metrics = numberMap(json.getJSONObject("metrics"), Number::doubleValue);
        Map<String, Integer> userCounts = numberMap(json.getJSONObject("userCounts"), Number::intValue);
        return new LiveSession(
                    json.getString("platform"),
                    json.getLong("uid"),
                    json.getString("uname"),
                    json.getLong("roomId"),
                    json.getLongValue("startTime"),
                    json.getLongValue("endTime"),
                    json.getLongValue("durationSeconds"),
                    metrics,
                    userCounts,
                    parseEndReason(json.getString("endReason")),
                    parseTitles(json.getJSONArray("titles")),
                    // 4.3.0 之前的记录没有这一项，缺失读成 0：那时候确实没在算缺口，
                    // 报成 0 是「不知道」而不是「我保证一秒没漏」——这一点在 analytics 里注明
                    json.getLongValue("maintenanceGapSeconds"),
                    // F5 之前的记录没有名单。缺失读成空表，同样是「不知道」而不是「没人来」——
                    // 分析侧必须用 LiveSession.hasUserSets() 把两者分开，
                    // 否则历史场次会显示成「零观众」，与上面那条缺口读成 0 是同一类误读
                    includeUserSets ? parseUserSets(json.getJSONObject("userSets")) : Map.of(),
                    // 单房断线缺口。与上面的程序停机缺口分开存、不相加：
                    // 停机期间所有房间都在断，两段重叠，相加就是重复计数
                    json.getLongValue("roomOutageSeconds"),
                    // 峰值。本版之前的记录没有这一项，缺失读成空表——同样是「不知道」而不是「最高 0」：
                    // 那时候序列没留下来，事后补不出来，界面上该显示「—」。
                    // 分析侧必须用 LiveSession.hasPeaks() 把两者分开
                    parsePeaks(json.getJSONObject("peaks")),
                    // 缺失读成 null 表示未记录；空表表示已记录且没有匿名事件。
                    parseAnonymousCounts(json.getJSONObject("anonymousCounts")));
    }

    /** 逐项保留有效的正整数次数，坏项不连累本场其余数据，缺失表示未知。 */
    private Map<String, Long> parseAnonymousCounts(JSONObject raw) {
        if (raw == null) {
            return null;
        }
        Map<String, Long> result = new HashMap<>();
        raw.forEach((metric, value) -> {
            if (value instanceof Number number) {
                try {
                    long count = new java.math.BigDecimal(number.toString()).longValueExact();
                    if (count > 0) {
                        result.put(metric, count);
                    }
                } catch (ArithmeticException | NumberFormatException e) {
                    log.debug("归档匿名次数 {} 里有认不出的次数, 已跳过", metric);
                }
            }
        });
        return result;
    }

    /**
     * 解析结束原因
     * <p>
     * 4.3.0 之前归档的记录没有这一项，认不出的一律当作正常结束——
     * <b>宁可把一场被切的算成正常，也不能把正常场次误标成事故</b>，
     * 后者会让人去追查一个根本不存在的问题。
     */
    private LiveEndReason parseEndReason(String name) {
        if (name == null || name.isBlank()) {
            return LiveEndReason.NORMAL;
        }

        try {
            return LiveEndReason.valueOf(name);
        } catch (IllegalArgumentException e) {
            log.debug("归档中出现无法识别的结束原因 {}, 按正常结束处理", name);
            return LiveEndReason.NORMAL;
        }
    }

    /**
     * 解析各计分表的参与者名单（F5），缺失或格式不符时为空表
     * <p>
     * ⚠️ <b>坏一个 uid 不能丢掉整场名单</b>：名单是一次性的，这一场丢了就再也补不回来，
     * 所以逐个跳过认不出的项、保住其余的，而不是整条记录判死。
     * 这与「跳过归档中无法解析的一行」是不同粒度的容错——那一层管的是整行 JSON 坏掉。
     */
    private Map<String, List<Long>> parseUserSets(JSONObject raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }

        Map<String, List<Long>> result = new HashMap<>();
        for (String metric : raw.keySet()) {
            JSONArray users = raw.getJSONArray(metric);
            if (users == null) {
                continue;
            }
            List<Long> uids = new ArrayList<>(users.size());
            for (Object user : users) {
                if (user instanceof Number number) {
                    uids.add(number.longValue());
                } else {
                    log.debug("归档名单 {} 里有认不出的用户项, 已跳过", metric);
                }
            }
            result.put(metric, uids);
        }
        return result;
    }

    /**
     * 解析各条序列的峰值，缺失或格式不符时为空表
     * <p>
     * ⚠️ <b>缺时刻的那一项整条跳过，不补一个 0 进去</b>：
     * 「峰值 137，出现在开播那一刻」是一句会被人当真的假话，
     * 而少一项至少还看得出这一条没有。
     */
    private Map<String, SeriesPeak> parsePeaks(JSONObject raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }

        Map<String, SeriesPeak> result = new HashMap<>();
        for (String metric : raw.keySet()) {
            JSONObject peak = raw.getJSONObject(metric);
            if (peak == null || !peak.containsKey("at") || !peak.containsKey("value")) {
                log.debug("跳过归档中形状不符的峰值项: {}", metric);
                continue;
            }
            result.put(metric, new SeriesPeak(peak.getLongValue("at"), peak.getDoubleValue("value")));
        }
        return result;
    }

    /**
     * 解析标题与分区轨迹，缺失或格式不符时为空表
     */
    private List<RoomInfoSnapshot> parseTitles(JSONArray raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        List<RoomInfoSnapshot> titles = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            JSONObject entry = raw.getJSONObject(i);
            if (entry == null) {
                continue;
            }
            titles.add(new RoomInfoSnapshot(entry.getLongValue("at"), entry.getString("title"), entry.getString("area")));
        }
        return titles;
    }

    /**
     * 归档文件路径，与直播数据同目录
     */
    private Path path() {
        Path liveData = Path.of(properties.getLive().getLiveDataPath());
        Path parent = liveData.getParent();
        return parent == null ? Path.of(FILE_NAME) : parent.resolve(FILE_NAME);
    }

    /**
     * 归档概况
     * @param count 已归档的场次数
     * @param earliestStart 最早一场的开播时刻，无数据时为 0
     * @param latestStart 最晚一场的开播时刻，无数据时为 0
     */
    public record Summary(long count, long earliestStart, long latestStart) {
    }

    private record StreamerId(String platform, Long uid) { }

    private record LatestNames(Path path, FileTime modified, long size, Map<StreamerId, String> names) {
        boolean matches(Path current, BasicFileAttributes attributes) {
            return path.equals(current)
                    && modified.equals(attributes.lastModifiedTime())
                    && size == attributes.size();
        }
    }
}
