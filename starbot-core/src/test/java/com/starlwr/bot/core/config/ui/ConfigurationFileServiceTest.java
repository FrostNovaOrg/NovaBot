package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
        int changed = service.write(Map.of("starbot.bilibili.dynamic.push-minutes", "720"));

        assertEquals(1, changed);
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
        int changed = service.write(Map.of("starbot.core.config-ui.allow-ips", "10.0.0.0/8\n192.168.0.0/16\n127.0.0.1/32"));

        assertEquals(1, changed);
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
        assertEquals(0, service.write(Map.of("starbot.bilibili.dynamic.push-minutes", "1440")));
    }

    @Test
    @DisplayName("写入前会备份原文件")
    void createsBackup() throws IOException {
        service.write(Map.of("starbot.bilibili.dynamic.push-minutes", "30"));

        List<String> backups = service.listBackups();
        assertEquals(1, backups.size(), "应生成一份备份");
        assertTrue(service.readBackup(backups.get(0)).contains("push-minutes: 1440"), "备份应保有修改前的内容");
    }

    @Test
    @DisplayName("多次保存应各留一份备份, 而非互相覆盖")
    void keepsBackupPerSave() throws IOException {
        service.write(Map.of("starbot.bilibili.dynamic.push-minutes", "30"));
        // 备份名精确到秒，同秒内的两次保存会落到同一文件名，因此此处跨秒再保存一次
        sleepPastSecond();
        service.write(Map.of("starbot.bilibili.dynamic.push-minutes", "60"));

        List<String> backups = service.listBackups();
        assertEquals(2, backups.size(), "两次保存应留下两份备份");
        assertTrue(service.readBackup(backups.get(0)).contains("push-minutes: 30"), "最新备份应是上一次保存后的内容");
        assertTrue(service.readBackup(backups.get(1)).contains("push-minutes: 1440"), "较早的备份应是最初的内容");
    }

    @Test
    @DisplayName("可回滚至指定备份, 且回滚本身也会先备份")
    void restoresBackup() throws IOException {
        service.write(Map.of("starbot.bilibili.dynamic.push-minutes", "30"));
        String original = service.listBackups().get(0);

        sleepPastSecond();
        service.restoreBackup(original);

        assertEquals("1440", service.read().get("starbot.bilibili.dynamic.push-minutes"), "内容应已回到备份中的取值");
        assertTrue(service.listBackups().size() >= 2, "回滚前应先把当前内容也备份下来, 以便再滚回去");
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
    @DisplayName("列表或元素不存在时应明确报错, 而非静默无操作")
    void failsWhenListItemMissing() {
        assertThrows(IOException.class,
                () -> service.writeListItemFields("starbot.adapter.onebot.senders", 5, Map.of("api", "/x")));
        assertThrows(IOException.class,
                () -> service.writeListItemFields("starbot.not.exist", 0, Map.of("api", "/x")));
    }

    @Test
    @DisplayName("非法的备份文件名应被拒绝, 防止越权读写")
    void rejectsIllegalBackupName() {
        assertThrows(IOException.class, () -> service.readBackup("../../etc/passwd"));
        assertThrows(IOException.class, () -> service.readBackup("application.yml"));
        assertThrows(IOException.class, () -> service.readBackup(null));
    }

    /**
     * 等到下一秒，使相邻两次保存产生不同的备份文件名
     */
    private void sleepPastSecond() {
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("等待被中断");
        }
    }

    @Test
    @DisplayName("尚不存在的配置项会被插入到已有父节点之下")
    void insertsMissingProperty() throws IOException {
        int changed = service.write(Map.of("starbot.bilibili.dynamic.auto-save-image", "true"));

        assertEquals(1, changed);
        assertEquals("true", service.read().get("starbot.bilibili.dynamic.auto-save-image"));
    }

    @Test
    @DisplayName("尚不存在的列表配置项写成 YAML 列表而非多行标量")
    void insertsMissingList() throws IOException {
        int changed = service.write(Map.of("starbot.core.plugin.maven-base-urls", "https://a.example\nhttps://b.example"));

        assertEquals(1, changed);
        assertTrue(content().contains("- https://a.example"), "应写成 YAML 列表:\n" + content());
        assertFalse(content().contains("\"https://a.example"), "不应写成带引号的多行标量:\n" + content());
        assertEquals("https://a.example\nhttps://b.example", service.read().get("starbot.core.plugin.maven-base-urls"));
    }

    @Test
    @DisplayName("整体覆盖写入后可原样读回")
    void writeRawRoundTrip() throws IOException {
        service.writeRaw("starbot:\n  core:\n    config-ui:\n      enabled: false\n");

        assertEquals("false", service.read().get("starbot.core.config-ui.enabled"));
        assertEquals(1, service.listBackups().size(), "整体覆盖前同样应先备份");
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

    @Test
    @DisplayName("清空 Redis 地址应删掉整行，而不是留下一个空值")
    void clearingRedisHostRemovesTheLine() throws Exception {
        // 留下「host: 」会让 Spring 启动时直接抛 'host' must not be empty，
        // 连配置界面都起不来，只能去手改文件——这个坑真踩过
        service.write(Map.of("spring.data.redis.host", "127.0.0.1"));
        assertTrue(Files.readString(config).contains("host: 127.0.0.1"));

        service.write(Map.of("spring.data.redis.host", ""));

        String content = Files.readString(config);
        assertFalse(content.contains("spring.data.redis.host"), content);
        for (String line : content.lines().toList()) {
            assertFalse(line.strip().equals("host:") || line.strip().startsWith("host: #"),
                    "不应残留空的 host 行: " + line);
        }
    }

    @Test
    @DisplayName("本就没配过 Redis 时清空应什么都不做，不要凭空插入一个空值")
    void clearingAbsentRedisHostInsertsNothing() throws Exception {
        String before = Files.readString(config);

        int changed = service.write(Map.of("spring.data.redis.host", ""));

        assertEquals(0, changed);
        assertEquals(before, Files.readString(config));
    }

    @Test
    @DisplayName("其余配置项留空仍是有意义的取值，不能一并删掉")
    void clearingOtherPropertiesKeepsTheLine() throws Exception {
        service.write(Map.of("starbot.core.push.quiet-start", "23:00"));
        assertTrue(Files.readString(config).contains("quiet-start: 23:00"));

        // 静音时段留空表示不启用，这一行必须留着
        service.write(Map.of("starbot.core.push.quiet-start", ""));

        assertTrue(Files.readString(config).contains("quiet-start"),
                "留空是有效取值的配置项不该被删行");
    }
}
