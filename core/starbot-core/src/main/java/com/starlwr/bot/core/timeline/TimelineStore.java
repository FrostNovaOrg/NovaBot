package com.starlwr.bot.core.timeline;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import jakarta.annotation.PostConstruct;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Stream;

/**
 * 事件时间线存储
 * <p>
 * 按日一个 {@code timeline/YYYY-MM-DD.jsonl}，与 {@code sessions.jsonl} 同目录、同形态
 * （每行一条 JSON、只追加）。选这个形态而不是数据库，理由与场次归档同源：
 * 不依赖外部服务、追加写不会因崩在中途而毁掉既有记录、人能直接看直接导出。
 * <p>
 * 与场次归档不同的是<b>它会被删</b>：时间线是排障线索不是业务数据，
 * 留 {@code novabot.core.timeline.retention-days} 天，过期的整日文件删掉。
 * 按日分文件正是为了这一点——删一天就是删一个文件，不必读改写。
 * <p>
 * <b>进程内另有一份索引</b>（各日条数 ＋ 最近若干条），启动时扫一遍现有文件建起来。
 * 「今天发生了几件事」这种问题每次刷新界面都要问一次，为它去读一遍磁盘不值当。
 * 索引只在本进程写入与清理时更新：<b>它答的是「本程序知道的」，不是「目录里现在有什么」</b>——
 * 有人在外面手工改了文件，索引不会跟着变，而逐条查询走的是磁盘，两者会对不上。
 * 真要对上就得每次查询都重扫目录，那正是索引要省掉的开销。
 */
@Slf4j
@Service
public class TimelineStore implements TimelineWriter {
    /**
     * 时间线目录名，与场次归档同目录
     */
    private static final String DIR_NAME = "timeline";

    /**
     * 日文件后缀
     */
    private static final String SUFFIX = ".jsonl";

    /**
     * 进程内保留的最近条数
     * <p>
     * 供「今天发生了什么」这类概览用。容量固定以免长期运行后无界增长——
     * 要看更多就去查磁盘，那一路本来就是全的。
     */
    static final int RECENT_CAPACITY = 200;

    /**
     * 单次查询返回的默认条数与上限
     */
    private static final int DEFAULT_LIMIT = 200;

    private static final int MAX_LIMIT = 2000;

    private final StarBotCoreProperties properties;

    /**
     * 写锁。多处现场可能同时记事件，追加写虽是原子的，但仍要避免两行交错
     */
    private final Object writeLock = new Object();

    /**
     * 各日条数。用有序表，「最近哪几天有记录」直接倒着取
     */
    private final NavigableMap<LocalDate, Integer> countsByDay = new ConcurrentSkipListMap<>();

    /**
     * 最近若干条，按时间升序
     */
    private final Deque<TimelineEvent> recent = new ArrayDeque<>();

