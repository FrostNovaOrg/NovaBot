package org.frostnova.nova.adapter.onebot.extension.napcat.aop;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.exception.OneBotApiException;
import org.frostnova.nova.adapter.onebot.extension.napcat.http.NapcatHttpAdapter;
import org.frostnova.nova.adapter.onebot.extension.napcat.util.NapcatServiceHolder;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Pointcut;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @全体成员 发不出去时改发群待办
 *
 * <h2>这把尺量的是「谁还没被打扰」</h2>
 * 这个切面的每一条放行分支都对应一群<b>不该被牵连的人</b>：不是 NapCat 的平台、
 * 私聊、正文里根本没有 @全体成员、次数还够用。这四条只要有一条判错，
 * 一次本该原样发出的推送就会被改写成群待办——而群待办是会顶在群名片上的，
 * 错发比不发更扰人。因此放行分支与替换分支同等重要，各有各的格。
 *
 * <h2>回调而不是直接调</h2>
 * 群待办要挂在一条<b>已经发出去的</b>消息上（待办认的是 message_id），
 * 所以这里只能先登记一个发送成功回调，等发完再打第二支接口。
 * 判据因此分两步：先看回调有没有登记上，再手动跑一次回调，看它打给谁、带的什么。
 */
@DisplayName("@全体成员 次数不足转群待办")
class BackupAtAllAspectTest {
    private static final String PLATFORM = "napcat-qq";

    private static final long GROUP = 700100200L;

    /**
     * 放行时被拦住的那一层交回来的返回值，用来分辨「放行了」与「拦下了」
     */
    private static final Object PROCEEDED = new Object();

    private NapcatServiceHolder holder;

    private NapcatHttpAdapter http;

    private BackupAtAllAspect aspect;

    private final OneBotSender sender = sender();

    private static OneBotSender sender() {
        OneBotSender sender = new OneBotSender();
        sender.setName(PLATFORM);
        return sender;
    }

    @BeforeEach
    void setUp() {
        holder = new NapcatServiceHolder();
        holder.registerNapcat(sender);
        http = mock(NapcatHttpAdapter.class);
        aspect = new BackupAtAllAspect(holder, http);
    }

    private Message message(String platform, PushTargetType type, String content) {
        Message message = new Message();
        message.setPlatform(platform);
        message.setType(type);
        message.setNum(GROUP);
        message.setContent(content);
        message.setSequence(1L);
        return message;
    }

