package com.starlwr.bot.core.sender;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.health.PushActivityRecorder;
import com.starlwr.bot.core.model.Message;
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

    /**
     * 一次回放用的整套零件
     * <p>
     * 每行语料新建一份：「已提示过」按会话记着，共用一份的话，前一行的痕迹会落到下一行头上。
     */
    private static class Fixture {
        private final StarBotCoreProperties properties = new StarBotCoreProperties();

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
                    new FirstPushTipService(new StarBotStateStore(properties)));
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
