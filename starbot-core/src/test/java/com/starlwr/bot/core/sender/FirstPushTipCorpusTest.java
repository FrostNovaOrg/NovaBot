package com.starlwr.bot.core.sender;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.health.PushActivityRecorder;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.model.Sender;
import com.starlwr.bot.core.service.AtAllQuotaService;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.service.StarBotStateStore;
import com.starlwr.bot.core.timeline.TimelineWriter;
import com.starlwr.bot.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 首次推送后的用法提示语料回放
 * <p>
 * 机器人被拉进一个群之后，群里的人只看得见通知，看不见「还能问它点什么」——命令表在控制台里，
 * 而看得见控制台的只有机器人的主人。提示就是补这一句，而它只在<b>第一条推送真的送到之后</b>发一次：
 * 发早了没人在意，发多了就是打扰。
 * <p>
 * 这条规矩的每一种边界（第二条、另一个群、私聊、静音、失败、命令回复）都要有人量，
 * 漏掉的那一种与「测过且通过」在报告里长得一样，因此摆成一张表：一行一段推送序列，
 * 加一种边界就是加一行。
 */
@DisplayName("首推用法提示语料")
class FirstPushTipCorpusTest {
    private static final String PLATFORM = "qq-onebot";

    private static final long GROUP_A = 30003L;

    private static final long GROUP_B = 30004L;

    private static final long FRIEND = 20001L;

    /**
     * 等一条消息真的投出去的上限。投递方是后台线程，量它只能等；
     * 而这里的「投递」是个内存里的替身，正常情形下几微秒就回来了
     */
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 等「不该再有下一条」的观察窗口
     * <p>
     * 提示是在同一个线程里紧跟着投出去的，隔的是几微秒。留这么久纯粹是为了
     * 让「多出来的那一条」有充分的机会露面——它若在这段时间里没出现，就是真的没有
     */
    private static final Duration SETTLE = Duration.ofMillis(200);

    /**
     * 落盘回环那一行用的目录，语料回放不落盘
     */
    @TempDir
    static Path dataDir;

    /**
     * 一条推送
     *
     * @param type 会话类型
     * @param num 会话号
     * @param succeeds 推送接口是否回成功
     * @param quiet 这一条发出时是否处于静音时段
     * @param reply 这一条是不是对命令的回复
     * @param tip 这条推送之后该跟的提示里出现的片段；为 null 表示不该有提示
     */
    private record Push(PushTargetType type, long num, boolean succeeds, boolean quiet, boolean reply, String tip) {
        /**
         * 这一次该发出去几条
         */
        int expected() {
            if (quiet) {
                // 静音时段整条丢弃，连推送本身都不出去
                return 0;
            }
            return tip == null ? 1 : 2;
        }
    }

    private static Push toGroup(long num, String tip) {
        return new Push(PushTargetType.GROUP, num, true, false, false, tip);
    }

    private static Push toFriend(long num, String tip) {
        return new Push(PushTargetType.FRIEND, num, true, false, false, tip);
    }

    private static Push failing(long num) {
        return new Push(PushTargetType.GROUP, num, false, false, false, null);
    }

    private static Push muted(long num) {
        return new Push(PushTargetType.GROUP, num, true, true, false, null);
    }

    private static Push replying(long num) {
        return new Push(PushTargetType.GROUP, num, true, false, true, null);
    }

    /**
     * 一行语料
     *
     * @param name 这一行在说哪种情形
     * @param pushes 依次发出的推送
     */
    private record Corpus(String name, List<Push> pushes) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Corpus corpus(String name, Push... pushes) {
        return new Corpus(name, List.of(pushes));
    }