    private ProceedingJoinPoint sending(Message message) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{message});
        when(joinPoint.proceed()).thenReturn(PROCEEDED);
        return joinPoint;
    }

    /**
     * 让「还能不能 @全体成员」这一问回给定的答案；null 表示回一份没有该字段的报文
     */
    private void canAtAll(Boolean answer) {
        JSONObject remain = new JSONObject();
        if (answer != null) {
            remain.put("can_at_all", answer);
        }
        when(http.getGroupAtAllRemain(any(), any())).thenReturn(remain);
    }

    @Nested
    @DisplayName("放行")
    class PassesThrough {
        @Test
        @DisplayName("不是 NapCat 的平台一概不管, 连问都不问")
        void otherPlatformIsUntouched() throws Throwable {
            Message message = message("qq-onebot", PushTargetType.GROUP, "开播啦{at=all}");

            assertSame(PROCEEDED, aspect.aroundSendMethod(sending(message)));

            assertEquals("开播啦{at=all}", message.getContent());
            verifyNoInteractions(http);
        }

        @Test
        @DisplayName("私聊不管, 群待办是群里的事")
        void privateMessageIsUntouched() throws Throwable {
            Message message = message(PLATFORM, PushTargetType.FRIEND, "开播啦{at=all}");

            assertSame(PROCEEDED, aspect.aroundSendMethod(sending(message)));

            assertEquals("开播啦{at=all}", message.getContent());
            verifyNoInteractions(http);
        }

        @Test
        @DisplayName("正文里没有 @全体成员 就不管")
        void contentWithoutAtAllIsUntouched() throws Throwable {
            Message message = message(PLATFORM, PushTargetType.GROUP, "开播啦{at=123456}");

            assertSame(PROCEEDED, aspect.aroundSendMethod(sending(message)));

            assertEquals("开播啦{at=123456}", message.getContent());
            verifyNoInteractions(http);
        }

        @Test
        @DisplayName("次数还够用时原样发出, 不碰正文也不设待办")
        void remainingQuotaKeepsAtAll() throws Throwable {
            canAtAll(true);
            Message message = message(PLATFORM, PushTargetType.GROUP, "开播啦{at=all}");

            assertSame(PROCEEDED, aspect.aroundSendMethod(sending(message)));

            assertEquals("开播啦{at=all}", message.getContent());
            verify(http, never()).setGroupTodo(any(), any());
        }

        @Test
        @DisplayName("问次数问的是登记在册的那个平台, 带的是本群群号")
        void asksAboutThisGroup() throws Throwable {
            canAtAll(true);

            aspect.aroundSendMethod(sending(message(PLATFORM, PushTargetType.GROUP, "{at=all}开播啦")));

            ArgumentCaptor<OneBotSender> asked = ArgumentCaptor.forClass(OneBotSender.class);
            ArgumentCaptor<JSONObject> params = ArgumentCaptor.forClass(JSONObject.class);
            verify(http).getGroupAtAllRemain(asked.capture(), params.capture());

            assertSame(sender, asked.getValue());
            assertEquals("{\"group_id\":700100200}", params.getValue().toJSONString());
        }
    }

    @Nested
    @DisplayName("次数不足")
    class QuotaExhausted {
        @Test
        @DisplayName("正文里的 @全体成员 摘掉, 剩下的照发, 发成功后补一条群待办")
        void stripsAtAllAndSetsTodoAfterSend() throws Throwable {
            canAtAll(false);
            Message message = message(PLATFORM, PushTargetType.GROUP, "开播啦{at=all}快来看");

            assertSame(PROCEEDED, aspect.aroundSendMethod(sending(message)));

            assertEquals("开播啦快来看", message.getContent());
            verify(http, never()).setGroupTodo(any(), any());

            // 消息发出去、拿到 ID 之后，回调才把待办挂上去
            message.setId("9527");
            runCallbacks(message.getOnSuccessCallbacks());

            ArgumentCaptor<JSONObject> todo = ArgumentCaptor.forClass(JSONObject.class);
            verify(http).setGroupTodo(any(), todo.capture());
            assertEquals("{\"group_id\":700100200,\"message_id\":\"9527\"}", todo.getValue().toJSONString());
        }

        @Test
        @DisplayName("⚠️ 报文里没有 can_at_all 时按「不能 @」办")
        void missingFieldCountsAsExhausted() throws Throwable {
            canAtAll(null);
            Message message = message(PLATFORM, PushTargetType.GROUP, "开播啦{at=all}");

            aspect.aroundSendMethod(sending(message));

            assertEquals("开播啦", message.getContent());
            assertEquals(1, message.getOnSuccessCallbacks().size());
        }

        @Test
        @DisplayName("⚠️ 对面回了报文却没给 data 时同样按「不能 @」办, 不在这里抛空指针")
        void missingDataCountsAsExhausted() throws Throwable {
            when(http.getGroupAtAllRemain(any(), any())).thenReturn(null);
            Message message = message(PLATFORM, PushTargetType.GROUP, "开播啦{at=all}");

            assertSame(PROCEEDED, aspect.aroundSendMethod(sending(message)));

            assertEquals("开播啦", message.getContent());
            assertEquals(1, message.getOnSuccessCallbacks().size());
        }

        @Test
        @DisplayName("正文里有多处 @全体成员 时一并摘掉")
        void stripsEveryOccurrence() throws Throwable {
            canAtAll(false);
            Message message = message(PLATFORM, PushTargetType.GROUP, "{at=all}开播啦{at=all}");

            aspect.aroundSendMethod(sending(message));

            assertEquals("开播啦", message.getContent());
        }
    }

    @Nested
    @DisplayName("摘掉之后这条什么都不剩")
    class NothingLeftToSend {
        @Test
        @DisplayName("有后一条时这条不发, 待办挂到后一条上")
        void todoRidesOnTheNextMessage() throws Throwable {
            canAtAll(false);
            Message message = message(PLATFORM, PushTargetType.GROUP, "{at=all}");
            Message next = message(PLATFORM, PushTargetType.GROUP, "开播啦");
            message.setNext(next);
            ProceedingJoinPoint joinPoint = sending(message);

            assertNull(aspect.aroundSendMethod(joinPoint));

            verify(joinPoint, never()).proceed();
            assertEquals(0, message.getOnSuccessCallbacks().size());
            assertEquals(1, next.getOnSuccessCallbacks().size());
            verify(http, never()).setGroupTodo(any(), any());

            next.setId("9528");
            runCallbacks(next.getOnSuccessCallbacks());

            ArgumentCaptor<JSONObject> todo = ArgumentCaptor.forClass(JSONObject.class);
            verify(http).setGroupTodo(any(), todo.capture());
            assertEquals("{\"group_id\":700100200,\"message_id\":\"9528\"}", todo.getValue().toJSONString());
        }

        @Test
        @DisplayName("没有后一条但有前一条时, 立刻把待办挂到已经发出去的前一条上")
        void todoRidesOnThePreviousMessage() throws Throwable {
            canAtAll(false);
            Message previous = message(PLATFORM, PushTargetType.GROUP, "开播啦");
            previous.setId("9529");
            Message message = message(PLATFORM, PushTargetType.GROUP, "{at=all}");
            message.setPrevious(previous);
            ProceedingJoinPoint joinPoint = sending(message);

            assertNull(aspect.aroundSendMethod(joinPoint));

            verify(joinPoint, never()).proceed();
            ArgumentCaptor<JSONObject> todo = ArgumentCaptor.forClass(JSONObject.class);
            verify(http).setGroupTodo(any(), todo.capture());
            assertEquals("{\"group_id\":700100200,\"message_id\":\"9529\"}", todo.getValue().toJSONString());
        }

        @Test
        @DisplayName("前后都没有时只记一条错误日志, 不发也不设待办")
        void nothingToHangTheTodoOn() throws Throwable {
            canAtAll(false);
            Message message = message(PLATFORM, PushTargetType.GROUP, "{at=all}");
            ProceedingJoinPoint joinPoint = sending(message);

            assertNull(aspect.aroundSendMethod(joinPoint));

            verify(joinPoint, never()).proceed();
            verify(http, never()).setGroupTodo(any(), any());
        }

        @Test
        @DisplayName("⚠️ 只剩空白也算什么都不剩")
        void whitespaceOnlyCountsAsEmpty() throws Throwable {
            canAtAll(false);
            Message message = message(PLATFORM, PushTargetType.GROUP, " {at=all} ");
            ProceedingJoinPoint joinPoint = sending(message);

            assertNull(aspect.aroundSendMethod(joinPoint));

            verify(joinPoint, never()).proceed();
        }
    }

    @Nested
    @DisplayName("前一条还没发出")
    class PreviousNotSentYet {
        @Test
        @DisplayName("等前一条发出、拿到编号再挂；挂的时候出错只记一行警告，推送照常结束")
        void waitsForTheIdAndAFailureIsOnlyAWarning() throws Throwable {
            canAtAll(false);
            Message previous = message(PLATFORM, PushTargetType.GROUP, "开播啦");
            Message message = message(PLATFORM, PushTargetType.GROUP, "{at=all}");
            message.setPrevious(previous);
            ProceedingJoinPoint joinPoint = sending(message);

            assertNull(aspect.aroundSendMethod(joinPoint));
            verify(joinPoint, never()).proceed();
            verify(http, never()).setGroupTodo(any(), any());

            doThrow(new IllegalStateException("连不上")).when(http).setGroupTodo(any(), any());
            previous.setId("4411");

            Logger logger = (Logger) LoggerFactory.getLogger(BackupAtAllAspect.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                assertDoesNotThrow(() -> runCallbacks(previous.getOnSuccessCallbacks()));
            } finally {
                logger.detachAppender(appender);
            }

            ArgumentCaptor<JSONObject> todo = ArgumentCaptor.forClass(JSONObject.class);
            verify(http).setGroupTodo(any(), todo.capture());
            assertEquals("4411", todo.getValue().getString("message_id"),
                    "应等前一条发出后，带着它的编号去挂");

            List<ILoggingEvent> warns = appender.list.stream()
                    .filter(event -> "WARN".equals(event.getLevel().toString()))
                    .toList();
            assertEquals(1, warns.size(), "挂不上只该多一行警告: " + appender.list);
            assertNull(warns.get(0).getThrowableProxy(), "警告不带堆栈");
            assertTrue(warns.get(0).getFormattedMessage().contains("连不上"),
                    "警告应带上失败原因: " + warns.get(0).getFormattedMessage());
        }

        @Test
        @DisplayName("前一条最终没发出去时不挂群待办，只记一行警告")
        void givesUpWhenPreviousNeverGoesOut() throws Throwable {
            canAtAll(false);
            Message previous = message(PLATFORM, PushTargetType.GROUP, "开播啦");
            Message message = message(PLATFORM, PushTargetType.GROUP, "{at=all}");
            message.setPrevious(previous);

            Logger logger = (Logger) LoggerFactory.getLogger(BackupAtAllAspect.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                assertNull(aspect.aroundSendMethod(sending(message)));
                verify(http, never()).setGroupTodo(any(), any());

                runCallbacks(previous.getOnFailureCallbacks());

                verify(http, never()).setGroupTodo(any(), any());
                List<ILoggingEvent> warns = appender.list.stream()
                        .filter(event -> "WARN".equals(event.getLevel().toString()))
                        .toList();
                assertEquals(1, warns.size(), "没挂上只该记一行警告: " + appender.list);
                assertNull(warns.get(0).getThrowableProxy(), "警告不带堆栈");
                assertTrue(warns.get(0).getFormattedMessage().contains("没有发出去"),
                        warns.get(0).getFormattedMessage());
            } finally {
                logger.detachAppender(appender);
            }
        }
    }

    /**
     * 问次数这一趟本身失败：连不上，或对面回了错误码
     * <p>
     * 切面织在发送器的 send 上，跑在推送处理器的线程里、排在入队之前。这一问的异常若顺着 send
     * 抛回推送处理器，这一条就没入队，同一次推送里排在它后面的分条也不再交出去；
     * 发送器的重试、失败计数与去图重发一样都轮不到，日志里只剩事件监听器记的一行错误，
     * 健康栏的推送活动里也不记这一条。
     * <p>
     * 问不出来时照原样交给发送器：发得出去就是配置里写的那条推送，发不出去由发送器记失败。
     * 只接这一问——发送器自己抛的错不归这里管，照样往外抛。
     */
    @Nested
    @DisplayName("问不通")
    class QueryFails {
        @Test
        @DisplayName("连不上 NapCat 时照原样交给发送器, 不摘 @全体成员也不挂待办, 记一条带原因的 WARN")
        void unreachableSendsAsIs() throws Throwable {
            when(http.getGroupAtAllRemain(any(), any())).thenThrow(unreachable());
            Message message = message(PLATFORM, PushTargetType.GROUP, "开播啦{at=all}");
            ProceedingJoinPoint joinPoint = sending(message);

            Logger logger = (Logger) LoggerFactory.getLogger(BackupAtAllAspect.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                assertSame(PROCEEDED, aspect.aroundSendMethod(joinPoint));
            } finally {
                logger.detachAppender(appender);
            }

            verify(joinPoint, times(1)).proceed();
            assertEquals("开播啦{at=all}", message.getContent());
            assertEquals(0, message.getOnSuccessCallbacks().size());
            verify(http, never()).setGroupTodo(any(), any());

            List<String> logged = appender.list.stream()
                    .map(event -> event.getLevel() + " " + event.getFormattedMessage())
                    .toList();
            assertEquals(1, logged.size(), "问不通只该记一条: " + logged);
            assertTrue(logged.get(0).startsWith("WARN ") && logged.get(0).contains("照原样发送")
                    && logged.get(0).contains("I/O error on POST request"), "该是一条带着原因的 WARN: " + logged);
        }

        @Test
        @DisplayName("对面回错误码时同样照原样交给发送器")
        void errorCodeSendsAsIs() throws Throwable {
            when(http.getGroupAtAllRemain(any(), any())).thenThrow(
                    new OneBotApiException("/get_group_at_all_remain", new JSONObject(), 1404, "API 不存在"));
            Message message = message(PLATFORM, PushTargetType.GROUP, "开播啦{at=all}");

            assertSame(PROCEEDED, aspect.aroundSendMethod(sending(message)));

            assertEquals("开播啦{at=all}", message.getContent());
        }

        @Test
        @DisplayName("⚠️ 问不通之后发送器自己抛的错照样往外抛, 只交一次")
        void senderFailureStillSurfacesAfterQueryFailure() throws Throwable {
            when(http.getGroupAtAllRemain(any(), any())).thenThrow(unreachable());
            ProceedingJoinPoint joinPoint = sending(message(PLATFORM, PushTargetType.GROUP, "开播啦{at=all}"));
            IllegalStateException broken = new IllegalStateException("发送器坏了");
            when(joinPoint.proceed()).thenThrow(broken);

            assertSame(broken, assertThrows(IllegalStateException.class, () -> aspect.aroundSendMethod(joinPoint)));
            verify(joinPoint, times(1)).proceed();
        }

        @Test
        @DisplayName("⚠️ 问得通时发送器抛的错不当成问不通再交一次")
        void senderFailureIsNotTakenForQueryFailure() throws Throwable {
            canAtAll(true);
            ProceedingJoinPoint joinPoint = sending(message(PLATFORM, PushTargetType.GROUP, "开播啦{at=all}"));
            IllegalStateException broken = new IllegalStateException("发送器坏了");
            when(joinPoint.proceed()).thenThrow(broken);

            assertSame(broken, assertThrows(IllegalStateException.class, () -> aspect.aroundSendMethod(joinPoint)));
            verify(joinPoint, times(1)).proceed();
        }

        /**
         * 上面几格量的是切面自己；这一格把它真织到发送器上，照推送处理器的写法逐条交，
         * 量的是现场那条路：异常会不会从 send 漏出去、后一条还交不交得到发送器
         */
        @Test
        @DisplayName("织进发送器后: 问不通时 send 不往外抛, 同一次推送的后一条照常交到发送器")
        void wovenSendKeepsTheRestOfThePush() {
            when(http.getGroupAtAllRemain(any(), any())).thenThrow(unreachable());
            NovaMessageSender target = mock(NovaMessageSender.class);
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.setProxyTargetClass(true);
            factory.addAspect(aspect);
            NovaMessageSender woven = factory.getProxy();

            List<Message> messages = Message.create(PLATFORM, PushTargetType.GROUP, GROUP, "{at=all}开播啦{next}第二条");
            assertDoesNotThrow(() -> messages.forEach(woven::send));

            ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
            verify(target, times(2)).send(sent.capture());
            assertEquals(List.of("{at=all}开播啦", "第二条"),
                    sent.getAllValues().stream().map(Message::getContent).toList());
        }
    }

    /**
     * 与现场同形的「连不上」：HTTP 客户端把连接失败包成这一种
     */
    private static ResourceAccessException unreachable() {
        return new ResourceAccessException(
                "I/O error on POST request for \"http://127.0.0.1:3000/get_group_at_all_remain\": null",
                new ConnectException());
    }

    @Nested
    @DisplayName("接线")
    class Wiring {
        @Test
        @DisplayName("切面拦的是核心的发送方法, 切点串一字不改")
        void pointcutStaysOnSend() throws Exception {
            Pointcut pointcut = BackupAtAllAspect.class
                    .getDeclaredMethod("sendMethod").getAnnotation(Pointcut.class);

            assertEquals("execution(* org.frostnova.nova.core.sender.NovaMessageSender.send(..))", pointcut.value());
        }

        /**
         * 开关键写在两处：一处是配置类的字段名，一处是这里的条件串。
         * 两处只改一处的下场是开关看着还在、拨过去没反应——而这件事没有任何现象
         */
        @Test
        @DisplayName("开关键与默认值不变, 没配过时按启用算")
        void toggleKeyAndDefaultAreFixed() {
            ConditionalOnProperty condition = BackupAtAllAspect.class.getAnnotation(ConditionalOnProperty.class);

            assertEquals(1, condition.name().length);
            assertEquals("novabot.adapter.onebot.extension.napcat.enable-backup-at-all", condition.name()[0]);
            assertEquals("true", condition.havingValue());
            assertTrue(condition.matchIfMissing());
        }
    }

    private static void runCallbacks(List<Runnable> callbacks) {
        callbacks.forEach(Runnable::run);
    }
}
