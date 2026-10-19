package org.frostnova.demo;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.Cookie;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.core.NovaCoreApplication;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Explicitly invoked response recorder; no assertions use these response files. */
@EnabledIfSystemProperty(named = "console.demo.record", matches = "true")
@SpringBootTest(classes = NovaCoreApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "novabot.bilibili.account.anonymous=true")
class ConsoleDemoRecorderTest {
    @TestBean(methodName = "portContext") WebServerApplicationContext serverContext;
    @TestBean(methodName = "botTransport") OneBotHttpAdapter oneBotHttpAdapter;
    @Autowired WebApplicationContext context;
    @Autowired ConfigUiSessionStore sessions;

    static WebServerApplicationContext portContext() {
        WebServer web = mock(WebServer.class);
        when(web.getPort()).thenReturn(7827);
        WebServerApplicationContext server = mock(WebServerApplicationContext.class);
        when(server.getWebServer()).thenReturn(web);
        return server;
    }

    static OneBotHttpAdapter botTransport() throws Exception {
        JSONObject replies = JSON.parseObject(Files.readString(Path.of("transport.json")));
        return mock(OneBotHttpAdapter.class, call -> {
            var api = call.getMethod().getAnnotation(org.frostnova.nova.adapter.onebot.annotation.OneBotApi.class);
            if (api == null) return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
            Object value = replies.get(api.url());
            if (value == null) throw new IllegalStateException("Unrecorded OneBot transport: " + api.url());
            return value;
        });
    }

    private MockMvc mvc;
    private Cookie cookie;
    private Path output;
    private final JSONArray entries = new JSONArray();
    private final java.util.List<String> misses = new java.util.ArrayList<>();
    private final java.util.Set<String> keys = new java.util.LinkedHashSet<>();
    private final java.util.Map<String, String> bodies = new java.util.LinkedHashMap<>();

    private JSONObject capture(String path, Object... params) throws Exception {
        return captureRequest("GET", path, params);
    }

