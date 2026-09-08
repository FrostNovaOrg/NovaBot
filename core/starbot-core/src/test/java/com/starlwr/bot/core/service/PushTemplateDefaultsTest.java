package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.NovaEventHandler;
import com.starlwr.bot.core.model.HandlerOption;
import com.starlwr.bot.core.model.PushMessage;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改过的默认模板：读、写与拒收
 * <p>
 * 这一层管的是「改一次默认，所有用默认的通道一起变」。它的两个方向都要量：
 * <b>阳</b>——写下去的确实落了盘，也确实盖在出厂默认之上；
 * <b>阴</b>——写不得的一律拒收，且拒收那一次<b>一个字也没写进去</b>。
 * <p>
 * 阴性那几条不是形式：这一层改的是所有用默认的通道，一个写错的占位符会同时出现在
 * 每一个群里，而现象只是消息里多了一串花括号——没有任何报错，也没人会想到去看这里。
 */
@DisplayName("改过的默认模板")
class PushTemplateDefaultsTest {
    private static final String FACTORY_MESSAGE = "{uname} 正在直播 {title}\n{url}{cover}";

    @TempDir
    Path dir;

    private StarBotCoreProperties properties;

    private PushTemplateDefaults defaults;

    private FakeHandler handler;

    @BeforeEach
    void setUp() {
        properties = new StarBotCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        defaults = new PushTemplateDefaults(properties);
        handler = new FakeHandler();
    }

    private Path file() {
        return dir.resolve(PushTemplateDefaults.FILE_NAME);
    }

    // ---------- 阳 ----------

    @Test
    @DisplayName("没改过时就是出厂默认，文件也不必存在")
    void untouchedFallsBackToFactory() {
        assertFalse(Files.exists(file()), "没人改过时不该凭空建出一个文件");
        assertEquals(FACTORY_MESSAGE, defaults.paramsOf(handler).getString("message"));
        assertTrue(defaults.all().isEmpty(), "没改过时读出来该是空的");
    }

    @Test
    @DisplayName("改过的键盖在出厂默认上，没改的键照旧跟着出厂走")
    void overrideCoversOnlyWhatWasChanged() {
        assertEquals(List.of(), defaults.save(handler, new JSONObject()
                .fluentPut("message", "{uname} 开播了 {title}")));

        JSONObject params = defaults.paramsOf(handler);
        assertEquals("{uname} 开播了 {title}", params.getString("message"), "改过的那一项按改过的算");
        assertEquals("默认的重连话术", params.getString("reconnect_message"),
                "没改过的那一项仍跟着出厂默认——只存覆盖而不是整份，正是为了这一条");
    }

    @Test
    @DisplayName("回来的是新实例，改它不会串到下一次")
    void paramsAreFreshEachTime() {
        defaults.save(handler, new JSONObject().fluentPut("message", "改过的"));

        JSONObject first = defaults.paramsOf(handler);
        first.put("message", "被调用方改掉了");

        assertEquals("改过的", defaults.paramsOf(handler).getString("message"),
                "推送消息会把使用者参数直接写进这个返回值，共用一份会串到别的推送目标上");
    }

    @Test
    @DisplayName("落了盘，换一个实例读回来还是它")
    void survivesRestart() throws IOException {
        defaults.save(handler, new JSONObject()
                .fluentPut("message", "{uname} 开播了")
                .fluentPut("at_mode", "all"));

        assertTrue(Files.exists(file()), "写完之后文件该在");
        PushTemplateDefaults reopened = new PushTemplateDefaults(properties);
        JSONObject params = reopened.paramsOf(handler);
        assertEquals("{uname} 开播了", params.getString("message"));
        assertEquals("all", params.getString("at_mode"), "@ 谁那一档不在出厂默认里，但存得下来");
        assertTrue(Files.readString(file(), StandardCharsets.UTF_8).contains(FakeHandler.class.getName()),
                "存的是按处理器全类名分的一份");
    }

    @Test
    @DisplayName("传空对象＝这一类整个回到出厂默认")
    void emptyOverrideRestoresFactory() {
        defaults.save(handler, new JSONObject().fluentPut("message", "改过的"));
        assertEquals(List.of(), defaults.save(handler, new JSONObject()));

        assertEquals(FACTORY_MESSAGE, defaults.paramsOf(handler).getString("message"));
        assertTrue(defaults.all().isEmpty(), "整类回到出厂之后，这个处理器不该还留在文件里");
    }

