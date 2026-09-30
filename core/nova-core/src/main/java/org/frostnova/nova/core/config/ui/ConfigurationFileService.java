package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.properties.DatasourceProperties;
import org.frostnova.nova.core.properties.EventStreamProperties;
import org.frostnova.nova.core.properties.NovaBotPrefixes;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.DurableFiles;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.Clock;

/**
 * 配置文件读写服务
 * <p>
 * 界面所做的修改直接落到 application.yml 上，配置文件始终是唯一的事实来源，不存在
 * 「界面上改了但文件没变」或「文件改了界面看不到」的情况。
 * <p>
 * 写入采用逐行定位替换而非整体序列化：配置模板中大量的中文注释是使用者理解配置项的主要依据，
 * 用 YAML 库反序列化再写回会把注释、空行与顺序全部丢失。
 */
@Slf4j
@Service
public class ConfigurationFileService {
    /**
     * 缩进单位，与配置模板保持一致
     */
    private static final int INDENT = 2;

    /**
     * 对象列表项的判定模式，形如 "键: 值"
     * <p>
     * 不能以「是否含冒号」来判断标量项与对象项：IPv6 地址、时间等标量本身就含冒号。
     */
    /**
     * 出现在值首位时必须加引号的 YAML 指示符
     */
    private static final String INDICATOR_START = "-?:,[]{}#&*!|>'\"%@`";

