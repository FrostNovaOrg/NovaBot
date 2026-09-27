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

        for (Line line : parse()) {
            if (line.path == null) {
                continue;
            }

            if (line.isList()) {
                // 字符串列表以换行连接，与界面中的多行输入框一一对应；空列表读成空串
                values.put(line.path, String.join("\n", line.items));
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

        healSlashAddress(fixes, rawByPath);
        return values;
    }

    /**
     * 按程序启动时读配置的那一路，读出每一项实际得到的值
     * <p>
     * 与 {@link #read()} 不是一回事：那边给界面看的是文件里的字面（只去掉两侧引号），
     * 这边是 Spring Boot 的 YAML 属性加载器读出来的值——不带引号的 {@code 23:00} 在这里是
     * 六十进制整数 1380，双引号里的 {@code \"} 在这里已经还原成引号。
     * 「重启之后读到的变没变」只能拿这一份比，字面一样不算数。
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
            throw new IOException("按启动时那一路读不下配置文件: " + e.getMessage(), e);
        }
        if (documents.size() != 1 || !(documents.get(0) instanceof EnumerablePropertySource<?> document)) {
            return Map.of();
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
     */
    private void healSlashAddress(Map<String, String> fixes, Map<String, String> rawByPath) {
        if (slashAddressHealed || fixes.isEmpty()) {
            return;
        }
        slashAddressHealed = true;
        try {
            List<String> changed = write(fixes);
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
     * 字符串列表不在此列：那里换行本就是各项之间的分隔符。
     * @param changes 待写入的配置项
     * @param index 配置文件中已有的行
     * @throws IOException 存在含换行的标量值时抛出
     */
    private void rejectMultilineScalars(Map<String, String> changes, Map<String, Line> index) throws IOException {
        for (Map.Entry<String, String> change : changes.entrySet()) {
            String value = change.getValue();
            if (value == null || (value.indexOf('\n') < 0 && value.indexOf('\r') < 0)) {
                continue;
            }

            Line line = index.get(change.getKey());
            // 文件里还没有这一项时，含换行的值会被当成字符串列表写入，那是合法的
            if (line != null && !line.isList()) {
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
     * @throws IOException 读写失败、存在含换行的标量值或有配置项在文件里找不到上级块时抛出
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
     * 不留备份也安全：换件失败时原件一个字节不动（见 {@link DurableFiles#replace}），
     * 而这条路的上一版本来就是那份明文，留在盘上与这次写回的目的正好相反。
     * 普通保存照旧走 {@link #write(Map)}：那才是使用者要能反悔的改动。
     * @param changes 待写入的配置项名到取值
     * @return 实际发生改动的配置项名
     * @throws IOException 读写失败、存在含换行的标量值或有配置项在文件里找不到上级块时抛出
     */
    public synchronized List<String> writeWithoutBackup(Map<String, String> changes) throws IOException {
        return write(changes, false);
    }

    /**
     * 备份那句实话，供日志原样引：同目录没有含明文的副本时说没有，有就点名，查不出也照说
     * <p>
     * 明文换哈希那条路自己不留备份，但更早的保存可能已经把明文抄进过备份，
     * 那些照约定不动。说「没有」之前先看盘：这次没写备份，不等于目录里就没有含明文的副本。
     * @param plaintext 刚从主配置文件换掉的那段明文
     * @return 同目录的实况
     */
    public String backupSituation(String plaintext) {
        List<String> left;
        try {
            left = siblingFilesHolding(plaintext);
        } catch (IOException e) {
            return "同目录的备份没查成: " + e.getMessage();
        }
        if (left.isEmpty()) {
            return "同目录未留含明文的备份";
        }
        return "同目录的 " + left + " 里还留着明文, 那几份本次不动";
    }

    /**
     * 主配置文件之外，同一目录里还含着这段字的文件名，一份没有时为空表
     * @param text 要找的字
     * @return 命中的文件名，按名排序
     * @throws IOException 读目录失败时抛出
     */
    private List<String> siblingFilesHolding(String text) throws IOException {
        List<String> left = new ArrayList<>();
        Path self = configPath.toAbsolutePath().normalize();
        Path dir = self.getParent();
        if (dir == null) {
            return left;
        }
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                if (!Files.isRegularFile(file) || file.toAbsolutePath().normalize().equals(self)) {
                    continue;
                }
                // 按字节找而不是读成字符串：目录里可能有不是文本的件，读成串会半路炸掉这次查询
                if (contains(Files.readAllBytes(file), needle)) {
                    left.add(file.getFileName().toString());
                }
            }
        }
        return left;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || needle.length > haystack.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private synchronized List<String> write(Map<String, String> changes, boolean keepBackup) throws IOException {
        if (changes.isEmpty()) {
            return List.of();
        }

        createIfAbsent();

        List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
        List<Line> parsed = parse();

        Map<String, Line> index = new LinkedHashMap<>();
        for (Line line : parsed) {
            if (line.path != null) {
                index.put(line.path, line);
            }
        }

        rejectMultilineScalars(changes, index);

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

            // 显式清除（值为 null）：把这一行删掉。清一个本来就没有的键是无操作——
            // 「没这一项」与「删掉之后没有」在读回来时是同一件事，都不该往文件里添一行空值
            if (change.getValue() == null) {
                if (line != null) {
                    lines.remove(line.index);
                    changed.add(change.getKey());
                }
                continue;
            }

            // 这类配置项清空等于「不配置」，要把整行删掉而不是留一个空值——
            // 留空会让程序下次启动直接失败。自下而上处理，删行不会让后续行号失效
            if (blankMeansAbsent.contains(change.getKey())
                    && (change.getValue() == null || change.getValue().isBlank())) {
                if (line != null) {
                    lines.remove(line.index);
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
                // 宁可拒存并说清原因（见本类 parse 里的同名标注）
                throw new IOException("配置项 " + change.getKey()
                        + " 的行内名单跨了行且含本界面读不了的写法, 为不写坏配置文件本批全部未保存, 请先在配置文件里把它改成每行一项");
            }

            String updated = replaceValue(lines.get(line.index), change.getValue());
            if (!updated.equals(lines.get(line.index))) {
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
            if (keepBackup) {
                backup();
            }
            DurableFiles.replace(configPath, lines, DurableFiles.OWNER_ONLY);
            log.info("配置界面已更新 {} 个配置项: {}", changed.size(), String.join(", ", changed));
        }

        return List.copyOf(changed);
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

        if (location.start() < 0) {
            if (index != 0) {
                throw new IOException("未在配置文件中找到 " + listPath + " 的第 " + (index + 1) + " 个元素");
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
     * 对象列表的键这一行若是读不了的行内写法（跨行的 {@code [...]} 或内嵌元素），
     * 就地改字段只会动键那一行，半截行内序列留在文件里会让整份配置读不了。
     * 一律拒存并说清原因；空表 {@code []} 与方括号里只有空白的 {@code [ ]} 不在其列——
     * 那是建出第一个元素的正常起点。
     */
    private void rejectUnreadableInlineList(List<String> lines, int keyLine, String listPath) throws IOException {
        String raw = lines.get(keyLine);
        String rest = raw.substring(raw.indexOf(':') + 1);
        int comment = commentIndex(rest);
        String onLine = (comment < 0 ? rest : rest.substring(0, comment)).strip();
        if (onLine.startsWith("[") && !onLine.replaceAll("\\s", "").equals("[]")) {
            throw new IOException(listPath + " 在文件里是跨行或内嵌的行内写法, 本界面读不了"
                    + ", 为不写坏配置文件本批全部未保存, 请先在配置文件里把它改成每行一项");
        }
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
     * @throws IOException 备份失败时抛出
     */
    private void backup() throws IOException {
        List<String> pruned = backups.backup(backupKeep.getAsInt());
        if (pruned.isEmpty()) {
            return;
        }

        // 一份没删的时候什么也不记：每次保存都记一条「清理了 0 份」，
        // 会让日志页上真正删掉东西的那几条淹在里面
        timeline.record(TimelineEvent.of(TimelineEventType.BACKUP_PRUNED, TimelineEvent.Level.INFO)
                .text("配置备份留 " + backupKeep.getAsInt() + " 份，清掉最旧的 " + pruned.size() + " 份")
                .detail("keep", String.valueOf(backupKeep.getAsInt()))
                .detail("pruned", String.join(",", pruned))
                .build());
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
        boolean needQuote = !value.strip().equals(value)
                || value.contains(": ") || value.endsWith(":")
                || value.contains(" #")
                || CLOCK_TIME.matcher(value).matches()
                || INDICATOR_START.indexOf(value.charAt(0)) >= 0;

        return needQuote ? "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : value;
    }

    /**
     * 找出一行中注释的起始位置
     * <p>
     * 引号只在值本身以引号开头时才是定界符，且只配对到闭引号（双引号内的 \" 不是闭引号）：
     * 否则 It's 里的撇号只是个只开不闭的普通字符，会把后面的行尾注释整段关进「引号内」。
     * # 也按 YAML 的规矩来：前面有空白才算注释，a#b 里的 # 是值的一部分。
     * @param text 冒号之后的内容
     * @return 注释起始下标，无注释时返回 -1
     */
    private int commentIndex(String text) {
        String value = text.stripLeading();
        char quote = !value.isEmpty() && (value.charAt(0) == '\'' || value.charAt(0) == '"') ? value.charAt(0) : 0;

        for (int i = quote > 0 ? 1 : 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote == '"' && c == '\\') {
                i++;
            } else if (quote > 0 && c == quote) {
                quote = 0;
            } else if (c == '#' && quote == 0 && (i == 0 || Character.isWhitespace(value.charAt(i - 1)))) {
                return i + (text.length() - value.length());
            }
        }

        return -1;
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
                throw new IOException("配置项 " + path + " 的键名段含不允许的字符, 本批全部未保存");
            }
        }
    }

    /**
     * 整块替换一个字符串列表
     * @param lines 文件行
     * @param line 列表所属的键
     * @param value 换行分隔的新内容
     * @return 是否发生变更
     */
    private boolean replaceList(List<String> lines, Line line, String value) {
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
        lines.subList(line.index + 1, line.listEnd + 1).clear();
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
     * （界面上不编辑的那类）也不收，维持普通文字；不带引号的 {@code 键: 值} 是流式键值对
     * （嵌套映射的写法），同样不收。尾逗号与连续逗号只是分隔符的痕迹，不产生空项。
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
            String bare = unquoteItem(strippedItem);
            if (bare.startsWith("{") || bare.startsWith("[")) {
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
     * @param joined 键行的值与续行以空格接起来的整段原文
     * @param end    收口那一行的下标
     */
    private record FlowTail(String joined, int end) {}

    /**
     * 行内序列跨了行（{@code key: [a,} 换行 {@code b]}）时把续行并入：行内序列里的
     * 换行只是空白，跨行写法与写在键那一行等价。并入后按同一把尺
     * {@link #flowSequenceItems} 判，读不了的写法由调用方标成不可存。
     * <p>
     * 扫到空行、注释行，或缩进退到键这一层及更浅的键/列表项仍未收口即停：
     * 那种文件按收不了口处理，保存时整批拒绝，绝不留下只有半截的行内序列。
     *
     * @param lines     文件行
     * @param keyIndex  键所在行的下标
     * @param keyIndent 键的缩进宽度
     * @param firstValue 键这一行冒号后的值（未收口的行内序列开头）
     * @return 收口后的整段原文与末行下标；收不了口时为 null
     */
    private FlowTail joinFlowTail(List<String> lines, int keyIndex, int keyIndent, String firstValue) {
        StringBuilder joined = new StringBuilder(firstValue);
        int depth = flowDepth(firstValue);
        for (int i = keyIndex + 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            String stripped = raw.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                return null;
            }
            if (indentOf(raw) <= keyIndent
                    && (stripped.startsWith("-") || stripped.contains(": ") || stripped.endsWith(":"))) {
                return null;
            }
            int comment = commentIndex(stripped);
            String value = (comment < 0 ? stripped : stripped.substring(0, comment)).strip();
            joined.append(' ').append(value);
            depth += flowDepth(value);
            if (depth <= 0) {
                return new FlowTail(joined.toString(), i);
            }
        }
        return null;
    }

    /**
     * 数一段行内原文里未收口的方括号层数，引号内的不算
     */
    private static int flowDepth(String text) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote > 0) {
                if (quote == '"' && c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
        }
        return depth;
    }

    /**
     * 去掉列表项两侧的引号，并按 YAML 引号规则还原内容：
     * 双引号里的 {@code \"} 与 {@code \\}、单引号里的 {@code ''}。
     * <p>
     * 还原与写出（{@link #render}）用同一套规则，存一次再读回还是原来的值。
     * 不加引号的项原样返回；其余转义序列不在还原之列——名单框按行拆项，
     * 元素里本就不该有换行这类控制字符。
     *
     * @param value 未去引号的列表项
     * @return 还原后的内容
     */
    private String unquoteItem(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            String body = value.substring(1, value.length() - 1);
            StringBuilder out = new StringBuilder(body.length());
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c == '\\' && i + 1 < body.length()
                        && (body.charAt(i + 1) == '\\' || body.charAt(i + 1) == '"')) {
                    out.append(body.charAt(++i));
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }

    /**
     * 拆行内序列的各项：引号里的逗号是字面字符，不是分隔符
     * @param inner 方括号内的原文
     * @return 按分隔符切开的各项，未去引号
     */
    private List<String> splitFlowItems(String inner) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;

        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (quote > 0) {
                current.append(c);
                if (quote == '"' && c == '\\' && i + 1 < inner.length()) {
                    current.append(inner.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
            } else if (c == ',') {
                items.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        items.add(current.toString());
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
                    continue;
                }
                listIndent = -1;
            }

            if (stripped.startsWith("-")) {
                listIndent = indent;

                // 形如 "- 值" 的标量项归属于上一个键；形如 "- 键: 值" 的是对象列表，不予收集。
                // 不能简单地以「是否含冒号」区分：IPv6 地址本身就带冒号。
                if (!result.isEmpty()) {
                    Line owner = result.get(result.size() - 1);
                    String item = stripped.substring(1).strip();
                    if (!OBJECT_ITEM.matcher(item).find() && owner.index == i - 1 - owner.items.size()) {
                        owner.items.add(unquoteItem(item));
                        owner.listEnd = i;
                    }
                }
                continue;
            }

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

            // 行内序列（key: []、key: [a, b]）：列表整个写在键这一行上，也按字符串列表收下
            List<String> flowItems = flowSequenceItems(value);
            if (flowItems != null) {
                line.items.addAll(flowItems);
                line.listEnd = i;
                line.flowInline = true;
            } else if (value.startsWith("[") && flowDepth(value) > 0) {
                // 行内序列跨了行（key: [a, 换行 b]）：续行并入后按同一把尺判；
                // 收不了口或并入后读不了的（嵌套对象/列表、流式键值对），标成不可存，
                // 保存时整批拒绝——只改键那一行会给文件留下半截方括号，整份配置从此读不了
                FlowTail tail = joinFlowTail(lines, i, indent, value);
                if (tail == null) {
                    line.flowUnreadable = true;
                } else {
                    List<String> across = flowSequenceItems(tail.joined());
                    if (across == null) {
                        line.flowUnreadable = true;
                    } else {
                        line.items.addAll(across);
                        line.listEnd = tail.end();
                        line.flowInline = true;
                    }
                    i = tail.end();
                }
            }
            result.add(line);
        }

        return result;
    }

    /**
     * 去除值两侧的引号
     * @param value 值
     * @return 去引号后的值
     */
    private String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }

        return value;
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
         * 判断该键是否为列表
         * @return 是否为列表
         */
        private boolean isList() {
            return listEnd >= 0;
        }
    }
}
