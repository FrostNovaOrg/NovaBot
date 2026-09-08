package com.starlwr.bot.core.datasource;

import com.alibaba.fastjson2.JSON;
import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.handler.StarBotEventHandlerPushMessageInitializer;
import com.starlwr.bot.core.service.PushTemplateDefaults;
import com.starlwr.bot.core.service.StarBotEventHandlerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 推送用户的线上形态守卫
 * <p>
 * {@link PushUser} 这一族既是使用者手写的 {@code datasource.json} 的映射，也参与
 * {@code equals}/{@code hashCode}（数据源用它判「是不是同一位主播」）。<b>它的线上形态
 * 一个字节都不许变</b>：字段名、字段顺序、哪些字段序列化、以及从示例文件解析出来的取值，
 * 任何一项漂了，既有部署的推送配置就会在下一次启动时静默解错——
 * 不报错、不告警，只是有些推送从此不再发出。
 * <p>
 * 因此这里把形态<b>钉成基线文件</b>逐字节比，而不是逐字段写断言：
 * 逐字段的断言只守得住写断言的人当时想到的那几项，新加一个字段照样悄悄进到线上形态里。
 * <p>
 * 基线取自发行包里那份 {@code datasource.example.json} 与一份把全部字段都填满的对象。
 * 形态确需变更时，改基线文件是必须走的一步，而不是顺手就能绕过去的一步。
 */
@DisplayName("推送用户线上形态")
class PushUserWireFormTest {
    private static final String BASE = "/pushuser-wire/";

    /**
     * 发行包里那份示例推送配置在测试资源中的副本
     */
    private static final String EXAMPLE = BASE + "datasource-example.json";

    /**
     * 示例解析后再序列化的基线
     */
    private static final String EXAMPLE_ROUNDTRIP = BASE + "datasource-example-roundtrip.json";

    /**
     * 搬包之前那份示例，逐字留着当「老配置」用
     * <p>
     * 这份<b>不跟着发行包走</b>，也不许跟着改名：它代表的是已经装在使用者机器上的那份文件，
     * 而那份文件不会因为我们改了包名就跟着变。把旧名从判据里删干净，等于把「老配置还读不读得开」
     * 这件事一起删掉。旧名解析成哪个实例是处理器那一侧的事（见 {@code LegacyHandlerClassNameTest}），
     * 这里只守它在线上形态这一层仍解得开、串还原样。
     */
    private static final String LEGACY = BASE + "datasource-legacy-handler.json";

    /**
     * 全字段推送用户的序列化基线
     */
    private static final String FULL = BASE + "pushuser-full.json";

    /**
     * 全字段推送用户的 toString 基线
     */
    private static final String FULL_TEXT = BASE + "pushuser-full.txt";

    @Test
    @DisplayName("全字段推送用户的序列化结果应与基线逐字节相同")
    void shouldKeepSerializedFormOfFullyPopulatedUser() throws IOException {
        String actual = JSON.toJSONString(fullyPopulated());

        assertEquals(baseline(FULL), actual, dumpHint("pushuser-full.json", actual)
                + "推送用户的序列化形态变了：字段名、字段顺序或参与序列化的字段有改动。"
                + "既有部署的 datasource.json 与之对不上时不会报错，只会少发推送");
    }

    @Test
    @DisplayName("发行包示例配置解析后再序列化应与基线逐字节相同")
    void shouldKeepDatasourceJsonRoundTrip() throws IOException {
        List<PushUser> users = newDataSource().parse(resource(EXAMPLE));
        String actual = JSON.toJSONString(users);

        assertEquals(baseline(EXAMPLE_ROUNDTRIP), actual, dumpHint("datasource-example-roundtrip.json", actual)
                + "示例推送配置的往返结果变了：解析器或模型的线上形态有改动");
    }

    @Test
    @DisplayName("发行包示例配置解析出的取值应逐项对得上")
    void shouldParseShippedExampleIntoExpectedValues() throws IOException {
        List<PushUser> users = newDataSource().parse(resource(EXAMPLE));

        assertEquals(1, users.size());
        PushUser user = users.get(0);
        assertEquals("bilibili", user.getPlatform());
        assertTrue(user.getEnabled(), "示例没写 enabled, 缺省应视为启用");
        assertEquals(1, user.getTargets().size());

        PushTarget target = user.getTargets().get(0);
        assertEquals("qq-onebot", target.getPlatform());
        assertEquals(PushTargetType.GROUP, target.getType(), "示例里 type 写的是 1, 对应群聊");
        assertTrue(target.getEnabled());
        assertEquals(3, target.getMessages().size());
        assertEquals(
                List.of("com.starlwr.bot.bilibili.handler.BilibiliLiveOnPushHandler",
                        "com.starlwr.bot.bilibili.handler.BilibiliLiveOffPushHandler",
                        "com.starlwr.bot.report.handler.BilibiliDynamicPushHandler"),
                target.getMessages().stream().map(PushMessage::getHandler).toList());
    }

    @Test
    @DisplayName("搬包之前那份配置仍解得开, handler 串原样保留")
    void shouldStillParseConfigWrittenBeforeHandlersMoved() throws IOException {
        List<PushUser> users = newDataSource().parse(resource(LEGACY));

        assertEquals(1, users.size());
        assertEquals(
                List.of("com.starlwr.bot.bilibili.handler.BilibiliLiveOnPushHandler",
                        "com.starlwr.bot.bilibili.handler.BilibiliLiveOffPushHandler",
                        "com.starlwr.bot.bilibili.handler.BilibiliDynamicPushHandler"),
                users.get(0).getTargets().get(0).getMessages().stream().map(PushMessage::getHandler).toList(),
                "解析这一层不许动 handler 串: 认旧名是处理器那一侧的事, 这里改一个字, "
                        + "使用者文件里到底写的什么就再也查不出来了");
    }

