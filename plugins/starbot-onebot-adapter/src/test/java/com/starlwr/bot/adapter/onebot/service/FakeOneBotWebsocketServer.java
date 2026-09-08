package com.starlwr.bot.adapter.onebot.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个只会握手的假 OneBot Websocket 服务端
 *
 * <h2>为什么要真起一个服务端</h2>
 * 这把尺要量的是「换了地址之后，旧地址上那条连接还在不在」。桩答不了这个问题——
 * 它顶多能记下调用方喊过几次断开，而<b>真正会出事的形态恰恰是没人喊断开</b>：
 * 重连循环仍在跑、握手已经发出去还没回来、检测任务还挂着。这些都只在真的套接字上看得见。
 *
 * <h2>它做的事</h2>
 * 收下握手、回一个 101，随后推一条 OneBot 的 lifecycle/connect 上报（客户端据此认为 Token 通过），
 * 然后一直读到对方断开为止。{@link #open()} 因此答的是<b>此刻还连着几条</b>，
 * 而 {@link #accepted()} 答的是从头到尾一共接过几条——两个数分开才看得出「断旧连新」有没有发生。
 */
final class FakeOneBotWebsocketServer implements AutoCloseable {
    /**
     * RFC 6455 规定的握手魔术串
     */
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /**
     * 连上之后推的那一条：客户端认它为「Token 校验成功」
     */
    private static final String LIFECYCLE_CONNECT =
            "{\"post_type\":\"meta_event\",\"meta_event_type\":\"lifecycle\",\"sub_type\":\"connect\"}";

    private final ServerSocket server;

    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());

    private final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger accepted = new AtomicInteger();

    private final AtomicInteger open = new AtomicInteger();

    private volatile boolean closed;

    FakeOneBotWebsocketServer() throws IOException {
        server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "fake-onebot-ws");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    /**
     * 从头到尾一共接过几条连接
     */
    int accepted() {
        return accepted.get();
    }

    /**
     * 此刻还连着几条
     */
    int open() {
        return open.get();
    }

    /**
     * 各次握手带来的 Authorization 请求头，按接入顺序
     */
    List<String> authorizations() {
        return List.copyOf(authorizations);
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                sockets.add(socket);
                Thread worker = new Thread(() -> serve(socket), "fake-onebot-ws-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        boolean counted = false;
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            Handshake handshake = readHandshake(in);
            if (handshake.key == null) {
                return;
            }

            out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept(handshake.key) + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            authorizations.add(handshake.authorization);
            accepted.incrementAndGet();
            open.incrementAndGet();
            counted = true;

            sendText(out, LIFECYCLE_CONNECT);
            readUntilClosed(in, out);
        } catch (IOException e) {
            // 断开就是这条连接的正常收场
        } finally {
            if (counted) {
                open.decrementAndGet();
            }
            closeQuietly(socket);
        }
    }

    /**
     * 一直读到对方断开为止，收到关闭帧就回一个
     * <p>
     * 🔴 <b>必须回。</b>客户端那一侧的 {@code close()} 会等对端把关闭帧回过来，
     * 等不到就一直挂到它自己的超时。而调用 {@code close()} 的正是「断旧连新」那一步，
     * 它是带锁的——不回这一帧，量出来的不是连接换没换，是这把尺自己卡了多久。
     */
    private void readUntilClosed(InputStream in, OutputStream out) throws IOException {
        while (true) {
            int first = in.read();
            if (first < 0) {
                return;
            }

            int second = in.read();
            if (second < 0) {
                return;
            }

            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7f;
            if (length == 126) {
                length = (readByte(in) << 8) | readByte(in);
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | readByte(in);
                }
            }

            if (masked) {
                for (int i = 0; i < 4; i++) {
                    readByte(in);
                }
            }
            for (long i = 0; i < length; i++) {
                readByte(in);
            }

            if ((first & 0x0f) == 0x8) {
                out.write(new byte[]{(byte) 0x88, 0x00});
                out.flush();
                return;
            }
        }
    }

    private int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new IOException("帧还没读完对方就断了");
        }
        return value;
    }

    /**
     * 握手请求里我们关心的两项
     */
    private static final class Handshake {
        private String key;

        private String authorization;
    }

    private Handshake readHandshake(InputStream in) throws IOException {
        Handshake handshake = new Handshake();

        StringBuilder line = new StringBuilder();
        int value;
        while ((value = in.read()) >= 0) {
            if (value == '\r') {
                continue;
            }
            if (value != '\n') {
                line.append((char) value);
                continue;
            }

            String header = line.toString();
            line.setLength(0);
            if (header.isEmpty()) {
                return handshake;
            }

            int colon = header.indexOf(':');
            if (colon < 0) {
                continue;
            }

            String name = header.substring(0, colon).strip().toLowerCase();
            String content = header.substring(colon + 1).strip();
            if ("sec-websocket-key".equals(name)) {
                handshake.key = content;
            } else if ("authorization".equals(name)) {
                handshake.authorization = content;
            }
        }

        return handshake;
    }

    private String accept(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("这台机器上没有 SHA-1", e);
        }
    }

    /**
     * 发一条不分片、不掩码的文本帧（服务端发出的帧按 RFC 6455 本就不该掩码）
     */
    private void sendText(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        out.write(0x81);
        if (payload.length < 126) {
            out.write(payload.length);
        } else {
            out.write(126);
            out.write((payload.length >> 8) & 0xff);
            out.write(payload.length & 0xff);
        }
        out.write(payload);
        out.flush();
    }

    private void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // 关不上也没什么可做的
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (sockets) {
            sockets.forEach(this::closeQuietly);
        }
        try {
            server.close();
        } catch (IOException e) {
            // 同上
        }
    }
}
