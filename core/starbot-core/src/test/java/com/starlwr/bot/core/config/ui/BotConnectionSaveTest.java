package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.account.BotConnectionTester;
import com.starlwr.bot.core.config.NovaCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 保存机器人连接信息
 *
 * <h2>这里守的是三件事</h2>
 * <ol>
 *   <li><b>一台全新的机器上这条连接建得出来</b>——发行包不带 application.yml，
 *       第一次保存时按配置面渲染出来的那一份里，机器人那一节是个<b>空表</b>。
 *       建不出第一条元素的话，初始设置第 2 步一步也走不下去，
 *       而报出来的是一句「保存失败」，指不到任何可改的地方</li>
 *   <li><b>建出来的那一份下次还起得来</b>——缺了平台名的一条连接会让程序直接起不来，
 *       所以落盘的必须是整条元素，且整份文件仍然解析得动</li>
 *   <li><b>写不进文件时不许装作什么都没发生</b>——那一刻连接已经接上了，
 *       笼统报一句「保存失败」会让人对着一个其实已经通了的连接反复重试</li>
 * </ol>
 */
@DisplayName("保存机器人连接信息")
class BotConnectionSaveTest {
    /**
     * 一台全新的机器：配置面渲染出来的那一份里，机器人那一节是空表
     */
    private static final String FRESH = """
            novabot:
              core:
                config-ui:
                  enabled: true         # 是否启用配置界面
              adapter:
                onebot:
                  # OneBot 推送平台列表
                  senders: []
                  detect:
                    enable-http-detect: true
            """;

    /**
     * 一台配过的机器：机器人那一节已经有一条
     */
    private static final String CONFIGURED = """
            novabot:
              adapter:
                onebot:
                  senders:
                    - name: qq-onebot
                      api: /send
                      websocket: true
                      one-bot-address: 10.0.0.9      # 老地址
                      one-bot-http-port: 3000
                      one-bot-websocket-port: 3001
                      one-bot-http-token: old-http
                      one-bot-websocket-token: old-ws
                      delay: 1000
                  detect:
                    enable-http-detect: true
            """;

    @TempDir
    Path dir;

    private Path config;

    private ConfigurationFileService fileService;

