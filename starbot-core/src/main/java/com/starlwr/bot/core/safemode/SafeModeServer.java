package com.starlwr.bot.core.safemode;

import com.starlwr.bot.core.util.SecureToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 安全模式服务
 * <p>
 * 配置文件写坏后，主程序会启动失败，而配置界面随主程序一同挂掉——远程部署时使用者就被挡在门外，
 * 只能 SSH 进去手工改文件。安全模式即为这种情况兜底：主程序启动失败时接管端口，
 * 提供一个只能编辑配置文件的极简页面，让人把配置改回来。
 * <p>
 * <b>刻意不使用 Spring</b>：坏掉的正是 application.yml，再起一个 Spring 上下文会以完全相同的方式失败。
 * 这里只用 JDK 自带的 HTTP 服务，除读写目标文件外不依赖任何配置。
 */
@Slf4j
public class SafeModeServer {
    /**
     * 读不到配置时使用的端口
     */
    private static final int FALLBACK_PORT = 7827;

    /**
     * 请求体大小上限，防止畸形请求耗尽内存
     */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final Path configPath;

    private final String failure;

    private final String token = SecureToken.generate();

    public SafeModeServer(Path configPath, String failure) {
        this.configPath = configPath;
        this.failure = failure;
    }

    /**
     * 启动安全模式并阻塞当前线程
     * <p>
     * 仅监听回环地址：此时尚无任何可信的访问控制配置可用，不应把一个能写文件的接口暴露到网络上。
     * 需要从其他机器访问时请自行建立 SSH 隧道。
     */
    public void start() {
        int port = resolvePort();

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (IOException e) {
            log.error("安全模式无法监听端口 {}, 请直接编辑 {} 后重启", port, configPath.toAbsolutePath(), e);
            return;
        }

        server.createContext("/", this::handle);
        server.setExecutor(null);
        server.start();

        log.error("配置有误, 已进入安全模式: 仅配置界面可用, 其余功能均未启动");
        log.error("请打开以下地址修正配置后重启: http://127.0.0.1:{}/?token={}", port, token);
        log.error("若无法访问该地址, 也可直接编辑 {} 后重启", configPath.toAbsolutePath());
    }