    @Test
    @DisplayName("传整份而不是增量：上一次改过的键这一次不写，就是回到出厂")
    void saveReplacesRatherThanMerges() {
        defaults.save(handler, new JSONObject()
                .fluentPut("message", "改过的")
                .fluentPut("at_mode", "all"));
        defaults.save(handler, new JSONObject().fluentPut("message", "又改了一次"));

        JSONObject params = defaults.paramsOf(handler);
        assertEquals("又改了一次", params.getString("message"));
        assertFalse(params.containsKey("at_mode"),
                "增量语义下「把一个覆盖删掉」没有说法，而那正是「恢复默认」按下去要做的事");
    }

    // ---------- 阴 ----------

    @Test
    @DisplayName("认不出的占位符拒收，且一个字也没写进去")
    void unknownPlaceholderIsRejected() {
        List<String> issues = defaults.save(handler, new JSONObject()
                .fluentPut("message", "{uname} 正在直播 {tittle}"));

        assertEquals(1, issues.size(), "该点名那个写错的占位符：" + issues);
        assertTrue(issues.get(0).contains("{tittle}"), issues.get(0));
        assertFalse(Files.exists(file()), "拒收的那一次不许留下半份默认模板");
        assertEquals(FACTORY_MESSAGE, defaults.paramsOf(handler).getString("message"));
    }

    @Test
    @DisplayName("空模板拒收：它与「配好了」在界面上长得一样")
    void blankMessageIsRejected() {
        assertFalse(defaults.save(handler, new JSONObject().fluentPut("message", "   ")).isEmpty());
        assertFalse(defaults.save(handler, new JSONObject().fluentPut("message", "")).isEmpty());
        assertFalse(Files.exists(file()));
    }

    @Test
    @DisplayName("模板不是一段文字时拒收")
    void nonTextMessageIsRejected() {
        assertFalse(defaults.save(handler, new JSONObject().fluentPut("message", 42)).isEmpty());
        assertFalse(Files.exists(file()));
    }

    @Test
    @DisplayName("@ 谁只认那三档")
    void atModeIsAClosedSet() {
        assertFalse(defaults.save(handler, new JSONObject().fluentPut("at_mode", "everyone")).isEmpty());
        assertFalse(defaults.save(handler, new JSONObject().fluentPut("at_mode", true)).isEmpty());
        assertEquals(List.of(), defaults.save(handler,
                new JSONObject().fluentPut("at_mode", "all_or_subscribers")));
    }

    @Test
    @DisplayName("这个处理器没有的参数拒收")
    void unknownKeyIsRejected() {
        List<String> issues = defaults.save(handler, new JSONObject().fluentPut("mesage", "拼错了"));
        assertEquals(1, issues.size(), issues.toString());
        assertTrue(issues.get(0).contains("mesage"), issues.get(0));
        assertFalse(Files.exists(file()));
    }

    @Test
    @DisplayName("处理器自报的可配置项存得下来")
    void declaredOptionIsWritable() {
        assertEquals(List.of(), defaults.save(handler, new JSONObject().fluentPut("danmu_top", 8)));
        assertEquals(8, defaults.paramsOf(handler).getIntValue("danmu_top"));
    }

    @Test
    @DisplayName("文件坏了时按出厂默认办，不让推送整个停摆")
    void brokenFileFallsBackToFactory() throws IOException {
        Files.writeString(file(), "{ 这不是 JSON", StandardCharsets.UTF_8);

        assertEquals(FACTORY_MESSAGE, new PushTemplateDefaults(properties).paramsOf(handler).getString("message"));
    }

    /**
     * 一个只为这几格存在的处理器：出厂默认两项，另外自报一个可配置项
     */
    private static final class FakeHandler implements NovaEventHandler {
        @Override
        public void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage) {
        }

        @Override
        public Class<? extends StarBotExternalBaseEvent> getEventType() {
            return StarBotExternalBaseEvent.class;
        }

        @Override
        public JSONObject getDefaultParams() {
            JSONObject params = new JSONObject();
            params.put("message", FACTORY_MESSAGE);
            params.put("reconnect_message", "默认的重连话术");
            return params;
        }

        @Override
        public List<String> placeholders() {
            return List.of("{uname}", "{title}", "{cover}", "{url}", "{at}", "{next}", "{at=all}");
        }

        @Override
        public List<String> attachmentPlaceholders() {
            return List.of("{cover}");
        }

        @Override
        public List<HandlerOption> options() {
            return List.of(HandlerOption.integer("danmu_top", "弹幕榜条数", "", 5, 0, 20));
        }
    }
}