    @Autowired
    public TimelineStore(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 启动时建索引并清一次过期
     * <p>
     * 清理放在这里而不是只靠每日定时：一台每天重启的机器永远等不到那个定时点，
     * 于是「留 14 天」在它身上就是一句空话。
     */
    @PostConstruct
    public void load() {
        rebuildIndex();
        purgeExpired();
    }

    @Override
    public void record(@NonNull TimelineEvent event) {
        LocalDate day = dayOf(event.at());
        String line = toJson(event).toJSONString() + System.lineSeparator();

        synchronized (writeLock) {
            try {
                Path file = dayFile(day);
                Files.createDirectories(file.getParent());
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                // 记不下来不能连累正事：调用点都在推送与告警的主路上
                log.error("写入事件时间线失败, 该事件将不会出现在日志页中", e);
                return;
            }

            countsByDay.merge(day, 1, Integer::sum);
            remember(event);
        }
    }

    /**
     * 有记录的日期与各自条数，最近的在前
     * @return 日期与条数
     */
    public List<Day> days() {
        List<Day> result = new ArrayList<>();
        countsByDay.descendingMap().forEach((day, count) -> result.add(new Day(day, count)));
        return result;
    }

    /**
     * 最近若干条，最近的在前
     * <p>
     * 供概览用；要筛要搜请走 {@link #query(Filter)}。
     * @return 最近的事件
     */
    public List<TimelineEvent> recent() {
        synchronized (writeLock) {
            List<TimelineEvent> result = new ArrayList<>(recent);
            Collections.reverse(result);
            return result;
        }
    }

    /**
     * 保留天数，{@code <= 0} 表示不自动清理
     * @return 保留天数
     */
    public int retentionDays() {
        return properties.getTimeline().getRetentionDays();
    }

    /**
     * 按条件查询，最近的在前
     * <p>
     * 逐条读磁盘而不是读索引：索引只有条数与最近若干条，答不了「上周二那天警告都有哪些」。
     * 射程被保留天数框住，最多也就那么几个文件。
     * <p>
     * <b>游标是必填参数而非可选</b>：不翻页时显式传 {@code null}。留一个不带游标的重载，
     * 调用方就可以在完全不知道有翻页这回事的情况下写完一整条取数路径——
     * 而那条路径在记录超过一页时会安静地只显示最近的那些，看起来与「一共就这么多」一样。
     * @param filter 筛选条件
     * @param cursor 从哪一条往更旧的接着翻，{@code null} 表示从最近的开始
     * @return 查询结果
     */
    public Result query(@NonNull Filter filter, Cursor cursor) {
        int limit = filter.limit() <= 0 ? DEFAULT_LIMIT : Math.min(filter.limit(), MAX_LIMIT);

        List<TimelineEvent> hits = new ArrayList<>();
        Set<String> streamers = new LinkedHashSet<>();
        Set<String> channels = new LinkedHashSet<>();
        Cursor last = null;
        int matched = 0;
        int passed = 0;

        for (LocalDate day : searchDays(filter.date())) {
            List<TimelineEvent> events = readDay(day);
            // 文件里是按发生顺序追加的，倒着走就是从近到远
            for (int i = events.size() - 1; i >= 0; i--) {
                TimelineEvent event = events.get(i);

                // 筛选框里的可选项取自这几天<b>全部</b>事件，不受当前筛选影响：
                // 跟着筛一起缩水的话，选了某位主播之后通道那一栏就只剩他推过的那几个，
                // 使用者再也换不回去，只能清掉整组筛选重来
                if (event.streamer() != null) {
                    streamers.add(event.streamer());
                }
                if (event.channel() != null) {
                    channels.add(event.channel());
                }

                if (!filter.matches(event)) {
                    continue;
                }
                // 命中总数答的是「一共有多少」，因此不看游标——翻到第二页时它要是跟着变小，
                // 页脚那句「共 N 条」就会在翻页过程中自己往下掉
                matched++;

                Cursor here = new Cursor(day, i);
                if (cursor != null && !here.olderThan(cursor)) {
                    continue;
                }
                passed++;
                if (hits.size() < limit) {
                    hits.add(event);
                    last = here;
                }
            }
        }

        // 还有更旧的命中没给出去时才给游标。给早了，界面上那个「看更早」永远按得下去
        //
        // 两栏可选项按字符序给，不按「谁最近出现过」：后者会在每一条新事件进来时
        // 把下拉框重排一遍，而使用者正对着它找上一次选过的那一项
        return new Result(hits, matched, limit, passed > hits.size() ? last : null,
                streamers.stream().sorted().toList(), channels.stream().sorted().toList());
    }

    /**
     * 某一天各类事件各有几条
     * <p>
     * 首页要写「今日推送 N 条、失败 M 条」。走这里而不是读进程内那份计数器：
     * 计数器是从进程启动起算的，重启一次「今天」就归零，而使用者问的今天是日历上的今天。
     * <p>
     * 一次读盘算出全部类型，不是一类查一遍：查两次就读两遍同一个文件，
     * 而且两遍之间还可能被写进新的一行，于是「成功数」与「失败数」取自两个不同的瞬间。
     * @param day 日期
     * @return 各类型的条数，没有发生过的类型不在表里
     */
    public Map<TimelineEventType, Integer> countsOn(@NonNull LocalDate day) {
        Map<TimelineEventType, Integer> counts = new EnumMap<>(TimelineEventType.class);
        for (TimelineEvent event : readDay(day)) {
            counts.merge(event.type(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * 删掉超出保留天数的整日文件
     * <p>
     * 保留天数含当天：留 14 天即今天与此前 13 天，更早的删。
     * <p>
     * 定在凌晨而不是整点轮询：这件事一天做一次就够，而放在 00:00 整会与别的日切任务撞在一起。
     */
    @Scheduled(cron = "0 5 0 * * *")
    public void purgeExpired() {
        int keep = retentionDays();
        if (keep <= 0) {
            // 配 0 或负数即「不要自动删」。这是一个说得出口的选择，不当成配错处理
            return;
        }

        LocalDate cutoff = LocalDate.now().minusDays(keep - 1L);
        for (LocalDate day : new ArrayList<>(countsByDay.headMap(cutoff, false).keySet())) {
            delete(day);
        }

        // 索引里没有、目录里却有的（上一次运行时写的、或本程序没记上的）一并清，
        // 否则「留 14 天」只对本进程写过的那些天成立
        for (LocalDate day : listDaysOnDisk()) {
            if (day.isBefore(cutoff)) {
                delete(day);
            }
        }
    }

    private void delete(LocalDate day) {
        try {
            Files.deleteIfExists(dayFile(day));
            countsByDay.remove(day);
            log.info("已删除超出保留期的事件时间线: {}", day);
        } catch (IOException e) {
            log.warn("删除事件时间线 {} 失败: {}", day, e.getMessage());
        }
    }

    /**
     * 要扫哪几天：点名了就只扫那一天，没点名就扫索引里有记录的全部，从近到远
     */
    private List<LocalDate> searchDays(LocalDate date) {
        if (date != null) {
            // 点名的日期直接读文件，不问索引：索引答的是「本程序知道的」，
            // 而点名查一天的人要的是那个文件里到底有什么
            return List.of(date);
        }
        return new ArrayList<>(countsByDay.descendingKeySet());
    }

    /**
     * 读一整天，按发生顺序
     */
    private List<TimelineEvent> readDay(LocalDate day) {
        List<TimelineEvent> result = new ArrayList<>();

        try (Stream<String> lines = Files.lines(dayFile(day), StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                TimelineEvent event = parse(line);
                if (event != null) {
                    result.add(event);
                }
            });
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            log.error("读取事件时间线 {} 失败", day, e);
            return List.of();
        }

        return result;
    }

    /**
     * 扫一遍现有文件重建索引
     * <p>
     * 条数与最近若干条在同一趟里取：分两趟就是把每个文件读两遍，
     * 而两趟之间文件还可能变——两个数会来自两个不同的瞬间。
     */
    private void rebuildIndex() {
        countsByDay.clear();
        synchronized (writeLock) {
            recent.clear();
        }

        List<LocalDate> days = new ArrayList<>(listDaysOnDisk());
        days.sort(Comparator.naturalOrder());

        for (LocalDate day : days) {
            int count = 0;
            try (Stream<String> lines = Files.lines(dayFile(day), StandardCharsets.UTF_8)) {
                for (String line : (Iterable<String>) lines::iterator) {
                    TimelineEvent event = parse(line);
                    if (event == null) {
                        continue;
                    }
                    count++;
                    synchronized (writeLock) {
                        remember(event);
                    }
                }
            } catch (IOException e) {
                log.error("读取事件时间线 {} 失败, 该日条数按 0 计", day, e);
                continue;
            }

            if (count > 0) {
                countsByDay.put(day, count);
            }
        }

        if (!countsByDay.isEmpty()) {
            log.info("事件时间线索引已建立: {} 天, 共 {} 条", countsByDay.size(),
                    countsByDay.values().stream().mapToInt(Integer::intValue).sum());
        }
    }

    /**
     * 记进最近队列，超容量丢最旧的。调用方须持有 {@link #writeLock}
     */
    private void remember(TimelineEvent event) {
        if (recent.size() >= RECENT_CAPACITY) {
            recent.removeFirst();
        }
        recent.addLast(event);
    }

    /**
     * 目录里现有哪几天，认不出名字的文件跳过
     */
    private List<LocalDate> listDaysOnDisk() {
        Path dir = directory();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }

        List<LocalDate> days = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith(SUFFIX)) {
                    continue;
                }
                try {
                    days.add(LocalDate.parse(name.substring(0, name.length() - SUFFIX.length())));
                } catch (DateTimeParseException e) {
                    log.debug("事件时间线目录里有认不出日期的文件, 已跳过: {}", name);
                }
            }
        } catch (IOException e) {
            log.error("列出事件时间线目录失败", e);
            return List.of();
        }
        return days;
    }