    private static final Pattern OBJECT_ITEM = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]*\\s*:(\\s|$)");
    /**
     * 形如「键:」「"键": 值」的一行：冒号后是空白或行尾。{@code http://x} 这种冒号后紧跟别的字的不算
     */
    private static final Pattern CHILD_KEY = Pattern.compile("^(\"[^\"]*\"|'[^']*'|[^\\s#'\"][^#]*?)\\s*:(\\s|$)");
    private static final Pattern CLOCK_TIME = Pattern.compile("^[+-]?\\d+(:[0-5]?\\d)+$");

    /**
     * 属性加载器把字符串名单摊成的逐项键，形如 {@code a.b[0]}
     */
    private static final Pattern LIST_ITEM = Pattern.compile("^(.+)\\[(\\d+)]$");

    /**
     * 键名段允许的字符
     * <p>
     * 段首不许是连字符或数字：纯连字符段会拼出 YAML 文档分隔符 {@code ---}，
     * 段里再不能出现换行、空格、{@code :}、{@code #}、引号这些会改写文件结构的字符。
     * 允许 {@code X-Api-Key} 这类 map 键：字母数字加下划线连字符，段首是字母或下划线。
     */
    private static final Pattern SAFE_KEY_SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_-]*");

    /**
     * 主配置文件路径
     */
    private final Path configPath;

    /**
     * 配置文件不在时，拿它当第一份文件写出去
     * <p>
     * 是个 {@code Supplier} 而不是一段现成的文本：算这份模板要把插件的配置项一起算进来，
     * 而插件是启动之后才装上的——构造时算出来的那一份，恰好缺的就是插件那几节。
     */
    private final Supplier<String> initialContent;

    private final TimestampedFileBackup backups;

    private final IntSupplier backupKeep;

    /**
     * 事件时间线
     * <p>
     * 只记一件事：旧备份被裁掉了几份。备份目录里少了几份文件是使用者迟早会撞上的现象，
     * 而删除本身发生在保存的顺带一步里，此前没有任何地方说过它。
     */
    private final TimelineWriter timeline;

    /**
     * 清空即视为「不配置」的配置项：核心自有 Redis 地址，加上各适配器申报的令牌键
     */
    private final Set<String> blankMeansAbsent;

    /**
     * 主监听地址键。完整集合见 {@link InetAddressText#ADDRESS_KEYS}。
     */
    static final String ADDRESS_KEY = "server.address";

    /**
     * 本进程是否已经把文件里的斜杠形态监听地址改回过。同一份文件在一次运行里只动一次。
     */
    private boolean slashAddressHealed;

    @Autowired
    public ConfigurationFileService(ConfigurationMetadataService metadata, ApplicationContext context,
                                    NovaCoreProperties properties, TimelineWriter timeline,
                                    ObjectProvider<BotConnectionContributor> connections) {
        this(metadata, context, properties, timeline, connections.orderedStream().toList());
    }

    private ConfigurationFileService(ConfigurationMetadataService metadata, ApplicationContext context,
                                     NovaCoreProperties properties, TimelineWriter timeline,
                                     Collection<BotConnectionContributor> contributors) {
        this(Path.of("application.yml"),
                initialContent(metadata, context, contributors),
                () -> properties.getConfigUi().getBackupKeep(), Clock.systemDefaultZone(), timeline,
                contributors);
    }

    private static Supplier<String> initialContent(ConfigurationMetadataService metadata,
                                                   ApplicationContext context,
                                                   Collection<BotConnectionContributor> contributors) {
        Set<String> blank = mergeBlankMeansAbsent(contributors);
        return () -> ConfigurationTemplate.render(metadata.getFields(),
                ConfigurationPropertyFields.values(
                        context.getBeansWithAnnotation(ConfigurationProperties.class).values()),
                blank);
    }

    /**
     * 指定配置文件路径，便于测试
     * <p>
     * 这一支没有容器可问，默认模板因此只算得上<b>核心自己</b>那三份配置对象的默认值。
     * 判据台架走的正是这一支，而它量的也正是「核心的配置面渲染成文件长什么样」。
     * @param configPath 配置文件路径
     */
    ConfigurationFileService(Path configPath) {
        this(configPath, List.of());
    }

    /**
     * 指定配置文件路径与连接列表申报，便于测试「留空即未配」走插件申报
     * @param configPath 配置文件路径
     * @param contributors 连接列表申报，空则核心只认 Redis 地址
     */
    ConfigurationFileService(Path configPath, Collection<BotConnectionContributor> contributors) {
        this(configPath, coreTemplate(mergeBlankMeansAbsent(contributors)),
                () -> TimestampedFileBackup.DEFAULT_KEEP, Clock.systemDefaultZone(), TimelineWriter.NONE,
                contributors);
    }

    private static Supplier<String> coreTemplate(Set<String> blankMeansAbsent) {
        return () -> ConfigurationTemplate.render(new ConfigurationMetadataService().getFields(),
                ConfigurationPropertyFields.values(List.of(
                        new NovaCoreProperties(), new EventStreamProperties(), new DatasourceProperties())),
                blankMeansAbsent);
    }

    ConfigurationFileService(Path configPath, Supplier<String> initialContent) {
        this(configPath, initialContent, () -> TimestampedFileBackup.DEFAULT_KEEP);
    }

    ConfigurationFileService(Path configPath, Supplier<String> initialContent,
                             Collection<BotConnectionContributor> contributors) {
        this(configPath, initialContent, () -> TimestampedFileBackup.DEFAULT_KEEP,
                Clock.systemDefaultZone(), TimelineWriter.NONE, contributors);
    }

    ConfigurationFileService(Path configPath, Supplier<String> initialContent, IntSupplier backupKeep) {
        this(configPath, initialContent, backupKeep, Clock.systemDefaultZone());
    }

    /**
     * 指定命名备份用的钟，便于测试把两次保存钉在同一秒内
     * <p>
     * 这一支<b>明写不记时间线</b>：判据台架问的是文件写成了什么样，没有一条要看日志页。
     * 写成「可以不传、不传就不记」的话，漏传与故意不传长得一模一样，
     * 而漏传的表现是时间线上安静地少一类事件（见 {@link TimelineWriter#NONE}）。
     * @param clock 备份命名的钟
     */
    ConfigurationFileService(Path configPath, Supplier<String> initialContent, IntSupplier backupKeep, Clock clock) {
        this(configPath, initialContent, backupKeep, clock, TimelineWriter.NONE);
    }

    /**
     * @param timeline 时间线写入口，生产装配走这一支
     */
    ConfigurationFileService(Path configPath, Supplier<String> initialContent, IntSupplier backupKeep,
                             Clock clock, TimelineWriter timeline) {
        this(configPath, initialContent, backupKeep, clock, timeline, List.of());
    }

    /**
     * @param timeline 时间线写入口，生产装配走这一支
     * @param contributors 连接列表申报
     */
    ConfigurationFileService(Path configPath, Supplier<String> initialContent, IntSupplier backupKeep,
                             Clock clock, TimelineWriter timeline,
                             Collection<BotConnectionContributor> contributors) {
        this.configPath = configPath;
        this.initialContent = initialContent;
        this.backups = new TimestampedFileBackup(configPath, clock);
        this.backupKeep = backupKeep;
        this.timeline = timeline;
        this.blankMeansAbsent = mergeBlankMeansAbsent(contributors);
    }

    /**
     * 读取配置文件中的全部键值
     * <p>
     * 返回的键为完整路径，例如 novabot.bilibili.dynamic.draw-logo。列表结构不在此处展开，
     * 由界面通过独立接口处理。
     * @return 键值映射
     * @throws IOException 读取失败时抛出
     */
    public synchronized Map<String, String> read() throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> fixes = new LinkedHashMap<>();
        Map<String, String> rawByPath = new LinkedHashMap<>();

        List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
        List<Line> parsed = parse(lines);
        Settled settled = settle(lines, parsed);

        for (Line line : parsed) {
            if (line.path == null || line.merge || line.hidden) {
                continue;
            }

            if (line.isList()) {
                // 字符串列表以换行连接，与界面中的多行输入框一一对应；空列表读成空串
                values.put(line.path, String.join("\n", line.items));
            } else if (line.fromLoader) {
                // 手写的跨行、锚点、别名写法：值已换成启动那一路读到的（见 settle），空串也照收——
                // 不收的话界面退回显示默认值，而程序读到的是空
                if (line.value != null) {
                    values.put(line.path, line.value);
                }
            } else if (line.value != null && !line.value.isEmpty()) {
                if (isAddressKey(line.path)) {
                    String canonical = InetAddressText.fromFile(line.value);
                    values.put(line.path, canonical);
                    if (!canonical.equals(line.value)) {
                        fixes.put(line.path, canonical);
                        rawByPath.put(line.path, line.value);
                    }
                } else {
                    values.put(line.path, line.value);
                }
            }
        }

        settled.inherited().forEach(values::putIfAbsent);
        healSlashAddress(fixes, rawByPath);
        return values;
    }

    /**
     * 界面不能改的项：键到一句说明（为什么不能改、该去哪儿改）
     * <p>
     * 值照样由 {@link #read()} 给，是程序启动读到的那个；这里只答「那一格装不装得下」。
     * 带换行的值塞不进单行框、别名引到的名单拆不开，摆个能打字的框，一改一存就把手写的写法压扁了。
     * @return 键到说明；没有这类项时为空
     * @throws IOException 读取失败时抛出
     */
    public synchronized Map<String, String> uiLocked() throws IOException {
        if (!exists()) {
            return Map.of();
        }
        List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
        return settle(lines, parse(lines)).locked();
    }

    /** 值里带换行，单行框装不下 */
    private static final String LOCK_MULTILINE = "这一项在配置文件里占了好几行（值写在「|」或「>」号下面，或折到了下一行），"
            + "值里带换行，这一格只装得下一行；这里显示的是程序实际读到的值，要改请到配置文件里改。";

    /** 名单里有一项带换行，每行一项的框表达不了 */
    private static final String LOCK_LIST_ITEM_MULTILINE = "这份名单里有一项在配置文件里写成了多行文字，"
            + "值里带换行，每行一项的框表达不了；这里显示的是程序实际读到的值，要改请到配置文件里改。";

    /**
     * 别名引到名单或一整块时，界面上的只读说明。
     * 括号里是配置文件里写的别名；取不到名字时不留括号。
     */
    private static String lockAlias(String alias) {
        String named = alias.isEmpty() ? "别名" : "别名（" + alias + "）";
        return "这一项在配置文件里写成了" + named + "，引到的是一份名单或一整块设置，"
                + "在界面改会拆掉这层引用；这里显示的是程序实际读到的值，要改请到配置文件里改。";
    }

    /**
     * 行内名单里有一项是别名时的只读说明。括号里是那一项写的别名。
     */
    private static String lockAliasItem(String alias) {
        String named = alias.isEmpty() ? "别名" : "别名（" + alias + "）";
        return "这份名单里有一项是" + named + "，在界面改会拆掉这层引用；"
                + "这里显示的是程序实际读到的值，要改请到配置文件里改。";
    }

    /**
     * 行内名单里有一项带着锚点或标签时的只读说明。括号里是那一项写的记号。
     */
    private static String lockMarkedItem(String mark) {
        return "这份名单里有一项带着" + markKind(mark) + "（" + mark + "），在界面改会把它丢掉；"
                + "这里显示的是程序实际读到的值，要改请到配置文件里改。";
    }

    /**
     * 行内名单整份带着锚点或标签时的只读说明。括号里是写在名单前的记号。
     */
    private static String lockMarkedList(String mark) {
        return "这份名单在配置文件里带着" + markKind(mark) + "（" + mark + "），在界面改会把它丢掉；"
                + "这里显示的是程序实际读到的值，要改请到配置文件里改。";
    }

    private static String markKind(String mark) {
        return mark.startsWith("&") ? "锚点" : "标签";
    }

    /** 行内名单里有一项本身又是名单或一组键值 */
    private static final String LOCK_LIST_NESTED = "这份名单里有一项本身又套着一份名单或一组「键: 值」，"
            + "每行一项的框表达不了；这里把那一项照配置文件里的写法列出，要改请到配置文件里改。";

    /** 行内名单写的字与程序读到的不一样 */
    private static final String LOCK_LIST_READ_DIFFERENTLY = "这份名单里有的项程序读到的和配置文件里写的字不一样"
            + "（比如不带引号的 yes 读成 true），在界面改会换掉原来的写法；这里显示的是程序实际读到的值，要改请到配置文件里改。";

    /** 名单里有空的一项，每行一项的框里是一行空，存回时被当成没有 */
    private static final String LOCK_LIST_EMPTY_ITEM = "这份名单里有空的一项，界面上显示不出来，"
            + "在界面改会把它丢掉；要改请到配置文件里改。";

    /** 名单项与启动读到的对不上号 */
    private static final String LOCK_LIST_UNREADABLE = "这份名单在配置文件里的写法界面读不准（有的项折到了好几行、"
            + "或以「|」「*名字」「&名字」这样的记号开头），为不写错不在界面改；要改请到配置文件里改。";

    /** 启动那一路也读不出来 */
    private static final String LOCK_UNREADABLE = "这一项的写法界面读不准，而程序启动时读这份配置文件也没读出这一项，"
            + "为不写错不在界面改；要改请到配置文件里改。";

    /**
     * {@link #settle} 的结果
     *
     * @param inherited 经合并键或整块别名继承、文件里没有自己那一行的子键，值为启动读到的
     * @param locked    界面不能改的项及说明
     */
    private record Settled(Map<String, String> inherited, Map<String, String> locked) {}

    /**
     * 把手写的跨行、锚点、别名与合并写法换成启动那一路读到的值
     * <p>
     * 逐行解析只看得见键那一行：块标量只看到「|」，跨行的值只看到半截，别名只看到「*w」，
     * 合并进来的子键根本不在文件里。这几种由 {@link #parse} 标出来（{@link Line#fromLoader}、
     * {@link Line#loaderItems}、{@link Line#merge}），这里按启动那一路读一遍，把界面值换成它读到的——
     * 就地改 {@link Line#value} 与 {@link Line#items}，写口据此认「原样送回＝没改」，
     * 改同一名单里别的项时把跨行那一项按读到的整段写回，启动值不变。
     * 别的写法不经这里，界面照旧显示文件里的文字（不带引号的 {@code 23:00} 仍是 23:00，不是 1380）。
     */
    private Settled settle(List<String> lines, List<Line> parsed) {
        boolean needed = false;
        for (Line line : parsed) {
            if (line.fromLoader || !line.loaderItems.isEmpty() || line.merge || line.flowInterrupted) {
                needed = true;
                break;
            }
        }
        if (!needed) {
            return new Settled(Map.of(), lockEmptyItems(parsed, new LinkedHashMap<>()));
        }

        Map<String, Object> loaded;
        try {
            loaded = load(String.join("\n", lines) + "\n");
        } catch (IOException e) {
            loaded = null;
        }

        Map<String, String> locked = new LinkedHashMap<>();
        Set<String> paths = new LinkedHashSet<>();
        Set<String> inheriting = new LinkedHashSet<>();
        for (Line line : parsed) {
            if (line.path == null) {
                continue;
            }
            paths.add(line.path);
            if (line.merge) {
                int dot = line.path.lastIndexOf('.');
                if (dot > 0) {
                    inheriting.add(line.path.substring(0, dot));
                }
                continue;
            }
            if (line.flowInterrupted) {
                // 跨行夹了空行、注释行的名单照旧拒存，只把显示换成启动读到的各项；读不出时列原文整段
                Object value = loaded == null ? null : loaded.get(line.path);
                line.value = value instanceof List<?> list && !hasNestedItems(loaded, line.path)
                        ? joinLoaded(list) : flowSpan(lines, line);
                continue;
            }
            if (line.fromLoader) {
                if (bodyOf(line.rawValue).startsWith("*")) {
                    inheriting.add(line.path);
                }
                if (loaded == null) {
                    // 整份读不通、或文件分了几段，比不了（checkAsLoaded 对这种文件也是照旧写）：
                    // 键行上没有记号、已按名单收下的照改前办——不锁、照旧可改；其余写法读不准，照旧锁
                    if (!line.flowInline || hasNoValue(line.rawValue)) {
                        locked.put(line.path, LOCK_UNREADABLE);
                    }
                    continue;
                }
                Object value = loaded.get(line.path);
                // 名单里只有套着的名单、键值时，启动那一路只摊出带下标的子键，这一项自己没有值
                boolean nested = hasNestedItems(loaded, line.path);
                if (value == null && nested) {
                    value = List.of();
                }
                if (value == null) {
                    // 引到的是一整块：值在子键上，这一项自己没有值可显示
                    line.hidden = true;
                    line.value = null;
                    locked.put(line.path, lockAlias(aliasWritten(lines, line)));
                } else if (value instanceof List<?> list) {
                    line.value = nested ? shownWithNested(lines, line, list) : joinLoaded(list);
                    String reason = flowListLock(lines, line, list, nested);
                    if (reason != null) {
                        // 按名单收下的是文件里的字面文字，和启动读到的不是同一套：
                        // 退回这套名单，界面改显示启动读到的值
                        if (line.flowInline) {
                            line.items.clear();
                            line.listEnd = -1;
                            line.flowInline = false;
                        }
                        locked.put(line.path, reason);
                    }
                } else {
                    line.value = String.valueOf(value);
                    if (hasLineBreak(line.value)) {
                        locked.put(line.path, LOCK_MULTILINE);
                    }
                }
            }
            if (!line.loaderItems.isEmpty() && line.isList()) {
                Object value = loaded == null ? null : loaded.get(line.path);
                if (!(value instanceof List<?> list) || list.size() != line.items.size()) {
                    locked.put(line.path, loaded == null ? LOCK_UNREADABLE : LOCK_LIST_UNREADABLE);
                    continue;
                }
                for (int index : line.loaderItems) {
                    String item = String.valueOf(list.get(index));
                    line.items.set(index, item);
                    if (hasLineBreak(item)) {
                        locked.put(line.path, LOCK_LIST_ITEM_MULTILINE);
                    }
                }
            }
        }

        Map<String, String> inherited = new LinkedHashMap<>();
        if (loaded != null) {
            for (Map.Entry<String, Object> entry : loaded.entrySet()) {
                String key = entry.getKey();
                if (key.contains("[") || paths.contains(key) || !underAny(key, inheriting)) {
                    continue;
                }
                Object value = entry.getValue();
                if (value instanceof List<?> list) {
                    inherited.put(key, joinLoaded(list));
                    if (list.stream().anyMatch(item -> hasLineBreak(String.valueOf(item)))) {
                        locked.put(key, LOCK_LIST_ITEM_MULTILINE);
                    }
                } else {
                    inherited.put(key, String.valueOf(value));
                    if (hasLineBreak(String.valueOf(value))) {
                        locked.put(key, LOCK_MULTILINE);
                    }
                }
            }
        }
        return new Settled(inherited, lockEmptyItems(parsed, locked));
    }

    /**
     * 名单里有空的一项（{@code ""}、只有短横的一项）的锁上：界面上那是一行空，
     * 存回时空行被当成没有，那一项就丢了。已因别的缘故锁着的不换说明
     */
    private static Map<String, String> lockEmptyItems(List<Line> parsed, Map<String, String> locked) {
        for (Line line : parsed) {
            if (line.path != null && line.isList() && line.items.contains("")) {
                locked.putIfAbsent(line.path, LOCK_LIST_EMPTY_ITEM);
            }
        }
        return locked;
    }

    private static boolean underAny(String key, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (key.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static String joinLoaded(List<?> list) {
        List<String> items = new ArrayList<>();
        for (Object item : list) {
            items.add(String.valueOf(item));
        }
        return String.join("\n", items);
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    /**
     * 一段值原文里的别名记号（{@code *名字}）；没有时为空串。
     * 键行与写在下一行的值都经这里取，不在这一处决定读哪一行。
     */
    private static String aliasToken(String rawValue) {
        if (rawValue == null) {
            return "";
        }
        String body = bodyOf(rawValue);
        for (String token : body.split("\\s+")) {
            if (token.length() > 1 && token.charAt(0) == '*') {
                int end = token.length();
                while (end > 1 && (token.charAt(end - 1) == ',' || token.charAt(end - 1) == ']'
                        || token.charAt(end - 1) == '}')) {
                    end--;
                }
                return token.substring(0, end);
            }
        }
        return "";
    }

    /**
     * 这一项在配置文件里写的别名记号。键行上有就用键行上的；
     * 键行没有值、别名写在下一行的，从那一行取。
     * 哪一行算值，与解析时认「值在下一行」相同：键行空着或只有锚点、标签，
     * 且底下不是子项（见 {@link #hasNoValue}、{@link #holdsChildren}、{@link #valueOnNextLineEnd}）。
     * 取不到时为空串。
     */
    private String aliasWritten(List<String> lines, Line line) {
        String onKey = aliasToken(line.rawValue);
        if (!onKey.isEmpty()) {
            return onKey;
        }
        if (line.rawValue != null && !hasNoValue(line.rawValue)) {
            return "";
        }
        if (holdsChildren(lines, line)) {
            return "";
        }
        int end = valueOnNextLineEnd(lines, line.index, line.indent);
        for (int i = line.index + 1; i <= end; i++) {
            String raw = lines.get(i);
            if (raw.isBlank() || raw.strip().startsWith("#")) {
                continue;
            }
            String stripped = raw.strip();
            int comment = commentIndex(stripped);
            String value = (comment < 0 ? stripped : stripped.substring(0, comment)).strip();
            return aliasToken(value);
        }
        return "";
    }

    /**
     * 键行冒号后的原文去掉打头的锚点、标签（{@code &名}、{@code !标签}）之后剩下的那段
     */
    private static String bodyOf(String value) {
        String rest = value.strip();
        while (!rest.isEmpty() && (rest.charAt(0) == '&' || rest.charAt(0) == '!')) {
            int end = 0;
            while (end < rest.length() && !Character.isWhitespace(rest.charAt(end))) {
                end++;
            }
            rest = rest.substring(end).strip();
        }
        return rest;
    }

    /**
     * 按程序启动时读配置的那一路，读出每一项实际得到的值
     * <p>
     * 与 {@link #read()} 不是一回事：那边给界面看的是去掉引号、还原了转义的文字，
     * 这边是 Spring Boot 的 YAML 属性加载器读出来的值——不带引号的 {@code 23:00} 在这里是
     * 六十进制整数 1380，而界面上是文字 {@code 23:00}。
     * 「重启之后读到的变没变」只能拿这一份比，文字一样不算数。
     * <p>
     * 字符串名单按下标收成一个 {@link List}，与界面上整份名单对应。
     * 文件分成了几段（{@code ---}）时各段怎么叠要看激活的配置档，这里比不准，读成空的。
     * @return 键到值；文件不在时为空
     * @throws IOException 读不下来或加载器解析不了时抛出
     */
    public synchronized Map<String, Object> readAsLoaded() throws IOException {
        if (!exists()) {
            return Map.of();
        }

        List<PropertySource<?>> documents;
        try {
            documents = new YamlPropertySourceLoader().load(configPath.toString(), new FileSystemResource(configPath));
        } catch (RuntimeException e) {
            throw new IOException("程序启动时读这份配置文件读不通: " + e.getMessage(), e);
        }
        Map<String, Object> values = flatten(documents);
        return values == null ? Map.of() : values;
    }

    /**
     * 按 {@link #readAsLoaded()} 那一路读一段还没落盘的文字
     * <p>
     * 写口落盘前拿它比「改前、改后启动各读到什么」，设置页拿它把手写写法换成读到的值。
     * @param text 整份配置文字
     * @return 键到值；文件分了几段时为 null（各段怎么叠比不准）
     * @throws IOException 加载器解析不了时抛出
     */
    private static Map<String, Object> load(String text) throws IOException {
        List<PropertySource<?>> documents;
        try {
            documents = new YamlPropertySourceLoader().load("application.yml",
                    new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw new IOException("程序启动时读这份配置文件读不通: " + e.getMessage(), e);
        }
        return flatten(documents);
    }

    /**
     * 字符串名单按下标收成一个 {@link List}；不是恰好一段时为 null
     */
    private static Map<String, Object> flatten(List<PropertySource<?>> documents) {
        if (documents.size() != 1 || !(documents.get(0) instanceof EnumerablePropertySource<?> document)) {
            return null;
        }

        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, TreeMap<Integer, Object>> lists = new LinkedHashMap<>();
        for (String name : document.getPropertyNames()) {
            Object value = document.getProperty(name);
            if (value == null) {
                continue;
            }
            Matcher item = LIST_ITEM.matcher(name);
            if (item.matches()) {
                lists.computeIfAbsent(item.group(1), key -> new TreeMap<>())
                        .put(Integer.parseInt(item.group(2)), value);
            } else {
                values.put(name, value);
            }
        }
        lists.forEach((path, items) -> values.put(path, List.copyOf(items.values())));
        return values;
    }

    static boolean isAddressKey(String path) {
        return InetAddressText.isAddressKey(path);
    }

    /**
     * 文件里的监听地址若是 {@code InetAddress.toString()} 那种斜杠形态，按既有写口改回裸地址。
     * 同一进程只改一次：启动期后处理器已经让绑定成功，这里只负责把落盘那一行扶正。
     * 一次写盘带上全部待改的地址键，免得两处斜杠只改到第一处。
     * <p>
     * 这次写不留备份：它只改地址的写法、用不着退回，而它早于口令哈希化——同一文件里
     * 还手写着明文口令时，留下的备份就是一份抄着明文的副本，要再存够十次才挤掉。
     */
    private void healSlashAddress(Map<String, String> fixes, Map<String, String> rawByPath) {
        if (slashAddressHealed || fixes.isEmpty()) {
            return;
        }
        slashAddressHealed = true;
        try {
            List<String> changed = writeWithoutBackup(fixes);
            if (!changed.isEmpty()) {
                for (String path : changed) {
                    log.info("配置文件里的监听地址是斜杠形态 {}, 已改回 {}",
                            rawByPath.getOrDefault(path, path), fixes.get(path));
                }
            }
        } catch (IOException e) {
            slashAddressHealed = false;
            log.warn("配置文件里的监听地址是斜杠形态, 未能改回 {}: {}", fixes, e.toString());
        }
    }

    /**
     * 启动时若文件已经在，读一次以触发斜杠形态自愈。读失败不挡启动——绑定已经由后处理器救过。
     */
    @PostConstruct
    void healSlashAddressOnStart() {
        if (!exists()) {
            return;
        }
        try {
            read();
        } catch (IOException e) {
            log.warn("启动时读取配置文件失败, 监听地址斜杠形态可能尚未改回: {}", e.toString());
        }
    }

    /**
     * 将修改写回配置文件
     * <p>
     * 写入前会先备份原文件。仅修改确实发生变化的行，其余内容逐字节保持不变。
     * @param changes 待写入的键值，键为完整路径
     * @return 实际发生变更的配置项数量
     * @throws IOException 写入失败时抛出
     */
    /**
     * 清空即视为「不配置」的配置项
     * <p>
     * 多数配置项留空是有意义的——静音时段留空表示不启用。但下面这几项写成空值
     * 要么让程序<b>根本起不来</b>（Redis 地址），要么表示「这一项没配」
     * （机器人连接的令牌：由适配器申报）。
     * <p>
     * 核心只留 Redis 地址。平台令牌键由 {@link BotConnectionContributor#blankMeansAbsentKeys()} 申报。
     * 键集只此一份：标量写口按完整路径认，对象列表渲染与列表元素字段按最后一段认。
     */
    static final Set<String> CORE_BLANK_MEANS_ABSENT = Set.of("spring.data.redis.host");

    static Set<String> mergeBlankMeansAbsent(Collection<BotConnectionContributor> contributors) {
        Set<String> keys = new LinkedHashSet<>(CORE_BLANK_MEANS_ABSENT);
        if (contributors == null) {
            return Set.copyOf(keys);
        }
        for (BotConnectionContributor contributor : contributors) {
            if (contributor == null) {
                continue;
            }
            Set<String> declared = contributor.blankMeansAbsentKeys();
            if (declared == null) {
                continue;
            }
            keys.addAll(declared);
        }
        return Set.copyOf(keys);
    }

    /**
     * 本实例合并后的「留空即未配」键，含核心自有与插件申报
     * @return 完整路径
     */
    Set<String> blankMeansAbsent() {
        return blankMeansAbsent;
    }

    /**
     * 某个列表元素内部的字段是不是「留空＝未配置」
     * <p>
     * 认的是 {@link #blankMeansAbsent()} 里那些键的最后一段，所以
     * 令牌字段短名与完整路径是同一张表上的同一项。
     */
    boolean isBlankMeansAbsentField(String field) {
        return isBlankMeansAbsentField(field, blankMeansAbsent);
    }

    static boolean isBlankMeansAbsentField(String field, Set<String> keys) {
        if (keys.contains(field)) {
            return true;
        }
        String suffix = "." + field;
        for (String key : keys) {
            if (key.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 配置文件在不在
     * <p>
     * 「这台机器配过没有」只此一处判据。装好之后一次也没保存过的实例没有这个文件——
     * 界面据此把人领到初始设置页去。<b>不另按「口令设没设」之类的现象去猜</b>：
     * 那种判法在一台配好了、只是没上锁的机器上会把人反复送回初始设置页。
     * @return 存在时为 true
     */
    public boolean exists() {
        return Files.isRegularFile(configPath);
    }

    /**
     * 文件不在就先按配置面写出完整的一份
     * <p>
     * 🔴 <b>位置在每一条写口的最前面，不在调用方那边</b>。发行包里不再带 application.yml，
     * 于是「第一次写配置」这件事有好几条路走得到：设置页保存、第一次上锁、同意使用协议、
     * 启动时把明文口令换成哈希写回。挑几条去补一句「文件不在就先建」，漏掉的那条
     * 表现是一个 {@code NoSuchFileException}，而它冒出来的地方与真实原因毫无关系。
     * <p>
     * <b>已有的文件一个字节也不动。</b>使用者手改过的配置被一份「完整的默认配置」盖掉，
     * 是这段代码最坏的失败形态，而它发生之后没有任何现象——除非那个人正好记得自己改过什么。
     * @return 这一次真的建了文件时为 true
     * @throws IOException 写入失败时抛出
     */
    public synchronized boolean createIfAbsent() throws IOException {
        if (exists()) {
            return false;
        }

        Path parent = configPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        DurableFiles.replace(configPath, initialContent.get(), DurableFiles.OWNER_ONLY);
        log.info("配置文件不存在, 已按当前配置面写出一份完整的 {}", describeConfigPath());
        return true;
    }

    /**
     * 拒绝写入含换行的标量值
     * <p>
     * 本类是逐行改写配置文件的，一个值里的换行会变成文件里真正的换行。
     * 实测（见 {@code ConfigurationFileServiceTest#rejectsMultilineScalarValue}）：
     * 值里带 {@code : } 时会被引号包住，折行后仍是同一个字符串，注入不了新的键；
     * <b>但不带冒号时不会加引号，多出来的那行会顶在第 0 列，整份配置从此解析不了</b>——
     * 而接口照样回报「已保存」，问题要到下次重启才暴露成安全模式。
     * <p>
     * 界面上标量走单行输入框，正常操作打不出换行，能走到这里的都是直接调接口的。
     * 与其把值悄悄改掉，不如当场拒绝并说清是哪一项。
     * <p>
     * 字符串列表不在此列：那里换行本就是各项之间的分隔符。界面改不了的项也不在此列：
     * 它们先过锁检查（见 {@link #rejectLockedChanges}），原样送回的多行值是「没改」，不当换行拒。
     * @param changes 待写入的配置项
     * @param index 配置文件中已有的行
     * @param locked 界面改不了的项（键到说明），它们的换行由锁那边说话
     * @throws IOException 存在含换行的标量值时抛出
     */
    private void rejectMultilineScalars(Map<String, String> changes, Map<String, Line> index,
                                        Map<String, String> locked) throws IOException {
        for (Map.Entry<String, String> change : changes.entrySet()) {
            String value = change.getValue();
            if (value == null || (value.indexOf('\n') < 0 && value.indexOf('\r') < 0)) {
                continue;
            }

            Line line = index.get(change.getKey());
            // 文件里还没有这一项时，含换行的值会被当成字符串列表写入，那是合法的。
            // 跨行读不了的名单界面上一行一项，拒存由写口那句说实际情形
            if (line != null && !line.isList() && !line.flowUnreadable && !locked.containsKey(change.getKey())) {
                throw new IOException("配置项 " + change.getKey() + " 的值不能包含换行");
            }
        }
    }

    /**
     * 把一批配置项写进配置文件
     * <p>
     * 回的是<b>真正落盘的那几个键的名字，而不是一个数目</b>。调用方接下来要回答的是
     * 「其中哪几项要等重启」——只给数目的话，那个红色的数字后面跟不出任何一个键名，
     * 而事后再算一遍得到的是「现在有哪些项与默认值不同」，答的已经是另一个问题了。
     * @param changes 待写入的配置项名到取值
     * @return 实际发生改动的配置项名
     * @throws IOException 读写失败、改了界面改不了的项、存在含换行的标量值或有配置项在文件里找不到上级块时抛出
     */
    public synchronized List<String> write(Map<String, String> changes) throws IOException {
        return write(changes, true);
    }

    /**
     * 把一批配置项写进配置文件，这次写入<b>不留备份</b>
     * <p>
     * 给「换掉的旧值正是要清掉的明文」的写入用：明文口令、明文 token 换成哈希写回时，
     * 备份照原样复制旧文件，等于把刚换掉的明文又抄一份放进同一个目录，
     * 而备份按份数轮换，那一份要等之后再存够十次才被挤掉。
     * <p>
     * 不留备份也安全：换名那条路失败时原件一个字节不动；退回直接写写到一半出错时，
     * 按写之前留在内存里的原文尽力写回（见 {@link DurableFiles#replace}），写回也失败
     * 才可能留下半截，那时日志点名。这条路的上一版本来就是那份明文，留在盘上与这次
     * 写回的目的正好相反。
     * 普通保存照旧走 {@link #write(Map)}：那才是使用者要能反悔的改动。
     * @param changes 待写入的配置项名到取值
     * @return 实际发生改动的配置项名
     * @throws IOException 读写失败、改了界面改不了的项、存在含换行的标量值或有配置项在文件里找不到上级块时抛出
     */
    public synchronized List<String> writeWithoutBackup(Map<String, String> changes) throws IOException {
        return write(changes, false);
    }

    /**
     * 备份那句实话，供日志原样引：同目录没有含明文的副本时说没有，有就点名，查不出也照说
     * <p>
     * 明文换哈希那条路自己不留备份，但更早的保存可能已经把明文抄进过备份，
     * 那些照约定不动。说「没有」之前先看盘：这次没写备份，不等于目录里就没有含明文的副本。
     * @param key 那段明文所在的配置项，完整路径
     * @param plaintext 刚从主配置文件换掉的那段明文
     * @return 同目录的实况
     */
    public String backupSituation(String key, String plaintext) {
        List<String> left;
        try {
            left = siblingFilesHolding(key, plaintext);
        } catch (IOException e) {
            return "同目录的备份没查成: " + e.getMessage();
        }
        if (left.isEmpty()) {
            return "同目录未留含明文的备份";
        }
        return "同目录的 " + left + " 里还留着明文, 那几份本次不动";
    }

    /**
     * 主配置文件之外，同一目录里还有一行写着「这个键: 这段明文」的文件名，一份没有时为空表
     * <p>
     * 按整行认，不按裸子串找：口令是 admin 这类常见词时，按子串找会把正文里、别的键上
     * 碰巧有这个词的件都报成「还留着明文」。键只比最后一段——备份是主配置文件的旧版，
     * 那一行在里面长得和主配置文件里一样；值可带引号，行尾可带注释。
     * @param key 配置项完整路径
     * @param plaintext 明文
     * @return 命中的文件名，按名排序
     * @throws IOException 读目录失败时抛出
     */
    private List<String> siblingFilesHolding(String key, String plaintext) throws IOException {
        List<String> left = new ArrayList<>();
        Path self = configPath.toAbsolutePath().normalize();
        Path dir = self.getParent();
        if (dir == null || plaintext.isEmpty()) {
            return left;
        }
        String leaf = key.substring(key.lastIndexOf('.') + 1);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                if (!Files.isRegularFile(file) || file.toAbsolutePath().normalize().equals(self)) {
                    continue;
                }
                // 按字节读再解码而不是 readString：目录里可能有不是文本的件，
                // readString 遇到坏字节会抛异常、半路炸掉这次查询，new String 只把坏字节换成替代符
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (text.lines().anyMatch(line -> holdsValue(line, leaf, plaintext))) {
                    left.add(file.getFileName().toString());
                }
            }
        }
        return left;
    }

    /**
     * 这一行是不是「键: 值」且值（去引号、去行尾注释后）正是这段明文
     */
    private static boolean holdsValue(String line, String leaf, String plaintext) {
        String stripped = line.strip();
        if (stripped.startsWith("- ")) {
            stripped = stripped.substring(2).stripLeading();
        }
        if (!stripped.startsWith(leaf + ":")) {
            return false;
        }
        String rest = stripped.substring(leaf.length() + 1);
        if (!rest.isEmpty() && !Character.isWhitespace(rest.charAt(0))) {
            return false;
        }
        int comment = commentIndex(rest);
        String value = (comment < 0 ? rest : rest.substring(0, comment)).strip();
        return unquote(value).equals(plaintext);
    }

    private synchronized List<String> write(Map<String, String> changes, boolean keepBackup) throws IOException {
        if (changes.isEmpty()) {
            return List.of();
        }
        // 调用方送来的可以是不许改的表：rejectLockedChanges 要从本批剔除原样送回的继承项，先备一份可动的
        changes = new LinkedHashMap<>(changes);

        createIfAbsent();

        List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
        List<String> original = List.copyOf(lines);
        List<Line> parsed = parse(lines);
        // 手写写法换成启动读到的值：原样送回才认得出是没改，改名单里别的项时跨行那一项按读到的整段写回
        Settled settled = settle(lines, parsed);

        Map<String, Line> index = new LinkedHashMap<>();
        for (Line line : parsed) {
            if (line.path != null) {
                index.put(line.path, line);
            }
        }

        rejectLockedChanges(changes, settled.locked(), index, settled.inherited());
        rejectMultilineScalars(changes, index, settled.locked());

        // 用有序集而不是计数器：同一个键在一次调用里只会处理一次，但名字要按处理顺序留下来
        Set<String> changed = new LinkedHashSet<>();
        List<Map.Entry<String, String>> missing = new ArrayList<>();

        // 列表整块替换会改变行号，因此自下而上处理，避免先前的替换让后面的行号失效
        List<Map.Entry<String, String>> ordered = new ArrayList<>(changes.entrySet());
        ordered.sort((a, b) -> {
            Line la = index.get(a.getKey());
            Line lb = index.get(b.getKey());
            return Integer.compare(lb == null ? -1 : lb.index, la == null ? -1 : la.index);
        });

        for (Map.Entry<String, String> change : ordered) {
            Line line = index.get(change.getKey());

            // 显式清除（值为 null）：把这一项删掉，连同它的续行（见 blockEnd）。清一个本来就没有的键是无操作——
            // 「没这一项」与「删掉之后没有」在读回来时是同一件事，都不该往文件里添一行空值
            if (change.getValue() == null) {
                if (line != null) {
                    lines.subList(line.index, blockEnd(lines, line) + 1).clear();
                    changed.add(change.getKey());
                }
                continue;
            }

            // 这类配置项清空等于「不配置」，要把整项删掉而不是留一个空值——
            // 留空会让程序下次启动直接失败。自下而上处理，删行不会让后续行号失效
            if (blankMeansAbsent.contains(change.getKey())
                    && (change.getValue() == null || change.getValue().isBlank())) {
                if (line != null) {
                    lines.subList(line.index, blockEnd(lines, line) + 1).clear();
                    changed.add(change.getKey());
                }
                continue;
            }

            if (line == null) {
                missing.add(change);
                continue;
            }

            if (line.isList()) {
                if (replaceList(lines, line, change.getValue())) {
                    changed.add(change.getKey());
                }
                continue;
            }

            if (line.flowUnreadable) {
                // 跨行行内名单读不了的写法：就地替换只动键那一行，续行留成孤行会写坏整份文件，
                // 宁可拒存并说清原因（见本类 parse 里的同名标注）。各说各的实际情形：
                // 中间夹了空行或注释行的，里面并没有套着什么；方括号到下一个键都没收口的，也没有；
                // 收了口才看得见套着的写法
                if (line.flowUnclosed) {
                    throw new IOException("配置项 " + change.getKey() + " 的名单写在一对方括号里还跨了行, "
                            + "方括号一直没有收口, 界面读不了, 为不写坏配置文件本批全部未保存, "
                            + "请先在配置文件里补上收口的「]」或把它改成每行一项");
                }
                throw new IOException("配置项 " + change.getKey() + " 的名单写在一对方括号里还跨了行, "
                        + (line.flowInterrupted
                        ? "中间夹着空行或注释行, 界面读不了"
                        : "里面套着的方括号、花括号或「词: 值」界面读不了")
                        + ", 为不写坏配置文件本批全部未保存, 请先在配置文件里把它改成每行一项");
            }

            // 引号包着的值启动时读到的就是界面上那个值，原样送回来就是没改：不动那一行，
            // 免得重新渲染换一种引号写法、把手写的原样冲掉还记成一次改动
            if (line.quoted && change.getValue().equals(line.value)) {
                continue;
            }

            // 手写的跨行、锚点、别名写法：界面上是启动读到的值，原样送回就是没改，锚点、续行一个字不动
            if (line.fromLoader && change.getValue().equals(line.value)) {
                continue;
            }

            // 别名引到的是一整块：值在引来的子键上，填值会把它们一起去掉
            if (line.hidden) {
                String alias = aliasWritten(lines, line);
                throw new IOException("配置项 " + change.getKey()
                        + " 在配置文件里是引用别处一整块的别名" + (alias.isEmpty() ? "" : " " + alias)
                        + ", 填值会去掉引来的子项, 本批全部未保存, 请先在配置文件里改写这一项");
            }

            // 键行带着续行的（跨行的值、块标量）连续行一起换掉：只换键那一行的话，续行并进新值。
            // 键行上没有值、底下是子项或名单项的是上级块，那些不是续行：给它填值只会删掉子项
            // 或写坏文件，整批拒存
            String updated = replaceValue(lines.get(line.index), change.getValue());
            if (!updated.equals(lines.get(line.index))) {
                if (hasNoValue(line.rawValue) && holdsChildren(lines, line)) {
                    throw new IOException("配置项 " + change.getKey()
                            + " 在配置文件里底下还挂着别的设置（往右缩进的那几行）, 填一个值会删掉它们, 本批全部未保存, 请先在配置文件里改写这一段");
                }
                lines.subList(line.index + 1, blockEnd(lines, line) + 1).clear();
                lines.set(line.index, updated);
                changed.add(change.getKey());
            }
        }

        // 配置文件中尚不存在的项追加到其最近的已有祖先之下；有一项插不进去则整批不落盘（见下方抛错）
        List<String> unplaceable = new ArrayList<>();
        for (Map.Entry<String, String> entry : missing) {
            if (insert(lines, entry.getKey(), entry.getValue())) {
                changed.add(entry.getKey());
            } else {
                unplaceable.add(entry.getKey());
            }
        }

        if (!unplaceable.isEmpty()) {
            throw new IOException("配置项 " + String.join(", ", unplaceable)
                    + " 在文件里没有它的任何上级块, 本批全部未保存, 请先在配置文件里补上该块");
        }

        if (!changed.isEmpty()) {
            checkAsLoaded(original, lines, changed, changes, index);
            if (keepBackup) {
                backup();
            }
            DurableFiles.replace(configPath, lines, DurableFiles.OWNER_ONLY);
            log.info("配置界面已更新 {} 个配置项: {}", changed.size(), String.join(", ", changed));
        }

        return List.copyOf(changed);
    }

    private static String unsavedReason(String reason) {
        return "界面改不了, 本批全部未保存: " + reason;
    }

    /**
     * 界面改不了的项照锁拒存：值与界面显示的不同就整批拒存，拒语用这把锁自己的说明
     * <p>
     * 锁着的格只在界面上画成只读，直接调保存接口可以绕开：锁住的名单格送一个值进来，
     * 标量那一路会把整份名单换成一个字，而读回核对期望的正是送来的值，比得上、拦不住。
     * 这里在动文件之前按同一份锁整体查一遍：原样送回＝没改，照旧放行；真改了就拒。
     * 查在「值不能包含换行」之前——锁住的项送来带换行的值时，拒语说的是锁的原因，
     * 不是那个照着改也存不进去的换行。
     * <p>
     * 文件里有这一行的原样送回就是没改，放行后写口自己认得；文件里没有这一行的
     * （经合并键或整块别名继承来的）不同——放行会让写口把它按新增写进文件，落成实有的
     * 一行，继承就断了。这种从本批剔除、不写。
     * <p>
     * 引到一整块的别名不在其列：那一格界面上没有值，写口里另有一句点出别名名的拒语。
     * @param changes 待写入的配置项，可变：原样送回的继承项从这里剔除
     * @param locked  界面改不了的项（键到说明），与 GET 给设置页的那一份同源
     * @param index   配置文件中已有的行
     * @param inherited 经合并键或整块别名继承、文件里没有自己那一行的子键，值为界面显示的
     * @throws IOException 有锁住的项被改时抛出
     */
    private void rejectLockedChanges(Map<String, String> changes, Map<String, String> locked,
                                     Map<String, Line> index, Map<String, String> inherited) throws IOException {
        for (Map.Entry<String, String> change : new ArrayList<>(changes.entrySet())) {
            String reason = locked.get(change.getKey());
            if (reason == null) {
                continue;
            }
            Line line = index.get(change.getKey());
            if (line != null && line.hidden) {
                continue;
            }
            // 界面显示的那个值：名单是各项一行，别的按启动读到的；不在文件里的项看继承来的
            String shown = line == null ? inherited.get(change.getKey())
                    : line.isList() ? String.join("\n", line.items) : line.value;
            if (change.getValue() == null || !change.getValue().equals(shown)) {
                throw new IOException("配置项 " + change.getKey() + " " + unsavedReason(reason));
            }
            if (line == null) {
                // 原样送回的继承项：文件里没有这一行，放行会被写口当新增写进去，从本批剔除
                changes.remove(change.getKey());
            }
        }
    }

    /**
     * 落盘前按启动那一路比一遍改前、改后：文件读得通、没改的项启动值一个不变、改的项读到的就是界面那个值
     * <p>
     * 逐行改写认不全手写写法：锚点被别处的别名引用着，改值去掉锚点、别名悬空，整份文件读不了；
     * 经合并键继承的一整块，插一个子键进去就把同块别的继承项顶掉了；整块别名底下插不进子键。
     * 这些逐条去认总有漏的，而漏掉的表现是下次启动进安全模式，或者一项设置悄悄变了。
     * 比的是结果，不是写法：三条有一条不成立就整批拒存。
     * <p>
     * 改前本就读不通、或文件分了几段的，比不了，照旧写——那种文件本就得靠界面之外修，挡住写口只会更糟。
     * @param original 改前的文件行
     * @param lines    改后的文件行
     * @param changed  真动了的键
     * @param changes  送上来的键值
     * @param index    改前各键所在行，报锚点用
     * @throws IOException 三条有一条不成立时抛出，文件未动
     */
    private void checkAsLoaded(List<String> original, List<String> lines, Set<String> changed,
                               Map<String, String> changes, Map<String, Line> index) throws IOException {
        Map<String, Object> before;
        try {
            before = load(String.join("\n", original) + "\n");
        } catch (IOException e) {
            return;
        }
        if (before == null) {
            return;
        }

        Map<String, Object> after;
        try {
            after = load(String.join("\n", lines) + "\n");
        } catch (IOException e) {
            String anchors = referencedAnchors(original, changed, index);
            throw new IOException("配置项 " + String.join(", ", changed) + " 改完后程序启动时读这份配置文件读不通"
                    + (anchors.isEmpty() ? "（多半是改到了带「*名字」「&名字」「<<:」这些引用别处的行）" : "（" + anchors + "）")
                    + ", 本批全部未保存, 请到配置文件里改");
        }
        if (after == null) {
            return;
        }

        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        List<String> dragged = new ArrayList<>();
        for (String key : keys) {
            if (!relatedToAny(key, changed) && !java.util.Objects.deepEquals(before.get(key), after.get(key))) {
                dragged.add(key);
            }
        }
        if (!dragged.isEmpty()) {
            throw new IOException("保存配置项 " + String.join(", ", changed) + " 会连带改掉 " + String.join(", ", dragged)
                    + " 启动时读到的值（它们在配置文件里折成了好几行、或带着「*名字」「&名字」「<<:」这类引用别处的记号）, 本批全部未保存, 请到配置文件里改");
        }

        List<String> off = new ArrayList<>();
        for (String key : changed) {
            String value = changes.get(key);
            if (value == null || (blankMeansAbsent.contains(key) && value.isBlank())) {
                continue;
            }
            Object actual = after.get(key);
            if (!java.util.Objects.deepEquals(expectedAsLoaded(value, actual instanceof List<?>), actual)) {
                off.add(key);
            }
        }
        if (!off.isEmpty()) {
            throw new IOException("配置项 " + String.join(", ", off)
                    + " 按界面的值写回后程序启动读到的不是这个值（多半是这一项的值经「<<:」或「*名字」从别处引来）, 本批全部未保存, 请到配置文件里改");
        }
    }

    /**
     * 一个键与这批改动有没有牵连：就是它、在它底下（子键、名单项），或是它的上级块
     */
    private static boolean relatedToAny(String key, Set<String> changed) {
        for (String path : changed) {
            if (key.equals(path) || key.startsWith(path + ".") || key.startsWith(path + "[")
                    || path.startsWith(key + ".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一个值照写口的写法（{@link #render}，名单每项一行）写出去，启动那一路读成什么
     */
    private Object expectedAsLoaded(String value, boolean asList) throws IOException {
        StringBuilder text = new StringBuilder("x:");
        if (asList) {
            for (String item : splitItems(value)) {
                text.append("\n  - ").append(render(item));
            }
        } else {
            text.append(' ').append(render(value));
        }
        Map<String, Object> loaded = load(text.append('\n').toString());
        return loaded == null ? null : loaded.get("x");
    }

    /**
     * 这批改动的键行上有哪些锚点被文件别处的别名引用着，写成一句话；没有时为空串
     */
    private static String referencedAnchors(List<String> original, Set<String> changed, Map<String, Line> index) {
        String text = String.join("\n", original);
        List<String> found = new ArrayList<>();
        for (String key : changed) {
            Line line = index.get(key);
            if (line == null || line.rawValue == null) {
                continue;
            }
            for (String token : line.rawValue.strip().split("\\s+")) {
                if (token.length() > 1 && token.charAt(0) == '&'
                        && Pattern.compile("\\*" + Pattern.quote(token.substring(1)) + "(?![^\\s,\\]}])").matcher(text).find()) {
                    found.add("配置项 " + key + " 上的锚点 " + token + " 被文件别处的 *" + token.substring(1)
                            + " 引用着, 在界面改值会去掉这个锚点");
                }
            }
        }
        return String.join("；", found);
    }

    /**
     * 修改列表中某一元素内部的字段
     * <p>
     * 列表元素内部的键不属于配置树的一级路径，{@link #write(Map)} 按设计会整段跳过。
     * 但机器人连接信息（地址、端口、Token）恰恰位于 {@code senders} 列表的元素内，
     * 且是唯一一批「不配置就跑不起来」的配置项，引导流程必须能写入它们。
     * <p>
     * 仍采用逐行定位替换：配置模板中的中文注释是使用者理解配置项的主要依据，
     * 用 YAML 库反序列化再写回会把注释、空行与顺序全部丢失。
     * <p>
     * 字段写成空串或全空白时<b>删掉该字段</b>，不留 {@code api: } 这种空值行。
     * 被删的若是元素首行（带 {@code -} 的那一行），短横顶到剩下的第一字段上，
     * 免得列表在这一处断开。一项的字段删尽则去掉整项，不留 {@code - {}} 或
     * 只有短横的空壳；若这是列表里最后一项，列表写成 {@code []}。
     *
     * <h2>列表还是空的时候，建出第一个元素来</h2>
     * 🔴 发行包不再带 application.yml，第一次保存时由 {@link #createIfAbsent()} 按配置面渲染一份，
     * 而配置面里这个列表的默认值是<b>空表</b>，渲染出来就是一行 {@code senders: []}。
     * 「找不到第 1 个元素」于是成了全新机器上的<b>必然</b>结果，而它的表现是引导流程第二步
     * 报一句「保存失败」——那台机器因此一步也走不下去。所以下标 0 且列表为空时建一个出来，
     * 字段与顺序由调用方给：写进去的必须是<b>整条</b>元素，缺了平台名的那一条会让下次启动直接失败。
     * @param listPath 列表的完整路径，由适配器申报
     * @param index 元素下标，从 0 开始
     * @param fields 待修改的字段名到取值，字段名为元素内部的键；建新元素时即为元素全文
     * @return 实际改动的字段数。建新元素时空值字段不写进文件、也不计入这个数，
     *         因此返回值可以小于 {@code fields.size()}
     * @throws IOException 读写失败时抛出
     */
    public synchronized int writeListItemFields(String listPath, int index, Map<String, String> fields) throws IOException {
        if (fields.isEmpty()) {
            return 0;
        }

        createIfAbsent();

        List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
        ListLocation location = locateListItem(lines, listPath, index);
        if (location == null) {
            throw new IOException("未在配置文件中找到 " + listPath);
        }

        rejectUnreadableInlineList(lines, location.keyLine(), listPath);
        List<Integer> emptyMarker = nextLineFlowList(lines, location, listPath);

        if (location.start() < 0) {
            if (index != 0) {
                throw new IOException("未在配置文件中找到 " + listPath + " 的第 " + (index + 1) + " 个元素");
            }

            for (int i = emptyMarker.size() - 1; i >= 0; i--) {
                lines.remove((int) emptyMarker.get(i));
            }
            int created = createFirstItem(lines, location, fields);
            backup();
            DurableFiles.replace(configPath, lines, DurableFiles.OWNER_ONLY);
            log.info("配置界面已在 {} 下建出第 1 个元素, 共 {} 个字段", listPath, created);
            return created;
        }

        int changed = 0;
        List<Integer> remove = new ArrayList<>();
        boolean dashGoes = false;
        String dashIndent = null;

        for (int i = location.start(); i < location.end(); i++) {
            String raw = lines.get(i);
            String stripped = raw.strip();
            boolean isDash = stripped.startsWith("-");
            String candidate = isDash ? stripped.substring(1).strip() : stripped;

            int colon = candidate.indexOf(':');
            if (colon < 0) {
                continue;
            }

            String key = candidate.substring(0, colon).strip();
            if (!fields.containsKey(key)) {
                continue;
            }

            String value = fields.get(key);
            if (value == null || value.isBlank()) {
                // 空值＝删字段，不留 `api: ` 这种空行：跟标量键清空即删行是同一条规则
                remove.add(i);
                if (isDash) {
                    dashGoes = true;
                    dashIndent = raw.substring(0, raw.indexOf('-'));
                }
                changed++;
                continue;
            }

            String replaced = replaceValue(raw, value);
            if (!replaced.equals(raw)) {
                lines.set(i, replaced);
                changed++;
            }
        }

        boolean anyKept = false;
        for (int i = location.start(); i < location.end(); i++) {
            if (remove.contains(i)) {
                continue;
            }
            String kept = lines.get(i);
            if (kept.isBlank() || kept.strip().startsWith("#")) {
                continue;
            }
            anyKept = true;
            break;
        }

        if (!anyKept && changed > 0) {
            // 这一项已经没有剩下的字段：整项去掉，不留空映射。
            dashGoes = false;
            remove.clear();
            for (int i = location.start(); i < location.end(); i++) {
                remove.add(i);
            }
            int itemIndent = indentOf(lines.get(location.start()));
            if (!hasSiblingListItem(lines, location, itemIndent)) {
                lines.set(location.keyLine(), withEmptyListMarker(lines.get(location.keyLine())));
            }
        } else if (dashGoes) {
            // 被删的是元素首行（带 "-" 的那一行），剩下的第一行要顶上这个短横，
            // 否则列表在这一处断开，后面的字段会被当成上一层的键。
            for (int i = location.start(); i < location.end(); i++) {
                if (remove.contains(i)) {
                    continue;
                }
                String kept = lines.get(i);
                if (kept.isBlank() || kept.strip().startsWith("#")) {
                    continue;
                }
                lines.set(i, dashIndent + "- " + kept.strip());
                break;
            }
        }

        for (int i = remove.size() - 1; i >= 0; i--) {
            lines.remove((int) remove.get(i));
        }

        if (changed > 0) {
            backup();
            DurableFiles.replace(configPath, lines, DurableFiles.OWNER_ONLY);
            log.info("配置界面已更新 {} 第 {} 个元素的 {} 个字段, 重启后生效", listPath, index + 1, changed);
        }

        return changed;
    }

    /**
     * 列表在配置文件里的位置
     *
     * @param keyLine 列表键那一行的行号
     * @param keyIndent 列表键的缩进
     * @param start 指定元素的起始行（含），该元素不存在时为 -1
     * @param end 指定元素的结束行（不含）
     */
    private record ListLocation(int keyLine, int keyIndent, int start, int end) {}

    /**
     * 对象列表的键这一行若是读不了的行内写法（各元素挤在同一对方括号里、或跨了行的），
     * 就地改字段只会动键那一行，半截方括号留在文件里会让整份配置读不了。
     * 一律拒存并说清原因；空表 {@code []} 与方括号里只有空白的 {@code [ ]} 不在其列——
     * 那是建出第一个元素的正常起点。
     */
    private void rejectUnreadableInlineList(List<String> lines, int keyLine, String listPath) throws IOException {
        String raw = lines.get(keyLine);
        String rest = raw.substring(raw.indexOf(':') + 1);
        int comment = commentIndex(rest);
        String onLine = (comment < 0 ? rest : rest.substring(0, comment)).strip();
        if (onLine.startsWith("[") && !onLine.replaceAll("\\s", "").equals("[]")) {
            throw new IOException(listPath + " 在文件里把各元素写在同一对方括号里, 本界面读不了"
                    + ", 为不写坏配置文件本批全部未保存, 请先在配置文件里把它改成每行一项");
        }
    }

    /**
     * 对象列表的键行空着、元素写在下一行的方括号里（{@code rules:} 换行 {@code [{name: a}]}）
     * <p>
     * 按「- 」逐行找元素的那一路看不见方括号里的元素，会当成空表在键下插一行新元素，
     * 旧的方括号留着，整份配置读不了。方括号里有东西的一律拒存，拒语用界面上这一格那把锁的说明；
     * 只有 {@code []} 的是空表，交回那几行由建第一个元素时换掉。
     * @return 空表 {@code []} 所占的行（不含注释行、空行）；不是这种写法时为空表
     * @throws IOException 方括号里有元素时抛出，文件未动
     */
    private List<Integer> nextLineFlowList(List<String> lines, ListLocation location, String listPath) throws IOException {
        String keyRaw = lines.get(location.keyLine());
        String rest = keyRaw.substring(keyRaw.indexOf(':') + 1);
        int comment = commentIndex(rest);
        if (!hasNoValue(comment < 0 ? rest : rest.substring(0, comment))) {
            return List.of();
        }

        int end = valueOnNextLineEnd(lines, location.keyLine(), location.keyIndent());
        List<Integer> valueLines = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int i = location.keyLine() + 1; i <= end; i++) {
            String stripped = lines.get(i).strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            int at = commentIndex(stripped);
            text.append((at < 0 ? stripped : stripped.substring(0, at)).strip());
            valueLines.add(i);
        }
        if (!text.toString().startsWith("[")) {
            return List.of();
        }
        if (text.toString().replaceAll("\\s", "").equals("[]")) {
            return valueLines;
        }

        String reason = settle(lines, parse(lines)).locked().get(listPath);
        throw new IOException("配置项 " + listPath + " " + unsavedReason(reason != null ? reason : LOCK_LIST_NESTED));
    }

    /**
     * 定位列表中某一元素所占的行范围
     * <p>
     * 「列表键在哪一行」与「那个元素在哪几行」一并回答，而不是分成两趟各走一遍：
     * 两趟就是同一条缩进规则的两个读者，其中一个哪天改了，另一个会安静地指到别处去。
     * @return 列表的位置；<b>连列表键都不在文件里时</b>返回 null——那与「列表是空的」是两件事，
     *         前者是配置文件本身不完整，后者只是还没配过第一台机器人
     */
    private ListLocation locateListItem(List<String> lines, String listPath, int index) {
        List<String> segments = List.of(listPath.split("\\."));
        List<String> stack = new ArrayList<>();

        int listIndent = -1;
        int keyLine = -1;
        // 列表项的缩进由第一个 "-" 决定，通常比列表键本身更深，不能假定二者相等
        int itemIndent = -1;
        int seen = -1;
        int start = -1;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.isBlank() || raw.strip().startsWith("#")) {
                continue;
            }

            int indent = indentOf(raw);
            String stripped = raw.strip();

            if (listIndent >= 0) {
                if (stripped.startsWith("-") && (itemIndent < 0 || indent == itemIndent)) {
                    itemIndent = indent;
                    if (start >= 0) {
                        return new ListLocation(keyLine, listIndent, start, i);
                    }
                    if (++seen == index) {
                        start = i;
                    }
                    continue;
                }

                // 缩进退回到列表键层级或更浅，说明列表已结束
                if (indent <= listIndent) {
                    return new ListLocation(keyLine, listIndent, start, start >= 0 ? i : -1);
                }
                continue;
            }

            int colon = stripped.indexOf(':');
            if (colon <= 0) {
                // 冒号缺失或位于行首都不是键定义，例如列表中的 IPv6 字面量
                continue;
            }

            String key = stripped.substring(0, colon).strip();
            int depth = indent / INDENT;
            while (stack.size() > depth) {
                stack.remove(stack.size() - 1);
            }
            stack.add(key);

            if (stack.equals(segments)) {
                listIndent = indent;
                keyLine = i;
            }
        }

        if (listIndent < 0) {
            return null;
        }

        return new ListLocation(keyLine, listIndent, start, start >= 0 ? lines.size() : -1);
    }

    /**
     * 在一个空列表下建出第一个元素
     * <p>
     * 空表在文件里写作 {@code senders: []}，那对方括号必须先去掉：留着它，
     * 新元素与它并存的那份文件<b>整个解析不了</b>，而接口这一侧照样回报「已保存」。
     * 行尾注释保留——它是使用者理解这一项的主要依据。
     * @param lines 文件行，就地修改
     * @param location 列表的位置
     * @param fields 元素全文，按传入顺序逐行写下
     * @return 实际写下的字段数。传入的空值字段既不写进文件、也不计入这个数，
     *         因此返回值可以小于 {@code fields.size()}。一项都没写时列表保持空表 {@code []}
     */
    private int createFirstItem(List<String> lines, ListLocation location, Map<String, String> fields) {
        String originalKey = lines.get(location.keyLine());
        lines.set(location.keyLine(), replaceValue(originalKey, "").stripTrailing());

        String indent = " ".repeat(location.keyIndent() + INDENT);
        int at = location.keyLine() + 1;
        boolean first = true;

        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (field.getValue() == null || field.getValue().isBlank()) {
                continue;
            }

            String rendered = render(field.getValue());
            String line = indent + (first ? "- " : "  ") + field.getKey() + ":"
                    + (rendered.isEmpty() ? "" : " " + rendered);
            lines.add(at++, line);
            first = false;
        }

        if (first) {
            lines.set(location.keyLine(), withEmptyListMarker(originalKey));
            return 0;
        }

        return fields.size() - (int) fields.values().stream()
                .filter(v -> v == null || v.isBlank()).count();
    }

    /**
     * 列表里指定元素之外还有没有别的元素
     */
    private boolean hasSiblingListItem(List<String> lines, ListLocation location, int itemIndent) {
        for (int i = location.keyLine() + 1; i < lines.size(); i++) {
            if (i >= location.start() && i < location.end()) {
                continue;
            }
            String raw = lines.get(i);
            if (raw.isBlank() || raw.strip().startsWith("#")) {
                continue;
            }
            int indent = indentOf(raw);
            if (indent <= location.keyIndent()) {
                if (i >= location.end()) {
                    break;
                }
                continue;
            }
            if (raw.strip().startsWith("-") && indent == itemIndent) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把列表键那一行写成空表 {@code []}，行尾注释留着
     * <p>
     * 不走 {@link #replaceValue}：那边会把以 {@code [} 开头的值加上引号，
     * 写成 {@code '[]'} 就不再是空表了。
     */
    private String withEmptyListMarker(String keyLine) {
        int colon = keyLine.indexOf(':');
        if (colon < 0) {
            return keyLine;
        }
        String rest = keyLine.substring(colon + 1);
        int comment = commentIndex(rest);
        String trailing = comment < 0 ? "" : rest.substring(comment);
        if (trailing.isEmpty()) {
            return keyLine.substring(0, colon + 1) + " []";
        }
        return keyLine.substring(0, colon + 1) + " [] " + trailing.strip();
    }

    /**
     * 配置文件的绝对路径，供界面显示「到服务器上改哪个文件」
     * <p>
     * 界面上不再提供配置文件编辑，取而代之的是一行路径。这行路径必须由本服务给出：
     * 界面自己拼一份的话，在换过工作目录或用 {@code -Dspring.config.location} 指过别处的部署里
     * 会指到一个并不生效的文件上，而使用者照着改完发现「怎么改都不生效」，
     * 却看不出是路径显示错了。
     * @return 绝对路径
     */
    public String describeConfigPath() {
        return configPath.toAbsolutePath().normalize().toString();
    }

    /**
     * 备份当前配置文件
     * <p>
     * 每次保存生成一份带时间戳的独立备份并保留最近若干份。此前只有单个 .bak 文件且每次覆盖，
     * 一旦连续保存两次，第一次保存前的内容就再也找不回来了。
     * 目录建不出新文件时（没有权限或文件系统只读）不备份也不挡保存：配置文件本身写得进的
     * 部署（只读的容器根上单独挂一个可写的配置文件）正是这样，备份先挡住保存会让配置改不了；
     * 这时往日志页记一条「没留备份」，见 {@link TimestampedFileBackup#backupForSave(int)}。
     * @throws IOException 目录建不出新文件之外的备份失败时抛出
     */
    private void backup() throws IOException {
        TimestampedFileBackup.BackupOutcome outcome = backups.backupForSave(backupKeep.getAsInt());
        if (outcome.skipped()) {
            timeline.record(outcome.skippedEvent("配置"));
            return;
        }
        if (outcome.pruned().isEmpty()) {
            return;
        }

        // 一份没删的时候什么也不记：每次保存都记一条「清理了 0 份」，
        // 会让日志页上真正删掉东西的那几条淹在里面
        timeline.record(TimelineEvent.of(TimelineEventType.BACKUP_PRUNED, TimelineEvent.Level.INFO)
                .text("配置备份留 " + backupKeep.getAsInt() + " 份，清掉最旧的 " + outcome.pruned().size() + " 份")
                .detail("keep", String.valueOf(backupKeep.getAsInt()))
                .detail("pruned", String.join(",", outcome.pruned()))
                .build());
    }

    /**
     * 保存失败时回给界面的那半句
     * <p>
     * 没权限或文件只读这一类翻成人话并点出配置文件本身（见 {@link SaveFailureText}），
     * 其余异常照旧回它自己的消息。各保存口子共用这一份，不各写各的。
     * @param failure 保存时抛出的异常
     * @return 给使用者看的一句话
     */
    public String describeSaveFailure(Exception failure) {
        return SaveFailureText.explain(failure, configPath);
    }

    /**
     * 替换一行中的值，保留缩进、键名与行尾注释
     * @param line 原始行
     * @param value 新值
     * @return 替换后的行
     */
    private String replaceValue(String line, String value) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return line;
        }

        String head = line.substring(0, colon + 1);
        String rest = line.substring(colon + 1);

        // 保留行尾注释；# 出现在引号内时不视为注释起点
        int comment = commentIndex(rest);
        String trailing = comment < 0 ? "" : rest.substring(comment);

        String rendered = render(value);
        if (trailing.isEmpty()) {
            return head + " " + rendered;
        }

        // 原有注释与值之间的空白宽度尽量保持，使注释仍然对齐
        int originalValueWidth = comment;
        int padding = Math.max(1, originalValueWidth - rendered.length() - 1);

        return head + " " + rendered + " ".repeat(padding) + trailing;
    }

    /**
     * 渲染值，必要时加引号
     * @param value 值
     * @return 渲染结果
     */
    private String render(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        // 按 YAML 的实际规则判断是否必须加引号，而不是见到冒号就加：
        // 冒号只有后接空格时才构成映射，因此 https://example 这类值无需引号。
        // 时:分这类「数字:数字」例外：冒号后无空格,但 SnakeYAML 按 YAML 1.1 六十进制把它读成整数。
        // 制表符、控制字符这类裸写不稳或读不了的字，只能在双引号里转义着写
        boolean needQuote = !value.strip().equals(value)
                || value.contains(": ") || value.endsWith(":")
                || value.contains(" #")
                || CLOCK_TIME.matcher(value).matches()
                || INDICATOR_START.indexOf(value.charAt(0)) >= 0
                || value.codePoints().anyMatch(cp -> escapeOf(cp) != null)
                || !readsBareAsWritten(value);

        if (!needQuote) {
            return value;
        }

        // 转义与读侧 unescapeDoubleQuoted 互逆：写出去按启动那一路读，读到的就是界面上的值
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        value.codePoints().forEach(cp -> {
            String escaped = cp == '\\' ? "\\\\" : cp == '"' ? "\\\"" : escapeOf(cp);
            if (escaped == null) {
                out.appendCodePoint(cp);
            } else {
                out.append(escaped);
            }
        });
        return out.append('"').toString();
    }

    /**
     * 这个值裸写出去，启动那一路读回的还是不是这几个字
     * <p>
     * yes、on、~、012、1_000、0x1F、1.50 这类字裸写会读成真假、空或另一个数，得加引号；
     * true、123 读回的字与写的一样，照旧裸写。哪些字会被读成别的样子不在这里列表，
     * 按启动那一路实际读一遍来定（它不认日期，{@code 2020-01-01} 读回的还是原字）。
     */
    private static boolean readsBareAsWritten(String value) {
        try {
            Map<String, Object> loaded = load("x: " + value + "\n");
            Object read = loaded == null ? null : loaded.get("x");
            return read != null && value.equals(String.valueOf(read));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 一个字要不要在双引号里转义着写，要的话怎么写
     * <p>
     * 可以原样写的是 SnakeYAML 认作可打印的字，去掉制表符、换行、回车、U+0085 与 U+2028、U+2029：
     * 这几样 SnakeYAML 都当换行，裸写在值里要么被当成空白折掉，要么断行、整份文件读不了。
     * 其余一律转义，启动那一路读得回原字。
     * @return 转义写法；原样写即可时为 null
     */
    private static String escapeOf(int cp) {
        if (((cp >= 0x20 && cp <= 0x7E) || (cp >= 0xA0 && cp <= 0xD7FF)
                || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF))
                && cp != 0x2028 && cp != 0x2029) {
            return null;
        }
        return switch (cp) {
            case 0 -> "\\0";
            case '\t' -> "\\t";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            default -> cp <= 0xFF ? String.format("\\x%02X", cp) : String.format("\\u%04X", cp);
        };
    }

    /**
     * 找出一行中注释的起始位置
     * <p>
     * 规矩见 {@link #scan}：It's 里的撇号不开引号，{@code ["a #b",} 里方括号后的引号才开。
     * @param text 冒号之后的内容
     * @return 注释起始下标，无注释时返回 -1
     */
    private static int commentIndex(String text) {
        return scan(text, false).comment();
    }

    /**
     * 一段值原文的扫描结果
     *
     * @param outside 各字是否在引号外（引号本身、注释及其后都不算）
     * @param comment 注释起始下标，无注释时为 -1
     */
    private record ValueScan(boolean[] outside, int comment) {}

    /**
     * 扫一段值原文：哪些字在引号外，行尾注释从哪起
     * <p>
     * {@link #commentIndex}、{@link #flowDepth}、{@link #splitFlowItems} 三处共用这一把尺，
     * 规矩只此一份（各写一份的话，哪天一处改了，界面读出的项会与文件悄悄对不上）：
     * <ul>
     *   <li>引号只在<b>一项的开头</b>才是开引号——值的首位，或行内序列里 {@code [ { ,} 之后
     *       跳过空白的第一个字。词中间的撇号、双引号是普通字：{@code [don't, x]} 是两项。</li>
     *   <li>双引号里 {@code \} 连同下一个字一起算，单引号里 {@code ''} 是一个撇号，都不闭引号。</li>
     *   <li>{@code #} 只在引号外、且在首位或前面是空白时才起注释：{@code a#b} 整个是值。</li>
     * </ul>
     * @param text 值原文
     * @param inFlow 这段原文已在行内序列里（续行、方括号里面那段）时为 true；
     *               为 false 时首个字是 {@code [} 或 {@code {} 也就进了行内序列
     * @return 扫描结果
     */
    private static ValueScan scan(String text, boolean inFlow) {
        boolean[] outside = new boolean[text.length()];
        boolean flow = inFlow;
        char quote = 0;
        // 引号外上一个非空白字；0 表示还没有，那时遇到的引号就在一项的开头
        char last = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote > 0) {
                if (quote == '"' && c == '\\') {
                    i++;
                } else if (c == quote) {
                    if (quote == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        quote = 0;
                        last = c;
                    }
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                outside[i] = true;
                continue;
            }
            if (c == '#' && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) {
                return new ValueScan(outside, i);
            }
            if ((c == '\'' || c == '"') && (last == 0 || (flow && "[{,".indexOf(last) >= 0))) {
                quote = c;
                continue;
            }
            if (last == 0 && (c == '[' || c == '{')) {
                flow = true;
            }
            outside[i] = true;
            last = c;
        }

        return new ValueScan(outside, -1);
    }

    /**
     * 将配置文件中尚不存在的配置项插入到最近的已有祖先之下
     * @param lines 文件行
     * @param path 配置项完整路径
     * @param value 值
     * @return 是否插入成功
     * @throws IOException 键名段含控制字符或 YAML 结构字符时抛出，整批不落盘
     */
    private boolean insert(List<String> lines, String path, String value) throws IOException {
        rejectUnsafeKeySegments(path);
        String[] segments = path.split("\\.");

        // 自最深的祖先开始向上寻找已经存在的父节点
        for (int depth = segments.length - 1; depth >= 1; depth--) {
            String parent = String.join(".", java.util.Arrays.copyOfRange(segments, 0, depth));

            Line parentLine = null;
            for (Line line : parse(lines)) {
                if (parent.equals(line.path)) {
                    parentLine = line;
                    break;
                }
            }

            if (parentLine == null) {
                continue;
            }

            // 定位父节点块的末尾
            int insertAt = parentLine.index + 1;
            while (insertAt < lines.size()) {
                String candidate = lines.get(insertAt);
                if (candidate.isBlank()) {
                    insertAt++;
                    continue;
                }
                if (indentOf(candidate) <= parentLine.indent) {
                    break;
                }
                insertAt++;
            }

            // 补齐父节点与目标之间缺失的中间层级
            List<String> inserted = new ArrayList<>();
            int indent = parentLine.indent + INDENT;
            for (int i = depth; i < segments.length - 1; i++) {
                inserted.add(" ".repeat(indent) + segments[i] + ":");
                indent += INDENT;
            }

            String leaf = segments[segments.length - 1];
            List<String> items = splitItems(value);

            if (items.size() > 1 || value.contains("\n")) {
                // 多行值代表字符串列表，需写成 YAML 列表而非带引号的多行标量
                inserted.add(" ".repeat(indent) + leaf + ":");
                for (String item : items) {
                    inserted.add(" ".repeat(indent + INDENT) + "- " + render(item));
                }
            } else {
                inserted.add(" ".repeat(indent) + leaf + ": " + render(value));
            }

            lines.addAll(insertAt, inserted);
            return true;
        }

        // 产品前缀改名后，只写着上一档根的既有文件里没有 novabot 这一层。
        // 写现行键时把根建在文件末尾，中间层级一并补齐。
        if (segments.length >= 2 && NovaBotPrefixes.CORE.startsWith(segments[0] + ".")) {
            int insertAt = lines.size();
            List<String> inserted = new ArrayList<>();
            int indent = 0;
            for (int i = 0; i < segments.length - 1; i++) {
                inserted.add(" ".repeat(indent) + segments[i] + ":");
                indent += INDENT;
            }
            String leaf = segments[segments.length - 1];
            List<String> items = splitItems(value);
            if (items.size() > 1 || value.contains("\n")) {
                inserted.add(" ".repeat(indent) + leaf + ":");
                for (String item : items) {
                    inserted.add(" ".repeat(indent + INDENT) + "- " + render(item));
                }
            } else {
                inserted.add(" ".repeat(indent) + leaf + ": " + render(value));
            }
            lines.addAll(insertAt, inserted);
            return true;
        }

        return false;
    }

    /**
     * 键名段不许夹控制字符与 YAML 结构字符
     * <p>
     * 插入时各段按原样拼成「段名:」行写出去。键名里带换行的话，换行之后那半截会变成文件里
     * 另一把键；带 {@code :}、{@code #}、引号同理。这不是值侧的事——值那边
     * {@link #rejectMultilineScalars} 已经挡了——是键名自己会改写文件结构。
     * @param path 配置项完整路径
     * @throws IOException 有一段不合规时抛出
     */
    private static void rejectUnsafeKeySegments(String path) throws IOException {
        // limit -1：末段为空也要看见，否则「foo.」这种会被当成合法的 foo
        String[] segments = path.split("\\.", -1);
        for (String segment : segments) {
            if (!SAFE_KEY_SEGMENT.matcher(segment).matches()) {
                throw new IOException("配置项 " + path + " 的名字只能用字母、数字、短横和下划线, 别的字符不允许, 本批全部未保存");
            }
        }
    }

    /**
     * 一个键在文件里占到哪一行为止（含）
     * <p>
     * 键行之后比它缩进更深的行都是它的续行——跨行名单、跨行的普通值、名单各项——
     * 直到下一个缩进不深于键行的非空、非注释行；夹在当中的空行与注释随块走，
     * 块尾之后的留给下一项（那多半是下一项的说明）。名单另记着收口行，续行可以不比键深，两者取靠后的。
     * <p>
     * 删一项、改一个跨行的值、整块换名单都按这里删：只动键那一行的话，续行留在原处，
     * 要么整份文件读不了，要么续行并进上一个键、悄悄改了它的值。
     */
    private int blockEnd(List<String> lines, Line line) {
        int end = Math.max(line.index, line.listEnd);
        for (int i = end + 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.isBlank() || raw.strip().startsWith("#")) {
                continue;
            }
            if (indentOf(raw) <= line.indent) {
                break;
            }
            end = i;
        }
        return end;
    }

    /**
     * 键行上有没有值：去掉行尾注释后为空，或只剩锚点、标签（{@code &名}、{@code !标签}、{@code !!类型}，
     * 可叠写、次序不拘）都算没有——值在底下几行，是块名单、子项，或写在下一行的文字
     * <p>
     * 开收块名单（{@link #parse}）与改值时认上级块（{@link #write}）共用这一个判定：
     * 两处各判一份的话，锚点名单会界面读成「&w」而改值那头照名单处理，或者反过来。
     * @param value 键行冒号后的原文，已去行尾注释
     */
    private static boolean hasNoValue(String value) {
        for (String token : value.strip().split("\\s+")) {
            if (!token.isEmpty() && token.charAt(0) != '&' && token.charAt(0) != '!') {
                return false;
            }
        }
        return true;
    }

    /**
     * 键行底下第一行实义行（跳过空行、注释）是不是子项：更深缩进的「键:」，或名单项「- 」
     * （块名单的项可以与键同缩进）。不是的话底下是续行文字，或者什么也没有
     * <p>
     * 方括号打头的是写在下一行的行内名单，里面的 {@code {k: v}} 像「键: 」也不是子键。
     */
    private boolean holdsChildren(List<String> lines, Line line) {
        for (int i = line.index + 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            String stripped = raw.strip();
            if (raw.isBlank() || stripped.startsWith("#")) {
                continue;
            }
            if (stripped.equals("-") || stripped.startsWith("- ")) {
                return indentOf(raw) >= line.indent;
            }
            if (stripped.startsWith("[")) {
                return false;
            }
            return indentOf(raw) > line.indent && CHILD_KEY.matcher(stripped).find();
        }
        return false;
    }

    /**
     * 整块替换一个字符串列表
     * @param lines 文件行
     * @param line 列表所属的键
     * @param value 换行分隔的新内容
     * @return 是否发生变更
     */
    private boolean replaceList(List<String> lines, Line line, String value) {
        // 界面读出的原样送回（名单里有空项时带空行）就是没改：不动文件。真改了才按下面去掉空行、整块重写
        List<String> asShown = new ArrayList<>();
        for (String item : value.split("\n", -1)) {
            asShown.add(item.strip());
        }
        if (asShown.equals(line.items)) {
            return false;
        }

        List<String> items = new ArrayList<>();
        for (String item : value.split("\n")) {
            if (!item.isBlank()) {
                items.add(item.strip());
            }
        }

        if (items.equals(line.items)) {
            return false;
        }

        int itemIndent = indentOf(lines.get(line.index)) + INDENT;

        List<String> replacement = new ArrayList<>();
        for (String item : items) {
            replacement.add(" ".repeat(itemIndent) + "- " + render(item));
        }

        // 先删除原有的列表项，再插入新的
        lines.subList(line.index + 1, blockEnd(lines, line) + 1).clear();
        lines.addAll(line.index + 1, replacement);

        if (items.isEmpty()) {
            // 清空写成 [] 而不是裸键：键这一行保住列表身份，下一次想填回多项时
            // 才不会被当成标量拦下；对象列表清空走的也是这个写法
            lines.set(line.index, withEmptyListMarker(lines.get(line.index)));
        } else if (line.flowInline) {
            // 行内序列（单行写就或跨行收口）：键这一行上的方括号要让位给块序列——
            // 方括号与「- 项」并存的那份文件整个解析不了；跨行时续行已在上面整块删去
            lines.set(line.index, replaceValue(lines.get(line.index), "").stripTrailing());
        }

        return true;
    }

    /**
     * 将界面提交的多行文本拆分为列表项
     * @param value 多行文本
     * @return 列表项
     */
    private List<String> splitItems(String value) {
        List<String> items = new ArrayList<>();

        if (value == null) {
            return items;
        }

        for (String item : value.split("\n")) {
            if (!item.isBlank()) {
                items.add(item.strip());
            }
        }

        return items;
    }

    /**
     * 行内序列写法（{@code key: []}、{@code key: [a, b]}）的各项
     * <p>
     * 首次安装写出的配置与对象列表清空后都会留下 {@code []}，那是空列表的合法写法，
     * 得按列表读写：当普通文字读回的话，每行一项的名单框会显示字面「[]」、一次填不进多行。
     * 引号包着的 {@code "[]"} 是逐字的文字值，不在其列；元素自身是对象或嵌套列表的
     * （界面上不编辑的那类）也不收，维持普通文字——但那要看它有没有带引号：引号里的
     * {@code "{name} 开播了"} 只是文字，照收；不带引号、真套着对象的才不收。
     * 不带引号的 {@code 键: 值} 是流式键值对（嵌套映射的写法），同样不收。
     * 尾逗号与连续逗号只是分隔符的痕迹，不产生空项。
     *
     * @param raw 冒号后去掉行尾注释的原文，未去引号（跨行写法须先把续行并入）
     * @return 列表各项；不是行内序列时返回 null，按普通文字值处理
     */
    private List<String> flowSequenceItems(String raw) {
        if (raw.length() < 2 || raw.charAt(0) != '[' || raw.charAt(raw.length() - 1) != ']') {
            return null;
        }

        String inner = raw.substring(1, raw.length() - 1);
        if (inner.isBlank()) {
            return new ArrayList<>();
        }

        List<String> items = new ArrayList<>();
        for (String item : splitFlowItems(inner)) {
            String strippedItem = item.strip();
            if (strippedItem.isEmpty()) {
                // 尾逗号、连续逗号是分隔符的痕迹，不是空项：名单框不该因此多出空行
                continue;
            }
            boolean quoted = strippedItem.charAt(0) == '"' || strippedItem.charAt(0) == '\'';
            String bare = unquote(strippedItem);
            if (!quoted && (bare.startsWith("{") || bare.startsWith("["))) {
                return null;
            }
            if (!quoted && (bare.contains(": ") || bare.endsWith(":"))) {
                return null;
            }
            items.add(bare);
        }
        return items;
    }

    /**
     * 行内序列跨行时的续行扫描结果
     *
     * @param joined      键行的值与续行以空格接起来的整段原文
     * @param end         收口那一行的下标
     * @param interrupted 中间夹了空行或注释行、收不了口：joined 与 end 不用，只报这一种情形
     */
    private record FlowTail(String joined, int end, boolean interrupted) {}

    /**
     * 行内序列跨了行（{@code key: [a,} 换行 {@code b]}）时把续行并入：行内序列里的
     * 换行只是空白，跨行写法与写在键那一行等价。并入后按同一把尺
     * {@link #flowSequenceItems} 判，读不了的写法由调用方标成不可存。
     * <p>
     * 扫到空行、注释行，或缩进退到键这一层及更浅的键/列表项仍未收口即停：
     * 那种文件按收不了口处理，保存时整批拒绝，绝不留下只有半截的行内序列。
     * 空行、注释行是夹在当中把名单截断的，与其余收不了口分开报，拒语才说得准。
     *
     * @param lines     文件行
     * @param keyIndex  键所在行的下标
     * @param keyIndent 键的缩进宽度
     * @param firstValue 键这一行冒号后的值（未收口的行内序列开头）
     * @return 收口后的整段原文与末行下标；中间夹了空行或注释行时带着这一情形返回；别的收不了口为 null
     */
    private FlowTail joinFlowTail(List<String> lines, int keyIndex, int keyIndent, String firstValue) {
        StringBuilder joined = new StringBuilder(firstValue);
        int depth = flowDepth(firstValue);
        for (int i = keyIndex + 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            String stripped = raw.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                // 夹在当中的空行、注释行：joined 与 end 不用，只带这一情形回去
                return new FlowTail("", -1, true);
            }
            if (indentOf(raw) <= keyIndent
                    && (stripped.startsWith("-") || stripped.contains(": ") || stripped.endsWith(":"))) {
                return null;
            }
            // 续行开头也是一项的开头：上一行停在逗号或方括号上
            int comment = scan(stripped, true).comment();
            String value = (comment < 0 ? stripped : stripped.substring(0, comment)).strip();
            joined.append(' ').append(value);
            depth += flowDepth(value);
            if (depth <= 0) {
                return new FlowTail(joined.toString(), i, false);
            }
        }
        return null;
    }

    /**
     * 跨行夹了空行、注释行的行内名单的原文整段：跳过夹着的那几行，续行以空格接起来，
     * 到收口或扫到下一个键、名单项为止。启动那一路读不出这份名单时拿它显示，不只显示键那一行的半截
     */
    private String flowSpan(List<String> lines, Line line) {
        StringBuilder joined = new StringBuilder(line.rawValue);
        int depth = flowDepth(line.rawValue);
        for (int i = line.index + 1; i < lines.size() && depth > 0; i++) {
            String raw = lines.get(i);
            String stripped = raw.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            if (indentOf(raw) <= line.indent
                    && (stripped.startsWith("-") || stripped.contains(": ") || stripped.endsWith(":"))) {
                break;
            }
            int comment = scan(stripped, true).comment();
            String value = (comment < 0 ? stripped : stripped.substring(0, comment)).strip();
            joined.append(' ').append(value);
            depth += flowDepth(value);
        }
        return joined.toString();
    }

    /**
     * 数一段行内原文里未收口的方括号层数，引号内的不算（引号怎么认见 {@link #scan}）
     */
    private static int flowDepth(String text) {
        boolean[] outside = scan(text, true).outside();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!outside[i]) {
                continue;
            }
            char c = text.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
        }
        return depth;
    }

    /**
     * 去掉值（标量或列表项）两侧的引号，并按 YAML 引号规则还原内容：
     * 双引号里的转义见 {@link #unescapeDoubleQuoted}，单引号里的 {@code ''} 是一个撇号。
     * <p>
     * 界面上看到的得是程序启动时读到的那个值：只去引号不还原的话，
     * {@code "say \"hi\""} 在界面上是 {@code say \"hi\"}，照它存回去重启读到的就多了反斜杠。
     * 写出（{@link #render}）按同一套规则转义，存一次再读回还是原来的值。
     * 不加引号的值原样返回。
     *
     * @param value 未去引号的值
     * @return 还原后的内容
     */
    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return unescapeDoubleQuoted(value.substring(1, value.length() - 1));
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }

    /**
     * 还原双引号里的转义：YAML 的单字转义（{@code \0 \a \b \t \n \v \f \r \e \" \/ \\}、
     * 转义空格与制表符、{@code \N \_ \L \P}）与 {@code \xNN}、<code>&#92;uNNNN</code>、{@code \UNNNNNNNN}。
     * （javadoc 里那个 u 转义写成实体：源码里反斜杠直接跟 u 会被编译器当成 Unicode 转义）
     * 认不出的转义连反斜杠原样留着，不猜它想写什么。
     * <p>
     * {@code \/} 是 YAML 1.2 的写法，启动那一路（SnakeYAML，YAML 1.1）不认、整份文件读不了；
     * 这里照样还原，界面至少显示得对。
     */
    private static String unescapeDoubleQuoted(String body) {
        StringBuilder out = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\' || i + 1 >= body.length()) {
                out.append(c);
                continue;
            }
            char e = body.charAt(i + 1);
            String single = switch (e) {
                case '0' -> "\0";
                case 'a' -> "\u0007";
                case 'b' -> "\b";
                case 't', '\t' -> "\t";
                case 'n' -> "\n";
                case 'v' -> "\u000B";
                case 'f' -> "\f";
                case 'r' -> "\r";
                case 'e' -> "\u001B";
                case ' ' -> " ";
                case '"' -> "\"";
                case '/' -> "/";
                case '\\' -> "\\";
                case 'N' -> "\u0085";
                case '_' -> " ";
                case 'L' -> " ";
                case 'P' -> " ";
                default -> null;
            };
            if (single != null) {
                out.append(single);
                i++;
                continue;
            }
            int digits = e == 'x' ? 2 : e == 'u' ? 4 : e == 'U' ? 8 : 0;
            int from = i + 2;
            if (digits > 0 && from + digits <= body.length()
                    && body.substring(from, from + digits).matches("[0-9A-Fa-f]+")) {
                long code = Long.parseLong(body.substring(from, from + digits), 16);
                if (code <= Character.MAX_CODE_POINT) {
                    out.appendCodePoint((int) code);
                    i = from + digits - 1;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 拆行内序列的各项：引号里的逗号是字面字符，不是分隔符（引号怎么认见 {@link #scan}）
     * @param inner 方括号内的原文
     * @return 按分隔符切开的各项，未去引号
     */
    private List<String> splitFlowItems(String inner) {
        boolean[] outside = scan(inner, true).outside();
        List<String> items = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            if (outside[i] && inner.charAt(i) == ',') {
                items.add(inner.substring(start, i));
                start = i + 1;
            }
        }
        items.add(inner.substring(start));
        return items;
    }

    /**
     * 解析配置文件
     * @return 行信息列表
     * @throws IOException 读取失败时抛出
     */
    private List<Line> parse() throws IOException {
        return parse(Files.readAllLines(configPath, StandardCharsets.UTF_8));
    }

    /**
     * 解析给定的文件行
     * <p>
     * 列表项内部的键属于元素对象而非配置树的一级路径，整段跳过。
     * @param lines 文件行
     * @return 行信息列表
     */
    private List<Line> parse(List<String> lines) {
        List<Line> result = new ArrayList<>();
        List<String> stack = new ArrayList<>();
        int listIndent = -1;
        // 正在收块名单项的键：键行上没有值，其后的「- 项」都归它。夹在当中的注释、空行不打断——
        // 启动那一路照样把它们后面的项收进同一份名单；遇上不是名单项的行才收口
        Line collecting = null;
        // 上一个收进名单的项：底下更深的行是它的续行，这一项的值要按启动那一路读（见 settle）
        Line itemOwner = null;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            int colon = raw.indexOf(':');
            if (raw.isBlank() || raw.strip().startsWith("#")) {
                continue;
            }

            int indent = indentOf(raw);
            String stripped = raw.strip();

            if (listIndent >= 0) {
                if (indent > listIndent) {
                    if (itemOwner != null) {
                        itemOwner.loaderItems.add(itemOwner.items.size() - 1);
                    }
                    continue;
                }
                listIndent = -1;
            }
            itemOwner = null;

            if (stripped.startsWith("-")) {
                listIndent = indent;

                // 形如 "- 值" 的标量项归属于上一个键；形如 "- 键: 值" 的是对象列表，不予收集。
                // 不能简单地以「是否含冒号」区分：IPv6 地址本身就带冒号。
                // 项后的行尾注释按 scan 那把尺去掉（引号里的 # 不算），再去引号还原；
                // 只有短横的空项（「-」「- # 注释」）启动那一路读成空串、照样占一项，这里也收成空串
                String item = stripped.substring(1).strip();
                if (collecting != null && indent >= collecting.indent && !OBJECT_ITEM.matcher(item).find()) {
                    int comment = commentIndex(item);
                    String bare = (comment < 0 ? item : item.substring(0, comment)).strip();
                    collecting.items.add(unquote(bare));
                    collecting.listEnd = i;
                    itemOwner = collecting;
                    if (needsLoader(bare)) {
                        collecting.loaderItems.add(collecting.items.size() - 1);
                    }
                } else {
                    collecting = null;
                }
                continue;
            }

            collecting = null;
            if (colon < 0) {
                continue;
            }

            String key = stripped.substring(0, stripped.indexOf(':')).strip();
            String rest = stripped.substring(stripped.indexOf(':') + 1);
            int comment = commentIndex(rest);
            String value = (comment < 0 ? rest : rest.substring(0, comment)).strip();

            int depth = indent / INDENT;
            while (stack.size() > depth) {
                stack.remove(stack.size() - 1);
            }
            stack.add(key);

            Line line = new Line();
            line.index = i;
            line.indent = indent;
            line.path = String.join(".", stack);
            line.value = unquote(value);
            line.rawValue = value;
            line.merge = key.equals("<<");
            line.quoted = value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                    && value.charAt(value.length() - 1) == value.charAt(0);
            if (hasNoValue(value)) {
                if (holdsChildren(lines, line)) {
                    collecting = line;
                } else {
                    // 值写在下一行（键行空着或只有锚点、标签）：底下是续行文字，不是子项。
                    // 注释行不论缩进都跳过，跟读取配置文件那一路一样
                    int end = valueOnNextLineEnd(lines, i, indent);
                    line.fromLoader = end > i || !value.isEmpty();
                    i = end;
                    if (!line.fromLoader) {
                        collecting = line;
                    }
                }
            } else if (!value.startsWith("[") && !value.startsWith("{")) {
                // 键行上有值的标量：底下更深的行都是它的续行（跨行的值、块标量内容），
                // 里面像「词: 」「- 项」的不是键、不是名单项，整段跳过
                int end = continuationEnd(lines, i, indent);
                line.fromLoader = end > i || needsLoader(value);
                i = end;
            }

            // 行内序列（key: []、key: [a, b]）：列表整个写在键这一行上，也按字符串列表收下。
            // 收下的各项也照启动那一路比一遍（见 settle）：键行上与写在下一行的同一把尺，
            // 写的字程序读成别的值的（如 yes 读成 true），锁住、显示读到的，不随写的位置变。
            // 有一项是别名、带锚点或标签的，收下的是字面文字、不是程序读到的值；套着名单或键值的收不下。
            // 这两种都按启动那一路读（见 settle）
            List<String> flowItems = flowSequenceItems(value);
            if (flowItems != null && flowItemMark(value).isEmpty()) {
                line.items.addAll(flowItems);
                line.listEnd = i;
                line.flowInline = true;
                line.fromLoader = true;
            } else if (flowItems != null || (value.startsWith("[") && flowDepth(value) == 0 && hasPlainFlowItem(value))) {
                line.fromLoader = true;
            } else if (value.startsWith("[") && flowDepth(value) > 0) {
                // 行内序列跨了行（key: [a, 换行 b]）：续行并入后按同一把尺判；
                // 收不了口或并入后读不了的（嵌套对象/列表、流式键值对），标成不可存，
                // 保存时整批拒绝——只改键那一行会给文件留下半截方括号，整份配置从此读不了
                FlowTail tail = joinFlowTail(lines, i, indent, value);
                if (tail == null) {
                    // 扫到下一个键、名单项或文件尾都没收口
                    line.flowUnreadable = true;
                    line.flowUnclosed = true;
                } else if (tail.interrupted()) {
                    // 中间夹了空行或注释行而收不了口：里面并没有套着的写法，保存的拒语照这一情形说
                    line.flowUnreadable = true;
                    line.flowInterrupted = true;
                } else {
                    List<String> across = flowSequenceItems(tail.joined());
                    if (across == null || !flowItemMark(tail.joined()).isEmpty()) {
                        line.flowUnreadable = true;
                        line.fromLoader = across != null || hasPlainFlowItem(tail.joined());
                    } else {
                        line.items.addAll(across);
                        line.listEnd = tail.end();
                        line.flowInline = true;
                        line.fromLoader = true;
                    }
                    i = tail.end();
                }
            }
            // 写在下一行的行内名单，没有一项是别名、带锚点或标签（引号里的星号是普通文字）：按名单收下才改得了。
            // 收下的各项与启动读到的对不上时，settle 退回这套名单并锁住
            if (!line.flowInline && line.fromLoader && hasNoValue(value)) {
                String nextLine = flowListText(lines, line);
                List<String> nextLineItems = nextLine.startsWith("[") && flowItemMark(nextLine).isEmpty()
                        && aliasItemInFlowList(lines, line) == null ? flowSequenceItems(nextLine) : null;
                if (nextLineItems != null) {
                    line.items.addAll(nextLineItems);
                    line.listEnd = valueOnNextLineEnd(lines, line.index, line.indent);
                    line.flowInline = true;
                }
            }
            result.add(line);
        }

        return result;
    }

    /**
     * 键行（或名单项）之后更深缩进的连续行到哪一行为止（含）；一行也没有时就是键行自己
     * <p>
     * 夹在当中的空行随块走，块尾的空行不算；更深的 {@code #} 行在块标量里是内容，一样算进来。
     * 缩进不深于键的注释行在这里是块的尽头：块标量、键行上已有值的跨行值用这把尺。
     * 「值在下一行」另用 {@link #valueOnNextLineEnd}，注释行不论缩进都跳过。
     * @param lines 文件行
     * @param keyIndex 键行下标
     * @param keyIndent 键行缩进
     * @return 最后一行续行的下标
     */
    private int continuationEnd(List<String> lines, int keyIndex, int keyIndent) {
        return scanContinuation(lines, keyIndex, keyIndent, false);
    }

    /**
     * 「值在下一行」的续行到哪一行为止（含）。注释行不论缩进多深都跳过，
     * 跟读取配置文件时注释不算数一样；缩进不深于键的非注释行才是尽头。
     * @param lines 文件行
     * @param keyIndex 键行下标
     * @param keyIndent 键行缩进
     * @return 最后一行续行的下标；底下没有值时就是键行自己
     */
    private int valueOnNextLineEnd(List<String> lines, int keyIndex, int keyIndent) {
        return scanContinuation(lines, keyIndex, keyIndent, true);
    }

    private int scanContinuation(List<String> lines, int keyIndex, int keyIndent, boolean skipComments) {
        int end = keyIndex;
        for (int k = keyIndex + 1; k < lines.size(); k++) {
            String raw = lines.get(k);
            if (raw.isBlank()) {
                continue;
            }
            if (skipComments && raw.strip().startsWith("#")) {
                continue;
            }
            if (indentOf(raw) <= keyIndent) {
                break;
            }
            end = k;
        }
        return end;
    }

    /**
     * 这一项若是行内名单、且其中一项是没加引号的别名，返回那个别名记号；否则返回 null
     * （整项写成别名，或名单里没有别名项，仍用整项那句说明）。
     * 加了引号的 {@code "*s"}、{@code '*s'} 是普通文字，不是别名。键行上与写在下一行的行内名单同一把尺。
     */
    private String aliasItemInFlowList(List<String> lines, Line line) {
        List<String> items = rawFlowItems(lines, line);
        if (items == null) {
            return null;
        }
        for (String item : items) {
            if (item.charAt(0) == '"' || item.charAt(0) == '\'') {
                continue;
            }
            String token = aliasToken(item);
            if (!token.isEmpty()) {
                return token;
            }
        }
        return null;
    }

    /**
     * 行内名单里，能认成别名记号的片段全都加了引号：星号可以在项的开头，
     * 也可以在引号里、空格的后面。那是普通文字，不是别名。
     * 没有这种项时返回 false，名单原有的说明不动。
     */
    private boolean starOnlyInsideQuotes(List<String> lines, Line line) {
        List<String> items = rawFlowItems(lines, line);
        if (items == null) {
            return false;
        }
        boolean sawQuotedStar = false;
        for (String item : items) {
            boolean quoted = item.charAt(0) == '"' || item.charAt(0) == '\'';
            if (aliasToken(quoted ? unquote(item) : item).isEmpty()) {
                continue;
            }
            if (!quoted) {
                return false;
            }
            sawQuotedStar = true;
        }
        return sawQuotedStar;
    }

    /**
     * 这一行已按名单收下，且收下的各项与启动读到的名单逐项相同。
     * 嵌套名单、项上的锚点或标签收下来的文字和程序读到的对不上，不能当普通名单放开。
     */
    private static boolean flowListMatchesLoaded(Line line, List<?> loaded) {
        if (!line.flowInline || line.items.size() != loaded.size()) {
            return false;
        }
        for (int i = 0; i < loaded.size(); i++) {
            Object item = loaded.get(i);
            if (item == null || item instanceof List || item instanceof Map) {
                return false;
            }
            if (!line.items.get(i).equals(String.valueOf(item))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 启动读到的是名单的一项，界面为什么改不了；改得了时返回 null。
     * 说明照文件里实际的写法说：项是别名、项带锚点或标签、项套着名单或键值、名单整份带锚点或标签、
     * 写的字和读到的不一样。文件里没有别名就不说别名；整项写成别名（引到一份名单）才说「写成了别名」。
     */
    private String flowListLock(List<String> lines, Line line, List<?> loaded, boolean nested) {
        String written = flowWritten(lines, line);
        if (!bodyOf(written).startsWith("[")) {
            return lockAlias(aliasWritten(lines, line));
        }
        String itemAlias = aliasItemInFlowList(lines, line);
        String itemMark = itemAlias != null ? itemAlias : flowItemMark(written);
        if (!itemMark.isEmpty()) {
            return itemMark.startsWith("*") ? lockAliasItem(itemMark) : lockMarkedItem(itemMark);
        }
        if (nested) {
            return LOCK_LIST_NESTED;
        }
        if (!bodyOf(written).equals(written.strip())) {
            return lockMarkedList(markToken(written.strip()));
        }
        return flowListMatchesLoaded(line, loaded) ? null : LOCK_LIST_READ_DIFFERENTLY;
    }

    /**
     * 行内名单的整段原文：键行上的（跨了行的把续行并进来），或写在下一行的那一段
     */
    private String flowWritten(List<String> lines, Line line) {
        if (line.rawValue != null && !hasNoValue(line.rawValue) && flowDepth(line.rawValue) > 0) {
            FlowTail tail = joinFlowTail(lines, line.index, line.indent, line.rawValue);
            if (tail != null && !tail.interrupted()) {
                return tail.joined();
            }
        }
        return flowListText(lines, line);
    }

    /**
     * 行内名单里第一处不在引号里、以别名、锚点或标签记号（{@code *}、{@code &}、{@code !}）打头的项，
     * 返回那个记号；没有时为空串。引号里的是普通文字，不算；写在整份名单前的记号不在此列。
     */
    private String flowItemMark(String text) {
        String body = bodyOf(text);
        if (!body.startsWith("[")) {
            return "";
        }
        String inner = body.endsWith("]") ? body.substring(1, body.length() - 1) : body.substring(1);
        for (String item : splitFlowItems(inner)) {
            String stripped = item.strip();
            if (!stripped.isEmpty() && "*&!".indexOf(stripped.charAt(0)) >= 0) {
                return markToken(stripped);
            }
        }
        return "";
    }

    /**
     * 打头的记号（到第一个空白为止），去掉粘在后面的逗号、括号
     */
    private static String markToken(String text) {
        int end = 0;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        while (end > 1 && "],}".indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * 行内名单按顶层逗号切开的各项：套着的方括号、花括号里的逗号不切（引号怎么认见 {@link #scan}）
     */
    private static List<String> topLevelFlowItems(String inner) {
        boolean[] outside = scan(inner, true).outside();
        List<String> items = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            if (!outside[i]) {
                continue;
            }
            char c = inner.charAt(i);
            if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                items.add(inner.substring(start, i).strip());
                start = i + 1;
            }
        }
        items.add(inner.substring(start).strip());
        items.removeIf(String::isEmpty);
        return items;
    }

    /**
     * 行内名单的一项本身套着名单或键值（{@code [a, b]}、{@code {k: v}}、{@code k: v}），启动那一路读成带下标的子键
     */
    private static boolean isNestedFlowItem(String item) {
        if (item.charAt(0) == '"' || item.charAt(0) == '\'') {
            return false;
        }
        String body = bodyOf(item);
        return body.startsWith("[") || body.startsWith("{") || body.contains(": ") || body.endsWith(":");
    }

    /**
     * 收了口的行内名单里至少有一项不套名单或键值。全是 {@code {k: v}} 的是对象列表，另有自己的读写，不在此列
     */
    private static boolean hasPlainFlowItem(String text) {
        String body = text.strip();
        if (!body.startsWith("[") || !body.endsWith("]")) {
            return false;
        }
        for (String item : topLevelFlowItems(body.substring(1, body.length() - 1))) {
            if (!isNestedFlowItem(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 启动那一路把这一项底下的名单项又摊成了带下标的子键（{@code a[1][0]}、{@code a[1].k}）：名单里套着名单或键值
     */
    private static boolean hasNestedItems(Map<String, Object> loaded, String path) {
        String prefix = path + "[";
        for (String key : loaded.keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 套着名单或键值的行内名单在界面上怎么列：普通项用启动读到的值，套着的那几项照文件里的写法。
     * 普通项与启动读到的数不上时，退回只列启动读到的那几项
     */
    private String shownWithNested(List<String> lines, Line line, List<?> loaded) {
        String body = bodyOf(flowWritten(lines, line));
        if (!body.startsWith("[") || !body.endsWith("]")) {
            return joinLoaded(loaded);
        }
        List<String> shown = new ArrayList<>();
        int next = 0;
        for (String item : topLevelFlowItems(body.substring(1, body.length() - 1))) {
            if (isNestedFlowItem(item)) {
                shown.add(item);
            } else if (next < loaded.size()) {
                shown.add(String.valueOf(loaded.get(next++)));
            } else {
                return joinLoaded(loaded);
            }
        }
        return next == loaded.size() ? String.join("\n", shown) : joinLoaded(loaded);
    }

    /**
     * 行内名单的各项原文，未去引号。不是行内名单时返回 null。
     * 收了口与没收口的都按方括号里的原文切，别名要在去引号之前认。
     */
    private List<String> rawFlowItems(List<String> lines, Line line) {
        String raw = flowListText(lines, line);
        if (!raw.startsWith("[")) {
            return null;
        }
        String inner = raw.endsWith("]") ? raw.substring(1, raw.length() - 1) : raw.substring(1);
        List<String> items = new ArrayList<>();
        for (String item : splitFlowItems(inner)) {
            String stripped = item.strip();
            if (!stripped.isEmpty()) {
                items.add(stripped);
            }
        }
        return items;
    }

    /**
     * 键行上的值；键行空着时，把「值在下一行」那一段里的非注释行接起来
     */
    private String flowListText(List<String> lines, Line line) {
        if (line.rawValue != null && !hasNoValue(line.rawValue)) {
            return line.rawValue.strip();
        }
        int end = valueOnNextLineEnd(lines, line.index, line.indent);
        StringBuilder joined = new StringBuilder();
        for (int i = line.index + 1; i <= end; i++) {
            String raw = lines.get(i);
            if (raw.isBlank() || raw.strip().startsWith("#")) {
                continue;
            }
            String stripped = raw.strip();
            int comment = commentIndex(stripped);
            String value = (comment < 0 ? stripped : stripped.substring(0, comment)).strip();
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(value);
        }
        return joined.toString();
    }

    /**
     * 一段值原文（键行冒号后、或名单项短横后，已去行尾注释）逐行读不准、得按启动那一路读：
     * 带锚点或标签、是别名、是块标量，或引号在这一行上没闭合（跨了行）
     */
    private static boolean needsLoader(String value) {
        String stripped = value.strip();
        String body = bodyOf(stripped);
        if (!body.equals(stripped) || body.startsWith("*") || body.startsWith("|") || body.startsWith(">")) {
            return true;
        }
        if (!body.isEmpty() && (body.charAt(0) == '"' || body.charAt(0) == '\'')) {
            return body.length() < 2 || body.charAt(body.length() - 1) != body.charAt(0);
        }
        return false;
    }

    /**
     * 计算一行的缩进宽度
     * @param line 行
     * @return 缩进宽度
     */
    private int indentOf(String line) {
        return line.length() - line.stripLeading().length();
    }

    /**
     * 配置文件中的一行
     */
    private static final class Line {
        /**
         * 行下标，从 0 开始
         */
        private int index;

        /**
         * 缩进宽度
         */
        private int indent;

        /**
         * 完整键路径
         */
        private String path;

        /**
         * 值，不含行尾注释
         */
        private String value;

        /**
         * 键这一行冒号后的原文，去掉行尾注释、未去引号；为空或只有锚点、标签（见 {@link #hasNoValue}）
         * 说明值在底下几行：上级块、块名单，或写在下一行的文字
         */
        private String rawValue;

        /**
         * 值在文件里是引号包着写的：这种值启动时读成什么就是 {@link #value} 本身，不再按类型认
         */
        private boolean quoted;

        /**
         * 字符串列表的各项，仅当该键为字符串列表时非空
         */
        private final List<String> items = new ArrayList<>();

        /**
         * 列表块的最后一行下标，用于整块替换
         */
        private int listEnd = -1;

        /**
         * 键这一行的值是行内序列（单行写就或跨行收口）时为 true：
         * 整块替换成每行一项的块序列时，键这一行上的方括号必须一并让位
         */
        private boolean flowInline;

        /**
         * 行内序列跨了行但收不了口、或收口后含本界面读不了的写法（嵌套对象/列表、流式键值对）时
         * 为 true：保存这一项会被整批拒绝，绝不留下孤行写坏文件
         */
        private boolean flowUnreadable;

        /**
         * flowUnreadable 里专指「中间夹了空行或注释行而收不了口」的那一种：里面并没有套着
         * 方括号、花括号或「词: 值」，保存的拒语照实际的情形说
         */
        private boolean flowInterrupted;

        /**
         * flowUnreadable 里专指「方括号到下一个键、名单项或文件尾都没收口」的那一种：
         * 里面同样没有套着的写法，保存的拒语说没收口
         */
        private boolean flowUnclosed;

        /**
         * 键这一行看不全这一项的值（跨行、块标量、锚点、标签、别名）：界面值按启动那一路读，
         * 由 {@link #settle} 换进 {@link #value}
         */
        private boolean fromLoader;

        /**
         * 名单里逐行读不准的那几项的下标（跨了行、带锚点或标签、是别名、块标量），由 {@link #settle} 换成启动读到的
         */
        private final Set<Integer> loaderItems = new java.util.TreeSet<>();

        /**
         * 这一项是别名、引到的是一整块：它自己没有值，界面不显示、写口不给填值
         */
        private boolean hidden;

        /**
         * 这一行是合并键 {@code <<}：不是配置项，它的上级块从别处继承子键
         */
        private boolean merge;

        /**
         * 判断该键是否为列表
         * @return 是否为列表
         */
        private boolean isList() {
            return listEnd >= 0;
        }
    }
}