    /**
     * 处理请求
     */
    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                respond(exchange, 403, "text/plain; charset=utf-8", "访问令牌无效，请使用启动日志中输出的完整地址");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && "/save".equals(path)) {
                save(exchange);
            } else {
                respond(exchange, 200, "text/html; charset=utf-8", page(null, null));
            }
        } catch (Exception e) {
            log.error("安全模式处理请求失败", e);
            respond(exchange, 500, "text/plain; charset=utf-8", "处理失败: " + e.getMessage());
        } finally {
            exchange.close();
        }
    }

    /**
     * 校验访问令牌
     */
    private boolean authorized(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return false;
        }

        for (String pair : query.split("&")) {
            int at = pair.indexOf('=');
            if (at > 0 && "token".equals(pair.substring(0, at))) {
                return SecureToken.verify(token, pair.substring(at + 1));
            }
        }

        return false;
    }

    /**
     * 保存配置
     */
    private void save(HttpExchange exchange) throws IOException {
        String content = readBody(exchange);

        String problem = validate(content);
        if (problem != null) {
            respond(exchange, 200, "text/html; charset=utf-8", page(content, problem));
            return;
        }

        if (Files.exists(configPath)) {
            Path backup = configPath.resolveSibling(configPath.getFileName() + "." + STAMP.format(Instant.now()) + ".bak");
            Files.copy(configPath, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(configPath, content, StandardCharsets.UTF_8);

        log.info("安全模式已保存 application.yml, 请重启程序");
        respond(exchange, 200, "text/html; charset=utf-8", page(content, null, "已保存。请重启程序，若配置无误将正常启动。"));
    }

    /**
     * 校验 YAML 是否可解析
     * @return 问题描述，通过时返回 null
     */
    private String validate(String content) {
        if (content == null || content.isBlank()) {
            return "内容为空，已拒绝保存";
        }

        try {
            Object parsed = new Yaml().load(content);
            if (!(parsed instanceof Map)) {
                return "配置文件的顶层必须是键值对结构";
            }
            return null;
        } catch (MarkedYAMLException e) {
            Mark mark = e.getProblemMark();
            String problem = e.getProblem() == null ? "格式错误" : e.getProblem();
            // snakeyaml 的行号从 0 开始，此处转为与编辑器一致的从 1 开始
            return mark == null ? "YAML 解析失败: " + problem
                    : "第 " + (mark.getLine() + 1) + " 行 YAML 解析失败: " + problem;
        } catch (Exception e) {
            return "YAML 解析失败: " + e.getMessage();
        }
    }

    /**
     * 读取请求体
     */
    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * 渲染页面
     */
    private String page(String draft, String problem) {
        return page(draft, problem, null);
    }

    private String page(String draft, String problem, String notice) {
        String content = draft;
        if (content == null) {
            try {
                content = Files.exists(configPath) ? Files.readString(configPath, StandardCharsets.UTF_8) : "";
            } catch (IOException e) {
                content = "";
                problem = "读取配置文件失败: " + e.getMessage();
            }
        }

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>StarBot 安全模式</title><style>")
                .append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;margin:0;padding:24px;")
                .append("background:#12141a;color:#e6e8ee;line-height:1.6}")
                .append("h1{font-size:19px;margin:0 0 4px}.sub{color:#8b93a7;font-size:13px;margin-bottom:18px}")
                .append("pre{background:#1b1e27;border:1px solid #2b3040;border-radius:8px;padding:12px 14px;")
                .append("font-size:12.5px;white-space:pre-wrap;word-break:break-all;max-height:220px;overflow:auto}")
                .append(".bad{border-color:#c0392b;color:#ff9f96}.good{border-color:#2e7d52;color:#8fe0b0}")
                .append("textarea{width:100%;height:52vh;box-sizing:border-box;background:#1b1e27;color:#e6e8ee;")
                .append("border:1px solid #2b3040;border-radius:8px;padding:12px;font-family:ui-monospace,Menlo,monospace;")
                .append("font-size:12.5px;line-height:1.55}")
                .append("button{margin-top:12px;background:#3b6fe0;color:#fff;border:0;border-radius:7px;")
                .append("padding:9px 20px;font-size:14px;cursor:pointer}")
                .append("</style></head><body>")
                .append("<h1>StarBot 安全模式</h1>")
                .append("<div class=\"sub\">主程序因配置问题未能启动，当前仅配置界面可用。修正后请重启程序。</div>");

        if (notice != null) {
            html.append("<pre class=\"good\">").append(escape(notice)).append("</pre>");
        }
        if (problem != null) {
            html.append("<pre class=\"bad\">").append(escape(problem)).append("</pre>");
        }

        html.append("<pre>启动失败原因：\n").append(escape(failure)).append("</pre>")
                .append("<form method=\"post\" action=\"/save?token=").append(escape(token)).append("\">")
                .append("<textarea name=\"content\" spellcheck=\"false\">").append(escape(content)).append("</textarea>")
                .append("<button type=\"submit\">保存</button></form>")
                .append("<script>")
                // 表单默认以 URL 编码提交，配置内容里的换行与特殊字符会被改写，因此直接提交原始文本
                .append("document.querySelector('form').addEventListener('submit',function(e){e.preventDefault();")
                .append("fetch(this.action,{method:'POST',body:document.querySelector('textarea').value})")
                .append(".then(r=>r.text()).then(t=>document.open()||document.write(t)||document.close());});")
                .append("</script></body></html>");

        return html.toString();
    }

    /**
     * 尽力读出配置中的端口
     * <p>
     * 配置若连解析都过不了就退回默认端口——此时也确实无从得知使用者配了什么。
     */
    private int resolvePort() {
        try {
            Object parsed = new Yaml().load(Files.readString(configPath, StandardCharsets.UTF_8));
            if (parsed instanceof Map<?, ?> root && root.get("server") instanceof Map<?, ?> server) {
                Object port = server.get("port");
                if (port instanceof Number number) {
                    return number.intValue();
                }
            }
        } catch (Exception e) {
            log.debug("安全模式无法读取配置中的端口, 使用默认值 {}: {}", FALLBACK_PORT, e.getMessage());
        }

        return FALLBACK_PORT;
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