    /**
     * 把一条事件转成落盘与接口共用的 JSON
     * <p>
     * <b>字段名与取值只有这一份</b>：两处各写一遍迟早会分叉，而分叉的表现是
     * 界面显示的字段名与导出文件里的对不上，导出的那份就没人敢用了。
     * <p>
     * 手写而不用对象序列化：这是要长期落在磁盘上的格式，
     * 字段名与枚举写法不该随序列化库的默认设置变。
     * <p>
     * ⚠️ 唯一的差别在<b>空值怎么写</b>，且不由这里决定：落盘走 fastjson2 的
     * {@code toJSONString()}，空值键<b>整项不写</b>；走 HTTP 时由框架的消息转换器渲染，
     * 空值键写成 {@code null}。两种形态读回来都是「这一项没有值」
     * （{@link #parse} 对「键缺席」与「键为 null」一视同仁），消费方不必分辨。
     * @param event 事件
     * @return JSON 对象
     */
    public static JSONObject toJson(TimelineEvent event) {
        JSONObject json = new JSONObject();
        json.put("at", event.at());
        json.put("type", event.type().name());
        json.put("typeText", event.type().getDescription());
        // 大类是由类型算出来的，落盘的这一份只是顺手带上，读回来时一律重算
        // （见 {@link #parse}）：改了归属之后，旧记录跟着新归属走，而不是各归各的
        json.put("category", event.type().getCategory().name());
        json.put("categoryText", event.type().getCategory().getDescription());
        json.put("level", event.level().name().toLowerCase(Locale.ROOT));
        json.put("streamer", event.streamer());
        json.put("channel", event.channel());
        json.put("text", event.text());

        JSONObject detail = new JSONObject();
        event.detail().forEach(detail::put);
        json.put("detail", detail);

        return json;
    }

