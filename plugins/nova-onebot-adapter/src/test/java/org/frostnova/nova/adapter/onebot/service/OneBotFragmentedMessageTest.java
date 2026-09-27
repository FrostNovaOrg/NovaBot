package org.frostnova.nova.adapter.onebot.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.core.event.remote.NovaRemoteMessageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * OneBot 上报被拆成多片发来时怎么拼
 *
 * <h2>这里守的是三件事</h2>
 * <ol>
 *   <li><b>对端一直发不收尾的分片，内存不会一直涨</b>——一条消息拼到多长都照收的话，
 *       发行包的堆顶是 512 MB、满了整个程序退出，所有推送一起停。
 *       要的是这条连接被断开、照常重连，重连后照常收消息</li>
 *   <li><b>上一条命令处理出错，不连累紧跟着到的下一条</b>——出错处理若去清那块
 *       正在拼下一条的缓冲，下一条的前半截就没了，里面的命令没有任何回应，
 *       日志里也只有一条看上去和它无关的解析错误</li>
 *   <li><b>超限断开那一下没断成，连接也得能自己缓过来</b>——丢超限消息后要断开连接，
 *       断开那一下要是抛了异常，连接其实还挂着：此后到的新消息若一概丢弃，
 *       界面上连接看着活着，消息却永远收不到。超限那条消息的最后一片到了就复位，
 *       下一条照常收</li>
 * </ol>
 */
@DisplayName("OneBot 分片消息")
class OneBotFragmentedMessageTest {
    private static final String SENDER_NAME = "测试机器人";

    /**
     * 等一件事发生的上限。断开之后要隔一秒才重连，给足余量让慢机器也稳
     */
    private static final long PATIENCE_SECONDS = 10;

    /**
     * 不收尾的分片推到多少个字符为止：适配器的上限是 8M 字符，推到它的两倍，
     * 没有上限的实现会一声不吭地全收下
     */
    private static final long ENDLESS_CHARS = 16L * 1024 * 1024;

    /**
     * 每一片的字符数
     */
    private static final int FRAGMENT_CHARS = 60_000;

    private FakeOneBotWebsocketServer websocket;

    private OneBotWebsocketService service;

    private ThreadPoolTaskExecutor executor;