    private static Stream<Corpus> corpus() {
        return Stream.of(
                corpus("向某个群成功推送第一条 —— 紧跟一条用法提示",
                        toGroup(GROUP_A, "先 @ 我")),

                corpus("同一个群的第二条 —— 不再提示",
                        toGroup(GROUP_A, "先 @ 我"),
                        toGroup(GROUP_A, null)),

                corpus("另一个群的第一条 —— 各群各提示一次",
                        toGroup(GROUP_A, "先 @ 我"),
                        toGroup(GROUP_B, "先 @ 我"),
                        toGroup(GROUP_A, null),
                        toGroup(GROUP_B, null)),

                corpus("私聊的第一条 —— 提示改成「直接发」，私聊里 @ 不到人",
                        toFriend(FRIEND, "直接发")),

                corpus("群与私聊互不相干 —— 各算各的第一条",
                        toGroup(GROUP_A, "先 @ 我"),
                        toFriend(FRIEND, "直接发")),

                corpus("推送失败 —— 不提示：群里根本没见过这个机器人说话",
                        failing(GROUP_A)),

                corpus("命令回复不算推送 —— 刚发过命令的人不必再被教一遍怎么发命令",
                        replying(GROUP_A),
                        toGroup(GROUP_A, "先 @ 我")),

                corpus("静音时段 —— 整条丢弃，也不算提示过；静音过去之后照样提示",
                        muted(GROUP_A),
                        toGroup(GROUP_A, "先 @ 我")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void replays(Corpus corpus) {
        Fixture fixture = new Fixture();

        for (Push push : corpus.pushes()) {
            List<String> sent = fixture.push(push);

            assertEquals(push.expected(), sent.size(),
                    corpus.name() + "：这一次该发出 " + push.expected() + " 条，实际发出 " + sent);
            if (sent.isEmpty()) {
                continue;
            }

            assertTrue(sent.get(0).contains("开播啦"), corpus.name() + "：第一条该是推送本身，实际是 " + sent.get(0));
            if (push.tip() != null) {
                assertTrue(sent.get(1).contains(push.tip()),
                        corpus.name() + "：提示里缺「" + push.tip() + "」，实际是「" + sent.get(1) + "」");
                assertTrue(sent.get(1).contains("菜单"),
                        corpus.name() + "：提示得指出发什么，实际是「" + sent.get(1) + "」");
            }
        }
    }

    @Test
    @DisplayName("已提示过落在运行状态里 —— 重启之后不会再教一遍")
    void survivesRestart() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dataDir.resolve("data.json").toString());

        StarBotStateStore before = new StarBotStateStore(properties);
        assertTrue(new FirstPushTipService(before).claim(PLATFORM, PushTargetType.GROUP, GROUP_A),
                "同一个群第一次推送该认成第一次");
        before.save();

        // 换一份存储，从盘上把它读回来：这一段若断了，现象是「机器人每重启一次就把同一句话再教一遍」，
        // 而在内存里量永远量不到——写进去的那个对象自己就是答案
        StarBotStateStore after = new StarBotStateStore(properties);
        after.onApplicationReadyEvent();
        try {
            assertFalse(new FirstPushTipService(after).claim(PLATFORM, PushTargetType.GROUP, GROUP_A),
                    "重启之后同一个群不该再算第一次");
            assertTrue(new FirstPushTipService(after).claim(PLATFORM, PushTargetType.GROUP, GROUP_B),
                    "另一个群还是各算各的");
        } finally {
            after.onContextClosedEvent();
        }
    }

    @Test
    @DisplayName("升级后把已配置的会话记成已提示；有标记后新加的会话仍会提示一次")
    void seedsConfiguredSessionsOnce() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dataDir.resolve("seed.json").toString());

        StarBotStateStore store = new StarBotStateStore(properties);
        FirstPushTipService service = new FirstPushTipService(store);
        service.seedExisting(List.of(
                session(PLATFORM, PushTargetType.GROUP, GROUP_A),
                session(PLATFORM, PushTargetType.FRIEND, FRIEND)));

        assertFalse(service.claim(PLATFORM, PushTargetType.GROUP, GROUP_A),
                "老群升级后被当成新群：已经在用的群还会再收到那句「想用我，先 @ 我」");
        assertFalse(service.claim(PLATFORM, PushTargetType.FRIEND, FRIEND),
                "老好友升级后被当成新会话：已经在用的好友还会再收到那句用法提示");
        assertTrue(service.claim(PLATFORM, PushTargetType.GROUP, GROUP_B),
                "补记当时还不在配置里的会话，第一次推送仍该提示");
        assertTrue(store.namespace("FirstPushTip").containsKey(FirstPushTipService.SEEDED_KEY),
                "补记之后要留下标记，下次启动才知道已经做过");
        assertFalse(service.claim(PLATFORM, PushTargetType.GROUP, GROUP_A),
                "标记键不得被当成某个会话：认领老群仍然只看会话键");