    /**
     * 假适配器记下的调用参数：核心该原样把界面填的东西交过去
     */
    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        config = dir.resolve("application.yml");
        fileService = new ConfigurationFileService(config);
    }

    /**
     * 一个只记账、不真连的适配器
     * @param live 它自称接上了没有
     */
    private BotConnectionTester tester(boolean live) {
        return new BotConnectionTester() {
            @Override
            public String adapter() {
                return "fake";
            }

            @Override
            public Result test(String address, int httpPort, String httpToken) {
                return Result.ok("不走这一支");
            }

            @Override
            public Applied apply(String address, int httpPort, int websocketPort,
                                 String httpToken, String websocketToken) {
                calls.add(address + "|" + httpPort + "|" + websocketPort + "|" + httpToken + "|" + websocketToken);

                Map<String, String> configuration = new LinkedHashMap<>();
                configuration.put("name", "qq-onebot");
                configuration.put("api", "/send");
                configuration.put("websocket", "true");
                configuration.put("one-bot-address", address);
                configuration.put("one-bot-http-port", String.valueOf(httpPort));
                configuration.put("one-bot-websocket-port", String.valueOf(websocketPort));
                configuration.put("one-bot-http-token", httpToken);
                configuration.put("one-bot-websocket-token", websocketToken);
                configuration.put("delay", "1000");
                return new Applied(live, live ? "连接正常，登录账号 test-bot(1000)" : "还差点什么", configuration);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private ConfigUiController controller(ConfigurationFileService files, BotConnectionTester tester) {
        NovaCoreProperties properties = new NovaCoreProperties();

        ObjectProvider<BotConnectionTester> testers = mock(ObjectProvider.class);
        when(testers.orderedStream()).thenAnswer(invocation -> Stream.of(tester));

        // 构造器只做赋值，其余依赖对本组用例毫无参与，全部给桩
        return new ConfigUiController(
                mock(ConfigurationMetadataService.class),
                files,
                properties,
                mock(com.starlwr.bot.core.datasource.AbstractDataSource.class),
                mock(ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(com.starlwr.bot.core.service.NovaSenderService.class),
                mock(com.starlwr.bot.core.sender.NovaMessageSender.class),
                mock(ObjectProvider.class),
                mock(com.starlwr.bot.core.health.PushActivityRecorder.class),
                mock(com.starlwr.bot.core.service.StarBotEventHandlerService.class),
                mock(com.starlwr.bot.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
                RuntimeConfigurationApplier.bench(properties).build(),
                testers,
                mock(ObjectProvider.class),
                mock(com.starlwr.bot.core.protocol.EventStreamTokenService.class),
                mock(ObjectProvider.class),
                mock(com.starlwr.bot.core.sender.PushGate.class),
                mock(com.starlwr.bot.core.service.LiveDataService.class),
                mock(com.starlwr.bot.core.timeline.TimelineStore.class),
                mock(com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService.class),
                new com.starlwr.bot.core.service.PushTemplateDefaults(properties),
                mock(UpdateCheckService.class));
    }

    private JSONObject body() {
        JSONObject body = new JSONObject();
        body.put("address", "127.0.0.1");
        body.put("httpPort", "3100");
        body.put("websocketPort", "3101");
        body.put("httpToken", "new-http");
        body.put("websocketToken", "new-ws");
        return body;
    }

    private void write(String content) throws IOException {
        Files.writeString(config, content, StandardCharsets.UTF_8);
    }

    private List<String> lines() throws IOException {
        return Files.readAllLines(config, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("⚠️ 空表的机器上建得出第一条连接，且建出来的那一份仍解析得动")
    void createsTheFirstItemOnAFreshMachine() throws IOException {
        write(FRESH);

        JSONObject result = controller(fileService, tester(true)).saveBot(body());

        assertTrue(result.getBooleanValue("success"), result.getString("message"));
        assertTrue(result.getBooleanValue("live"), "适配器说接上了，接口就该照实说接上了");

        String text = Files.readString(config, StandardCharsets.UTF_8);
        assertFalse(text.contains("senders: []"), "那对方括号必须去掉，否则新元素与它并存时整份文件解析不了");
        assertTrue(text.contains("- name: qq-onebot"), "落盘的必须是整条元素，缺了平台名的那一条会让下次启动直接失败:\n" + text);
        assertTrue(text.contains("one-bot-address: 127.0.0.1"), "地址该落下去:\n" + text);

        // 用启动时真正在跑的那个加载器解析：逐行看键路径看不见缩进错位这类结构性毛病，
        // 而它的表现是下次启动直接掉进安全模式
        assertEquals(1, load().stream()
                        .filter(key -> key.startsWith("novabot.adapter.onebot.senders[0]"))
                        .filter(key -> key.endsWith(".name")).count(),
                "解析回来应当恰好有一条机器人，且它有名字。解析结果: " + load());
        assertEquals("127.0.0.1", value("novabot.adapter.onebot.senders[0].one-bot-address"));
        assertEquals("3100", value("novabot.adapter.onebot.senders[0].one-bot-http-port"));
        assertEquals("new-ws", value("novabot.adapter.onebot.senders[0].one-bot-websocket-token"));

        // 别的键一个也不许被这一趟碰掉
        assertEquals("true", value("novabot.adapter.onebot.detect.enable-http-detect"));
        assertEquals("true", value("novabot.core.config-ui.enabled"));
    }

    @Test
    @DisplayName("已经配过的机器上只改到的那几行，其余逐字节不动")
    void rewritesOnlyTheChangedLinesOnAConfiguredMachine() throws IOException {
        write(CONFIGURED);
        List<String> before = lines();

        JSONObject result = controller(fileService, tester(true)).saveBot(body());
        assertTrue(result.getBooleanValue("success"), result.getString("message"));

        List<String> after = lines();
        assertEquals(before.size(), after.size(), "行数不该变：这一趟只是改值");

        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).equals(after.get(i))) {
                changed.add(i);
            }
        }

        // 五项连接信息各一行；平台名、接口路径、是否启用 Websocket 与发送间隔写回去的是原值，不算改动
        assertEquals(5, changed.size(), "改动应当只落在那五行上，实际落在行号 " + changed + ":\n" + String.join("\n", after));
        assertTrue(after.stream().anyMatch(line -> line.contains("# 老地址")), "行尾注释该留着");
    }

    @Test
    @DisplayName("⚠️ 连接已经接上却写不进文件时，如实说是哪一半没成")
    void tellsTheTruthWhenTheFileCannotBeWritten() throws IOException {
        write(FRESH);

        ConfigurationFileService broken = new ConfigurationFileService(config) {
            @Override
            public int writeListItemFields(String listPath, int index, Map<String, String> fields) throws IOException {
                throw new IOException("磁盘满了");
            }
        };

        JSONObject result = controller(broken, tester(true)).saveBot(body());

        assertTrue(result.getBooleanValue("live"), "连接确实已经接上了");
        assertTrue(result.getString("message").contains("重启后将恢复原状"),
                "得说清「已经接上、但没存下来」，笼统报保存失败会让人对着一个已经通了的连接反复重试: "
                        + result.getString("message"));
    }

    @Test
    @DisplayName("界面填的东西原样交给适配器，端口按数交")
    void handsTheFormOverAsIs() throws IOException {
        write(FRESH);

        controller(fileService, tester(true)).saveBot(body());

        assertEquals(List.of("127.0.0.1|3100|3101|new-http|new-ws"), calls);
    }

    @Test
    @DisplayName("saveBot 把令牌留空时删掉该字段，其它连接信息照写")
    void saveBotDropsBlankTokenFields() throws IOException {
        write(CONFIGURED);

        JSONObject body = body();
        body.put("httpToken", "");
        body.put("websocketToken", "");

        JSONObject result = controller(fileService, tester(true)).saveBot(body);
        assertTrue(result.getBooleanValue("success"), result.getString("message"));

        String text = Files.readString(config, StandardCharsets.UTF_8);
        assertFalse(text.contains("one-bot-http-token"), "空令牌应被删掉，不留空值行:\n" + text);
        assertFalse(text.contains("one-bot-websocket-token"), text);
        assertTrue(text.contains("one-bot-address: 127.0.0.1"), "地址仍该写下去:\n" + text);
        assertTrue(text.contains("delay: 1000"), "没被清空的字段还在:\n" + text);
        assertEquals("127.0.0.1", value("novabot.adapter.onebot.senders[0].one-bot-address"));
        assertNull(value("novabot.adapter.onebot.senders[0].one-bot-http-token"));
    }

    @Test
    @DisplayName("saveBot 令牌非空时照写")
    void saveBotWritesNonEmptyTokens() throws IOException {
        write(CONFIGURED);

        JSONObject result = controller(fileService, tester(true)).saveBot(body());
        assertTrue(result.getBooleanValue("success"), result.getString("message"));
        assertEquals("new-http", value("novabot.adapter.onebot.senders[0].one-bot-http-token"));
        assertEquals("new-ws", value("novabot.adapter.onebot.senders[0].one-bot-websocket-token"));
    }

    @Test
    @DisplayName("端口填成不是数字时当场说清楚，而不是把它当成「不改」")
    void refusesANonNumericPort() throws IOException {
        write(FRESH);

        JSONObject body = body();
        body.put("httpPort", "三千");

        JSONObject result = controller(fileService, tester(true)).saveBot(body);

        assertFalse(result.getBooleanValue("success"));
        assertTrue(result.getString("message").contains("端口"), result.getString("message"));
        assertTrue(calls.isEmpty(), "参数不对就不该去动运行中的连接");
    }

    /**
     * 用启动时真正在跑的那个加载器把文件读回来
     */
    private List<String> load() throws IOException {
        return new YamlPropertySourceLoader()
                .load("saved", new FileSystemResource(config.toFile()))
                .stream()
                .flatMap(source -> java.util.Arrays.stream(
                        ((org.springframework.core.env.EnumerablePropertySource<?>) source).getPropertyNames()))
                .toList();
    }

    private String value(String key) throws IOException {
        Optional<Object> found = new YamlPropertySourceLoader()
                .load("saved", new FileSystemResource(config.toFile()))
                .stream()
                .map(source -> source.getProperty(key))
                .filter(java.util.Objects::nonNull)
                .findFirst();

        return found.map(String::valueOf).orElse(null);
    }
}