    private ThreadPoolTaskScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.stop(SENDER_NAME);
        }
        if (websocket != null) {
            websocket.close();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("⚠️ 对端一直发不收尾的分片：这条连接被断开，随后照常重连、照常收消息")
    void endlessFragmentsDropTheConnectionAndReconnect() throws Exception {
        websocket = new FakeOneBotWebsocketServer();

        AtomicReference<FakeOneBotWebsocketServer.Frames> first = new AtomicReference<>();
        CountDownLatch firstConnected = new CountDownLatch(1);
        websocket.onConnect((index, frames) -> {
            if (index == 1) {
                first.set(frames);
                firstConnected.countDown();
                // 开了头就再不收尾：一条群消息的前半截，后面全是填充
                frames.fragment("{\"post_type\":\"message\",\"raw_message\":\"", true, false);
                String filler = "a".repeat(FRAGMENT_CHARS);
                while (frames.sentChars() < ENDLESS_CHARS && !frames.isClosed()) {
                    frames.fragment(filler, false, false);
                }
            } else {
                // 重连之后是一条正常的、分成两片的命令
                String command = groupMessage("菜单", "");
                int half = command.length() / 2;
                frames.fragment(command.substring(0, half), true, false);
                frames.fragment(command.substring(half), false, true);
            }
        });

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AtomicReference<NovaRemoteMessageEvent> received = new AtomicReference<>();
        CountDownLatch commandReceived = new CountDownLatch(1);
        doAnswer(invocation -> {
            received.set(invocation.getArgument(0));
            commandReceived.countDown();
            return null;
        }).when(publisher).publishEvent(any(NovaRemoteMessageEvent.class));

        startService(publisher);

        assertTrue(firstConnected.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "第一条连接该连上");
        FakeOneBotWebsocketServer.Frames endless = first.get();
        assertTrue(endless.awaitClosed(PATIENCE_SECONDS, TimeUnit.SECONDS),
                "对端推了 " + endless.sentChars() + " 个字符的不收尾分片，这条连接仍没被断开——缓冲还在跟着涨");
        assertTrue(endless.sentChars() < ENDLESS_CHARS,
                "推满 " + ENDLESS_CHARS + " 个字符才断，说明断开不是因为累计超了上限");

        assertTrue(commandReceived.await(PATIENCE_SECONDS, TimeUnit.SECONDS),
                "断开之后该照常重连并收到下一条命令，已接入 " + websocket.accepted() + " 条连接");
        assertEquals("菜单", received.get().getText(), "重连后收到的命令该是完整的");
        assertEquals(2, websocket.accepted(), "断开后该重连一次");
    }

    @Test
    @DisplayName("⚠️ 上一条命令处理出错时，紧跟着到的下一条分片长消息照样完整收到")
    void failureOfThePreviousMessageKeepsTheNextOneWhole() throws Exception {
        // 手摇的线程池：提交进来的活先排着，由这一格决定什么时候跑。
        // 线上的次序是「I/O 线程已经接上了下一条的前半截，线程池那边的上一条才出错」，
        // 这里一步一步摇出这个次序，而不是指望两条线程碰巧撞上
        Deque<Runnable> pending = new ArrayDeque<>();
        ThreadPoolTaskExecutor manual = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            pending.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        }).when(manual).submit(any(Runnable.class));

        // 第一条命令的处理出错（比如处理它的那个插件抛了异常），第二条照常。
        // 事件是 ApplicationEvent，走的是按它取类型的那个重载，按 Object 打桩打不中
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        doThrow(new IllegalStateException("处理命令时出错"))
                .doNothing()
                .when(publisher).publishEvent(any(NovaRemoteMessageEvent.class));

        OneBotSender sender = sender(0);
        WebSocketHandler handler = new OneBotWebsocketService(mock(ThreadPoolTaskScheduler.class), manual,
                quietProperties(), mock(OneBotHttpService.class), new OneBotConnectionState(), publisher)
                .handlerFor(sender);
        WebSocketSession session = mock(WebSocketSession.class);

        handler.handleMessage(session, new TextMessage(groupMessage("第一条", ""), true));

        // 下一条是一条长消息，拆成两片；前半截先到
        String next = groupMessage("菜单", "填充".repeat(20_000));
        int half = next.length() / 2;
        handler.handleMessage(session, new TextMessage(next.substring(0, half), false));

        // 这时线程池才跑到第一条，并且出错
        assertEquals(1, pending.size(), "此刻该只有第一条在排着");
        pending.poll().run();

        handler.handleMessage(session, new TextMessage(next.substring(half), true));
        assertEquals(1, pending.size(), "下一条收齐后该排进线程池");
        pending.poll().run();

        ArgumentCaptor<NovaRemoteMessageEvent> events = ArgumentCaptor.forClass(NovaRemoteMessageEvent.class);
        verify(publisher, times(2)).publishEvent(events.capture());
        assertEquals("菜单", events.getAllValues().get(1).getText(), "下一条该完整收到，里面的命令照常生效");
    }

    @Test
    @DisplayName("⚠️ 超限断开那一下抛了异常：那条消息最后一片之后到的新消息照常处理")
    void oversizedDisconnectFailureStillReceivesMessagesAfterItsLastFragment() throws Exception {
        Deque<Runnable> pending = new ArrayDeque<>();
        ThreadPoolTaskExecutor manual = manualExecutorCapturingInto(pending);

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        OneBotSender sender = sender(0);
        WebSocketHandler handler = new OneBotWebsocketService(mock(ThreadPoolTaskScheduler.class), manual,
                quietProperties(), mock(OneBotHttpService.class), new OneBotConnectionState(), publisher)
                .handlerFor(sender);

        WebSocketSession session = mock(WebSocketSession.class);
        // 断开那一下没断成：连接还挂着，此后还会有帧进来
        doThrow(new RuntimeException("断不开")).when(session).close(any(CloseStatus.class));

        // 一条消息拼到超过上限
        handler.handleMessage(session, new TextMessage("x".repeat(OneBotWebsocketService.MAX_MESSAGE_CHARS - 100), false));
        handler.handleMessage(session, new TextMessage("y".repeat(200), false));
        verify(session).close(CloseStatus.TOO_BIG_TO_PROCESS);

        // 超限那条消息剩下的分片照旧一个不收
        handler.handleMessage(session, new TextMessage("收尾之前的一片", false));
        assertEquals(0, pending.size(), "超限消息自己的分片一个都不该进线程池");

        // 它的最后一片到了：复位，但这一片本身仍属于被丢弃的那条
        handler.handleMessage(session, new TextMessage("z", true));
        assertEquals(0, pending.size(), "最后一片本身也是那条超限消息的，不该进线程池");

        // 下一条消息照常收——连接看着还活着的那会儿，消息不能永远丢下去
        handler.handleMessage(session, new TextMessage(groupMessage("菜单", ""), true));
        assertEquals(1, pending.size(), "下一条该照常进线程池");
        pending.poll().run();

        ArgumentCaptor<NovaRemoteMessageEvent> events = ArgumentCaptor.forClass(NovaRemoteMessageEvent.class);
        verify(publisher, times(1)).publishEvent(events.capture());
        assertEquals("菜单", events.getValue().getText(), "超限之后的新消息该完整收到，里面的命令照常生效");
    }

    @Test
    @DisplayName("断开那一下不抛时照旧：丢弃、断开，超限消息自己的分片一个不收")
    void oversizedDropStillClosesTheConnectionWhenCloseSucceeds() throws Exception {
        Deque<Runnable> pending = new ArrayDeque<>();
        ThreadPoolTaskExecutor manual = manualExecutorCapturingInto(pending);

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        OneBotSender sender = sender(0);
        WebSocketHandler handler = new OneBotWebsocketService(mock(ThreadPoolTaskScheduler.class), manual,
                quietProperties(), mock(OneBotHttpService.class), new OneBotConnectionState(), publisher)
                .handlerFor(sender);

        WebSocketSession session = mock(WebSocketSession.class);

        handler.handleMessage(session, new TextMessage("x".repeat(OneBotWebsocketService.MAX_MESSAGE_CHARS - 100), false));
        handler.handleMessage(session, new TextMessage("y".repeat(200), false));
        verify(session).close(CloseStatus.TOO_BIG_TO_PROCESS);

        handler.handleMessage(session, new TextMessage("收尾之前的一片", false));
        handler.handleMessage(session, new TextMessage("z", true));
        assertEquals(0, pending.size(), "被丢弃那条消息的分片（含最后一片）一个都不该进线程池");
        verify(session, times(1)).close(any(CloseStatus.class));
    }

    /**
     * 手摇的线程池：提交进来的活先排着，由这一格决定什么时候跑
     */
    private static ThreadPoolTaskExecutor manualExecutorCapturingInto(Deque<Runnable> pending) {
        ThreadPoolTaskExecutor manual = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            pending.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        }).when(manual).submit(any(Runnable.class));
        return manual;
    }

    private void startService(ApplicationEventPublisher publisher) {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.initialize();

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();

        service = new OneBotWebsocketService(scheduler, executor, quietProperties(),
                mock(OneBotHttpService.class), new OneBotConnectionState(), publisher);
        service.start(sender(websocket.port()));
    }

    /**
     * 定期检测只会往连接状态里写噪声，这里量的是收消息
     */
    private OneBotAdapterPluginProperties quietProperties() {
        OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
        properties.getDetect().setEnableHttpDetect(false);
        properties.getDetect().setEnableWebsocketDetect(false);
        return properties;
    }

    private OneBotSender sender(int websocketPort) {
        OneBotSender sender = new OneBotSender();
        sender.setName(SENDER_NAME);
        sender.setWebsocket(true);
        sender.setOneBotAddress("127.0.0.1");
        sender.setOneBotWebsocketPort(websocketPort);
        sender.setOneBotWebsocketToken("ws-token");
        return sender;
    }

    /**
     * 一条群聊上报，可带一段不影响命令的填充把它撑长
     */
    private static String groupMessage(String rawMessage, String padding) {
        JSONObject json = new JSONObject();
        json.put("post_type", "message");
        json.put("message_type", "group");
        json.put("group_id", 30003L);
        json.put("user_id", 2000000002L);
        json.put("self_id", 1000000001L);
        json.put("raw_message", rawMessage);
        json.put("message", rawMessage);
        json.put("padding", padding);
        return json.toJSONString();
    }
}
