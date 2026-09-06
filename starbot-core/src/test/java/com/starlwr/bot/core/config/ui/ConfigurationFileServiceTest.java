package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.service.TotalDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置文件读写测试
 * <p>
 * 重点校验「界面上的改动确实落到文件」以及「落盘时不破坏注释与结构」这两件事。
 */
@DisplayName("配置文件读写")
class ConfigurationFileServiceTest {
    /**
     * 测试用配置文件内容
     * <p>
     * <b>请勿向本模板添加 {@code starbot.bilibili.dynamic.auto-save-image}</b>：
     * {@link #insertsMissingProperty()} 依赖该键「不存在」来验证插入逻辑，一旦加入该用例即失效。
     * 需要新的样例配置项时，请另选一个本模板与该用例都未使用的键。
     */
    private static final String TEMPLATE = """
            server:
              port: 7827                # 服务端口
              address: 127.0.0.1        # 监听地址

            spring:
              mail:
                host:                   # SMTP 服务器地址

            starbot:
              core:
                push:
                  quiet-start:          # 静音时段开始
                config-ui:
                  enabled: true         # 是否启用配置界面
                  allow-ips:
                    - 127.0.0.1/32
                    - ::1/128
              adapter:
                onebot:
                  senders:
                    - name: qq-onebot
                      api: /send
                      delay: 1000
              bilibili:
                dynamic:
                  auto-follow: true     # 是否自动关注
                  push-minutes: 1440    # 超时不推送
            """;

    @TempDir
    Path dir;

    private Path config;
    private ConfigurationFileService service;

