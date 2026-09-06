package com.starlwr.bot.core.safemode;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全模式 HTTP 闸门：令牌不对一律 403，POST /save 只在令牌正确时落盘
 * <p>
 * 正文本体那一层（校验、备份、写盘）已有 {@code SafeModeServerSaveBodyTest} 看着，
 * 这里的射程是它外面那一圈：{@code handle} 的令牌闸门与 /save 路由。
 * 把 {@code authorized} 改成恒 true、或把 /save 分支改回直接回页面，此前全仓没有一把尺会红。
 * <p>
 * 不开端口：{@code HttpExchange} 是抽象类，同包写桩直接调 {@code handle}；
 * 令牌在构造时随机生成，经包内读取口 {@code token()} 取真值来对照。
 */
@DisplayName("安全模式HTTP闸门")
class SafeModeServerGateTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("无令牌的 GET / 被拒：403，正文写明访问令牌无效")
    void getWithoutTokenIsRejected() throws IOException {
        SafeModeServer server = newServer();
        StubExchange exchange = StubExchange.get("/");

        server.handle(exchange);

        assertEquals(403, exchange.status(), "不带令牌应被拒以 403");
        assertTrue(exchange.body().contains("访问令牌无效"), "拒因应写明访问令牌无效");
    }

    @Test
    @DisplayName("错一位的令牌被拒 403；对令牌回编辑页")
    void wrongTokenRejectedAndRightTokenServesEditor() throws IOException {
        SafeModeServer server = newServer();

        StubExchange wrong = StubExchange.get("/?token=" + flipFirstChar(server.token()));
        server.handle(wrong);
        assertEquals(403, wrong.status(), "错一位的令牌同样应被拒以 403");

        StubExchange right = StubExchange.get("/?token=" + server.token());
        server.handle(right);
        assertEquals(200, right.status(), "对令牌应放行");
        assertTrue(right.contentType().startsWith("text/html"), "编辑页应以 HTML 应答");
        assertTrue(right.body().contains("action=\"/save?token="), "表单应把令牌带回 /save");
    }

    @Test
    @DisplayName("POST /save 带对令牌：本体写入新内容，备份目录多一份")
    void postSaveWithTokenWritesConfigAndLeavesBackup() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, "seed: 1\n", StandardCharsets.UTF_8);
        SafeModeServer server = new SafeModeServer(config, "测试用的启动失败原因");

        StubExchange exchange = new StubExchange("POST", URI.create("/save?token=" + server.token()), "ok: true\n");
        server.handle(exchange);

        assertEquals(200, exchange.status(), "保存应答应为 200");
        assertEquals("ok: true\n", Files.readString(config, StandardCharsets.UTF_8),
                "经 HTTP 层进来的保存就应真的写进本体");
        assertEquals(1, backupCount(config), "保存一次应多出一份备份");
    }

    @Test
    @DisplayName("POST /save 无令牌 403 且盘上分毫不动；GET /save 回页面不写盘")
    void saveWithoutTokenNeverTouchesDisk() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, "seed: 1\n", StandardCharsets.UTF_8);
        SafeModeServer server = new SafeModeServer(config, "测试用的启动失败原因");
        String before = sha256(config);

        StubExchange post = new StubExchange("POST", URI.create("/save"), "ok: true\n");
        server.handle(post);
        assertEquals(403, post.status(), "无令牌的保存请求应被拒以 403");
        assertEquals(before, sha256(config), "被拒的保存不许改动配置本体");
        assertEquals(0, backupCount(config), "被拒的保存不许产生备份");

        StubExchange get = StubExchange.get("/save?token=" + server.token());
        server.handle(get);
        assertEquals(200, get.status(), "GET 到 /save 应回页面而非保存");
        assertTrue(get.body().contains("安全模式"), "应答应是编辑页");
        assertFalse(get.body().contains("已保存"), "没走保存不许出现已保存提示");
        assertEquals(before, sha256(config), "GET 不许写盘");
        assertEquals(0, backupCount(config), "GET 不许产生备份");
    }

    @Test
    @DisplayName("端口认带引号的数字：\"8080\" 也接管 8080；裸数字照旧；非数字仍回默认")
    void resolvePortReadsQuotedNumber() throws IOException {
        Path config = dir.resolve("application.yml");

        List<String> unresolved = new ArrayList<>();
        Files.writeString(config, "server:\n  port: \"8080\"\n", StandardCharsets.UTF_8);
        tally(unresolved, 8080, newServer().resolvePort(), "带引号的 8080 应被认出");
        Files.writeString(config, "server:\n  port: 8080\n", StandardCharsets.UTF_8);
        tally(unresolved, 8080, newServer().resolvePort(), "裸数字 8080 应照旧被认出");
        Files.writeString(config, "server:\n  port: \"abc\"\n", StandardCharsets.UTF_8);
        tally(unresolved, 7827, newServer().resolvePort(), "非数字串应回默认端口 7827");

        assertTrue(unresolved.isEmpty(),
                () -> "端口三问中 " + unresolved.size() + " 问未销: " + String.join("; ", unresolved));
    }

    @Test
    @DisplayName("端口越界夹默认：裸 99999／0／65536 回 7827，界上沿 65535 与界内 8080 照用")
    void resolvePortClampsBareNumberOutOfRange() throws IOException {
        Path config = dir.resolve("application.yml");

        List<String> unresolved = new ArrayList<>();
        Files.writeString(config, "server:\n  port: 99999\n", StandardCharsets.UTF_8);
        tally(unresolved, 7827, newServer().resolvePort(), "裸数字 99999 越界应夹回默认端口 7827");
        Files.writeString(config, "server:\n  port: 0\n", StandardCharsets.UTF_8);
        tally(unresolved, 7827, newServer().resolvePort(), "裸数字 0 越界应夹回默认端口 7827");
        Files.writeString(config, "server:\n  port: 8080\n", StandardCharsets.UTF_8);
        tally(unresolved, 8080, newServer().resolvePort(), "阳性对照: 界内 8080 照用");
        Files.writeString(config, "server:\n  port: 65535\n", StandardCharsets.UTF_8);
        tally(unresolved, 65535, newServer().resolvePort(), "界上沿 65535 应照用不夹");
        Files.writeString(config, "server:\n  port: 65536\n", StandardCharsets.UTF_8);
        tally(unresolved, 7827, newServer().resolvePort(), "裸数字 65536 越界一格应夹回默认端口 7827");

        assertTrue(unresolved.isEmpty(),
                () -> "端口越界五问中 " + unresolved.size() + " 问未销: " + String.join("; ", unresolved));
    }

    private SafeModeServer newServer() {
        return new SafeModeServer(dir.resolve("application.yml"), "测试用的启动失败原因");
    }

    /**
     * 逐问各自捕获、末尾汇总，一问红不许短路其余问
     */
    private static void tally(List<String> unresolved, int expected, int actual, String question) {
        try {
            assertEquals(expected, actual, question);
        } catch (AssertionError e) {
            unresolved.add(e.getMessage());
        }
    }

    /**
     * 把令牌首字符换成另一个字符：与真令牌恰差一位，长度不变
     */
    private static String flipFirstChar(String token) {
        char replacement = token.charAt(0) == '0' ? '1' : '0';
        return replacement + token.substring(1);
    }

    /**
     * 只数备份这件事发生了没有，份数裁剪与命名形状是备份组件那组判据的事，不在此重复
     */
    private static int backupCount(Path config) throws IOException {
        String prefix = config.getFileName() + ".";
        try (Stream<Path> files = Files.list(config.getParent())) {
            List<String> names = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix) && name.endsWith(".bak"))
                    .toList();
            return names.size();
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (Exception e) {
            throw new IOException("计算文件摘要失败", e);
        }
    }

    /**
     * 不开端口的请求桩：预置方法／URI／请求体，捕获状态码、Content-Type 与响应体。
     * 闸门路径用不到的成员（请求头、上下文）给最省事且会响的实现，不模拟连接语义。
     */
    private static final class StubExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final byte[] requestBody;
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private int status = -1;

        static StubExchange get(String uri) {
            return new StubExchange("GET", URI.create(uri), null);
        }

        StubExchange(String method, URI uri, String requestBody) {
            this.method = method;
            this.uri = uri;
            this.requestBody = requestBody == null ? new byte[0] : requestBody.getBytes(StandardCharsets.UTF_8);
        }

        int status() {
            return status;
        }

        String contentType() {
            return responseHeaders.getFirst("Content-Type");
        }

        String body() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }

        @Override
        public Headers getRequestHeaders() {
            return new Headers();
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public HttpContext getHttpContext() {
            throw new UnsupportedOperationException("测试桩不提供上下文");
        }

        @Override
        public HttpPrincipal getPrincipal() {
            throw new UnsupportedOperationException("测试桩不经 SSL, 无客户端主体");
        }

        @Override
        public void setAttribute(String name, Object value) {
            // 闸门路径用不到
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setStreams(InputStream in, OutputStream out) {
            // 闸门路径用不到
        }

        @Override
        public String getProtocol() {
            throw new UnsupportedOperationException("测试桩不走真实协议");
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            throw new UnsupportedOperationException("测试桩不监听地址");
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            throw new UnsupportedOperationException("测试桩不监听地址");
        }

        @Override
        public void close() {
            // 应答全在内存里，无需连接清理
        }

        @Override
        public InputStream getRequestBody() {
            return new ByteArrayInputStream(requestBody);
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            this.status = rCode;
        }

        // CPU 版 17u 把读取应答码定为抽象方法（本机 17.0.20 已含），桩照实回报捕获值；应答长度无此方法，不入桩
        @Override
        public int getResponseCode() {
            return status;
        }
    }
}
