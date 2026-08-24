package com.starlwr.bot.bilibili.protocol;

import com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.读;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.AUTH;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.GRACE;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.PING;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.判据等待;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.标准时限;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.心跳线程卡在别人的监视器上;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.先验尺_发送线程确实卡在写里;
import static com.starlwr.bot.bilibili.protocol.NovaEvent慢消费者台架.读数;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一个慢消费者不许钉住全局共享的心跳线程
 * <p>
 * 三条判据量的是「别人还好着没有」，不是「慢的那个还好没好」——慢客户端自己发不出去是它自己的事。
 * <p>
 * 🔴 台架与探针在 {@link NovaEvent慢消费者台架}，本类<b>只</b>负责判。
 * 探针同时被 {@link NovaEvent判据探针独立性Test} 用着，共一份。
 */
@DisplayName("慢消费者不许钉住共享心跳线程")
class NovaEventSlowConsumerTest {

    @TempDir
    Path dir;

    private NovaEvent慢消费者台架 台;

    private NovaEvent慢消费者台架 起(boolean 慢客户端读) throws IOException {
        台 = new NovaEvent慢消费者台架(dir, 慢客户端读, 标准时限);
        return 台;
    }

    @AfterEach
    void 收摊() {
        if (台 != null) {
            台.close();
            台 = null;
        }
    }

    // ══════════════════════════ 夹具 ══════════════════════════

    /**
     * 真 socket 会话：{@code sendMessage} 直接写进一对真 socket。
     * <p>
     * 对端默认<b>永不 read</b>；灌满之后，端点这一侧的每一次 write 都真的卡在内核里。
     */
    static final class SocketSession implements WebSocketSession {
        private final String id;
        private final ServerSocket listener;
        private final Socket outbound;
        private final Socket peer;
        private final OutputStream out;

        /** 正卡在 write 里的那一刻（nanoTime）。0 表示此刻不在写 */
        volatile long 写入开始于;

        /** 最近一次 write 花了多少毫秒 */
        volatile long 上次写阻塞毫秒;

        private final AtomicLong 已写字节 = new AtomicLong();

        private volatile long 填充线程卡在;

        volatile CloseStatus closedWith;

        SocketSession(String id, int sndBuf, int rcvBuf, boolean peerReads) throws IOException {
            this.id = id;
            listener = new ServerSocket();
            // 🔴 接收缓冲要在 bind **之前**设在 ServerSocket 上，accept 出来的那条才继承得到
            listener.setReceiveBufferSize(rcvBuf);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
            outbound = new Socket();
            outbound.setSendBufferSize(sndBuf);
            outbound.connect(listener.getLocalSocketAddress(), 2_000);
            peer = listener.accept();
            out = outbound.getOutputStream();
            if (peerReads) {
                Thread reader = new Thread(() -> {
                    byte[] buf = new byte[8192];
                    try (InputStream in = peer.getInputStream()) {
                        while (in.read(buf) >= 0) {
                            // 正常读——阴性对照走这条
                        }
                    } catch (IOException ignored) {
                        // 关连接就是这里退出
                    }
                }, "nova-test-drainer-" + id);
                reader.setDaemon(true);
                reader.start();
            }
        }

        /**
         * 把管道灌到「下一次写必阻塞」。
         * <p>
         * 🔴 灌满这一步不是在模拟阻塞，它是在<b>造成</b>阻塞：一个真实的慢客户端落后之后，
         * 内核缓冲与对端窗口就是这个状态。灌不满就没有背压，此时任何「绿」都不算数。
         *
         * @return 灌进去的字节数
         */
        long 灌满() throws Exception {
            byte[] chunk = new byte[4096];
            Thread filler = new Thread(() -> {
                try {
                    while (true) {
                        填充线程卡在 = System.nanoTime();
                        out.write(chunk);
                        填充线程卡在 = 0;
                        已写字节.addAndGet(chunk.length);
                        if (已写字节.get() > 32L * 1024 * 1024) {
                            return;
                        }
                    }
                } catch (IOException ignored) {
                    填充线程卡在 = 0;
                }
            }, "nova-test-filler-" + id);
            filler.setDaemon(true);
            filler.start();

            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                long 卡 = 填充线程卡在;
                if (卡 != 0 && (System.nanoTime() - 卡) / 1_000_000 > 300) {
                    return 已写字节.get();
                }
                Thread.sleep(5);
            }
            throw new IllegalStateException(
                    "10 秒内灌不满这条 socket（已写 " + 已写字节.get() + " 字节）——"
                            + "没有真背压就没有阳性对照，**此时的绿和修好了的绿长得一样**");
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            byte[] bytes = ((TextMessage) message).getPayload().getBytes(StandardCharsets.UTF_8);
            写入开始于 = System.nanoTime();
            try {
                out.write(bytes);
                out.flush();
                已写字节.addAndGet(bytes.length);
            } finally {
                上次写阻塞毫秒 = (System.nanoTime() - 写入开始于) / 1_000_000;
                写入开始于 = 0;
            }
        }

        /** 此刻卡在写里多少毫秒；不在写时为 -1 */
        long 此刻卡了多久() {
            long t = 写入开始于;
            return t == 0 ? -1 : (System.nanoTime() - t) / 1_000_000;
        }

        void 关掉() {
            closeQuietly(peer);
            closeQuietly(outbound);
            closeQuietly(listener);
        }