    @Test
    @DisplayName("推送用户的同一性只认 uid 与平台")
    void shouldKeepIdentityContract() {
        PushUser one = fullyPopulated();
        PushUser two = fullyPopulated();
        two.setUname("换个昵称");
        two.setRoomId(999999L);
        two.setFace("https://example.invalid/other.jpg");
        two.setEnabled(false);
        two.setTargets(new ArrayList<>());

        assertEquals(one, two, "昵称/房间号/头像/启用状态/推送目标都不参与同一性");
        assertEquals(one.hashCode(), two.hashCode());
        assertEquals(Objects.hash(one.getUid(), one.getPlatform()), one.hashCode(),
                "hashCode 的取值本身也是线上形态：数据源用它做去重与索引");

        PushUser otherPlatform = fullyPopulated();
        otherPlatform.setPlatform("other-platform");
        assertNotEquals(one, otherPlatform);

        PushUser otherUid = fullyPopulated();
        otherUid.setUid(2L);
        assertNotEquals(one, otherUid);

        assertTrue(one.same(fullyPopulated()), "各字段与目标都相同时 same 应为真");
        assertTrue(!one.same(two), "字段有差异时 same 应为假, 否则配置更新不会生效");
    }

    @Test
    @DisplayName("推送用户的 toString 应与基线逐字相同")
    void shouldKeepToStringForm() throws IOException {
        String actual = fullyPopulated().toString();

        assertEquals(baseline(FULL_TEXT), actual, dumpHint("pushuser-full.txt", actual)
                + "toString 会进日志, 排障时按它对账");
    }

    @Test
    @DisplayName("测试资源里的示例副本应与发行包里那份相同")
    void shouldKeepExampleCopyInSyncWithShippedTemplate() throws IOException {
        Path shipped = locateShippedExample();

        assertEquals(Files.readString(shipped, StandardCharsets.UTF_8), resource(EXAMPLE),
                "发行包示例 " + shipped + " 改了而测试资源里的副本没跟上, "
                        + "两份对不上时本组判据守的就不再是真正发出去的那一份");
    }

    /**
     * 构造一个每个字段都填了值的推送用户
     * <p>
     * 取值刻意都写死：基线比的是字节，任何随机或与当前时刻相关的取值都会让判据自己变红。
     * @return 推送用户
     */
    private static PushUser fullyPopulated() {
        PushUser user = new PushUser();
        user.setUid(1L);
        user.setUname("测试主播");
        user.setRoomId(123456L);
        user.setFace("https://example.invalid/face.jpg");
        user.setPlatform("bilibili");
        user.setEnabled(true);

        PushTarget target = new PushTarget();
        target.setUser(user);
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(654321L);
        target.setEnabled(true);

        PushMessage message = new PushMessage();
        message.setTarget(target);
        message.setHandler("com.example.SomeHandler");
        message.setParams("{\"key\":\"value\"}");
        message.setEnabled(true);

        PushMessage disabled = new PushMessage();
        disabled.setTarget(target);
        disabled.setHandler("com.example.DisabledHandler");
        disabled.setEnabled(false);

        target.setMessages(new ArrayList<>(List.of(message, disabled)));
        user.setTargets(new ArrayList<>(List.of(target)));
        return user;
    }

    /**
     * 构造一个只用来调解析器的 JSON 数据源
     * @return JSON 数据源
     */
    private static JsonDataSource newDataSource() {
        return new JsonDataSource(
                mock(ApplicationEventPublisher.class),
                new DataSourceServiceRegistry(List.of()),
                new StarBotEventHandlerPushMessageInitializer(mock(StarBotEventHandlerService.class),
                        new PushTemplateDefaults(new NovaCoreProperties())),
                new NovaCoreProperties().getDatasource()
        );
    }

    /**
     * 定位发行包里那份示例推送配置
     * <p>
     * 测试的工作目录是模块目录，仓库根可能在上一级或再上一层；向上走到找到那份文件为止，
     * 找不到就当场红——找不到时悄悄跳过的判据，等于没有这条判据。
     * @return 示例文件路径
     */
    private static Path locateShippedExample() {
        String relative = "dist/templates/datasource.example.json";
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到发行包示例 " + relative + ", 工作目录: " + Path.of("").toAbsolutePath());
    }

    /**
     * 读取基线文件
     * @param name 资源路径
     * @return 内容，行尾统一，末尾换行去掉
     */
    private static String baseline(String name) throws IOException {
        return resource(name).stripTrailing();
    }

    /**
     * 读取测试资源
     * @param name 资源路径
     * @return 内容
     */
    private static String resource(String name) throws IOException {
        try (InputStream in = PushUserWireFormTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "基线资源缺席: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 判据红时把实际值落到 target 下，便于逐字节比对
     * @param name 文件名
     * @param actual 实际值
     * @return 提示语
     */
    private static String dumpHint(String name, String actual) {
        try {
            Path directory = Files.createDirectories(Path.of("target", "pushuser-wire"));
            Path file = directory.resolve(name);
            Files.writeString(file, actual, StandardCharsets.UTF_8);
            return "实际值已落至 " + file.toAbsolutePath() + "。";
        } catch (IOException e) {
            return "";
        }
    }
}
