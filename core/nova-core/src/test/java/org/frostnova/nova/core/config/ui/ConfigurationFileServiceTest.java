package org.frostnova.nova.core.config.ui;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.frostnova.nova.core.service.TotalDataStorage;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
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
     * <b>请勿向本模板添加 {@code novabot.bilibili.dynamic.auto-save-image} 或
     * {@code novabot.core.paint.fonts}</b>：{@link #insertsMissingProperty()} 与
     * {@link #insertsMissingList()} 分别依赖这两个键「不存在」来验证插入逻辑，一旦加入用例即失效。
     * 需要新的样例配置项时，请另选一个本模板与这两个用例都未使用的键；
     * 列表那一个还须是元数据里当真登记过的列表型键。
     */
    private static final String TEMPLATE = """
            server:
              port: 7827                # 服务端口
              address: 127.0.0.1        # 监听地址

            spring:
              mail:
                host:                   # SMTP 服务器地址

            novabot:
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

    @Test
    @DisplayName("桩贡献者报键 X 时清空 X 不写进文件")
    void stubContributorBlankKeyIsOmittedOnWrite() throws IOException {
        BotConnectionContributor stub = new BotConnectionContributor() {
            @Override
            public String connectionListKey() {
                return "demo.list";
            }

            @Override
            public java.util.Set<String> blankMeansAbsentKeys() {
                return java.util.Set.of("demo.secret");
            }
        };
        Files.writeString(config, """
                demo:
                  secret: old
                server:
                  port: 1
                """, StandardCharsets.UTF_8);
        service = new ConfigurationFileService(config, () -> "x: 1\n", java.util.List.of(stub));

        service.write(java.util.Map.of("demo.secret", ""));

        String text = content();
        assertFalse(text.contains("secret:"), "申报后空值不得写进文件:\n" + text);

        Files.writeString(config, """
                demo:
                  secret: old
                server:
                  port: 1
                """, StandardCharsets.UTF_8);
        ConfigurationFileService coreOnly = new ConfigurationFileService(config, () -> "x: 1\n");
        coreOnly.write(java.util.Map.of("demo.secret", ""));
        assertTrue(content().contains("secret:"), "去掉申报后空值应留在文件里:\n" + content());
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
        assertEquals("true", values.get("novabot.core.config-ui.enabled"));
        assertEquals("1440", values.get("novabot.bilibili.dynamic.push-minutes"));
    }

    @Test
    @DisplayName("字符串列表按行读出")
    void readsStringList() throws IOException {
        assertEquals("127.0.0.1/32\n::1/128", service.read().get("novabot.core.config-ui.allow-ips"));
    }

    @Test
    @DisplayName("对象列表内部的键不会被当作配置路径")
    void ignoresObjectListItems() throws IOException {
        Map<String, String> values = service.read();

        assertFalse(values.containsKey("novabot.adapter.onebot.senders.api"));
        assertFalse(values.containsKey("novabot.adapter.onebot.senders.delay"));
    }

    @Test
    @DisplayName("修改标量值后文件内容随之改变")
    void writesScalar() throws IOException {
        List<String> changed = service.write(Map.of("novabot.bilibili.dynamic.push-minutes", "720"));

        assertEquals(List.of("novabot.bilibili.dynamic.push-minutes"), changed);
        assertTrue(content().contains("push-minutes: 720"));
        assertEquals("720", service.read().get("novabot.bilibili.dynamic.push-minutes"));
    }

    @Test
    @DisplayName("修改后行尾注释仍然保留")
    void keepsTrailingComment() throws IOException {
        service.write(Map.of("novabot.bilibili.dynamic.auto-follow", "false"));

        String line = content().lines().filter(l -> l.contains("auto-follow")).findFirst().orElseThrow();
        assertTrue(line.contains("false"), "值应已更新: " + line);
        assertTrue(line.contains("# 是否自动关注"), "行尾注释应保留: " + line);
    }

    @Test
    @DisplayName("仅改动目标行，其余内容逐行不变")
    void touchesOnlyTargetLines() throws IOException {
        List<String> before = content().lines().toList();
        service.write(Map.of("novabot.bilibili.dynamic.auto-follow", "false"));
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
        List<String> changed = service.write(Map.of("novabot.core.config-ui.allow-ips", "10.0.0.0/8\n192.168.0.0/16\n127.0.0.1/32"));

        assertEquals(List.of("novabot.core.config-ui.allow-ips"), changed);
        assertEquals("10.0.0.0/8\n192.168.0.0/16\n127.0.0.1/32", service.read().get("novabot.core.config-ui.allow-ips"));

        assertTrue(content().contains("      - 10.0.0.0/8"), "列表项缩进应与原文件一致:\n" + content());
        assertFalse(content().contains("::1/128"), "旧的列表项应被移除");
    }

    @Test
    @DisplayName("同时修改列表与标量互不干扰")
    void writesListAndScalarTogether() throws IOException {
        service.write(Map.of(
                "novabot.core.config-ui.allow-ips", "10.0.0.0/8",
                "novabot.bilibili.dynamic.push-minutes", "60",
                "server.port", "8080"
        ));

        Map<String, String> values = service.read();
        assertEquals("10.0.0.0/8", values.get("novabot.core.config-ui.allow-ips"));
        assertEquals("60", values.get("novabot.bilibili.dynamic.push-minutes"));
        assertEquals("8080", values.get("server.port"));
    }

    @Test
    @DisplayName("值未变化时不计入改动")
    void noChangeWhenValueIdentical() throws IOException {
        assertEquals(List.of(), service.write(Map.of("novabot.bilibili.dynamic.push-minutes", "1440")));
    }

    @Test
    @DisplayName("保存到一半失败时配置文件保持原样")
    void failedSaveLeavesConfigFileIntact() throws IOException {
        String original = content();
        Files.createDirectory(dir.resolve("application.yml.tmp"));

        assertThrows(IOException.class,
                () -> service.write(Map.of("novabot.bilibili.dynamic.push-minutes", "720")));

        assertEquals(original, content(),
                "写到一半失败时盘上的配置文件被改掉了，下次启动会掉进安全模式");
    }

    /**
     * 配置文件里有口令与令牌，第一次写出的那一份就该只有属主能读写：
     * 从宽权限起步再收紧的窗口里，同机别的账号能整份读走
     */
    @Test
    @DisplayName("首次写出的配置文件是仅属主可读写")
    void firstWrittenConfigIsOwnerOnly(@TempDir Path fresh) throws IOException {
        Path newConfig = fresh.resolve("application.yml");
        ConfigurationFileService first = new ConfigurationFileService(newConfig, () -> "server: 7827\n");

        assertTrue(first.createIfAbsent());

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(newConfig), "配置文件里有口令与令牌，新建时就得仅属主可读写");
    }

    @Test
    @DisplayName("写入后磁盘上应留下一份 .bak 备份")
    void writeLeavesBackupFile() throws IOException {
        service.write(Map.of("novabot.bilibili.dynamic.push-minutes", "30"));

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

    /**
     * 旧备份被裁掉时应往日志页记一条
     * <p>
     * 删除发生在保存的顺带一步里，此前只在 debug 级别留过一行。使用者去翻备份目录
     * 却发现少了几份，而没有任何地方说过它们是被谁、什么时候删的——
     * 「我明明存过那一版」与「被裁掉了」在目录里长得一模一样。
     * <p>
     * 阴性对照是<b>没裁掉任何东西的那几次保存</b>：每次保存都记一条「清理了 0 份」的话，
     * 真正删掉东西的那几条会淹在里面，而这一格照样绿。
     */
    @Test
    @DisplayName("裁掉旧备份时记一条「清理旧备份」，没裁到东西的那几次一条不记")
    void prunedBackupsLandOnTheLogPage() throws IOException {
        List<TimelineEvent> recorded = new ArrayList<>();
        service = new ConfigurationFileService(config, () -> TEMPLATE, () -> 2,
                Clock.systemDefaultZone(), recorded::add);

        // 前两次留在保留份数内，一份也裁不掉
        service.write(Map.of("server.port", "7000"));
        service.write(Map.of("server.port", "7001"));
        assertTrue(recorded.isEmpty(), "一份没裁的时候不该记, 否则真删掉东西的那几条会淹在里面: " + recorded);

        service.write(Map.of("server.port", "7002"));

        assertEquals(1, recorded.size(), "第三次保存挤掉最旧的一份, 该记一条: " + recorded);
        TimelineEvent event = recorded.get(0);
        assertEquals(TimelineEventType.BACKUP_PRUNED, event.type());
        assertEquals("2", event.detail().get("keep"));
        assertEquals(1, event.detail().get("pruned").split(",").length, event.detail().get("pruned"));
        assertEquals(2, stampedBackupNames().size(), "记下来的那一条得与盘上真剩几份对得上");
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
        service.writeListItemFields("novabot.adapter.onebot.senders", 0, Map.of("name", ""));

        String content = content();
        assertFalse(content.contains("name: qq-onebot"), content);
        assertTrue(content.matches("(?s).*\\n\\s+- api: /send\\n.*"),
                "短横必须跟着剩下的第一字段走，否则列表在这里断开:\n" + content);
        assertTrue(content.contains("delay: 1000"), content);
    }

    @Test
    @DisplayName("清空列表元素内部的字段应删掉该字段，而不是留下空值")
    void clearingListItemFieldRemovesTheField() throws IOException {
        int changed = service.writeListItemFields("novabot.adapter.onebot.senders", 0,
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
        service.writeListItemFields("novabot.adapter.onebot.senders", 0, Map.of("api", "/push"));

        String content = content();
        assertTrue(content.contains("api: /push"), content);
        assertTrue(content.contains("name: qq-onebot"), content);
    }

    @Test
    @DisplayName("可修改列表元素内部的字段")
    void writesFieldsInsideListItem() throws IOException {
        int changed = service.writeListItemFields("novabot.adapter.onebot.senders", 0,
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
        service.writeListItemFields("novabot.adapter.onebot.senders", 0, Map.of("port", "9999"));

        // server.port 与列表元素无关，不得被改写
        assertEquals("7827", service.read().get("server.port"));
    }

    @Test
    @DisplayName("列表元素字段删尽后整条去掉，列表回到空表")
    void clearingEveryListItemFieldRemovesTheItem() throws IOException {
        service.writeListItemFields("novabot.adapter.onebot.senders", 0,
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
                novabot:
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

        int changed = service.writeListItemFields("novabot.adapter.onebot.senders", 0, fields);

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
                () -> service.writeListItemFields("novabot.adapter.onebot.senders", 5, Map.of("api", "/x")));
        assertThrows(IOException.class,
                () -> service.writeListItemFields("starbot.not.exist", 0, Map.of("api", "/x")));
    }

    @Test
    @DisplayName("尚不存在的配置项会被插入到已有父节点之下")
    void insertsMissingProperty() throws IOException {
        List<String> changed = service.write(Map.of("novabot.bilibili.dynamic.auto-save-image", "true"));

        assertEquals(List.of("novabot.bilibili.dynamic.auto-save-image"), changed);
        assertEquals("true", service.read().get("novabot.bilibili.dynamic.auto-save-image"));
    }

    @Test
    @DisplayName("尚不存在的列表配置项写成 YAML 列表而非多行标量")
    void insertsMissingList() throws IOException {
        List<String> changed = service.write(Map.of("novabot.core.paint.fonts", "https://a.example\nhttps://b.example"));

        assertEquals(List.of("novabot.core.paint.fonts"), changed);
        assertTrue(content().contains("- https://a.example"), "应写成 YAML 列表:\n" + content());
        assertFalse(content().contains("\"https://a.example"), "不应写成带引号的多行标量:\n" + content());
        assertEquals("https://a.example\nhttps://b.example", service.read().get("novabot.core.paint.fonts"));
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
                "novabot.bilibili.dynamic.draw-logo", "true",
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
                () -> service.write(Map.of("novabot.core.push.quiet-start", "22:00\nevil")));

        assertTrue(error.getMessage().contains("novabot.core.push.quiet-start"), "报错要说清是哪一项: " + error.getMessage());
        assertEquals(before, content(), "拒绝时文件必须原样不动");
    }

    @Test
    @DisplayName("字符串列表的换行是分隔符，不受影响")
    void allowsMultilineForStringList() throws IOException {
        service.write(Map.of("novabot.core.config-ui.allow-ips", "127.0.0.1/32\n10.0.0.0/8"));

        // 读回来仍是以换行连接的一个串，与界面上的多行输入框一一对应
        assertEquals("127.0.0.1/32\n10.0.0.0/8", service.read().get("novabot.core.config-ui.allow-ips"));
    }

    @Test
    @DisplayName("换行不能凭空造出新的配置项")
    void multilineCannotInjectKeys() throws IOException {
        // 这一条即使当前已被拒绝也要留着：将来若放宽了限制，注入才是真正危险的那一面
        assertThrows(IOException.class,
                () -> service.write(Map.of("novabot.core.push.quiet-start", "x\n      enabled: false")));

        assertEquals("true", service.read().get("novabot.core.config-ui.enabled"), "既有配置项不该被顶掉");
    }

    // ============ 行内序列（key: []、key: [a, b]） ============

    /**
     * 首次安装写出的配置把空列表写成 {@code key: []}，这样的名单得按列表读写：
     * 读回空名单而不是字面「[]」，名单框里才空着、不标「已改」；
     * 一次填多行的保存就是这里的多行值，被当普通文字读时会被整批拒绝。
     */
    @Test
    @DisplayName("空名单写成 key: [] 也按名单读写：读是空的，能一次存多行")
    void flowEmptyListBehavesAsList() throws IOException {
        Files.writeString(config, """
                novabot:
                  core:
                    command:
                      admins: []          # 超级管理员
                """, StandardCharsets.UTF_8);
        String key = "novabot.core.command.admins";
        List<String> bad = new ArrayList<>();

        try {
            assertEquals("", service.read().get(key), "空名单应读成空串, 而不是字面 []");
        } catch (AssertionError e) {
            bad.add("① 读值: " + e.getMessage());
        }

        try {
            List<String> changed = service.write(Map.of(key, "111\n222"));
            assertEquals(List.of(key), changed);
            String text = content();
            assertTrue(text.contains("- 111"), "应落成每行一项的块序列:\n" + text);
            assertTrue(text.contains("- 222"), text);
            assertFalse(text.contains("[]"), "行内的空表记号必须让位给块序列, 两者并存整份文件解析不了:\n" + text);
            assertTrue(text.contains("# 超级管理员"), "行尾注释应保留:\n" + text);
            assertEquals("111\n222", service.read().get(key), "读回的形态与界面上的多行框一一对应");
        } catch (AssertionError | IOException e) {
            bad.add("② 存两行: " + e.getMessage());
        }

        try {
            Files.writeString(config, """
                    novabot:
                      core:
                        command:
                          admins: []
                    """, StandardCharsets.UTF_8);
            service.write(Map.of(key, "111"));
            assertEquals("111", service.read().get(key), "只填一行也应写成列表");
            service.write(Map.of(key, "111\n222"));
            assertEquals("111\n222", service.read().get(key), "单行之后再改成多行, 不该被当成标量拦下");
        } catch (AssertionError | IOException e) {
            bad.add("③ 一行再改多行: " + e.getMessage());
        }

        try {
            service.write(Map.of(key, ""));
            String text = content();
            assertTrue(text.contains("admins: []"), "清空应写成空表记号, 键这一行保住列表身份:\n" + text);
            assertEquals("", service.read().get(key));
            service.write(Map.of(key, "111\n222"));
            assertEquals("111\n222", service.read().get(key), "清空之后再想填回多行, 也不该被拦下");
        } catch (AssertionError | IOException e) {
            bad.add("④ 清空再填: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(), () -> "空名单行内写法 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 行内写法 {@code key: [a, b]} 与空表 {@code key: []} 同族：读按名单拆项，
     * 存过一次落成每行一项。引号包着的 {@code "[]"} 是逐字的文字值，不在其列。
     */
    @Test
    @DisplayName("行内写法 key: [a, b] 同按名单读写；带引号的 \"[]\" 仍是普通文字")
    void inlineListItemsBehaveAsList() throws IOException {
        Files.writeString(config, """
                novabot:
                  core:
                    command:
                      admins: [111, 222]
                    push:
                      quiet-start: "[]"
                """, StandardCharsets.UTF_8);
        String key = "novabot.core.command.admins";
        List<String> bad = new ArrayList<>();

        try {
            assertEquals("111\n222", service.read().get(key), "行内各项应按名单读回");
        } catch (AssertionError e) {
            bad.add("① 行内读值: " + e.getMessage());
        }

        try {
            service.write(Map.of(key, "333"));
            String text = content();
            assertTrue(text.contains("- 333"), "存过一次应落成每行一项:\n" + text);
            String adminsLine = text.lines().filter(l -> l.contains("admins")).findFirst().orElseThrow();
            assertFalse(adminsLine.contains("["), "行内方括号应让位给块序列:\n" + adminsLine);
            assertEquals("333", service.read().get(key));
        } catch (AssertionError | IOException e) {
            bad.add("② 行内改块: " + e.getMessage());
        }

        try {
            assertEquals("[]", service.read().get("novabot.core.push.quiet-start"),
                    "引号包着的字面 [] 是普通文字值, 不得被当成空名单");
        } catch (AssertionError e) {
            bad.add("③ 引号阴性: " + e.getMessage());
        }

        try {
            Files.writeString(config, """
                    novabot:
                      core:
                        command:
                          admins: ["111,222", 333]
                    """, StandardCharsets.UTF_8);
            assertEquals("111,222\n333", service.read().get(key), "引号里的逗号是字面字符, 不是分隔符");
        } catch (AssertionError | IOException e) {
            bad.add("④ 引号内逗号: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(), () -> "行内名单 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 手写配置会把行内名单折成几行（{@code admins: [111,} 换行 {@code 222]}）。
     * 行内序列里的换行只是空白，得跨行拼起来按名单读；保存时它占的所有行整体让位给
     * 每行一项的块序列——只改键那一行会给文件留下半截方括号，整份配置从此读不了，
     * 而保存接口照样回报成功。
     */
    @Test
    @DisplayName("跨行写的行内名单按名单读写：保存后整块换成每行一项, 文件仍能整份读")
    void multilineInlineListBehavesAsList() throws IOException {
        Files.writeString(config, """
                novabot:
                  core:
                    command:
                      admins: [111,          # 超级管理员
                        222]
                      prefix: '!'            # 命令前缀
                """, StandardCharsets.UTF_8);
        String key = "novabot.core.command.admins";
        List<String> bad = new ArrayList<>();

        try {
            assertEquals("111\n222", service.read().get(key), "跨行的行内名单应拼起来按名单读回");
        } catch (AssertionError e) {
            bad.add("① 跨行读值: " + e.getMessage());
        }

        try {
            service.write(Map.of(key, "333"));
            String text = content();
            assertTrue(text.contains("- 333"), "存过一次应落成每行一项:\n" + text);
            assertFalse(text.lines().anyMatch(l -> l.strip().equals("222]")),
                    "续行必须整块让位, 留下来就是孤行:\n" + text);
            String adminsLine = text.lines().filter(l -> l.contains("admins")).findFirst().orElseThrow();
            assertFalse(adminsLine.contains("["), "键这一行上的半截方括号必须让位:\n" + adminsLine);
        } catch (AssertionError | IOException e) {
            bad.add("② 跨行存: " + e.getMessage());
        }

        try {
            Map<String, Object> whole = new Yaml().load(Files.readString(config, StandardCharsets.UTF_8));
            @SuppressWarnings("unchecked")
            Map<String, Object> command = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) whole.get("novabot")).get("core")).get("command");
            assertEquals("[333]", String.valueOf(command.get("admins")), "保存后的文件须还能整份读回同样的名单");
        } catch (AssertionError | RuntimeException e) {
            bad.add("③ 整份可读: " + e.getMessage());
        }

        try {
            assertEquals("!", service.read().get("novabot.core.command.prefix"), "相邻键不该被写坏");
        } catch (AssertionError e) {
            bad.add("④ 相邻键: " + e.getMessage());
        }

        try {
            Files.writeString(config, """
                    novabot:
                      core:
                        command:
                          admins: [111,
                            222]
                    """, StandardCharsets.UTF_8);
            service.write(Map.of(key, "111\n222"));
            assertEquals("111\n222", service.read().get(key), "一次存多行也不该被当成标量拦下");
            Map<String, Object> whole = new Yaml().load(Files.readString(config, StandardCharsets.UTF_8));
            @SuppressWarnings("unchecked")
            Map<String, Object> command = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) whole.get("novabot")).get("core")).get("command");
            assertEquals("[111, 222]", String.valueOf(command.get("admins")), "存多行后文件仍能整份读回同样的名单");
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("⑤ 存多行: " + e.getMessage());
        }

        try {
            // 认不全的写法（跨行且元素是对象）宁可拒存并说清原因，也不许写坏文件
            Files.writeString(config, """
                    novabot:
                      core:
                        command:
                          admins: [111,
                            {a: 1}]
                    """, StandardCharsets.UTF_8);
            String before = content();
            int filesBefore;
            try (var files = Files.list(dir)) {
                filesBefore = (int) files.count();
            }
            IOException refused = assertThrows(IOException.class,
                    () -> service.write(Map.of(key, "x")),
                    "认不全的跨行写法应拒存, 而不是只改键那一行留下孤行");
            assertTrue(refused.getMessage().contains(key), "拒存的原因里要说是哪一项: " + refused.getMessage());
            assertEquals(before, content(), "拒存时文件一个字节都不能动");
            try (var files = Files.list(dir)) {
                assertEquals(filesBefore, files.count(), "拒存不是保存, 不该多出备份");
            }
        } catch (AssertionError | IOException e) {
            bad.add("⑥ 认不全拒存: " + e.getMessage());
        }

        try {
            // 对象列表的键这一行同理：跨行/内嵌的行内写法就地改字段会留下半截, 一样拒存
            Files.writeString(config, """
                    novabot:
                      adapter:
                        onebot:
                          senders: [{name: a},
                            {name: b}]
                    """, StandardCharsets.UTF_8);
            String before = content();
            IOException refused = assertThrows(IOException.class,
                    () -> service.writeListItemFields("novabot.adapter.onebot.senders", 0, Map.of("name", "qq-new")),
                    "对象列表的跨行行内写法应拒存, 而不是建元素留下孤行");
            assertTrue(refused.getMessage().contains("senders"), "拒存的原因里要说是哪一项: " + refused.getMessage());
            assertEquals(before, content(), "拒存时文件一个字节都不能动");
        } catch (AssertionError | IOException e) {
            bad.add("⑦ 对象列表拒存: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(), () -> "跨行行内名单 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 名单元素里带转义引号时按 YAML 引号规则还原：双引号里的 {@code \"} 与 {@code \\}、
     * 单引号里的 {@code ''}。写回时 render 按同样规则转义，存一次再读回还是原来的值。
     */
    @Test
    @DisplayName("名单元素里的转义引号按 YAML 规则还原：双引号 \\\" 与 \\\\、单引号 ''")
    void quotedListItemsUnescape() throws IOException {
        Files.writeString(config, "novabot:\n"
                + "  core:\n"
                + "    command:\n"
                + "      admins: [\"a\\\"b\", 'it''s', \"c\\\\d\"]\n", StandardCharsets.UTF_8);
        String key = "novabot.core.command.admins";
        List<String> bad = new ArrayList<>();

        try {
            assertEquals("a\"b\nit's\nc\\d", service.read().get(key), "引号里的转义应还原成它包住的字符");
        } catch (AssertionError e) {
            bad.add("① 转义读值: " + e.getMessage());
        }

        try {
            service.write(Map.of(key, "a\"b\nit's\nc\\d"));
            assertEquals("a\"b\nit's\nc\\d", service.read().get(key), "存一次再读回应是同样的值");
            Map<String, Object> whole = new Yaml().load(Files.readString(config, StandardCharsets.UTF_8));
            @SuppressWarnings("unchecked")
            Map<String, Object> command = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) whole.get("novabot")).get("core")).get("command");
            assertEquals("[a\"b, it's, c\\d]", String.valueOf(command.get("admins")), "写出的块序列本身也是合法的名单");
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("② 转义存: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(), () -> "转义引号 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 行内名单行尾多写的逗号只是分隔符的痕迹，不是一项：读回不该多出一个空行，
     * 与读到的内容原样再存一次也不该被当成改过。
     */
    @Test
    @DisplayName("行内名单的尾逗号不读出空项")
    void trailingCommaYieldsNoEmptyItem() throws IOException {
        Files.writeString(config, """
                novabot:
                  core:
                    command:
                      admins: [111, 222, ]
                """, StandardCharsets.UTF_8);
        String key = "novabot.core.command.admins";
        List<String> bad = new ArrayList<>();

        try {
            assertEquals("111\n222", service.read().get(key), "尾逗号不是一项, 不该多出空行");
        } catch (AssertionError e) {
            bad.add("① 尾逗号读值: " + e.getMessage());
        }

        try {
            List<String> changed = service.write(Map.of(key, "111\n222"));
            assertTrue(changed.isEmpty(), "读到的与要存的一致, 不该被当成改过: " + changed);
            assertEquals("111\n222", service.read().get(key), "原样再存一次后仍是两项");
        } catch (AssertionError | IOException e) {
            bad.add("② 尾逗号再存: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(), () -> "尾逗号 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 手写配置里空对象列表可以写成 {@code senders: [ ]}——方括号里只有空白，仍是合法的空表。
     * 建第一个元素走的路与 {@code []} 同一条：方括号让位给块序列，存后文件整份仍可读。
     */
    @Test
    @DisplayName("手写 senders: [ ] 是合法空表：建第一项不拒存, 存后文件整份可读")
    void spacedEmptyListCreatesFirstItem() throws IOException {
        Files.writeString(config, """
                novabot:
                  adapter:
                    onebot:
                      senders: [ ]
                """, StandardCharsets.UTF_8);
        List<String> bad = new ArrayList<>();

        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("name", "qq-onebot");
            fields.put("api", "/send");
            int changed = service.writeListItemFields("novabot.adapter.onebot.senders", 0, fields);
            assertEquals(2, changed, "两个非空字段都应写下");
            String text = content();
            assertTrue(text.contains("- name: qq-onebot"), "第一项应落成每行一项的块序列:\n" + text);
            assertFalse(text.contains("["), "空表记号应让位给块序列, 两者并存整份文件解析不了:\n" + text);
        } catch (AssertionError | IOException e) {
            bad.add("① 建第一项: " + e.getMessage());
        }

        try {
            Map<String, Object> whole = new Yaml().load(Files.readString(config, StandardCharsets.UTF_8));
            @SuppressWarnings("unchecked")
            Map<String, Object> onebot = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) whole.get("novabot")).get("adapter")).get("onebot");
            assertEquals("[{name=qq-onebot, api=/send}]", String.valueOf(onebot.get("senders")),
                    "存后的文件须还能整份读回第一个元素");
        } catch (AssertionError | RuntimeException e) {
            bad.add("② 整份可读: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(), () -> "带空白空表 " + bad.size() + " 问未销: " + String.join("; ", bad));
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
        service.write(Map.of("novabot.core.push.quiet-start", "23:00"));
        assertTrue(Files.readString(config).contains("quiet-start: \"23:00\""));

        // 静音时段留空表示不启用，这一行必须留着
        service.write(Map.of("novabot.core.push.quiet-start", ""));

        assertTrue(Files.readString(config).contains("quiet-start"),
                "留空是有效取值的配置项不该被删行");
    }

    @Test
    @DisplayName("时:分取值落盘要带引号，重启后读回仍是字符串而非六十进制整数")
    void clockTimeValueIsQuotedOnDisk() throws Exception {
        service.write(Map.of("novabot.core.push.quiet-start", "23:00"));

        Map<?, ?> root = new Yaml().load(content());
        Map<?, ?> push = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) root.get("novabot")).get("core")).get("push");
        assertEquals("23:00", push.get("quiet-start"),
                "裸写 quiet-start: 23:00 时 SnakeYAML 按 YAML 1.1 六十进制把它读成整数 1380");

        assertTrue(content().contains("quiet-start: \"23:00\""), content());
    }

    @Test
    @DisplayName("# 前无空白不算注释：a#b、链接锚点整个是值；空白后的 # 才是注释")
    void hashWithoutLeadingWhitespaceIsNotComment() throws IOException {
        String key = "novabot.core.push.quiet-start";
        List<String> unresolved = new ArrayList<>();

        // ① # 紧贴前文：写盘再读回必须是整个值，行尾注释不丢，回显值再存不该有改动
        try {
            service.write(Map.of(key, "a#b"));
            assertEquals("a#b", service.read().get(key), "a#b 里的 # 是值的一部分, 不该在 # 前截断");
            List<String> rewritten = service.write(Map.of(key, service.read().get(key)));
            assertEquals(List.of(), rewritten, "盘上值与回显值一致时, 再存一次应当没有任何改动");
            String line = content().lines().filter(l -> l.contains("quiet-start")).findFirst().orElseThrow();
            assertTrue(line.contains("# 静音时段开始"), "行尾注释应原样保留: " + line);
        } catch (AssertionError | IOException e) {
            unresolved.add("① a#b: " + e.getMessage());
        }

        // ② URL 锚点的 # 同样是值的一部分：回显、再存都与 ① 同形
        try {
            service.write(Map.of(key, "http://x/#frag"));
            assertEquals("http://x/#frag", service.read().get(key), "链接锚点不该在 # 前截断");
            List<String> rewritten = service.write(Map.of(key, service.read().get(key)));
            assertEquals(List.of(), rewritten, "盘上值与回显值一致时, 再存一次应当没有任何改动");
            String line = content().lines().filter(l -> l.contains("quiet-start")).findFirst().orElseThrow();
            assertTrue(line.contains("# 静音时段开始"), "行尾注释应原样保留: " + line);
        } catch (AssertionError | IOException e) {
            unresolved.add("② http://x/#frag: " + e.getMessage());
        }

        // ③ 阳性对照：# 前有空白才是注释起点；含 " #" 的值 render 会加引号，读回是完整值
        try {
            service.write(Map.of(key, "x #y"));
            assertEquals("x #y", service.read().get(key), "含空格井号的值落盘带引号, 读回应是完整值");
        } catch (AssertionError | IOException e) {
            unresolved.add("③ x #y: " + e.getMessage());
        }

        assertTrue(unresolved.isEmpty(),
                () -> "井号三问中 " + unresolved.size() + " 问未销: " + String.join("; ", unresolved));
    }

    // ============ 值里带引号 ============

    @Test
    @DisplayName("值含引号时行尾注释不再吞进值")
    void valueWithQuoteKeepsTrailingCommentOutOfValue() throws IOException {
        // ① 撇号只是普通字符：旧判法把它当开了个没闭上的引号，后面的整段行尾注释被当成值回显
        service.write(Map.of("novabot.core.push.quiet-start", "It's"));
        assertEquals("It's", service.read().get("novabot.core.push.quiet-start"),
                "行尾注释不该被吞进值里");

        // ② 界面拿着回显值再存一次：回显值若已带注释，render 见 " #" 会加引号，注释真成了值的一部分
        List<String> rewritten = service.write(Map.of("novabot.core.push.quiet-start",
                service.read().get("novabot.core.push.quiet-start")));
        assertEquals(List.of(), rewritten, "盘上值与回显值一致时，再存一次应当没有任何改动");
        String line = content().lines().filter(l -> l.contains("quiet-start")).findFirst().orElseThrow();
        assertFalse(line.contains("\"It's"), "回显值再存不得把注释包进引号里: " + line);
        assertTrue(line.contains("# 静音时段开始"), "行尾注释应原样保留: " + line);

        // ③ 阳性对照：值本身以引号开头、引号内含 " #"，# 不能被当成注释起点提前截断
        service.write(Map.of("novabot.core.push.quiet-start", "\"a # b\""));
        String quoted = service.read().get("novabot.core.push.quiet-start");
        assertTrue(quoted.contains("a # b"), "引号内的 # 不是注释起点: " + quoted);
    }

    // ============ 旧根不再折叠 ============

    @Test
    @DisplayName("新旧根全镜像保存后旧根仍在，日志无折叠字样")
    void saveKeepsLegacyRootEvenWhenFullyMirrored() throws Exception {
        Files.writeString(config, """
                server:
                  port: 7827

                starbot:
                  core:
                    log:
                      event-log: true
                    event-stream:
                      enabled: true

                novabot:
                  core:
                    log:
                      event-log: false
                    event-stream:
                      enabled: false
                """, StandardCharsets.UTF_8);

        Logger logger = (Logger) LoggerFactory.getLogger(ConfigurationFileService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        List<String> unresolved = new ArrayList<>();
        try {
            service.write(Map.of("novabot.core.log.event-log", "true"));
            String text = content();

            try {
                assertTrue(text.lines().anyMatch(line -> line.startsWith("starbot:")),
                        "保存后 starbot 根应原样在:\n" + text);
                assertTrue(text.contains("novabot:"), "新树应仍在:\n" + text);
            } catch (AssertionError e) {
                unresolved.add("① 旧根仍在: " + e.getMessage());
            }

            try {
                List<String> messages = appender.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .toList();
                assertTrue(messages.stream().noneMatch(line -> line.contains("已迁移旧键")),
                        "日志不应有折叠字样, 实有: " + messages);
            } catch (AssertionError e) {
                unresolved.add("② 日志无折叠: " + e.getMessage());
            }

            try {
                assertEquals("true", service.read().get("novabot.core.log.event-log"),
                        "本次写入的新树键应已落下");
            } catch (AssertionError | IOException e) {
                unresolved.add("③ 新树取值: " + e.getMessage());
            }
        } finally {
            logger.detachAppender(appender);
        }

        assertTrue(unresolved.isEmpty(),
                () -> "旧根不折叠三问中 " + unresolved.size() + " 问未销: " + String.join("; ", unresolved));
    }

    @Test
    @DisplayName("读到 InetAddress.toString 形态的监听地址时收成点分地址")
    void readsSlashLoopbackAsHostAddress() throws IOException {
        List<String> bad = new ArrayList<>();
        try {
            Files.writeString(config, """
                    server:
                      address: /127.0.0.1
                      port: 7827
                    """, StandardCharsets.UTF_8);
            assertEquals("127.0.0.1", service.read().get("server.address"));
        } catch (AssertionError | IOException e) {
            bad.add("① 斜杠形态: " + e.getMessage());
        }
        try {
            Files.writeString(config, """
                    server:
                      address: localhost/127.0.0.1
                      port: 7827
                    """, StandardCharsets.UTF_8);
            assertEquals("127.0.0.1", service.read().get("server.address"));
        } catch (AssertionError | IOException e) {
            bad.add("② 主机名/地址形态: " + e.getMessage());
        }
        try {
            Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);
            assertEquals("127.0.0.1", service.read().get("server.address"),
                    "阴性：本来就是点分的不得改写");
            assertEquals("127.0.0.1/32", InetAddressText.fromFile("127.0.0.1/32"),
                    "CIDR 直呼 fromFile 不得改写");
            assertEquals("::1/128", InetAddressText.fromFile("::1/128"),
                    "IPv6 CIDR 直呼 fromFile 不得改写");
        } catch (AssertionError | IOException e) {
            bad.add("③ 阴性: " + e.getMessage());
        }
        assertTrue(bad.isEmpty(), "监听地址读数三问中未销: " + String.join("; ", bad));
    }

    @Test
    @DisplayName("读到斜杠形态时把文件改回裸地址，同一实例只改一次，并记一行说明")
    void healsSlashAddressInFileOnce() throws IOException {
        List<String> bad = new ArrayList<>();
        Logger logger = (Logger) LoggerFactory.getLogger(ConfigurationFileService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Files.writeString(config, """
                    server:
                      address: /127.0.0.1
                      port: 7827
                    """, StandardCharsets.UTF_8);
            assertEquals("127.0.0.1", service.read().get("server.address"));
            String text = content();
            try {
                assertTrue(text.contains("address: 127.0.0.1"),
                        "文件应改回裸地址, 实有:\n" + text);
                assertFalse(text.contains("address: /127.0.0.1"),
                        "斜杠形态应已从文件消失, 实有:\n" + text);
            } catch (AssertionError e) {
                bad.add("① 文件自愈: " + e.getMessage());
            }
            try {
                assertTrue(appender.list.stream().anyMatch(event ->
                                event.getLevel() == ch.qos.logback.classic.Level.INFO
                                        && event.getFormattedMessage().contains("已改回")),
                        "日志应为 INFO 且含「已改回」, 实有: "
                                + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
            } catch (AssertionError e) {
                bad.add("② INFO: " + e.getMessage());
            }
            try {
                Files.writeString(config, """
                        server:
                          address: /127.0.0.1
                          port: 7827
                        """, StandardCharsets.UTF_8);
                long backupsBefore = backupCount();
                String slashAgain = content();
                assertEquals("127.0.0.1", service.read().get("server.address"));
                assertEquals(slashAgain, content(), "自愈后再写成斜杠, 同一实例不得再改文件");
                assertEquals(backupsBefore, backupCount(), "同一实例不得再留备份");
            } catch (AssertionError | IOException e) {
                bad.add("③ 只改一次: " + e.getMessage());
            }
            try {
                Files.writeString(config, """
                        server:
                          address: 127.0.0.1
                          port: 7827
                        """, StandardCharsets.UTF_8);
                long backupsBefore = backupCount();
                String naked = content();
                ConfigurationFileService restarted = new ConfigurationFileService(config);
                assertEquals("127.0.0.1", restarted.read().get("server.address"));
                assertEquals(naked, content(), "再起已是裸地址时不得写盘");
                assertEquals(backupsBefore, backupCount(), "再起不得再留备份");
            } catch (AssertionError | IOException e) {
                bad.add("④ 再起不再改: " + e.getMessage());
            }
        } finally {
            logger.detachAppender(appender);
        }
        assertTrue(bad.isEmpty(), "自愈格未销 " + bad.size() + " 问: " + String.join("; ", bad));
    }

    @Test
    @DisplayName("启动时不打开设置页也会把斜杠形态改回")
    void healsSlashAddressOnStartWithoutOpeningSettings() throws IOException {
        Files.writeString(config, """
                server:
                  address: /127.0.0.1
                  port: 7827
                """, StandardCharsets.UTF_8);
        ConfigurationFileService started = new ConfigurationFileService(config);
        started.healSlashAddressOnStart();
        String text = content();
        assertTrue(text.contains("address: 127.0.0.1"), "启动自愈后文件应是裸地址, 实有:\n" + text);
        assertFalse(text.contains("address: /127.0.0.1"), "斜杠形态应已消失, 实有:\n" + text);
        assertEquals("127.0.0.1", started.read().get("server.address"));
    }

    /**
     * 启动时把斜杠地址改回来的那一次早于口令哈希化：同一文件里还手写着明文口令时，
     * 这次写要是留备份，同目录就多出一份抄着明文的副本，要再存够十次才挤掉。
     * 自愈只改地址写法、用不着退回，不留备份；普通保存照旧留。
     */
    @Test
    @DisplayName("启动时改回斜杠地址不留备份：同目录不多出抄着明文口令的副本，普通保存照旧留备份")
    void healsSlashAddressOnStartWithoutBackup() throws IOException {
        List<String> bad = new ArrayList<>();
        Files.writeString(config, """
                server:
                  address: /127.0.0.1
                  port: 7827
                novabot:
                  core:
                    config-ui:
                      auth:
                        password: plain-text-secret-900
                """, StandardCharsets.UTF_8);
        long backupsBefore = backupCount();
        ConfigurationFileService started = new ConfigurationFileService(config);
        started.healSlashAddressOnStart();
        try {
            assertFalse(content().contains("address: /127.0.0.1"), "斜杠形态应已消失, 实有:\n" + content());
        } catch (AssertionError e) {
            bad.add("① 自愈: " + e.getMessage());
        }
        try {
            assertEquals(backupsBefore, backupCount(), "自愈那一次不该留备份");
            assertEquals("同目录未留含明文的备份",
                    started.backupSituation("novabot.core.config-ui.auth.password", "plain-text-secret-900"),
                    "同目录多出了抄着明文口令的副本");
        } catch (AssertionError e) {
            bad.add("② 不留备份: " + e.getMessage());
        }
        try {
            long beforeSave = backupCount();
            started.write(Map.of("server.port", "7828"));
            assertEquals(beforeSave + 1, backupCount(), "普通保存照旧该留一份备份");
        } catch (AssertionError e) {
            bad.add("③ 普通保存留备份: " + e.getMessage());
        }
        assertTrue(bad.isEmpty(), "自愈不留备份格未销 " + bad.size() + " 问: " + String.join("; ", bad));
    }

    @Test
    @DisplayName("管理端口监听地址斜杠形态也归一")
    void healsManagementServerAddressSlash() throws IOException {
        List<String> bad = new ArrayList<>();
        Files.writeString(config, """
                server:
                  address: 127.0.0.1
                  port: 7827
                management:
                  server:
                    address: /127.0.0.1
                    port: 7828
                """, StandardCharsets.UTF_8);
        try {
            assertTrue(ConfigurationFileService.isAddressKey("management.server.address"));
            assertTrue(ConfigurationFileService.isAddressKey("server.address"));
            assertFalse(ConfigurationFileService.isAddressKey("novabot.core.demo-mask"));
        } catch (AssertionError e) {
            bad.add("① 地址键: " + e.getMessage());
        }
        try {
            assertEquals("127.0.0.1", service.read().get("management.server.address"));
        } catch (AssertionError | IOException e) {
            bad.add("② 读口: " + e.getMessage());
        }
        try {
            String text = content();
            assertFalse(text.contains("address: /127.0.0.1"), "斜杠形态应已从文件消失, 实有:\n" + text);
        } catch (AssertionError | IOException e) {
            bad.add("③ 文件自愈: " + e.getMessage());
        }
        assertTrue(bad.isEmpty(), "管理端口监听地址格未销: " + String.join("; ", bad));
    }

    @Test
    @DisplayName("非监听地址键的掩码写法原样保留")
    void doesNotRewriteMaskOnOtherKeys() throws IOException {
        List<String> bad = new ArrayList<>();
        Files.writeString(config, """
                server:
                  address: 127.0.0.1
                  port: 7827
                novabot:
                  core:
                    demo-mask: 10.0.0.0/255.255.255.0
                """, StandardCharsets.UTF_8);
        Map<String, String> values = service.read();
        try {
            assertEquals("10.0.0.0/255.255.255.0", values.get("novabot.core.demo-mask"),
                    "掩码不得被收成斜杠后半段");
        } catch (AssertionError e) {
            bad.add("① 读口: " + e.getMessage());
        }
        try {
            assertTrue(content().contains("demo-mask: 10.0.0.0/255.255.255.0"),
                    "文件里的掩码也不得被改写:\n" + content());
        } catch (AssertionError | IOException e) {
            bad.add("② 文件: " + e.getMessage());
        }
        try {
            assertEquals("127.0.0.1", values.get("server.address"));
        } catch (AssertionError e) {
            bad.add("③ 监听地址阴性: " + e.getMessage());
        }
        assertTrue(bad.isEmpty(), "阴性格未销 " + bad.size() + " 问: " + String.join("; ", bad));
    }
}
