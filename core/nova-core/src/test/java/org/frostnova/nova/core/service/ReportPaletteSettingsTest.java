package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigurationValidator;
import org.frostnova.nova.core.model.PushTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告图配色存在哪、按什么先后取
 */
@DisplayName("报告图配色：默认模板与通道另选")
class ReportPaletteSettingsTest {
    @TempDir
    Path dir;

    private NovaCoreProperties properties;

    private PushTemplateDefaults defaults;

    private ReportPaletteSettings settings;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        defaults = new PushTemplateDefaults(properties);
        settings = new ReportPaletteSettings(defaults);
    }

    private static PushTarget channel(String palette) {
        PushTarget target = new PushTarget();
        target.setReportPalette(palette);
        return target;
    }

    // 用户故障：保存了默认模板，别的设置项没了
    @Test
    @DisplayName("保存默认模板的配色：文件里无关的项原样都在，值为空的嵌套项也不丢")
    void savingTheDefaultKeepsUnrelatedNullItems() throws Exception {
        Path file = dir.resolve("template-defaults.json");
        Files.writeString(file, "{\"org.example.SomeHandler\":{\"message\":\"{uname} 开播了\",\"at\":null},"
                + "\"org.example.OtherHandler\":{\"image\":null}}", StandardCharsets.UTF_8);
        JSONObject before = JSONObject.parseObject(Files.readString(file));

        assertTrue(settings.saveDefault("night").isEmpty());
        JSONObject after = JSONObject.parseObject(Files.readString(file));
        System.out.println("PRESERVE_DEFAULT before=" + before.keySet() + "/" + before.getJSONObject("org.example.SomeHandler").keySet()
                + " after=" + after.getJSONObject("org.example.SomeHandler").keySet()
                + "/" + after.getJSONObject("org.example.OtherHandler").keySet());
        assertTrue(after.getJSONObject("org.example.SomeHandler").containsKey("at"), after.toString());
        assertTrue(after.getJSONObject("org.example.OtherHandler").containsKey("image"), after.toString());
        after.remove(PushTemplateDefaults.REPORT_PALETTE_KEY);
        assertEquals(before, after);
        assertEquals("night", settings.defaultId());
    }

    // 用户故障：升级后没选过的通道还是旧样子
    @Test
    @DisplayName("老配置升级：默认模板里没有这一项、通道也没选，取出厂的粉蓝")
    void upgradeWithoutAnySettingIsPinkBlue() throws Exception {
        // 升级前改过别的默认模板，文件里只有处理器的覆盖
        Files.writeString(dir.resolve("template-defaults.json"),
                "{\"org.example.SomeHandler\":{\"message\":\"{uname} 开播了\"}}", StandardCharsets.UTF_8);
        assertEquals("pink-blue", settings.defaultId());
        assertNull(settings.storedDefault());
        assertEquals("pink-blue", settings.idFor(null));
        assertEquals("pink-blue", settings.idFor(channel(null)));
        assertEquals("pink-blue", settings.idFor(channel(" ")));
    }

    // 用户故障：改了默认模板，另选过的通道也被改掉
    @Test
    @DisplayName("先看通道自己选的，没选才跟默认模板")
    void channelChoiceWinsOverTheDefault() {
        assertTrue(settings.saveDefault("classic").isEmpty());
        assertEquals("classic", settings.idFor(channel(null)));
        assertEquals("warm", settings.idFor(channel("warm")));
        // 不认识的 id 原样交给画图那一侧，由它照默认模板出图并记日志
        assertEquals("sakura", settings.idFor(channel("sakura")));
    }

    @Test
    @DisplayName("默认模板的配色与处理器的覆盖同住一份文件，互不吃掉；「哪几类通知改过」里不出现它")
    void storedBesideHandlerOverridesAsAnId() throws Exception {
        Files.writeString(dir.resolve("template-defaults.json"),
                "{\"org.example.SomeHandler\":{\"message\":\"{uname} 开播了\"}}", StandardCharsets.UTF_8);
        assertTrue(settings.saveDefault("night").isEmpty());

        JSONObject file = JSONObject.parseObject(Files.readString(dir.resolve("template-defaults.json")));
        assertEquals("night", file.getString("reportPalette"), "存的是 id，不是颜色值");
        assertEquals("{uname} 开播了", file.getJSONObject("org.example.SomeHandler").getString("message"));
        assertFalse(defaults.all().containsKey("reportPalette"));
        assertTrue(defaults.all().containsKey("org.example.SomeHandler"));

        // 重启后照样读得回来
        assertEquals("night", new ReportPaletteSettings(new PushTemplateDefaults(properties)).defaultId());

        assertTrue(settings.saveDefault(null).isEmpty());
        assertEquals("pink-blue", settings.defaultId());
        assertFalse(JSONObject.parseObject(Files.readString(dir.resolve("template-defaults.json")))
                .containsKey("reportPalette"));
    }

    @Test
    @DisplayName("通道另选的那一套存在推送配置的通道上，校验只认 id 或空")
    void channelChoiceLivesOnTheChannel() {
        PushTarget parsed = JSONObject.parseObject("{\"platform\":\"qq-onebot\",\"type\":1,\"num\":30003,"
                + "\"reportPalette\":\"night\"}", PushTarget.class);
        assertEquals("night", parsed.getReportPalette());
        assertTrue(JSONObject.from(parsed).toJSONString().contains("\"reportPalette\":\"night\""));
        PushTarget other = JSONObject.parseObject("{\"platform\":\"qq-onebot\",\"type\":1,\"num\":30003}", PushTarget.class);
        assertFalse(parsed.same(other), "只改了配色也算改了这个通道，重载时要认得出");

        ConfigurationValidator validator = new ConfigurationValidator(null);
        assertTrue(validator.validateChannelSettings(JSONObject.of("reportPalette", "night")).isEmpty());
        assertTrue(validator.validateChannelSettings(new JSONObject()).isEmpty());
        assertEquals(1, validator.validateChannelSettings(JSONObject.of("reportPalette", 3)).size());
        assertEquals(1, validator.validateChannelSettings(JSONObject.of("reportPalette", "")).size());
        assertEquals(1, validator.validateChannelSettings(JSONObject.of("reportPalette", new JSONArray())).size());
    }
}
