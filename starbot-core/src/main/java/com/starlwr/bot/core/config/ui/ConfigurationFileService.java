package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.DatasourceProperties;
import com.starlwr.bot.core.config.EventStreamProperties;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
     * 备份文件名后缀
     */
    private static final String BACKUP_SUFFIX = ".bak";

    /**
     * 备份保留份数
     */
    private static final int BACKUP_KEEP = 10;

    /**
     * 备份文件名的时间戳格式，形如 20260803-172530
     */
    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    /**
     * 备份文件名的判定模式
     * <p>
     * 备份名来自接口入参，必须严格校验：直接拿它拼路径的话，传入 ../ 即可读取或覆盖任意文件。
     */
    private static final Pattern BACKUP_NAME = Pattern.compile("^[A-Za-z0-9_.-]+\\.\\d{8}-\\d{6}\\.bak$");

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

    @Autowired
    public ConfigurationFileService(ConfigurationMetadataService metadata, ApplicationContext context) {
        this(Path.of("application.yml"), () -> ConfigurationTemplate.render(metadata.getFields(),
                ConfigurationPropertyFields.values(
                        context.getBeansWithAnnotation(ConfigurationProperties.class).values())));
    }

    /**
     * 指定配置文件路径，便于测试
     * <p>
     * 这一支没有容器可问，默认模板因此只算得上<b>核心自己</b>那三份配置对象的默认值。
     * 判据台架走的正是这一支，而它量的也正是「核心的配置面渲染成文件长什么样」。
     * @param configPath 配置文件路径
     */
    ConfigurationFileService(Path configPath) {
        this(configPath, () -> ConfigurationTemplate.render(new ConfigurationMetadataService().getFields(),
                ConfigurationPropertyFields.values(List.of(
                        new StarBotCoreProperties(), new EventStreamProperties(), new DatasourceProperties()))));
    }

    ConfigurationFileService(Path configPath, Supplier<String> initialContent) {
        this.configPath = configPath;
        this.initialContent = initialContent;
    }

    /**
     * 读取配置文件中的全部键值
     * <p>
     * 返回的键为完整路径，例如 starbot.bilibili.dynamic.draw-logo。列表结构不在此处展开，
     * 由界面通过独立接口处理。
     * @return 键值映射
     * @throws IOException 读取失败时抛出
     */
    public synchronized Map<String, String> read() throws IOException {
        Map<String, String> values = new LinkedHashMap<>();

        for (Line line : parse()) {
            if (line.path == null) {
                continue;
            }

            if (!line.items.isEmpty()) {
                // 字符串列表以换行连接，与界面中的多行输入框一一对应
                values.put(line.path, String.join("\n", line.items));
            } else if (line.value != null && !line.value.isEmpty()) {
                values.put(line.path, line.value);
            }
        }

        return values;
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
     * 多数配置项留空是有意义的——静音时段留空表示不启用，Token 留空表示自动生成。
     * 但少数框架配置写成空值会让程序<b>根本起不来</b>：
     * {@code spring.data.redis.host} 写成空串时，Spring 在启动时直接抛
     * 「'host' must not be empty」，连界面都起不来，只能去手改配置文件。
     * <p>
     * 这个坑是把该配置项搬上界面时踩到的：界面上把地址清空 → 写出 {@code host: } →
     * 下次启动失败。对这类配置项，清空的语义必须是<b>删掉这一行</b>而不是写一个空值。
     */
    static final Set<String> BLANK_MEANS_ABSENT = Set.of("spring.data.redis.host");

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

        Files.writeString(configPath, initialContent.get(), StandardCharsets.UTF_8);
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
     * @throws IOException 读写失败或存在含换行的标量值时抛出
     */
    public synchronized List<String> write(Map<String, String> changes) throws IOException {
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

            // 这类配置项清空等于「不配置」，要把整行删掉而不是留一个空值——
            // 留空会让程序下次启动直接失败。自下而上处理，删行不会让后续行号失效
            if (BLANK_MEANS_ABSENT.contains(change.getKey())
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

            String updated = replaceValue(lines.get(line.index), change.getValue());
            if (!updated.equals(lines.get(line.index))) {
                lines.set(line.index, updated);
                changed.add(change.getKey());
            }
        }

        // 配置文件中尚不存在的项追加到其最近的已有祖先之下
        for (Map.Entry<String, String> entry : missing) {
            if (insert(lines, entry.getKey(), entry.getValue())) {
                changed.add(entry.getKey());
            } else {
                log.warn("配置项 {} 无法定位到合适的插入位置, 已跳过", entry.getKey());
            }
        }

        if (!changed.isEmpty()) {
            backup();
            Files.write(configPath, lines, StandardCharsets.UTF_8);
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
     * @param listPath 列表的完整路径，例如 starbot.adapter.onebot.senders
     * @param index 元素下标，从 0 开始
     * @param fields 待修改的字段名到取值，字段名为元素内部的键
     * @return 实际修改的字段数
     * @throws IOException 读写失败时抛出
     */
    public synchronized int writeListItemFields(String listPath, int index, Map<String, String> fields) throws IOException {
        if (fields.isEmpty()) {
            return 0;
        }

        createIfAbsent();

        List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
        int[] range = locateListItem(lines, listPath, index);
        if (range == null) {
            throw new IOException("未在配置文件中找到 " + listPath + " 的第 " + (index + 1) + " 个元素");
        }

        int changed = 0;
        for (int i = range[0]; i < range[1]; i++) {
            String stripped = lines.get(i).strip();
            // 元素首行形如 "- name: xxx"，其键同样需要参与匹配
            String candidate = stripped.startsWith("-") ? stripped.substring(1).strip() : stripped;

            int colon = candidate.indexOf(':');
            if (colon < 0) {
                continue;
            }

            String key = candidate.substring(0, colon).strip();
            if (!fields.containsKey(key)) {
                continue;
            }

            String replaced = replaceValue(lines.get(i), fields.get(key));
            if (!replaced.equals(lines.get(i))) {
                lines.set(i, replaced);
                changed++;
            }
        }

        if (changed > 0) {
            backup();
            Files.write(configPath, lines, StandardCharsets.UTF_8);
            log.info("配置界面已更新 {} 第 {} 个元素的 {} 个字段, 重启后生效", listPath, index + 1, changed);
        }

        return changed;
    }

    /**
     * 定位列表中某一元素所占的行范围
     * @return 长度为 2 的数组，分别为起始行（含）与结束行（不含）；未找到时返回 null
     */
    private int[] locateListItem(List<String> lines, String listPath, int index) {
        List<String> segments = List.of(listPath.split("\\."));
        List<String> stack = new ArrayList<>();

        int listIndent = -1;
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
                        return new int[]{start, i};
                    }
                    if (++seen == index) {
                        start = i;
                    }
                    continue;
                }

                // 缩进退回到列表键层级或更浅，说明列表已结束
                if (indent <= listIndent) {
                    return start >= 0 ? new int[]{start, i} : null;
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
            }
        }

        return start >= 0 ? new int[]{start, lines.size()} : null;
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
     * 读取配置文件原始文本
     * @return 原始文本
     * @throws IOException 读取失败时抛出
     */
    public synchronized String readRaw() throws IOException {
        return Files.readString(configPath, StandardCharsets.UTF_8);
    }

    /**
     * 覆盖写入配置文件原始文本
     * <p>
     * ⚠️ 控制台已不再提供配置文件编辑，本方法当前没有调用方。整份覆盖的正门是安全模式
     * （{@code SafeModeServer}），那条路只在程序起不来时才走得到。
     * 要重新给它接一个界面入口之前，请先读设置页底部那行路径旁边的理由。
     * @param content 新内容
     * @throws IOException 写入失败时抛出
     */
    public synchronized void writeRaw(String content) throws IOException {
        backup();
        Files.writeString(configPath, content, StandardCharsets.UTF_8);
        log.info("配置界面已整体覆盖 application.yml, 重启后生效");
    }

    /**
     * 备份当前配置文件
     * <p>
     * 每次保存生成一份带时间戳的独立备份并保留最近若干份。此前只有单个 .bak 文件且每次覆盖，
     * 一旦连续保存两次，第一次保存前的内容就再也找不回来了。
     * @throws IOException 备份失败时抛出
     */
    private void backup() throws IOException {
        if (!Files.exists(configPath)) {
            return;
        }

        String name = configPath.getFileName() + "." + BACKUP_STAMP.format(Instant.now()) + BACKUP_SUFFIX;
        Files.copy(configPath, configPath.resolveSibling(name), StandardCopyOption.REPLACE_EXISTING);
        log.debug("已备份原配置至 {}", name);

        pruneBackups();
    }

    /**
     * 删除超出保留数量的旧备份
     */
    private void pruneBackups() {
        try (Stream<Path> files = Files.list(directory())) {
            files.filter(this::isBackup)
                    // 备份名内含形如 20260803-172530 的时间戳，字典序即时间序
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .skip(BACKUP_KEEP)
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.debug("删除旧备份 {} 失败: {}", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            // 备份清理失败不应影响保存本身
            log.debug("清理旧备份失败: {}", e.getMessage());
        }
    }

    /**
     * 列出全部可用备份，按时间倒序
     * @return 备份文件名列表
     */
    public synchronized List<String> listBackups() {
        try (Stream<Path> files = Files.list(directory())) {
            return files.filter(this::isBackup)
                    .map(path -> path.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            log.error("列出配置备份失败", e);
            return List.of();
        }
    }

    /**
     * 读取指定备份的内容
     * @param name 备份文件名
     * @return 备份内容
     * @throws IOException 读取失败或文件名非法时抛出
     */
    public synchronized String readBackup(String name) throws IOException {
        Path path = resolveBackup(name);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 回滚至指定备份
     * <p>
     * 回滚本身也会先备份当前内容，因此误回滚同样可以再滚回来。
     * @param name 备份文件名
     * @throws IOException 读取或写入失败时抛出
     */
    public synchronized void restoreBackup(String name) throws IOException {
        String content = readBackup(name);
        backup();
        Files.writeString(configPath, content, StandardCharsets.UTF_8);
        log.info("配置界面已回滚 application.yml 至备份 {}, 重启后生效", name);
    }

    /**
     * 解析备份文件名为路径
     * <p>
     * 只接受本目录下符合命名规则的备份文件：文件名来自接口入参，若直接拼接路径，
     * 传入 ../ 即可读取或覆盖任意文件。
     * @param name 备份文件名
     * @return 备份文件路径
     * @throws IOException 文件名非法或文件不存在时抛出
     */
    private Path resolveBackup(String name) throws IOException {
        if (name == null || !isBackupName(name)) {
            throw new IOException("非法的备份文件名: " + name);
        }

        Path path = directory().resolve(name).normalize();
        if (!path.getParent().equals(directory()) || !Files.isRegularFile(path)) {
            throw new IOException("备份文件不存在: " + name);
        }

        return path;
    }

    /**
     * 配置文件所在目录
     */
    private Path directory() {
        Path parent = configPath.toAbsolutePath().getParent();
        return parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
    }

    /**
     * 判断是否为本服务生成的备份文件
     */
    private boolean isBackup(Path path) {
        return Files.isRegularFile(path) && isBackupName(path.getFileName().toString());
    }

    /**
     * 判断文件名是否符合备份命名规则
     */
    private boolean isBackupName(String name) {
        return BACKUP_NAME.matcher(name).matches();
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
        boolean needQuote = !value.strip().equals(value)
                || value.contains(": ") || value.endsWith(":")
                || value.contains(" #")
                || INDICATOR_START.indexOf(value.charAt(0)) >= 0;

        return needQuote ? "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : value;
    }

    /**
     * 找出一行中注释的起始位置
     * @param text 冒号之后的内容
     * @return 注释起始下标，无注释时返回 -1
     */
    private int commentIndex(String text) {
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                return i;
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
     */
    private boolean insert(List<String> lines, String path, String value) {
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
                        owner.items.add(unquote(item));
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
         * 判断该键是否为列表
         * @return 是否为列表
         */
        private boolean isList() {
            return listEnd >= 0;
        }
    }
}