    @BeforeEach
    void setUp() throws IOException {
        config = dir.resolve("application.yml");
        Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);
        service = new ConfigurationFileService(config);
    }

    private String content() throws IOException {
        return Files.readString(config, StandardCharsets.UTF_8);
    }

    /**
     * 带时间戳的备份名（含同一秒加序号的变体）
     */
    private List<String> stampedBackupNames() throws IOException {
        String prefix = config.getFileName() + ".";
        try (var files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix) && name.endsWith(".bak"))
                    .filter(name -> name.length() > prefix.length() + ".bak".length())
                    .filter(name -> name.substring(prefix.length(), name.length() - ".bak".length())
                            .matches("\\d{8}-\\d{6}(-\\d+)?"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * 配置文件当前内容的摘要，用于断言「整批被拒时盘上一个字节没动」
     */
    private String sha256() throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(config)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须提供 SHA-256", e);
        }
    }

    /**
     * 目录里 .bak 备份的个数
     */
    private long backupCount() throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".bak")).count();
        }
    }

    @Test
    @DisplayName("读取出的键为完整路径")
    void readsFullPaths() throws IOException {
        Map<String, String> values = service.read();

        assertEquals("7827", values.get("server.port"));
        assertEquals("true", values.get("starbot.core.config-ui.enabled"));
        assertEquals("1440", values.get("starbot.bilibili.dynamic.push-minutes"));
    }

    @Test
    @DisplayName("字符串列表按行读出")
    void readsStringList() throws IOException {
        assertEquals("127.0.0.1/32\n::1/128", service.read().get("starbot.core.config-ui.allow-ips"));
    }

    @Test
    @DisplayName("对象列表内部的键不会被当作配置路径")
    void ignoresObjectListItems() throws IOException {
        Map<String, String> values = service.read();

        assertFalse(values.containsKey("starbot.adapter.onebot.senders.api"));
        assertFalse(values.containsKey("starbot.adapter.onebot.senders.delay"));
    }

    @Test
    @DisplayName("修改标量值后文件内容随之改变")
    void writesScalar() throws IOException {
        List<String> changed = service.write(Map.of("starbot.bilibili.dynamic.push-minutes", "720"));

        assertEquals(List.of("starbot.bilibili.dynamic.push-minutes"), changed);
        assertTrue(content().contains("push-minutes: 720"));
        assertEquals("720", service.read().get("starbot.bilibili.dynamic.push-minutes"));
    }

    @Test
    @DisplayName("修改后行尾注释仍然保留")
    void keepsTrailingComment() throws IOException {
        service.write(Map.of("starbot.bilibili.dynamic.auto-follow", "false"));

        String line = content().lines().filter(l -> l.contains("auto-follow")).findFirst().orElseThrow();
        assertTrue(line.contains("false"), "值应已更新: " + line);
        assertTrue(line.contains("# 是否自动关注"), "行尾注释应保留: " + line);
    }

    @Test
    @DisplayName("仅改动目标行，其余内容逐行不变")
    void touchesOnlyTargetLines() throws IOException {
        List<String> before = content().lines().toList();
        service.write(Map.of("starbot.bilibili.dynamic.auto-follow", "false"));
        List<String> after = content().lines().toList();

        assertEquals(before.size(), after.size());

        int diff = 0;
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).equals(after.get(i))) {
                diff++;
            }
        }

        assertEquals(1, diff, "只应有一行发生变化");
    }

    @Test
    @DisplayName("字符串列表整块替换且缩进正确")
    void writesStringList() throws IOException {
        List<String> changed = service.write(Map.of("starbot.core.config-ui.allow-ips", "10.0.0.0/8\n192.168.0.0/16\n127.0.0.1/32"));

        assertEquals(List.of("starbot.core.config-ui.allow-ips"), changed);
        assertEquals("10.0.0.0/8\n192.168.0.0/16\n127.0.0.1/32", service.read().get("starbot.core.config-ui.allow-ips"));

        assertTrue(content().contains("      - 10.0.0.0/8"), "列表项缩进应与原文件一致:\n" + content());
        assertFalse(content().contains("::1/128"), "旧的列表项应被移除");
    }

    @Test
    @DisplayName("同时修改列表与标量互不干扰")
    void writesListAndScalarTogether() throws IOException {
        service.write(Map.of(
                "starbot.core.config-ui.allow-ips", "10.0.0.0/8",
                "starbot.bilibili.dynamic.push-minutes", "60",
                "server.port", "8080"
        ));

        Map<String, String> values = service.read();
        assertEquals("10.0.0.0/8", values.get("starbot.core.config-ui.allow-ips"));
        assertEquals("60", values.get("starbot.bilibili.dynamic.push-minutes"));
        assertEquals("8080", values.get("server.port"));
    }

    @Test
    @DisplayName("值未变化时不计入改动")
    void noChangeWhenValueIdentical() throws IOException {
        assertEquals(List.of(), service.write(Map.of("starbot.bilibili.dynamic.push-minutes", "1440")));
    }

    @Test
    @DisplayName("写入后磁盘上应留下一份 .bak 备份")
    void writeLeavesBackupFile() throws IOException {
        service.write(Map.of("starbot.bilibili.dynamic.push-minutes", "30"));

        List<Path> backups;
        try (var files = Files.list(dir)) {
            backups = files.filter(path -> path.getFileName().toString().endsWith(".bak")).toList();
        }
        assertEquals(1, backups.size(), "应生成一份备份");
        assertTrue(Files.readString(backups.get(0), StandardCharsets.UTF_8).contains("push-minutes: 1440"),
                "备份应保有修改前的内容");
    }

    @Test
    @DisplayName("保留份数取自配置而不是默认值：连写 5 次只留 3 份")
    void backupKeepComesFromConfiguration() throws IOException {
        service = new ConfigurationFileService(config, () -> TEMPLATE, () -> 3);

        for (int i = 0; i < 5; i++) {
            service.write(Map.of("server.port", String.valueOf(7000 + i)));
        }

        assertEquals(3, stampedBackupNames().size(), "backupKeep=3 时连写 5 次应裁到 3 份");
    }

    @Test
    @DisplayName("同一秒连存两次：两份备份都在，内容各是各的")
    void sameSecondSavesKeepBothBackups() throws IOException {
        service = new ConfigurationFileService(config, () -> TEMPLATE, () -> 10,
                Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC));

        service.write(Map.of("server.port", "7000"));
        String afterFirst = content();
        service.write(Map.of("server.port", "7001"));

        List<String> names = stampedBackupNames();
        assertEquals(2, names.size(), "同一秒的第二份备份不该覆盖第一份");

        Set<String> contents = new HashSet<>();
        for (String name : names) {
            contents.add(Files.readString(dir.resolve(name), StandardCharsets.UTF_8));
        }
        assertEquals(Set.of(TEMPLATE, afterFirst), contents, "两份备份应分别是两次保存前的旧文");
    }

    @Test
    @DisplayName("清空列表元素首字段时，短横要顶到下一字段上")
    void clearingFirstListItemFieldKeepsTheDash() throws IOException {
        service.writeListItemFields("starbot.adapter.onebot.senders", 0, Map.of("name", ""));

        String content = content();
        assertFalse(content.contains("name: qq-onebot"), content);
        assertTrue(content.matches("(?s).*\\n\\s+- api: /send\\n.*"),
                "短横必须跟着剩下的第一字段走，否则列表在这里断开:\n" + content);
        assertTrue(content.contains("delay: 1000"), content);
    }

    @Test
    @DisplayName("清空列表元素内部的字段应删掉该字段，而不是留下空值")
    void clearingListItemFieldRemovesTheField() throws IOException {
        int changed = service.writeListItemFields("starbot.adapter.onebot.senders", 0,
                Map.of("api", ""));

        assertEquals(1, changed);
        String content = content();
        assertFalse(content.contains("api:"), "空字段应被删掉, 实为:\n" + content);
        assertFalse(content.contains("api: \"\""), content);
        // 阴性：同一元素里没被清空的字段、列表外的同名键，一个字不动
        assertTrue(content.contains("name: qq-onebot"), content);
        assertTrue(content.contains("delay: 1000"), content);
        assertEquals("7827", service.read().get("server.port"));
    }

    @Test
    @DisplayName("列表元素字段写成非空值时照写")
    void writesNonEmptyListItemField() throws IOException {
        service.writeListItemFields("starbot.adapter.onebot.senders", 0, Map.of("api", "/push"));

        String content = content();
        assertTrue(content.contains("api: /push"), content);
        assertTrue(content.contains("name: qq-onebot"), content);
    }

    @Test
    @DisplayName("可修改列表元素内部的字段")
    void writesFieldsInsideListItem() throws IOException {
        int changed = service.writeListItemFields("starbot.adapter.onebot.senders", 0,
                Map.of("api", "/push", "delay", "2000"));

        assertEquals(2, changed);
        String content = content();
        assertTrue(content.contains("api: /push"), content);
        assertTrue(content.contains("delay: 2000"), content);
        // 同一元素内未指定的字段不应被动到
        assertTrue(content.contains("name: qq-onebot"), content);
    }

    @Test
    @DisplayName("修改列表元素字段时不应影响列表之外的同名键")
    void doesNotTouchSameKeyOutsideList() throws IOException {
        service.writeListItemFields("starbot.adapter.onebot.senders", 0, Map.of("port", "9999"));

        // server.port 与列表元素无关，不得被改写
        assertEquals("7827", service.read().get("server.port"));
    }

    @Test
    @DisplayName("列表元素字段删尽后整条去掉，列表回到空表")
    void clearingEveryListItemFieldRemovesTheItem() throws IOException {
        service.writeListItemFields("starbot.adapter.onebot.senders", 0,
                Map.of("name", "", "api", "", "delay", ""));

        String text = content();
        assertFalse(text.contains("name:"), "字段应全部消失:\n" + text);
        assertFalse(text.contains("api:"), text);
        assertFalse(text.contains("delay:"), text);
        assertFalse(text.contains("- {}"), "不该留下空映射:\n" + text);
        assertFalse(text.matches("(?s).*senders:\\s*\\n\\s+-\\s*(\\n|$).*"),
                "不该留下只有短横的空元素:\n" + text);
        assertTrue(text.contains("senders: []"),
                "唯一一项删尽后应回到空表，否则下次启动读到的是一个空对象:\n" + text);
    }

    @Test
    @DisplayName("空表建第一项时跳过空字段，返回的是实际写下的个数")
    void createFirstItemSkipsBlankFieldsAndDoesNotCountThem() throws IOException {
        Files.writeString(config, """
                starbot:
                  adapter:
                    onebot:
                      senders: []
                """, StandardCharsets.UTF_8);
        service = new ConfigurationFileService(config);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", "qq-onebot");
        fields.put("api", "/send");
        fields.put("one-bot-http-token", "");
        fields.put("one-bot-websocket-token", "  ");

        int changed = service.writeListItemFields("starbot.adapter.onebot.senders", 0, fields);

        assertEquals(2, changed, "空字段不写也不计入返回值");
        String text = content();
        assertTrue(text.contains("- name: qq-onebot"), text);
        assertTrue(text.contains("api: /send"), text);
        assertFalse(text.contains("one-bot-http-token"), "空令牌不该写成空值行:\n" + text);
        assertFalse(text.contains("one-bot-websocket-token"), text);
    }

    @Test
    @DisplayName("列表或元素不存在时应明确报错, 而非静默无操作")
    void failsWhenListItemMissing() {
        assertThrows(IOException.class,
                () -> service.writeListItemFields("starbot.adapter.onebot.senders", 5, Map.of("api", "/x")));
        assertThrows(IOException.class,
                () -> service.writeListItemFields("starbot.not.exist", 0, Map.of("api", "/x")));
    }

    @Test
    @DisplayName("尚不存在的配置项会被插入到已有父节点之下")
    void insertsMissingProperty() throws IOException {
        List<String> changed = service.write(Map.of("starbot.bilibili.dynamic.auto-save-image", "true"));

        assertEquals(List.of("starbot.bilibili.dynamic.auto-save-image"), changed);
        assertEquals("true", service.read().get("starbot.bilibili.dynamic.auto-save-image"));
    }

    @Test
    @DisplayName("尚不存在的列表配置项写成 YAML 列表而非多行标量")
    void insertsMissingList() throws IOException {
        List<String> changed = service.write(Map.of("starbot.core.plugin.maven-base-urls", "https://a.example\nhttps://b.example"));

        assertEquals(List.of("starbot.core.plugin.maven-base-urls"), changed);
        assertTrue(content().contains("- https://a.example"), "应写成 YAML 列表:\n" + content());
        assertFalse(content().contains("\"https://a.example"), "不应写成带引号的多行标量:\n" + content());
        assertEquals("https://a.example\nhttps://b.example", service.read().get("starbot.core.plugin.maven-base-urls"));
    }

    @Test
    @DisplayName("找不到任何上级块的配置项: 整批不落盘, 报错点名是哪一项")
    void orphanKeyRejectsWholeBatch() throws IOException {
        String before = sha256();

        IOException error = assertThrows(IOException.class,
                () -> service.write(Map.of("logging.level.root", "DEBUG")));

        assertTrue(error.getMessage().contains("logging.level.root"), "报错要说清是哪一项: " + error.getMessage());
        assertEquals(before, sha256(), "整批被拒时文件必须一个字节不动");
        assertEquals(0, backupCount(), "整批被拒时不应生成备份");
    }

    @Test
    @DisplayName("孤儿键混着能插的键: 同样整批拒绝, 能插的那项也不落盘")
    void orphanKeyInMixedBatchWritesNothing() throws IOException {
        String before = sha256();

        IOException error = assertThrows(IOException.class, () -> service.write(Map.of(
                "starbot.bilibili.dynamic.draw-logo", "true",
                "logging.level.root", "DEBUG")));

        assertTrue(error.getMessage().contains("logging.level.root"), "报错要说清是哪一项: " + error.getMessage());
        assertEquals(before, sha256(), "能插进去的那一项也不许单独落盘");
        assertFalse(content().contains("draw-logo"), "混批必须整体拒绝, 不能一半落一半不落:\n" + content());
        assertEquals(0, backupCount(), "整批被拒时不应生成备份");
    }

    // ============ 值里带换行 ============

    @Test
    @DisplayName("标量值含换行时应当场拒绝，而不是写出一份解析不了的配置")
    void rejectsMultilineScalarValue() throws IOException {
        String before = content();

        // 不带冒号的那行不会被加引号，会顶在第 0 列，整份配置从此解析不了，
        // 而接口照样回报「已保存」——问题要到下次重启才暴露成安全模式
        IOException error = assertThrows(IOException.class,
                () -> service.write(Map.of("starbot.core.push.quiet-start", "22:00\nevil")));

        assertTrue(error.getMessage().contains("starbot.core.push.quiet-start"), "报错要说清是哪一项: " + error.getMessage());
        assertEquals(before, content(), "拒绝时文件必须原样不动");
    }

    @Test
    @DisplayName("字符串列表的换行是分隔符，不受影响")
    void allowsMultilineForStringList() throws IOException {
        service.write(Map.of("starbot.core.config-ui.allow-ips", "127.0.0.1/32\n10.0.0.0/8"));

        // 读回来仍是以换行连接的一个串，与界面上的多行输入框一一对应
        assertEquals("127.0.0.1/32\n10.0.0.0/8", service.read().get("starbot.core.config-ui.allow-ips"));
    }

    @Test
    @DisplayName("换行不能凭空造出新的配置项")
    void multilineCannotInjectKeys() throws IOException {
        // 这一条即使当前已被拒绝也要留着：将来若放宽了限制，注入才是真正危险的那一面
        assertThrows(IOException.class,
                () -> service.write(Map.of("starbot.core.push.quiet-start", "x\n      enabled: false")));

        assertEquals("true", service.read().get("starbot.core.config-ui.enabled"), "既有配置项不该被顶掉");
    }

    // ============ 清空即移除（否则程序起不来） ============

    /**
     * 从写回后的文件里读一个键，走框架自己那套 YAML 加载，不自己按行猜
     * @param name 完整路径
     * @return 值，键不在时为 null（与空串不是一回事）
     */
    private String property(String name) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new FileSystemResource(config));
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(name);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    @Test
    @DisplayName("清空 Redis 地址应删掉整行，而不是留下一个空值")
    void clearingRedisHostRemovesTheLine() throws Exception {
        // 旧码（把「空值即删行」那张表掏空后现跑）写出的实值是「      host: 」
        // （冒号后一个空格、后面什么都没有），不是带引号的 host: ""。
        // 两种都会让框架绑定到空串，下次启动抛 'host' must not be empty。
        service.write(Map.of("spring.data.redis.host", "127.0.0.1"));
        assertTrue(Files.readString(config).contains("host: 127.0.0.1"));
        assertEquals("7827", service.read().get("server.port"), "阴性：别的键一个字不动");

        service.write(Map.of("spring.data.redis.host", ""));

        String content = Files.readString(config);
        assertFalse(content.contains("spring.data.redis.host"), content);
        assertFalse(content.contains("host: \"\""), content);
        for (String line : content.lines().toList()) {
            String stripped = line.strip();
            if (stripped.startsWith("host:") && !line.contains("SMTP")) {
                fail("不应残留空的 Redis host 行: " + line);
            }
        }
        assertEquals("7827", service.read().get("server.port"));
        assertEquals("127.0.0.1", service.read().get("server.address"));
        assertNull(property("spring.data.redis.host"), "键删掉之后框架读出来必须是「没有」，不是空串");
        assertFalse(new TotalDataStorage.Settings(property("spring.data.redis.host"), 6379, null, 0).configured(),
                "删键后运行期判定应是未配置");
    }

    @Test
    @DisplayName("全空白与空串一样，清空 Redis 地址也是删行")
    void clearingHostWhitespaceRemovesTheLine() throws Exception {
        service.write(Map.of("spring.data.redis.host", "127.0.0.1"));
        service.write(Map.of("spring.data.redis.host", " \t "));

        assertNull(property("spring.data.redis.host"));
        assertFalse(new TotalDataStorage.Settings(property("spring.data.redis.host"), 6379, null, 0).configured());
        assertEquals("7827", service.read().get("server.port"));
    }

    @Test
    @DisplayName("本就没配过 Redis 时清空应什么都不做，不要凭空插入一个空值")
    void clearingAbsentRedisHostInsertsNothing() throws Exception {
        String before = Files.readString(config);

        List<String> changed = service.write(Map.of("spring.data.redis.host", ""));

        assertEquals(List.of(), changed);
        assertEquals(before, Files.readString(config));
    }

    @Test
    @DisplayName("其余配置项留空仍是有意义的取值，不能一并删掉")
    void clearingOtherPropertiesKeepsTheLine() throws Exception {
        service.write(Map.of("starbot.core.push.quiet-start", "23:00"));
        assertTrue(Files.readString(config).contains("quiet-start: \"23:00\""));

        // 静音时段留空表示不启用，这一行必须留着
        service.write(Map.of("starbot.core.push.quiet-start", ""));

        assertTrue(Files.readString(config).contains("quiet-start"),
                "留空是有效取值的配置项不该被删行");
    }

    @Test
    @DisplayName("时:分取值落盘要带引号，重启后读回仍是字符串而非六十进制整数")
    void clockTimeValueIsQuotedOnDisk() throws Exception {
        service.write(Map.of("starbot.core.push.quiet-start", "23:00"));

        Map<?, ?> root = new Yaml().load(content());
        Map<?, ?> push = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) root.get("starbot")).get("core")).get("push");
        assertEquals("23:00", push.get("quiet-start"),
                "裸写 quiet-start: 23:00 时 SnakeYAML 按 YAML 1.1 六十进制把它读成整数 1380");

        assertTrue(content().contains("quiet-start: \"23:00\""), content());
    }

    // ============ 值里带引号 ============

    @Test
    @DisplayName("值含引号时行尾注释不再吞进值")
    void valueWithQuoteKeepsTrailingCommentOutOfValue() throws IOException {
        // ① 撇号只是普通字符：旧判法把它当开了个没闭上的引号，后面的整段行尾注释被当成值回显
        service.write(Map.of("starbot.core.push.quiet-start", "It's"));
        assertEquals("It's", service.read().get("starbot.core.push.quiet-start"),
                "行尾注释不该被吞进值里");

        // ② 界面拿着回显值再存一次：回显值若已带注释，render 见 " #" 会加引号，注释真成了值的一部分
        List<String> rewritten = service.write(Map.of("starbot.core.push.quiet-start",
                service.read().get("starbot.core.push.quiet-start")));
        assertEquals(List.of(), rewritten, "盘上值与回显值一致时，再存一次应当没有任何改动");
        String line = content().lines().filter(l -> l.contains("quiet-start")).findFirst().orElseThrow();
        assertFalse(line.contains("\"It's"), "回显值再存不得把注释包进引号里: " + line);
        assertTrue(line.contains("# 静音时段开始"), "行尾注释应原样保留: " + line);

        // ③ 阳性对照：值本身以引号开头、引号内含 " #"，# 不能被当成注释起点提前截断
        service.write(Map.of("starbot.core.push.quiet-start", "\"a # b\""));
        String quoted = service.read().get("starbot.core.push.quiet-start");
        assertTrue(quoted.contains("a # b"), "引号内的 # 不是注释起点: " + quoted);
    }
}