        private static void closeQuietly(java.io.Closeable c) {
            try {
                c.close();
            } catch (IOException ignored) {
                // 收摊阶段，关不上也没什么可做的
            }
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public void close() {
            close(CloseStatus.NORMAL);
        }

        @Override
        public void close(CloseStatus status) {
            closedWith = status;
            关掉();
        }

        @Override
        public boolean isOpen() {
            return closedWith == null;
        }

        @Override
        public URI getUri() {
            return URI.create("ws://127.0.0.1/nova/events");
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return new HttpHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return new HashMap<>();
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return (InetSocketAddress) outbound.getLocalSocketAddress();
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return (InetSocketAddress) outbound.getRemoteSocketAddress();
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 0;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 0;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }
    }
    // ══════════════════════════ 三条判据 ══════════════════════════

    @Test
    @DisplayName("判据 1：慢客户端在场时，健康客户端仍在心跳周期内收到 ping")
    void 判据1_健康客户端仍收得到ping() throws Exception {
        起(false).支起慢客户端();

        读 r = 台.探1_健康客户端收得到ping(台.连并认证("healthy"));
        读数("判据1-ping", Map.of("心跳间隔毫秒", PING, "实际收到 ping 耗时毫秒", r.耗时毫秒(),
                "心跳线程卡住的栈摘录", String.valueOf(心跳线程卡在别人的监视器上()),
                "慢客户端此刻卡了毫秒", 台.慢客户端.此刻卡了多久()));

        assertTrue(r.绿(), "慢客户端在场时，健康客户端在 " + 判据等待 + " 毫秒内一个 ping 都没收到——"
                + "心跳线程被别人的监视器钉住了（心跳周期 " + PING + " 毫秒）");
    }

    @Test
    @DisplayName("判据 2：慢客户端在场时，未认证连接仍在时限内被关")
    void 判据2_认证闸仍然关得掉() throws Exception {
        起(false).支起慢客户端();

        读 r = 台.探2_认证闸关得掉(台.连("silent"));
        读数("判据2-认证闸", Map.of("认证闸时限毫秒", AUTH, "实际关闭耗时毫秒", r.耗时毫秒(),
                "心跳线程卡住的栈摘录", String.valueOf(心跳线程卡在别人的监视器上())));

        assertTrue(r.绿(), "慢客户端在场时，未认证连接在 " + 判据等待 + " 毫秒内没有被关——"
                + "认证闸（" + AUTH + " 毫秒）派在心跳线程上，而它被钉住了");
    }

    @Test
    @DisplayName("判据 3：慢客户端在场时，其它连接仍能转进实时流")
    void 判据3_他连仍能转进实时流() throws Exception {
        起(false).支起慢客户端();

        读 r = 台.探3_他连转进实时流(台.连并认证("other"));
        读数("判据3-转实时流", Map.of("回补窗口毫秒", GRACE, "实际转实时流耗时毫秒", r.耗时毫秒(),
                "心跳线程卡住的栈摘录", String.valueOf(心跳线程卡在别人的监视器上())));

        assertTrue(r.绿(), "慢客户端在场时，其它连接在 " + 判据等待 + " 毫秒内没能转进实时流——"
                + "goLive 派在心跳线程上，而它被钉住了");
    }

    // ══════════════════════════ 阴性对照 ══════════════════════════

    @Test
    @DisplayName("阴性对照：同一夹具、慢客户端正常读 —— 三条判据必须全绿")
    void 阴性对照_客户端正常读时三条全绿() throws Exception {
        起(true);
        台.endpoint.afterConnectionEstablished(台.慢客户端);
        台.认证(台.慢客户端, 台.tokens.issue("正常读的客户端"));

        NovaEventEndpointTest.FakeSession 健康 = 台.连并认证("healthy");
        NovaEventEndpointTest.FakeSession 沉默 = 台.连("silent");
        NovaEventEndpointTest.FakeSession 他连 = 台.连并认证("other");

        读 一 = 台.探1_健康客户端收得到ping(健康);
        读 三 = 台.探3_他连转进实时流(他连);
        读 二 = 台.探2_认证闸关得掉(沉默);

        读数("阴性对照", Map.of("慢客户端是否正常读", true, "判据1", 一.绿(),
                "判据2", 二.绿(), "判据3", 三.绿()));

        assertTrue(一.绿(), "阴性对照：客户端正常读时判据 1 也不绿，说明红的是夹具本身把服务端跑垮了");
        assertTrue(二.绿(), "阴性对照：客户端正常读时判据 2 也不绿");
        assertTrue(三.绿(), "阴性对照：客户端正常读时判据 3 也不绿");
    }

    @Test
    @DisplayName("先验尺自证：管道没灌满时不许当成「已复现」")
    void 先验尺_没灌满时不算复现() throws Exception {
        起(false);
        台.endpoint.afterConnectionEstablished(台.慢客户端);
        台.认证(台.慢客户端, 台.tokens.issue("没灌满"));

        // 🔴 没灌满就不该抓到「卡在写里」那个读数。抓到了说明先验尺认错了东西，
        //    那它在真复现时说的「亮」也不算数。
        Thread.sleep(200);
        assertNull(先验尺_发送线程确实卡在写里(),
                "管道没灌满时先验尺就说「卡住了」——这把尺认错了东西，它说的亮不算数");
    }
}
