package org.frostnova.nova.core.api;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.protocol.NovaEventEndpoint;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试里的一个事件流客户端：一条替身连接，记下收到的帧与关闭
 */
public final class EventStreamProbe {
    private final WebSocketSession session = mock(WebSocketSession.class);

    private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();

    private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

    public EventStreamProbe(String id) throws Exception {
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenAnswer(invocation -> !closed.isDone());
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 50123));
        doAnswer(invocation -> {
            frames.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any());
        doAnswer(invocation -> {
            closed.complete(invocation.getArgument(0));
            return null;
        }).when(session).close(any(CloseStatus.class));
    }

    /** 连上并在首帧出示 {@code token} */
    public EventStreamProbe connect(NovaEventEndpoint endpoint, String token) throws Exception {
        endpoint.afterConnectionEstablished(session);
        endpoint.handleMessage(session, new TextMessage(JSONObject.of("kind", "auth", "token", token).toJSONString()));
        return this;
    }

    /** 下一帧，5 秒内没有为 null */
    public JSONObject next() throws InterruptedException {
        String frame = frames.poll(5, TimeUnit.SECONDS);
        return frame == null ? null : JSONObject.parseObject(frame);
    }

    /** 等它被关掉，5 秒内没关为 null */
    public CloseStatus awaitClose() throws Exception {
        try {
            return closed.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException notYet) {
            return null;
        }
    }

    public boolean isOpen() {
        return !closed.isDone();
    }

    public WebSocketSession session() {
        return session;
    }
}