    /**
     * 解析一行，坏行与认不出类型的行跳过而不是让整份时间线不可用
     * <p>
     * ⚠️ 认不出的类型<b>会让那一行整条消失</b>。这只会发生在降级运行时——
     * 新版本写下的类型，旧版本不认得。代价记在这里：降级之后，
     * 新版本期间记下的那几类事件在日志页上看不见，文件里仍在。
     */
    private TimelineEvent parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        try {
            JSONObject json = JSON.parseObject(line);
            TimelineEventType type = TimelineEventType.parse(json.getString("type"));
            if (type == null) {
                log.debug("跳过时间线中认不出类型的一行: {}", json.getString("type"));
                return null;
            }

            Map<String, String> detail = new LinkedHashMap<>();
            JSONObject rawDetail = json.getJSONObject("detail");
            if (rawDetail != null) {
                rawDetail.forEach((key, value) -> {
                    if (value != null) {
                        detail.put(key, String.valueOf(value));
                    }
                });
            }

            return new TimelineEvent(
                    json.getLongValue("at"),
                    type,
                    TimelineEvent.Level.parse(json.getString("level")),
                    json.getString("streamer"),
                    json.getString("channel"),
                    json.getString("text"),
                    detail);
        } catch (Exception e) {
            log.debug("跳过时间线中无法解析的一行: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 某一天的文件
     */
    private Path dayFile(LocalDate day) {
        return directory().resolve(day + SUFFIX);
    }

    /**
     * 时间线目录，与场次归档同目录下的 {@code timeline/}
     */
    private Path directory() {
        Path liveData = Path.of(properties.getLive().getLiveDataPath());
        Path parent = liveData.getParent();
        return parent == null ? Path.of(DIR_NAME) : parent.resolve(DIR_NAME);
    }

    /**
     * 时刻落在哪一天
     * <p>
     * 按<b>本地时区</b>分日，与场次分析里的周月归属同一口径：使用者问的「昨天」
     * 是他所在时区的昨天，不是 UTC 的。
     */
    private static LocalDate dayOf(long at) {
        return Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 一天的条数
     *
     * @param date 日期
     * @param count 条数
     */
    public record Day(LocalDate date, int count) {
    }

    /**
     * 翻页位置：某一天里的第几条
     * <p>
     * 不用时刻当游标：同一毫秒里可以记下好几条事件，按时刻翻会把其中几条整批跳过或整批重发。
     * 用「哪一天的第几条」是因为<b>日文件只追加不改写</b>——已经写下的那几行位置不会再变，
     * 新事件一律加在末尾。这条性质正是这个游标能用的全部理由，
     * 哪天时间线改成可编辑的存储，这个游标就得跟着换。
     * <p>
     * 下标数的是<b>解析得出的事件</b>，不是文件行数：坏行本来就不在结果里，
     * 按物理行号数的话，游标会指到一条根本读不出来的记录上。
     *
     * @param day 哪一天
     * @param index 那一天里的第几条，从 0 起
     */
    public record Cursor(LocalDate day, int index) {
        /**
         * 分隔符。日期里本来就有连字符，冒号不与它撞
         */
        private static final String SEPARATOR = ":";

        /**
         * 本位置是否比另一个更旧
         * <p>
         * 「更旧」＝日子更早，或同一天里下标更小。翻页取的是严格更旧的那些，
         * 取到「不比它新」就会把上一页最后那条再发一遍
         * @param other 另一个位置
         * @return 更旧返回 true
         */
        boolean olderThan(Cursor other) {
            return day.isBefore(other.day()) || (day.equals(other.day()) && index < other.index());
        }

        /**
         * 按文本解析，认不出时返回 {@code null}
         * <p>
         * 不抛异常，与 {@link TimelineEventType#parse} 同法：调用方要能分辨
         * 「没给游标」与「给了但认不出」，而后者必须当场说出来——当成没给去从头翻的话，
         * 「看更早」会一直翻回第一页，而屏幕上看起来只是「没有更早的了」。
         * @param text 文本形态
         * @return 位置，认不出时为 {@code null}
         */
        public static Cursor parse(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }

            int cut = text.lastIndexOf(SEPARATOR);
            if (cut < 0) {
                return null;
            }

            try {
                int index = Integer.parseInt(text.substring(cut + 1).trim());
                return index < 0 ? null : new Cursor(LocalDate.parse(text.substring(0, cut).trim()), index);
            } catch (DateTimeParseException | NumberFormatException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return day + SEPARATOR + index;
        }
    }

    /**
     * 查询条件
     * <p>
     * 各项为空即不按该项筛。
     *
     * @param date 只看某一天，为空则看全部保留期内的记录
     * @param problemsOnly 只看有问题的（级别非 {@code info}）
     * @param category 只看某一大类的事件
     * @param type 只看某一类事件
     * @param streamer 只看某位主播，全等匹配
     * @param channel 只看某个通道，全等匹配
     * @param keyword 在正文与补充键值里搜关键词，不区分大小写
     * @param limit 最多返回多少条
     */
    public record Filter(LocalDate date, boolean problemsOnly, TimelineCategory category,
                         TimelineEventType type, String streamer, String channel,
                         String keyword, int limit) {
        /**
         * 该事件是否命中本条件
         * <p>
         * 大类与类型两项<b>同时生效</b>，不是后者盖掉前者：贴过来的地址可以两项都带着，
         * 只认其中一项的话，对方打开的是一张筛得不一样的页，而它与筛对了的长得一模一样。
         * @param event 事件
         * @return 命中返回 true
         */
        public boolean matches(TimelineEvent event) {
            if (problemsOnly && event.level() == TimelineEvent.Level.INFO) {
                return false;
            }
            if (category != null && event.type().getCategory() != category) {
                return false;
            }
            if (type != null && event.type() != type) {
                return false;
            }
            if (isPresent(streamer) && !streamer.equals(event.streamer())) {
                return false;
            }
            if (isPresent(channel) && !channel.equals(event.channel())) {
                return false;
            }
            return !isPresent(keyword) || containsKeyword(event);
        }

        /**
         * 关键词射程：正文与补充键值
         * <p>
         * 主播与通道<b>也在射程内</b>——搜索框里打一个群号，使用者想找的是与那个群有关的事，
         * 而不是「正文里恰好提到过那个群号的事」。
         */
        private boolean containsKeyword(TimelineEvent event) {
            String needle = keyword.toLowerCase(Locale.ROOT);
            if (contains(event.text(), needle) || contains(event.streamer(), needle)
                    || contains(event.channel(), needle)) {
                return true;
            }
            for (String value : event.detail().values()) {
                if (contains(value, needle)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean contains(String haystack, String needle) {
            return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
        }

        private static boolean isPresent(String value) {
            return value != null && !value.isBlank();
        }
    }

    /**
     * 查询结果
     *
     * @param events 命中的事件，最近的在前，最多 {@code limit} 条
     * @param matched 命中总条数，<b>不受游标影响</b>。与 {@code events.size()} 不是一回事——
     *                截断了就得说，否则界面看起来像是「一共就发生了这些」
     * @param limit 本次生效的条数上限
     * @param nextCursor 接着往更旧的翻的位置，没有更旧的命中时为 {@code null}
     * @param streamers 射程内出现过的主播，供筛选框用；<b>不随本次筛选缩水</b>
     * @param channels 射程内出现过的通道，同上
     */
    public record Result(List<TimelineEvent> events, int matched, int limit, Cursor nextCursor,
                         List<String> streamers, List<String> channels) {
        /**
         * 是否还有没给出去的命中
         * <p>
         * 按游标判而不是按「命中总数 &gt; 这一页条数」：翻到第二页之后后者恒为真，
         * 于是最后一页也会显示「还有更多」，而按下去什么都不会发生。
         * @return 还有更旧的命中时返回 true
         */
        public boolean truncated() {
            return nextCursor != null;
        }
    }
}
