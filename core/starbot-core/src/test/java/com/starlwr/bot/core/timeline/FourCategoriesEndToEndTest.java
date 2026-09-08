package com.starlwr.bot.core.timeline;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.alert.AlertChannel;
import com.starlwr.bot.core.alert.AlertService;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandDispatcher;
import com.starlwr.bot.core.command.CommandFollowUp;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.command.CommandSettingsService;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.RuntimeConfigurationApplier;
import com.starlwr.bot.core.config.ui.RuntimeConfigurationApplierContributor;
import com.starlwr.bot.core.config.ui.TimelineController;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.remote.StarBotRemoteMessageEvent;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.StarBotStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 命令、告警、设置、系统四类记事的端到端
 * <p>
 * 这四类的大类此前都在 {@link TimelineCategory} 里声明着，却<b>一条记事都没有</b>，
 * 于是 {@link TimelineCategory#inUse()} 把它们摘掉，屏幕上只剩「全部＋推送＋连接」三枚药丸。
 * 空着的那几类不报错、不留痕，看起来与「这台机器没发生过这类事」一模一样。
 * <p>
 * 一类一格，每格走完整条路：<b>真现场触发 → 真的 {@link TimelineStore} 落盘 →
 * {@link TimelineController} 下发 → 药丸清单里出现这一类</b>。
 * 少量任何一环，那一类在日志页上就是不存在的：
 * <ul>
 *   <li>现场不调，落盘再对也没有东西可落；</li>
 *   <li>落盘不认这个类型（比如类型名拼错），读回来时整行被跳过；</li>
 *   <li>药丸清单里没有这一类，事件在不筛的时候照常显示，一点那枚药丸却筛不到——
 *       而那枚药丸根本不出现。</li>
 * </ul>
 * 只在接入点那一层量（如 {@link TimelineHookTest}）验不到后两环。
 */
@DisplayName("四大类记事端到端")
class FourCategoriesEndToEndTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 30003L;

    @TempDir
    Path dir;

    private TimelineStore store;

    private TimelineController controller;

    @BeforeEach
    void setUp() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        store = new TimelineStore(properties);
        store.load();
        controller = new TimelineController(store);
    }

    @Test
    @DisplayName("命令：群里执行一条命令, 日志页上出现「命令」这一类")
    void commandLandsOnTheLogPage() {
        dispatcher().onRemoteMessage(new StarBotRemoteMessageEvent(
                PLATFORM, "group", GROUP, 1L, "测试命令", null, true));

        TimelineEvent event = assertLanded(TimelineCategory.COMMAND, TimelineEventType.COMMAND_EXECUTED);
        assertEquals("群 " + GROUP, event.channel(), "哪个群里执行的, 是这一条最要紧的一栏");
    }

    @Test
    @DisplayName("告警：一路通道报出去了, 日志页上出现「告警」这一类")
    void alertLandsOnTheLogPage() {
        alertService(new RecordingChannel()).alert("机器人连接", "NovaBot 异常告警：机器人连接", "连不上");

        TimelineEvent event = assertLanded(TimelineCategory.ALERT, TimelineEventType.ALERT_SENT);
        assertEquals("邮件", event.channel(), "哪一路报出去的得写清: 邮件通了而 Webhook 挂了是常事");
    }

    @Test
    @DisplayName("设置：改动落到运行中的程序上, 日志页上出现「设置」这一类")
    void settingsLandOnTheLogPage() {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("starbot.core.push.enabled", "false");
        changes.put("starbot.core.push.quiet-start", "23:00");

        applier().applyAndTrack(changes);

        TimelineEvent event = assertLanded(TimelineCategory.SETTINGS, TimelineEventType.SETTINGS_APPLIED);
        assertEquals("starbot.core.push", event.detail().get("groups"),
                "两项同属一组, 记成一条一组; 组名答得了「昨天谁动了推送那一摊」");
        assertEquals("2", event.detail().get("count"));
    }

    /**
     * 设置类记事里不许出现被改的值
     * <p>
     * 时间线是逐行落在磁盘上的 {@code timeline/*.jsonl}，而经这条路的键里就有 Redis 口令
     * 与告警接收人。把取值抄进去，等于给配置文件里那几项做了一份不设防的副本——
     * 而日志页是登录后随手就能翻的一页。
     * <p>
     * 阴性对照喂的是<b>带唯一标记的口令值</b>：拿一个普通值去搜，搜不到也可能是
     * 它本来就不长那样；这一串只可能来自本次改动。同时搜整个磁盘文件而不是搜事件对象——
     * 落盘那一层若另抄了一份原始改动，只看事件对象是看不见的。
     */
    @Test
    @DisplayName("设置：正文与字段里一个字都不许出现被改的值")
    void settingsNeverRecordTheValue() throws IOException {
        // 三条各走一支：落得下的、走专用口落不下的、得等重启的。
        // 只喂落得下的那一支，另外两支往哪里记就没人看着了
        String marker = "NOVA-SECRET-9f3a1c7e";
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("starbot.core.push.quiet-end", marker);
        changes.put("starbot.core.config-ui.auth.password", marker);
        changes.put("spring.data.redis.password", marker);

        applier().applyAndTrack(changes);

        // 阳性：这几笔确实被记下来了，否则下面那一问在一份空文件上恒真
        assertFalse(store.recent().isEmpty(), "一条都没记的话, 「不含口令」这一问在空文件上恒真");

        String jsonl = readTimelineFiles();
        assertFalse(jsonl.isEmpty(), "落盘的那一份得真在, 搜一份空字符串搜不出任何东西");
        assertFalse(jsonl.contains(marker),
                "被改的值出现在了时间线文件里, 日志页等于给口令做了一份不设防的副本:\n" + jsonl);
    }

    @Test
    @DisplayName("系统：启动完成, 日志页上出现「系统」这一类")
    void systemStartLandsOnTheLogPage() {
        new SystemTimelineRecorder(store).onApplicationReadyEvent();

        TimelineEvent event = assertLanded(TimelineCategory.SYSTEM, TimelineEventType.SYSTEM_STARTED);
        assertTrue(event.detail().containsKey("startup_ms"),
                "「今天怎么起了三分钟」得有一个数才答得了, 而它只有起来的那一刻知道");
    }

    /**
     * 走完整条路：落盘的那一份读得回来、接口下发得出去、药丸清单里有这一类
     * @param category 该出现的大类
     * @param type 该出现的类型
     * @return 接口下发的那一条
     */
    private TimelineEvent assertLanded(TimelineCategory category, TimelineEventType type) {
        List<TimelineEvent> hit = store.query(new TimelineStore.Filter(
                        null, false, category, type, null, null, null, 0), null).events();
        assertEquals(1, hit.size(),
                type + " 没有落进时间线, 或落进去了读不回来: " + store.recent());

        JSONObject result = controller.timeline(null, false, category.name(), null, null, null, null, 0, null);
        assertTrue(result.getBooleanValue("success"), result.getString("message"));
        assertEquals(1, result.getIntValue("matched"), "接口按这一类筛不到它");
        assertEquals(type.name(), result.getJSONArray("events").getJSONObject(0).getString("type"));

        JSONArray categories = result.getJSONArray("categories");
        List<String> pills = new ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            pills.add(categories.getJSONObject(i).getString("name"));
        }
        assertTrue(pills.contains(category.name()),
                category + " 那枚药丸不出现, 记下来的事在屏幕上筛不到: " + pills);

        return hit.get(0);
    }

    /**
     * 把时间线目录里的整份 jsonl 读成一段文本
     */
    private String readTimelineFiles() throws IOException {
        Path timeline = dir.resolve("timeline");
        if (!Files.isDirectory(timeline)) {
            return "";
        }

        StringBuilder text = new StringBuilder();
        try (Stream<Path> files = Files.list(timeline)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                text.append(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return text.toString();
    }

    // —— 以下为夹具 ——

    private CommandDispatcher dispatcher() {
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of(configuredUser()));

        @SuppressWarnings("unchecked")
        ObjectProvider<StarBotCommand> commands = mock(ObjectProvider.class);
        StarBotCommand command = new StubCommand();
        when(commands.iterator()).thenAnswer(invocation -> List.of(command).iterator());
        when(commands.orderedStream()).thenAnswer(invocation -> Stream.of(command));

        @SuppressWarnings("unchecked")
        ObjectProvider<CommandFollowUp> followUps = mock(ObjectProvider.class);
        when(followUps.iterator()).thenAnswer(invocation -> List.<CommandFollowUp>of().iterator());

        return new CommandDispatcher(commands, followUps,
                new CommandSettingsService(new StarBotStateStore(new StarBotCoreProperties())),
                dataSource, mock(StarBotMessageSender.class), new StarBotCoreProperties(), store);
    }

    /**
     * 走生产那一支构造：判据台架那个包内构造口在别的包里够不着，
     * 而这一格量的正是「装好之后这条路通不通」
     */
    private RuntimeConfigurationApplier applier() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RuntimeConfigurationApplierContributor> contributors = mock(ObjectProvider.class);
        when(contributors.orderedStream())
                .thenAnswer(invocation -> Stream.<RuntimeConfigurationApplierContributor>of());
        return new RuntimeConfigurationApplier(new StarBotCoreProperties(), null, contributors, store);
    }

    private AlertService alertService(AlertChannel channel) {
        @SuppressWarnings("unchecked")
        ObjectProvider<AlertChannel> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(channel));
        return new AlertService(new StarBotCoreProperties(), provider, store);
    }

    private PushUser configuredUser() {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.GROUP);
        target.setNum(GROUP);
        target.setMessages(new ArrayList<>());

        PushUser user = new PushUser();
        user.setUid(10001L);
        user.setUname("主播甲");
        user.setPlatform("bilibili");
        user.setTargets(List.of(target));
        return user;
    }

    /**
     * 一条什么也不做、只答应一声的命令
     */
    private static final class StubCommand implements StarBotCommand {
        @Override
        public String name() {
            return "测试命令";
        }

        @Override
        public List<String> aliases() {
            return List.of();
        }

        @Override
        public String description() {
            return "供判据使用";
        }

        @Override
        public CommandReply execute(CommandContext context) {
            return CommandReply.of("已执行");
        }
    }

    /**
     * 一路配好了、发得出去的通道
     */
    private static final class RecordingChannel implements AlertChannel {
        @Override
        public String id() {
            return "mail";
        }

        @Override
        public String name() {
            return "邮件";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void send(String subject, String content) {
            // 发出去了就是发出去了，这一格问的是时间线上留没留下这一条
        }
    }
}
