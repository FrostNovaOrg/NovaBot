package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.protocol.EventStreamTokenService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 配置界面读数里成图字体表那一栏：存的是不是旧默认表，本系统默认表是哪张
 * <p>
 * 抓的用户故障：老实例的配置文件里存着旧版自动写进去的默认字体表，启动时已按未设处理，
 * 可设置页照原样显示它、一个字的说明都没有。使用者改一项再存，它就成了使用者自己的表，
 * 表情空白复发。读数得告诉界面「这是旧默认表」和「空着时用的是哪张」，界面才说得清。
 * <p>
 * 旧默认表照 git 史抄字面量，不取程序里那份历代表：拿被测自己的表当参照，少抄一代照样全绿。
 */
@DisplayName("配置界面成图字体表读数")
class PaintFontsValuesEchoTest {

    private static final String FONTS = "novabot.core.paint.fonts";

    /** 2026-09-17 以前本机这一系统的默认表 */
    private static final List<String> LOCAL_FIRST_GENERATION = firstGeneration(System.getProperty("os.name"));

    @TempDir
    Path dir;

    private static List<String> firstGeneration(String osName) {
        String os = osName.toLowerCase();
        if (os.contains("win")) {
            return List.of("内置", "微软雅黑", "宋体", "Segoe UI Emoji", "Segoe UI Symbol", "Arial", "SansSerif");
        }
        if (os.contains("mac")) {
            return List.of("内置", "PingFang SC", "Apple Color Emoji", "SansSerif");
        }
        return List.of("内置", "Noto Sans CJK SC", "WenQuanYi Zen Hei", "Noto Color Emoji", "DejaVu Sans", "FreeSans", "SansSerif");
    }