        store.save();
        StarBotStateStore again = new StarBotStateStore(properties);
        again.onApplicationReadyEvent();
        try {
            long third = 30005L;
            FirstPushTipService restarted = new FirstPushTipService(again);
            restarted.seedExisting(List.of(
                    session(PLATFORM, PushTargetType.GROUP, GROUP_A),
                    session(PLATFORM, PushTargetType.FRIEND, FRIEND),
                    session(PLATFORM, PushTargetType.GROUP, third)));
            assertTrue(restarted.claim(PLATFORM, PushTargetType.GROUP, third),
                    "已经补记过一次之后，后来才加进来的第三个会话不该被二次补记盖住");
            assertFalse(restarted.claim(PLATFORM, PushTargetType.GROUP, GROUP_A),
                    "已经补记过的老群，再启动一次也不该再算第一次");
        } finally {
            again.onContextClosedEvent();
        }
    }

    @Test
    @DisplayName("补记跳过停用目标 —— 日后启用第一条仍会提示")
    void seedExistingSkipsDisabledTargets() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dataDir.resolve("seed-disabled.json").toString());

        StarBotStateStore store = new StarBotStateStore(properties);
        FirstPushTipService service = new FirstPushTipService(store);

        PushUser disabled = session(PLATFORM, PushTargetType.GROUP, GROUP_B);
        disabled.getTargets().get(0).setEnabled(false);

        service.seedExisting(List.of(
                session(PLATFORM, PushTargetType.GROUP, GROUP_A),
                disabled));

        assertFalse(service.claim(PLATFORM, PushTargetType.GROUP, GROUP_A),
                "启用目标升级后不该再提示");
        assertTrue(service.claim(PLATFORM, PushTargetType.GROUP, GROUP_B),
                "停用目标被补记成已提示过：日后启用第一条推送永远收不到首次用法提示");
    }

    @Test
    @DisplayName("空名单不钉已补过 —— 配好之后再补一次")
    void seedExistingEmptyListDoesNotPinSeeded() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dataDir.resolve("seed-empty.json").toString());

        StarBotStateStore store = new StarBotStateStore(properties);
        FirstPushTipService service = new FirstPushTipService(store);

        service.seedExisting(List.of());
        assertFalse(store.namespace("FirstPushTip").containsKey(FirstPushTipService.SEEDED_KEY),
                "名单为空仍钉「已补过」：数据源配错那一趟空表被钉死，配好重启后老群没人补记");

        service.seedExisting(List.of(session(PLATFORM, PushTargetType.GROUP, GROUP_A)));
        assertTrue(store.namespace("FirstPushTip").containsKey(FirstPushTipService.SEEDED_KEY),
                "非空名单补记之后才该留下标记");
        assertFalse(service.claim(PLATFORM, PushTargetType.GROUP, GROUP_A),
                "非空名单里的会话该被记成已提示");
    }

    @Test
    @DisplayName("全停用名单不钉、文案无可补记；空名单仍写名单为空")
    void seedExistingAllDisabledDoesNotPinLogsNothingToSeed() {
        List<String> red = new ArrayList<>();

        Logger logger = (Logger) LoggerFactory.getLogger(FirstPushTipService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            StarBotCoreProperties properties = new StarBotCoreProperties();
            properties.getLive().setLiveDataPath(dataDir.resolve("seed-all-disabled.json").toString());
            StarBotStateStore store = new StarBotStateStore(properties);
            FirstPushTipService service = new FirstPushTipService(store);

            PushUser disabledGroup = session(PLATFORM, PushTargetType.GROUP, GROUP_A);
            disabledGroup.getTargets().get(0).setEnabled(false);
            PushUser disabledFriend = session(PLATFORM, PushTargetType.FRIEND, FRIEND);
            disabledFriend.getTargets().get(0).setEnabled(false);
            service.seedExisting(List.of(disabledGroup, disabledFriend));

            try {
                assertFalse(store.namespace("FirstPushTip").containsKey(FirstPushTipService.SEEDED_KEY),
                        "名单非空但全停用仍钉「已补过」：启用后重启会被当成已提示");
            } catch (Throwable t) {
                red.add("① " + t.getMessage());
            }
            try {
                String joined = infoMessages(appender);
                assertTrue(joined.contains("无可补记"), "全停用名单日志应含「无可补记」，实际: " + joined);
            } catch (Throwable t) {
                red.add("② " + t.getMessage());
            }

            appender.list.clear();
            StarBotCoreProperties emptyProps = new StarBotCoreProperties();
            emptyProps.getLive().setLiveDataPath(dataDir.resolve("seed-empty-log.json").toString());
            StarBotStateStore emptyStore = new StarBotStateStore(emptyProps);
            new FirstPushTipService(emptyStore).seedExisting(List.of());
            try {
                String joined = infoMessages(appender);
                assertTrue(joined.contains("名单为空"), "真空名单日志应仍含「名单为空」，实际: " + joined);
                assertFalse(joined.contains("无可补记"), "真空名单不该写成「无可补记」，实际: " + joined);
            } catch (Throwable t) {
                red.add("③ " + t.getMessage());
            }
        } finally {
            logger.detachAppender(appender);
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("关掉首次提示时不发也不认领，打开后第一条才发")
    void switchOffSkipsTipAndClaim() {
        Fixture fixture = new Fixture();
        fixture.properties.getPush().setFirstPushTip(false);

        List<String> off = fixture.push(toGroup(GROUP_A, null));
        assertEquals(1, off.size(),
                "关着时不该附那句用法提示，老群升级后被当新群的那一句会在关着时仍发出去");
        assertTrue(off.get(0).contains("开播啦"), "关着时推送本身仍该发出");

        fixture.properties.getPush().setFirstPushTip(true);
        List<String> on = fixture.push(toGroup(GROUP_A, "先 @ 我"));
        assertEquals(2, on.size(),
                "关着时若已经认领，打开后第一条也不会再提示——关着必须不认领");
        assertTrue(on.get(1).contains("先 @ 我"), "打开后第一条该带用法提示");
    }

    private static String infoMessages(ListAppender<ILoggingEvent> appender) {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            if (event.getLevel() != Level.INFO) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(event.getFormattedMessage());
        }
        return text.toString();
    }

    private static PushUser session(String platform, PushTargetType type, long num) {
        PushUser user = new PushUser();
        PushTarget target = new PushTarget();
        target.setPlatform(platform);
        target.setType(type);
        target.setNum(num);
        user.getTargets().add(target);
        return user;
    }

    /**
     * 一次回放用的整套零件
     * <p>
     * 每行语料新建一份：「已提示过」按会话记着，共用一份的话，前一行的痕迹会落到下一行头上。
     */
    private static class Fixture {
        final StarBotCoreProperties properties = new StarBotCoreProperties();

        private final List<String> delivered = Collections.synchronizedList(new ArrayList<>());

        private final StarBotMessageSender sender;

        private volatile boolean succeeds = true;

        Fixture() {
            HttpUtil http = mock(HttpUtil.class);
            when(http.postJson(anyString(), any(), any())).thenAnswer(invocation -> {
                Map<String, Object> params = invocation.getArgument(2);
                delivered.add(String.valueOf(params.get("content")));
                return succeeds
                        ? new JSONObject().fluentPut("code", 0).fluentPut("id", "m" + delivered.size())
                        : new JSONObject().fluentPut("code", 2).fluentPut("message", "群不存在");
            });

            Sender target = new Sender();
            target.setName(PLATFORM);
            target.setUrl("http://127.0.0.1:7827/onebot/send");
            target.setDelay(0);

            StarBotSenderService senderService = mock(StarBotSenderService.class);
            when(senderService.getSender(PLATFORM)).thenReturn(Optional.of(target));

            @SuppressWarnings("unchecked")
            ObjectProvider<AtAllPermissionResolver> resolvers = mock(ObjectProvider.class);
            when(resolvers.iterator()).thenAnswer(invocation -> List.<AtAllPermissionResolver>of().iterator());

            sender = new StarBotMessageSender(http, senderService,
                    new PushActivityRecorder(TimelineWriter.NONE), new PushGate(properties),
                    TimelineWriter.NONE, new AtAllQuotaService(properties), resolvers,
                    new FirstPushTipService(new StarBotStateStore(properties), properties));
        }

        /**
         * 推一条，返回这一次实际发出去的每一条（按顺序）
         * <p>
         * 走 {@link StarBotMessageSender#send} 而不是 sendNow：提示只跟在真正的推送后面，
         * 而 sendNow 是「发送测试消息」按钮那条路。代价是投递在后台线程上，只能等——
         * 因此先等够该有的条数，再多看一会儿有没有多出来的
         */
        List<String> push(Push push) {
            quiet(push.quiet());
            succeeds = push.succeeds();

            int before = delivered.size();
            Message message = Message.create(PLATFORM, push.type(), push.num(), "开播啦").get(0);
            message.setReply(push.reply());
            sender.send(message);

            await(before + push.expected());
            settle();
            synchronized (delivered) {
                return List.copyOf(delivered.subList(before, delivered.size()));
            }
        }

        /**
         * 等到投出去的总条数达到目标，或超时为止
         */
        private void await(int total) {
            Instant deadline = Instant.now().plus(DELIVERY_TIMEOUT);
            while (delivered.size() < total && Instant.now().isBefore(deadline)) {
                Thread.onSpinWait();
            }
        }

        private void settle() {
            try {
                Thread.sleep(SETTLE.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * 把静音时段设成「此刻」的前后各一小时，或撤掉
         */
        private void quiet(boolean quiet) {
            if (!quiet) {
                properties.getPush().setQuietStart("");
                properties.getPush().setQuietEnd("");
                return;
            }
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
            LocalTime now = LocalTime.now();
            properties.getPush().setQuietStart(formatter.format(now.minusHours(1)));
            properties.getPush().setQuietEnd(formatter.format(now.plusHours(1)));
        }
    }
}