    private JSONObject captureRequest(String method, String path, Object... params) throws Exception {
        var query = new java.util.TreeMap<String, String>();
        for (int i = 0; i < params.length; i += 2) query.put(params[i].toString(), params[i + 1].toString());
        String encoded = query.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        String key = method + " /config/api" + path + (encoded.isEmpty() ? "" : "?" + encoded);
        if (!keys.add(key)) return null;
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .request(org.springframework.http.HttpMethod.valueOf(method), "/config/api" + path)
                .header("Host", "localhost:7827").cookie(cookie);
        if (method.equals("POST")) request.contentType("application/json").content("{}");
        query.forEach((k, v) -> request.queryParam(k, v));
        org.springframework.mock.web.MockHttpServletResponse response;
        try {
            response = mvc.perform(request).andReturn().getResponse();
        } catch (jakarta.servlet.ServletException error) {
            // MockMvc has no servlet container to perform its normal /error dispatch.
            response = mvc.perform(get("/error").header("Accept", "application/json")
                    .with(req -> { req.setDispatcherType(jakarta.servlet.DispatcherType.ERROR); return req; })
                    .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 500)
                    .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI, "/config/api" + path)
                    .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_EXCEPTION, error.getCause()))
                    .andReturn().getResponse();
        }
        byte[] bytes = response.getContentAsByteArray();
        if (path.equals("/status")) {
            // Keep the controller response, replacing only the local deployment path.
            String configPath = java.util.Objects.requireNonNull(JSON.parseObject(bytes).getString("configPath"));
            String body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            bytes = body.replace(JSON.toJSONString(configPath), JSON.toJSONString("/opt/novabot/application.yml"))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        String contentType = response.getContentType();
        if (contentType == null) contentType = "application/octet-stream";
        String ext = contentType.contains("json") ? ".json" : contentType.contains("html") ? ".html"
                : contentType.contains("png") ? ".png" : ".bin";
        String file = path.substring(1).replace('/', '_') + "--" + sha(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 12) + ext;
        if (contentType.contains("json")) {
            bytes = (JSON.toJSONString(JSON.parse(bytes), com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat)
                    + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        String digest = sha(bytes);
        if (bodies.containsKey(digest)) file = bodies.get(digest);
        else {
            bodies.put(digest, file);
            Files.write(output.resolve(file), bytes);
        }
        entries.add(JSONObject.of("method", method, "path", "/config/api" + path, "query", encoded,
                "file", "data/" + file, "status", response.getStatus(), "contentType", contentType, "sha256", sha(bytes)));
        // Missing report details and an unadmitted anonymous streamer are genuine empty states.
        boolean empty = response.getStatus() == 404 && (path.endsWith("/preview") || path.endsWith("/report"));
        boolean offlineError = response.getStatus() == 500 && path.equals("/streamer/name");
        String line = key + " -> HTTP " + response.getStatus() + (empty ? " (empty seed)" : offlineError ? " (backend offline error)" : "");
        System.out.println("HIT " + line);
        if (response.getStatus() >= 400 && !empty && !offlineError) misses.add(line);
        return contentType.contains("json") ? JSON.parseObject(bytes) : null;
    }

    private static String encode(String text) {
        return java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String sha(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test void record() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        ConfigUiSession session = sessions.issue("127.0.0.1", Instant.now(), ConfigUiSession.Channel.PASSWORD);
        cookie = new Cookie("starbot_config_session", session.getId());
        output = Path.of("recorded-data");
        Files.createDirectory(output);
        JSONObject meta = JSON.parseObject(Files.readString(Path.of("recording.json")));
        String today = meta.getString("today");
        for (String path : java.util.List.of("/auth/state", "/auth/agreement", "/auth/passkeys", "/auth/totp/setup",
                "/alert/channels", "/bot/console", "/handlers", "/login", "/pages", "/platforms", "/schema",
                "/status", "/values", "/vocab", "/event-tokens", "/setup/bot", "/setup/state", "/datasource",
                "/state", "/push-history", "/at-all/quota", "/templates", "/timeline/days", "/streamers",
                "/report/layout-options")) capture(path);
        captureRequest("POST", "/report/preview");
        capture("/bot/targets", "type", "group");
        capture("/bot/targets", "type", "friend");
        capture("/timeline");
        capture("/timeline", "date", today);
        capture("/timeline", "date", today, "problems", true);
        capture("/timeline", "date", today, "limit", 200);
        capture("/timeline", "date", today, "limit", 200, "problems", true);
        for (String category : java.util.List.of("PUSH", "LIVE", "SYSTEM"))
            capture("/timeline", "date", today, "limit", 200, "category", category);
        capture("/engineering-log");
        for (int limit : new int[]{0, 300, 1000, 2000}) {
            capture("/engineering-log", "limit", limit);
            capture("/engineering-log", "d", today, "limit", limit);
        }
        JSONArray users = JSON.parseArray(Files.readString(Path.of("datasource.json")));
        JSONArray archives = new JSONArray();
        for (String line : Files.readAllLines(Path.of("sessions.jsonl"))) archives.add(JSON.parseObject(line));
        for (Object raw : users) {
            JSONObject user = (JSONObject) raw;
            String platform = user.getString("platform"), uid = user.getString("uid");
            String p = "/streamers/" + platform + "/" + uid;
            capture("/streamer/name", "platform", platform, "uid", uid);
            capture(p);
            capture(p + "/live");
            capture(p + "/live", "series", "online_count");
            for (int days : new int[]{7, 30, 90, 180, 360}) {
                JSONObject data = capture(p + "/insights", "days", days);
                for (String suffix : java.util.List.of("calendar", "fans", "weekly-report", "audience"))
                    capture(p + "/" + suffix, "days", days);
                for (String period : java.util.List.of("day", "week", "month"))
                    capture(p + "/trend", "days", days, "period", period);
                for (String group : java.util.List.of("all", "first", "returning", "interaction"))
                    for (String sort : java.util.List.of("attendance", "revenue", "recent"))
                        capture(p + "/audience", "days", days, "group", group, "sort", sort, "page", 1);
                JSONObject scope = data.getJSONObject("insights");
                if (scope != null) {
                    capture(p + "/sessions", "from", scope.get("from"), "to", scope.get("to"), "page", 1, "size", 10);
                }
            }
            for (int days : new int[]{90, 180}) capture(p + "/start-slots", "days", days);
            capture(p + "/sessions");
            for (int page : new int[]{1, 2}) capture(p + "/sessions", "page", page, "size", 10);
            for (Object row : archives) {
                JSONObject archive = (JSONObject) row;
                capture(p + "/sessions/" + archive.getString("startTime") + "/report");
                capture(p + "/sessions", "starts", archive.getString("startTime"), "page", 1, "size", 10);
            }
            for (Object row : user.getJSONArray("targets")) {
                JSONObject target = (JSONObject) row;
                Object[] query = {"pushPlatform", target.get("platform"), "type", target.get("type"), "num", target.get("num")};
                String wp = "/weekly-report/" + platform + "/" + uid;
                capture(wp + "/status", query);
                capture(wp + "/preview", query);
                capture(wp + "/preview", "pushPlatform", target.get("platform"), "type", target.get("type"),
                        "num", target.get("num"), "format", "json");
            }
        }
        System.out.println("RECORDED " + entries.size() + " responses (MockMvc bodies; local config path replaced)");
        System.out.println("UNIQUE " + bodies.size() + " response files (SHA-256 deduplicated)");
        System.out.println("MISS " + (misses.isEmpty() ? "(none)" : String.join("\n", misses)));
        if (!misses.isEmpty()) throw new IllegalStateException("Some endpoints could not be recorded");
        meta.put("responses", entries);
        Files.writeString(output.resolve("index.json"), JSON.toJSONString(meta, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat) + "\n");
    }
}
