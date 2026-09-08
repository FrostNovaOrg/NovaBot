package com.starlwr.bot.adapter.onebot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个假的 OneBot HTTP 服务端
 * <p>
 * 只回这几支接口：体检（版本、登录账号、状态）与名单（群、好友、群成员）。
 * 用真服务端而不是桩：这把尺要量的是「一台什么都没配的机器，存下连接之后名单取不取得到」，
 * 而那条链路从注册一直走到一次真的往返；桩把中间那几段一起替掉，量到的就只剩自己写的返回值。
 */
final class FakeOneBotHttpServer implements AutoCloseable {
    /**
     * 假 OneBot 上的那个群
     */
    static final long GROUP_NUM = 700100200L;

    /**
     * 假 OneBot 上的那位好友
     */
    static final long FRIEND_NUM = 10086L;

    static final String NICKNAME = "test-bot";

    private final HttpServer server;

    /**
     * 按到达顺序记下的 {@code /send_group_msg} 请求体。
     * 拆成两条发时，这里会有两条，第一条没有图、第二条只有图
     */
    private final List<JSONObject> groupMessages = new ArrayList<>();

    /**
     * 第几次群消息故意失败。0 表示都不失败
     */
    private int groupMessageFailAt;
    private boolean forbidSends;

    FakeOneBotHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    List<JSONObject> groupMessages() {
        return List.copyOf(groupMessages);
    }

    void failGroupMessageAt(int n) {
        this.groupMessageFailAt = n;
    }

    void forbidSends() { this.forbidSends = true; }

    private void handle(HttpExchange exchange) throws IOException {
        JSONObject request = readJson(exchange);
        String path = exchange.getRequestURI().getPath();

        if (forbidSends && ("/send_group_msg".equals(path) || "/send_private_msg".equals(path))) {
            exchange.sendResponseHeaders(403, 0);
            exchange.close();
            return;
        }
        JSONObject body = new JSONObject();
        if ("/send_group_msg".equals(path)) {
            groupMessages.add(request);
            if (groupMessages.size() == groupMessageFailAt) {
                body.put("retcode", 1);
                body.put("message", "image send failed");
                body.put("data", null);
                write(exchange, body);
                return;
            }
            body.put("retcode", 0);
            body.put("data", new JSONObject().fluentPut("message_id", String.valueOf(groupMessages.size())));
            write(exchange, body);
            return;
        }

        body.put("retcode", 0);
        body.put("data", dataOf(path));
        write(exchange, body);
    }

    private JSONObject readJson(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        if (raw.length == 0) {
            return new JSONObject();
        }
        JSONObject parsed = JSON.parseObject(new String(raw, StandardCharsets.UTF_8));
        return parsed == null ? new JSONObject() : parsed;
    }

    private void write(HttpExchange exchange, JSONObject body) throws IOException {
        byte[] payload = body.toJSONString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private Object dataOf(String path) {
        return switch (path) {
            case "/get_version_info" -> new JSONObject().fluentPut("app_version", "9.9.9");
            case "/get_login_info" -> new JSONObject()
                    .fluentPut("nickname", NICKNAME)
                    .fluentPut("user_id", 1000L);
            case "/get_status" -> new JSONObject()
                    .fluentPut("good", true)
                    .fluentPut("online", true);
            case "/get_group_member_info" -> new JSONObject().fluentPut("role", "member");
            case "/get_group_list" -> new JSONArray().fluentAdd(new JSONObject()
                    .fluentPut("group_id", GROUP_NUM)
                    .fluentPut("group_name", "test-group")
                    .fluentPut("member_count", 3));
            case "/get_friend_list" -> new JSONArray().fluentAdd(new JSONObject()
                    .fluentPut("user_id", FRIEND_NUM)
                    .fluentPut("nickname", "test-friend")
                    .fluentPut("remark", ""));
            default -> new JSONObject();
        };
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