    /**
     * 写一份配置、接上控制台，取读数里字体表那一栏；依赖接法与同目录的 BackupKeepEchoTest 一致
     */
    @SuppressWarnings("unchecked")
    private JSONObject fontTableOf(String yaml) throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, yaml, StandardCharsets.UTF_8);

        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());

        ConfigUiController controller = new ConfigUiController(
                new ConfigurationMetadataService(),
                new ConfigurationFileService(config),
                properties,
                mock(org.frostnova.nova.core.datasource.AbstractDataSource.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(org.frostnova.nova.core.service.NovaSenderService.class),
                mock(org.frostnova.nova.core.sender.NovaMessageSender.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationLabelResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
                RuntimeConfigurationApplier.bench(properties).build(),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                new EventStreamTokenService(properties.getLive()),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.frostnova.nova.core.sender.PushGate.class),
                mock(org.frostnova.nova.core.service.LiveDataService.class),
                mock(org.frostnova.nova.core.timeline.TimelineStore.class),
                mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService.class),
                new PushTemplateDefaults(new NovaCoreProperties()),
                mock(UpdateCheckService.class));

        JSONObject result = controller.values();
        assertTrue(result.getBooleanValue("success"), "读配置本身不该失败");
        JSONObject tables = result.getJSONObject("fontTables");
        assertNotNull(tables, "读数里没有字体表那一栏: " + result.keySet());
        JSONObject table = tables.getJSONObject(FONTS);
        assertNotNull(table, "字体表那一栏里没有 " + FONTS + ": " + tables);
        return table;
    }

    private static String yamlWithFonts(List<String> fonts) {
        StringBuilder yaml = new StringBuilder("""
                novabot:
                  core:
                    paint:
                      fonts:
                """);
        for (String font : fonts) {
            yaml.append("        - ").append(font).append('\n');
        }
        return yaml.toString();
    }

    @Test
    @DisplayName("本机存着旧默认表：读数说是旧表，默认表与启动时空表接上的那张相同")
    void pastDefaultTableIsFlagged() throws IOException {
        String os = System.getProperty("os.name");
        JSONObject table = fontTableOf(yamlWithFonts(LOCAL_FIRST_GENERATION));

        assertTrue(table.getBooleanValue("pastDefault"), "存着旧版写下的默认表, 读数得说出来: " + table);
        assertEquals(NovaCoreProperties.fontChain(List.of(), os), table.getList("defaults", String.class),
                "默认表读数得是表为空时启动接上的那张");
    }

    @Test
    @DisplayName("使用者的表、空表、没写这一项：都不是旧表，默认表照给")
    void otherTablesAreNotFlagged() throws IOException {
        String os = System.getProperty("os.name");
        List<String> expected = NovaCoreProperties.fontChain(List.of(), os);

        JSONObject user = fontTableOf(yamlWithFonts(List.of("我的字体", "内置")));
        assertFalse(user.getBooleanValue("pastDefault"), "使用者的表不是旧默认表: " + user);

        List<String> edited = new java.util.ArrayList<>(LOCAL_FIRST_GENERATION);
        edited.add("我的字体");
        JSONObject oneMore = fontTableOf(yamlWithFonts(edited));
        assertFalse(oneMore.getBooleanValue("pastDefault"), "旧表后面多加一项就是使用者的表: " + oneMore);

        JSONObject empty = fontTableOf("""
                novabot:
                  core:
                    paint:
                      fonts: []
                """);
        assertFalse(empty.getBooleanValue("pastDefault"), "空表不是旧默认表: " + empty);
        assertEquals(expected, empty.getList("defaults", String.class), "空表时默认表照给");

        JSONObject absent = fontTableOf("""
                server:
                  port: 7827
                """);
        assertFalse(absent.getBooleanValue("pastDefault"), "没写这一项不是旧默认表: " + absent);
        assertEquals(expected, absent.getList("defaults", String.class), "没写这一项时默认表照给");
    }

    /**
     * 本机只量得到一个系统，另两个系统的判法经附注的构造口喂系统名量
     */
    @Test
    @DisplayName("三个系统各一代旧默认表都认得出，使用者的表与空表不认")
    void everySystemFlagsItsPastTable() {
        java.util.Map<String, List<String>> stored = java.util.Map.of(
                "Windows 11", firstGeneration("Windows 11"),
                "Mac OS X", firstGeneration("Mac OS X"),
                // 2026-09-17 起的 Linux 默认表
                "Linux", List.of("内置", "内置表情", "Noto Sans CJK SC", "内置符号", "SansSerif"));
        List<String> failures = new java.util.ArrayList<>();
        stored.forEach((os, fonts) -> {
            JSONObject past = ConfigUiController.fontTables(String.join("\n", fonts), os).getJSONObject(FONTS);
            if (!past.getBooleanValue("pastDefault")) {
                failures.add(os + " 存着 " + fonts + " 没认出是旧默认表");
            }
            if (!NovaCoreProperties.fontChain(List.of(), os).equals(past.getList("defaults", String.class))) {
                failures.add(os + " 的默认表读数是 " + past.getList("defaults", String.class));
            }
            for (String other : new String[]{"我的字体\n内置", ""}) {
                if (ConfigUiController.fontTables(other, os).getJSONObject(FONTS).getBooleanValue("pastDefault")) {
                    failures.add(os + " 把 " + other.replace("\n", "、") + " 当成了旧默认表");
                }
            }
        });
        assertTrue(failures.isEmpty(), "红格数 " + failures.size() + ":\n" + String.join("\n", failures));
    }

    /**
     * 「恢复默认」在界面上填回空串；它送上来之后文件里得是空表，而不是删键或留着旧表
     */
    @Test
    @DisplayName("旧表存成空串：写成 fonts: []，读回空串")
    void clearingPastTableWritesEmptyList() throws IOException {
        Path config = dir.resolve("clear.yml");
        Files.writeString(config, yamlWithFonts(LOCAL_FIRST_GENERATION), StandardCharsets.UTF_8);
        ConfigurationFileService service = new ConfigurationFileService(config);

        assertEquals(List.of(FONTS), service.write(java.util.Map.of(FONTS, "")));

        String text = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(text.contains("fonts: []"), "清空应写成空表记号:\n" + text);
        assertEquals("", service.read().get(FONTS), "清空后读回空串");
    }
}
