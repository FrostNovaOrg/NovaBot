package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.model.HandlerOption;
import org.frostnova.nova.core.model.PushMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

    private NovaCoreProperties properties;

    private PushTemplateDefaults defaults;

    private FakeHandler handler;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        defaults = new PushTemplateDefaults(properties);
        handler = new FakeHandler();
    }

    private Path file() {
        return dir.resolve(PushTemplateDefaults.FILE_NAME);
    }

    // ---------- 阳 ----------

    @Test
    @DisplayName("写盘半途失败时返回问题清单，盘上的默认模板原样")
    void failedSaveLeavesTemplateFileIntact() throws IOException {
        assertEquals(List.of(), defaults.save(handler, new JSONObject().fluentPut("message", "第一次改")));
        String original = Files.readString(file(), StandardCharsets.UTF_8);
        Files.createDirectory(dir.resolve(PushTemplateDefaults.FILE_NAME + ".tmp"));

        List<String> issues = defaults.save(handler, new JSONObject().fluentPut("message", "第二次改"));

        assertFalse(issues.isEmpty(), "写失败应返回问题清单，而不是装作已经改好");
        assertEquals(original, Files.readString(file(), StandardCharsets.UTF_8),
                "写到一半失败时盘上的默认模板被改掉了");
    }

    /**
     * 默认模板不含秘密，新建时跟系统默认权限走，不必收得比直接写还紧
     */
    @Test
    @DisplayName("新建的默认模板文件跟着系统默认权限走")
    void newTemplateFileFollowsSystemDefault() throws IOException {
        assertEquals(List.of(), defaults.save(handler, new JSONObject().fluentPut("message", "{uname} 开播了")));
        Path probe = dir.resolve("probe.txt");
        Files.writeString(probe, "p\n", StandardCharsets.UTF_8);

        assertEquals(Files.getPosixFilePermissions(probe), Files.getPosixFilePermissions(file()),
                "不含秘密的件新建时该跟直接写一样宽");
    }

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
    @DisplayName("旧包根下存的覆盖读得到, 写回落在新键并把旧键清掉")
    void oldPackageRootOverridesAreReadAndClearedOnSave() throws IOException {
        String oldKey = HandlerPackageNames.toOldPackage(FakeHandler.class.getName());
        Files.writeString(file(), new JSONObject()
                .fluentPut(oldKey, new JSONObject().fluentPut("message", "{uname} 旧键"))
                .toJSONString(), StandardCharsets.UTF_8);

        PushTemplateDefaults reopened = new PushTemplateDefaults(properties);
        assertEquals("{uname} 旧键", reopened.overridesOf(handler).getString("message"),
                "template-defaults.json 里仍是旧包根时读不到, 自定义模板会悄悄回到出厂默认");

        assertEquals(List.of(), reopened.save(handler, new JSONObject().fluentPut("message", "{uname} 新键")));
        String stored = Files.readString(file(), StandardCharsets.UTF_8);
        assertTrue(stored.contains(FakeHandler.class.getName()), "写回必须落在现行类名下");
        assertFalse(stored.contains(oldKey), "旧包根那一份必须清掉, 否则恢复默认之后还会被读回来");
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
     * 版式项删掉之后，老机器上 {@code template-defaults.json} 里存着的那个键就成了死键。
     * 读的一侧必须当它不存在：留着它的话，控制台按「默认与出厂的差集」算出来的覆盖
     * 会带着这个死键发回来，保存那一步被校验拒收——升级前改过的默认模板，升级后反而存不进去。
     */
    @Test
    @DisplayName("已删版式键的旧覆盖：读时忽略，后续保存不再被死键拖垮")
    void overrideOnRemovedOptionKeyIsIgnoredAtRead() throws IOException {
        Files.writeString(file(), new JSONObject()
                .fluentPut(FakeHandler.class.getName(), new JSONObject()
                        .fluentPut("message", "{uname} 改过的")
                        .fluentPut("danmu_top", 8)
                        .fluentPut("removed_layout_key", 5))
                .toJSONString(), StandardCharsets.UTF_8);

        JSONObject params = defaults.paramsOf(handler);
        List<String> red = new ArrayList<>();
        try {
            assertEquals("{uname} 改过的", params.getString("message"), "活着的覆盖照旧生效");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertEquals(8, params.getIntValue("danmu_top"), "处理器仍自报的可配置项照旧生效");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            assertFalse(params.containsKey("removed_layout_key"),
                    "处理器已不认的键不该再出现在默认参数里——它正是把保存拖垮的那个键");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }
        try {
            // 控制台按这份默认与出厂的差集发回的保存（不含死键）要能存进
            assertEquals(List.of(), defaults.save(handler, new JSONObject()
                    .fluentPut("message", "{uname} 又改的")
                    .fluentPut("danmu_top", 8)));
            assertEquals("{uname} 又改的", defaults.paramsOf(handler).getString("message"));
        } catch (Throwable t) {
            red.add("④ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 一个只为这几格存在的处理器：出厂默认两项，另外自报一个可配置项
     */
    private static final class FakeHandler implements NovaEventHandler {
        @Override
        public void handle(NovaExternalBaseEvent baseEvent, PushMessage pushMessage) {
        }

        @Override
        public Class<? extends NovaExternalBaseEvent> getEventType() {
            return NovaExternalBaseEvent.class;
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
