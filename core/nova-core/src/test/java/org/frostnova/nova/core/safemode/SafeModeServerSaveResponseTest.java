package org.frostnova.nova.core.safemode;

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
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全模式点保存后回给浏览器的那一页
 * <p>
 * 两个使用者故障钉在这里：保存应答要在当前页面里真的显示出来（页面脚本把应答写进
 * document），与超过上限的请求体整段拒收（不截成前半段照存）。正文本体那层
 * （校验、备份、写盘）归 {@code SafeModeServerSaveBodyTest}，令牌闸门归 {@code SafeModeServerGateTest}。
 * <p>
 * 不开端口：与闸门那组同款的请求桩直接调 {@code handle}；脚本那一格由 node 夹具
 * 替浏览器真执行页面里的脚本，量行为不量字面。
 */
@DisplayName("安全模式保存应答")
class SafeModeServerSaveResponseTest {

    @TempDir
    Path dir;

    /**
     * 使用者故障：点保存后整页变白。页面脚本曾写成
     * {@code document.open()||document.write(t)||document.close()}——document.open()
     * 返回文档对象（真值），{@code ||} 链到它就短路，应答一个字也没写进页面，
     * 保存成没成、YAML 错在哪都看不到。
     * 这里把服务端真出的编辑页与保存应答（成功与校验失败各一份）喂给 node 夹具，
     * 替浏览器执行编辑页里那段脚本：应答全文要写进 document，且清页、写入、收尾按序走完。
     */
    @Test
    @DisplayName("点保存后：应答整页写进当前页面，open、write、close 按序走完")
    void saveResponseIsWrittenIntoThePage() throws IOException, InterruptedException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, "seed: 1\n", StandardCharsets.UTF_8);
        SafeModeServer server = new SafeModeServer(config, "测试用的启动失败原因");

        StubExchange editor = StubExchange.get("/?token=" + server.token());
        server.handle(editor);

        StubExchange saved = new StubExchange("POST", URI.create("/save?token=" + server.token()), "ok: true\n");
        server.handle(saved);
        assertTrue(saved.body().contains("已保存"), "喂给夹具的应答应是保存成功那一页");
        runFixture(editor.body(), saved.body(), "保存成功应答");

        StubExchange refused = new StubExchange("POST", URI.create("/save?token=" + server.token()), "a: [unclosed");
        server.handle(refused);
        assertTrue(refused.body().contains("解析失败"), "喂给夹具的应答应是校验失败那一页");
        runFixture(editor.body(), refused.body(), "校验失败应答");
    }

    /**
     * 使用者故障：超过 1 MiB 的配置被截断后照存。请求体原先只读到上限就停，
     * 截下来的前半段碰巧还是合法 YAML 时就落了盘，尾部的设置悄悄丢掉。
     * 这具正文特意让恰好上限的那前 1 MiB 本身也是合法 YAML——截断照存的旧毛病在它身上会真发生。
     */
    @Test
    @DisplayName("请求体比上限多一字节：页面说明太大、没有保存，原配置逐字节不动")
    void oversizeBodyIsRefusedWithoutTouchingConfig() throws IOException {
        Path config = dir.resolve("application.yml");
        byte[] original = "seed: 1\n".getBytes(StandardCharsets.UTF_8);
        Files.write(config, original);
        SafeModeServer server = new SafeModeServer(config, "测试用的启动失败原因");

        byte[] body = ("pad: " + "a".repeat(1024 * 1024 - 4)).getBytes(StandardCharsets.UTF_8);
        assertEquals(1024 * 1024 + 1, body.length, "正文应恰比上限多一字节");

        StubExchange exchange = new StubExchange("POST", URI.create("/save?token=" + server.token()),
                new String(body, StandardCharsets.UTF_8));
        server.handle(exchange);

        assertEquals(200, exchange.status(), "拒收也应答应 200 的页面");
        assertTrue(exchange.body().contains("配置太大"), "应说明配置太大");
        assertTrue(exchange.body().contains("没有保存"), "应说明没有保存");
        assertFalse(exchange.body().contains("已保存"), "没保存成不许说已保存");
        assertArrayEquals(original, Files.readAllBytes(config), "超限的保存不许改动配置本体");
        assertEquals(0, backupCount(config), "超限的保存不许产生备份");
    }

    /**
     * 对照格：正好等于上限、且是合法 YAML 的正文不误拒，照常保存
     */
    @Test
    @DisplayName("对照格：正文正好等于上限且是合法 YAML 时照常保存")
    void bodyExactlyAtLimitStillSaves() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, "seed: 1\n", StandardCharsets.UTF_8);
        SafeModeServer server = new SafeModeServer(config, "测试用的启动失败原因");

        byte[] body = ("pad: " + "a".repeat(1024 * 1024 - 5)).getBytes(StandardCharsets.UTF_8);
        assertEquals(1024 * 1024, body.length, "正文应恰好等于上限");

        StubExchange exchange = new StubExchange("POST", URI.create("/save?token=" + server.token()),
                new String(body, StandardCharsets.UTF_8));
        server.handle(exchange);

        assertTrue(exchange.body().contains("已保存"), "等于上限的合法正文应照常保存");
        assertArrayEquals(body, Files.readAllBytes(config), "本体应是这次保存的正文");
    }

    /**
     * 把编辑页与保存应答落成文件，交给 node 夹具替浏览器执行页面里的脚本
     */
    private void runFixture(String page, String response, String what) throws IOException, InterruptedException {
        Path fixture = fixturePath();
        Path pageFile = dir.resolve("editor-page.html");
        Path responseFile = dir.resolve("save-response.html");
        Files.writeString(pageFile, page, StandardCharsets.UTF_8);
        Files.writeString(responseFile, response, StandardCharsets.UTF_8);

        Process process;
        try {
            process = new ProcessBuilder("node", fixture.toString(), pageFile.toString(), responseFile.toString())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            // 起不来就红。页面里的脚本只有 node 能替浏览器执行，缺了它这一格量不到任何东西——
            // 而「量不到」不许读成「没问题」
            throw new IOException("起不动 node，" + what + "这一格没量。这一格要真执行页面里的脚本，需要 node 在 PATH 上", e);
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), what + "的夹具跑了 60 秒还没结束:\n" + output);

        // 绿的时候也要留下跑了几格：只印失败的话，「全绿」与「一格没跑」在日志上长得一样
        System.out.println(what + "夹具：" + output);
        assertEquals(0, process.exitValue(), what + "与预期不符:\n" + output);
        assertTrue(output.contains("跑了") && !output.contains("跑了 0 格"),
                "夹具没报出跑了几格，或者一格都没跑:\n" + output);
    }

    private static Path fixturePath() throws IOException {
        try {
            return Path.of(SafeModeServerSaveResponseTest.class
                    .getResource("/safemode/save-response-script-fixture.mjs").toURI());
        } catch (Exception e) {
            throw new IOException("定位保存应答夹具失败", e);
        }
    }

    /**
     * 只数备份这件事发生了没有，份数裁剪与命名形状是备份组件那组判据的事，不在此重复
     */
    private static int backupCount(Path config) throws IOException {
        String prefix = config.getFileName() + ".";
        try (Stream<Path> files = Files.list(config.getParent())) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix) && name.endsWith(".bak"))
                    .toList()
                    .size();
        }
    }

    /**
     * 不开端口的请求桩：与闸门那组同款，预置方法／URI／请求体，捕获状态码与响应体
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
            // 应答路径用不到
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setStreams(InputStream in, OutputStream out) {
            // 应答路径用不到
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

        // CPU 版 17u 把读取应答码定为抽象方法，桩照实回报捕获值；应答长度无此方法，不入桩
        @Override
        public int getResponseCode() {
            return status;
        }
    }
}
