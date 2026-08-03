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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置文件读写测试
 * <p>
 * 重点校验「界面上的改动确实落到文件」以及「落盘时不破坏注释与结构」这两件事。
 */
@DisplayName("配置文件读写")
class ConfigurationFileServiceTest {
    private static final String TEMPLATE = """
            server:
              port: 7827                # 服务端口
              address: 127.0.0.1        # 监听地址

            starbot:
              core:
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
                  draw-logo: true       # 是否绘制 logo
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
        service.write(Map.of("starbot.bilibili.dynamic.draw-logo", "false"));

        String line = content().lines().filter(l -> l.contains("draw-logo")).findFirst().orElseThrow();
        assertTrue(line.contains("false"), "值应已更新: " + line);
        assertTrue(line.contains("# 是否绘制 logo"), "行尾注释应保留: " + line);
    }

    @Test
    @DisplayName("仅改动目标行，其余内容逐行不变")
    void touchesOnlyTargetLines() throws IOException {
        List<String> before = content().lines().toList();
        service.write(Map.of("starbot.bilibili.dynamic.draw-logo", "false"));
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

        Path backup = dir.resolve("application.yml.bak");
        assertTrue(Files.exists(backup));
        assertTrue(Files.readString(backup, StandardCharsets.UTF_8).contains("push-minutes: 1440"));
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
        assertTrue(Files.exists(dir.resolve("application.yml.bak")));
    }
}
