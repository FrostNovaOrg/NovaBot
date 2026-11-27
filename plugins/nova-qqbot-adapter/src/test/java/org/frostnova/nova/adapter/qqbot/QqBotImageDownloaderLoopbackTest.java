package org.frostnova.nova.adapter.qqbot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.frostnova.nova.core.util.MessageImageAddressPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 正式用的客户端对着本机起的 HTTP/1.1 服务真连一次
 * <p>
 * 地址规则只放行这台服务所在的 127.0.0.1；环境不许用本机回环端口时跳过。
 */
@DisplayName("下载图片：真客户端连本机服务")
class QqBotImageDownloaderLoopbackTest {
    private static final String SKIPPED = "当前环境不许用本机回环端口，跳过；不受限的环境里照常跑";
    private static final byte[] NOTE = "<html><body>Found</body></html>".getBytes(StandardCharsets.UTF_8);

    private final byte[] image = new byte[200 * 1024];
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private HttpServer server;
    private String base;
    private QqBotImageDownloader downloader;

    @BeforeEach
    void start() throws IOException {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        try {
            server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
            try (Socket probe = new Socket()) {
                probe.connect(server.getAddress(), 2_000);
            }
        } catch (IOException | SecurityException e) {
            stop();
            Assumptions.assumeFalse(permissionDenied(e), SKIPPED);
            throw e;
        }
        int port = server.getAddress().getPort();
        base = "http://127.0.0.1:" + port;
        new Random(42).nextBytes(image);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
            switch (path) {
                case "/direct.png", "/final.png" -> reply(exchange, 200, null, image);
                case "/moved.png" -> reply(exchange, 302, base + "/final.png", NOTE);
                // 127.0.0.2 也是本机，但没放行：不该连过去
                case "/away.png" -> reply(exchange, 302, "http://127.0.0.2:" + port + "/final.png", NOTE);
                default -> reply(exchange, 404, null, new byte[0]);
            }
        });
        server.start();
        downloader = new QqBotImageDownloader(new MessageImageAddressPolicy(List.of("127.0.0.1/32"), host -> {
            throw new UnknownHostException(host);
        }));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    @DisplayName("直接回图片：收到的字节和服务端发的一样")
    void downloadsTheImage() throws Exception {
        assertArrayEquals(image, downloader.download(base + "/direct.png"));
        assertEquals(Map.of("/direct.png", 1), counts());
    }

    @Test
    @DisplayName("302 带一段正文跳到同一服务的另一路径：取到最终的图片，两条路径各请求一次")
    void followsARedirectCarryingABody() throws Exception {
        assertArrayEquals(image, downloader.download(base + "/moved.png"));
        assertEquals(Map.of("/moved.png", 1, "/final.png", 1), counts());
    }

    @Test
    @DisplayName("302 跳到没放行的本机地址：不跟过去，服务端只收到一次请求")
    void refusesARedirectToAnAddressThatIsNotAllowed() {
        assertThrows(QqBotImageDownloader.Blocked.class, () -> downloader.download(base + "/away.png"));
        assertEquals(Map.of("/away.png", 1), counts());
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        hits.forEach((path, count) -> counts.put(path, count.get()));
        return counts;
    }

    private static void reply(HttpExchange exchange, int status, String location, byte[] body) throws IOException {
        try {
            exchange.getRequestBody().readAllBytes();
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } finally {
            exchange.close();
        }
    }

    /** 只有绑或连报了权限类错误，才当作环境不许 */
    private static boolean permissionDenied(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SecurityException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String text = message.toLowerCase(Locale.ROOT);
                if (text.contains("operation not permitted") || text.contains("permission denied")) {
                    return true;
                }
            }
        }
        return false;
    }
}
