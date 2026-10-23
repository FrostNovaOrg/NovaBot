package org.frostnova.demo;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.Cookie;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.core.NovaCoreApplication;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.adapter.onebot.exception.OneBotApiException;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Explicitly invoked response recorder; no assertions use these response files. */
@EnabledIfSystemProperty(named = "console.demo.record", matches = "true")
@SpringBootTest(classes = NovaCoreApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"novabot.bilibili.account.anonymous=true",
                "novabot.core.config-ui.token=console-demo-recording-token-0123456789"})
public class ConsoleDemoRecorderTest {
    @TestBean(methodName = "portContext") WebServerApplicationContext serverContext;
    @TestBean(methodName = "botTransport") OneBotHttpAdapter oneBotHttpAdapter;
    @Autowired WebApplicationContext context;
    @Autowired NovaMessageSender sender;
    private static boolean failSend;

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
            if (failSend && api.url().equals("/send_group_msg"))
                throw new OneBotApiException(api.url(), call.getArgument(1), 100, "演示传输：群暂不可用");
            Object value = replies.get(api.url());
            if (value == null) throw new IllegalStateException("Unrecorded OneBot transport: " + api.url());
            return value;
        });
    }

    @TestBean(methodName = "deferredStartup")
    org.frostnova.nova.core.timeline.SystemTimelineRecorder startup;

    static org.frostnova.nova.core.timeline.SystemTimelineRecorder deferredStartup() {
        return mock(org.frostnova.nova.core.timeline.SystemTimelineRecorder.class);
    }

    public static long recordingMillis() { return Instant.now().toEpochMilli(); }

    private MockMvc mvc;
    private Cookie cookie;
    private Path output;
    private final java.util.Set<String> expiredReports = new java.util.HashSet<>();
    private final JSONArray entries = new JSONArray();
    private final java.util.List<String> misses = new java.util.ArrayList<>();
    private final java.util.Set<String> keys = new java.util.LinkedHashSet<>();
    private final java.util.Map<String, String> bodies = new java.util.LinkedHashMap<>();

    private JSONObject capture(String path, Object... params) throws Exception {
        return captureRequest("GET", path, null, params);
    }

    private JSONObject captureRequest(String method, String path, JSONObject requestBody, Object... params) throws Exception {
        var query = new java.util.TreeMap<String, String>();
        for (int i = 0; i < params.length; i += 2) query.put(params[i].toString(), params[i + 1].toString());
        String encoded = query.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        String key = method + " /config/api" + path + (encoded.isEmpty() ? "" : "?" + encoded);
        if (requestBody != null) key += " " + JSON.toJSONString(sortedJson(requestBody));
        if (!keys.add(key)) return null;
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .request(org.springframework.http.HttpMethod.valueOf(method), "/config/api" + path)
                .header("Host", "localhost:7827").cookie(cookie);
        if (method.equals("POST")) request.contentType("application/json").content(requestBody == null ? "{}" : JSON.toJSONString(sortedJson(requestBody)));
        query.forEach((k, v) -> request.queryParam(k, v));
        org.springframework.mock.web.MockHttpServletResponse response;
        long previewStarted = System.nanoTime();
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
        if (method.equals("POST") && path.startsWith("/weekly-report/") && path.endsWith("/preview"))
            System.out.printf(java.util.Locale.ROOT, "WEEKLY_PREVIEW_MS %s %.3f%n", path,
                    (System.nanoTime() - previewStarted) / 1_000_000.0);
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
        String file = path.substring(1).replace('/', '_') + "--" + sha((encoded + (requestBody == null ? "" : JSON.toJSONString(sortedJson(requestBody)))).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 12) + ext;
        if (contentType.contains("json")) {
            bytes = (JSON.toJSONString(sortedJson(JSON.parse(bytes)), com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat)
                    + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        if (contentType.contains("png")) bytes = compressPng(bytes);
        String digest = sha(bytes);
        if (bodies.containsKey(digest)) file = bodies.get(digest);
        else {
            bodies.put(digest, file);
            Files.write(output.resolve(file), bytes);
        }
        entries.add(JSONObject.of("method", method, "path", "/config/api" + path, "query", encoded,
                "file", "data/" + file, "status", response.getStatus(), "contentType", contentType, "sha256", sha(bytes)));
        if (requestBody != null) entries.getJSONObject(entries.size() - 1).put("body", requestBody);
        // Anonymous nickname lookup can fail without an upstream connection.
        boolean offlineError = response.getStatus() == 500 && path.equals("/streamer/name");
        boolean expired = response.getStatus() == 404 && expiredReports.contains(path);
        String line = key + " -> HTTP " + response.getStatus() + (offlineError ? " (backend offline error)" : expired ? " (expired detail)" : "");
        System.out.println("HIT " + line);
        if (response.getStatus() >= 400 && !offlineError && !expired) misses.add(line);
        return contentType.contains("json") ? JSON.parseObject(bytes) : null;
    }

    private static Object sortedJson(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            var sorted = new java.util.TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put(key.toString(), sortedJson(item)));
            return sorted;
        }
        if (value instanceof java.util.List<?> list) return list.stream().map(ConsoleDemoRecorderTest::sortedJson).toList();
        return value;
    }

    private static String encode(String text) {
        return java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String sha(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Recompress IDAT losslessly; headers, metadata and decoded scanlines stay identical. */
    private static byte[] compressPng(byte[] source) throws Exception {
        var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(source));
        byte[] signature = input.readNBytes(8);
        var chunks = new java.util.ArrayList<byte[][]>();
        var idat = new java.io.ByteArrayOutputStream();
        while (input.available() > 0) {
            int length = input.readInt();
            byte[] type = input.readNBytes(4), data = input.readNBytes(length);
            input.readInt();
            chunks.add(new byte[][]{type, data});
            if (java.util.Arrays.equals(type, new byte[]{73, 68, 65, 84})) idat.write(data);
        }
        byte[] scanlines;
        try (var inflater = new java.util.zip.InflaterInputStream(
                new java.io.ByteArrayInputStream(idat.toByteArray()))) {
            scanlines = inflater.readAllBytes();
        }
        var packed = new java.io.ByteArrayOutputStream();
        var deflater = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
        try (var compressor = new java.util.zip.DeflaterOutputStream(packed, deflater)) {
            compressor.write(scanlines);
        } finally { deflater.end(); }
        try (var inflater = new java.util.zip.InflaterInputStream(
                new java.io.ByteArrayInputStream(packed.toByteArray()))) {
            assertArrayEquals(scanlines, inflater.readAllBytes(), "lossless PNG scanlines");
        }
        var result = new java.io.ByteArrayOutputStream();
        var output = new java.io.DataOutputStream(result);
        output.write(signature);
        boolean written = false;
        for (byte[][] chunk : chunks) {
            byte[] type = chunk[0], data = chunk[1];
            if (java.util.Arrays.equals(type, new byte[]{73, 68, 65, 84})) {
                if (written) continue;
                written = true;
                data = packed.toByteArray();
            }
            output.writeInt(data.length);
            output.write(type);
            output.write(data);
            var crc = new java.util.zip.CRC32();
            crc.update(type);
            crc.update(data);
            output.writeInt((int) crc.getValue());
        }
        return result.size() < source.length ? result.toByteArray() : source;
    }

    @Test void record() throws Exception {
        var registration = context.getBean("configUiSecurityFilterRegistration",
                org.springframework.boot.web.servlet.FilterRegistrationBean.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(registration.getFilter()).build();
        cookie = new Cookie("starbot_config_token", "console-demo-recording-token-0123456789");
        output = Path.of("recorded-data");
        Files.createDirectory(output);
        JSONObject meta = JSON.parseObject(Files.readString(Path.of("recording.json")));

        Instant instant = Instant.ofEpochMilli(meta.getLongValue("epochMs"));
        java.time.Clock clock = java.time.Clock.fixed(instant, java.time.ZoneId.of("Asia/Shanghai"));
        // Fix only the recording clock; controllers and renderers still run normally.
        for (Class<?> type : java.util.List.of(
                org.frostnova.nova.console.controller.StreamerController.class,
                org.frostnova.nova.core.service.WeeklyReportDeliveryService.class))
            org.springframework.test.util.ReflectionTestUtils.setField(context.getBean(type), "clock", clock);
        var directory = context.getBean(org.frostnova.nova.adapter.onebot.service.OneBotTargetDirectory.class);
        org.springframework.test.util.ReflectionTestUtils.setField(directory, "clock", clock);
        directory.invalidate("qq-onebot");
        // Substitute the wall clock at its call site; the controller builds the response.
        new net.bytebuddy.ByteBuddy().redefine(org.frostnova.nova.console.controller.StreamerController.class)
                .visit(net.bytebuddy.asm.MemberSubstitution.relaxed()
                        .method(net.bytebuddy.matcher.ElementMatchers.isDeclaredBy(System.class)
                                .and(net.bytebuddy.matcher.ElementMatchers.named("currentTimeMillis")))
                        .replaceWith(ConsoleDemoRecorderTest.class.getMethod("recordingMillis"))
                        .on(net.bytebuddy.matcher.ElementMatchers.named("live")))
                .make().load(getClass().getClassLoader(),
                        net.bytebuddy.dynamic.loading.ClassReloadingStrategy.of(org.mockito.internal.PremainAttachAccess.getInstrumentation()));
        new net.bytebuddy.ByteBuddy().redefine(org.frostnova.nova.core.timeline.TimelineEvent.Builder.class)
                .visit(net.bytebuddy.asm.MemberSubstitution.relaxed()
                        .method(net.bytebuddy.matcher.ElementMatchers.isDeclaredBy(System.class)
                                .and(net.bytebuddy.matcher.ElementMatchers.named("currentTimeMillis")))
                        .replaceWith(ConsoleDemoRecorderTest.class.getMethod("recordingMillis"))
                        .on(net.bytebuddy.matcher.ElementMatchers.isConstructor()))
                .make().load(getClass().getClassLoader(), net.bytebuddy.dynamic.loading.ClassReloadingStrategy.of(
                        org.mockito.internal.PremainAttachAccess.getInstrumentation()));
        Instant started = instant.minusSeconds(3 * 3600);
        var runtime = mock(java.lang.management.RuntimeMXBean.class);
        when(runtime.getUptime()).thenReturn(2500L);
        try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS);
             var management = mockStatic(java.lang.management.ManagementFactory.class, CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(started);
            management.when(java.lang.management.ManagementFactory::getRuntimeMXBean).thenReturn(runtime);
            new org.frostnova.nova.core.timeline.SystemTimelineRecorder(
                    context.getBean(org.frostnova.nova.core.timeline.TimelineStore.class)).onApplicationReadyEvent();
        }
        replayPushes();
        // Restore chronological disk order after the real sender appended its events.
        try (var files = Files.list(Path.of("timeline"))) {
            for (Path file : files.toList()) {
                var lines = new java.util.ArrayList<>(Files.readAllLines(file));
                lines.sort(java.util.Comparator.comparingLong(line -> JSON.parseObject(line).getLongValue("at")));
                Files.write(file, lines);
            }
        }
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                context.getBean(org.frostnova.nova.core.timeline.TimelineStore.class), "rebuildIndex");
        java.time.LocalDate day = instant.atZone(clock.getZone()).toLocalDate();
        // Quiet hours are judged by LocalTime.now(); pin it too, or the recording depends on the hour it runs.
        java.time.LocalTime hour = instant.atZone(clock.getZone()).toLocalTime();
        try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS);
             var dates = mockStatic(java.time.LocalDate.class, CALLS_REAL_METHODS);
             var times = mockStatic(java.time.LocalTime.class, CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(instant);
            dates.when(java.time.LocalDate::now).thenReturn(day);
            times.when(() -> java.time.LocalTime.now()).thenReturn(hour);
            recordResponses(meta);
        }
    }

    private void recordResponses(JSONObject meta) throws Exception {
        String today = meta.getString("today");
        for (String path : java.util.List.of("/auth/state", "/auth/agreement", "/auth/passkeys", "/auth/totp/setup",
                "/alert/channels", "/bot/console", "/handlers", "/login", "/pages", "/platforms", "/schema",
                "/status", "/values", "/vocab", "/event-tokens", "/setup/bot", "/setup/state", "/datasource",
                "/datasource/users", "/exec/notice", "/state", "/push-history", "/at-all/quota", "/templates",
                "/timeline/days", "/streamers", "/report/layout-options", "/report/palettes")) {
            JSONObject body = capture(path);
            if (path.equals("/auth/passkeys")) assertEquals(2, body.getJSONArray("passkeys").size());
            if (path.equals("/event-tokens")) assertEquals(2, body.getJSONArray("tokens").size());
            if (path.equals("/streamers")) assertEquals(4, body.getJSONArray("streamers").size());
        }
        captureRequest("POST", "/report/preview", new JSONObject());
        for (long num : new long[]{990000000301L, 990000000302L, 990000000303L, 990000000502L})
            for (boolean visible : new boolean[]{false, true})
                captureRequest("POST", "/report/preview", JSONObject.of("platform", "qq-onebot",
                        "type", num == 990000000502L ? 0 : 1, "num", num, "revenueVisible", visible));
        capture("/bot/targets", "type", "group");
        capture("/bot/targets", "type", "friend");
        capture("/timeline");
        capture("/timeline", "date", today);
        capture("/timeline", "date", today, "problems", true);
        capture("/timeline", "date", today, "limit", 200);
        capture("/timeline", "date", today, "limit", 200, "problems", true);
        for (String category : java.util.List.of("PUSH", "LIVE", "SYSTEM"))
            capture("/timeline", "date", today, "limit", 200, "category", category);
        for (int age : new int[]{1, 2, 3}) {
            String date = java.time.LocalDate.parse(today).minusDays(age).toString();
            capture("/timeline", "date", date);
            capture("/timeline", "date", date, "problems", true);
            capture("/timeline", "date", date, "limit", 200);
            capture("/timeline", "date", date, "limit", 200, "problems", true);
            for (String category : java.util.List.of("PUSH", "LIVE", "SYSTEM"))
                capture("/timeline", "date", date, "limit", 200, "category", category);
        }
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
            captureRequest("POST", "/streamer/lookup", JSONObject.of("platform", platform, "uid", uid));
            // The push page loads one streamer's full configuration when a channel is opened.
            capture("/datasource/users/" + platform + "/" + uid);
            capture("/datasource/users/" + platform + "/" + uid + "/version");
            capture(p);
            capture(p + "/live");
            capture(p + "/live", "series", "online_count");
            boolean frequent = user.getString("uname").equals("演示主播常青");
            for (int days : frequent ? new int[]{7, 30, 90, 180, 360} : new int[]{7, 30, 90}) {
                JSONObject data = capture(p + "/insights", "days", days);
                for (String suffix : java.util.List.of("calendar", "fans", "audience")) {
                    JSONObject body = capture(p + "/" + suffix, "days", days);
                    if (suffix.equals("calendar"))
                        for (Object item : body.getJSONObject("calendar").getJSONArray("items")) {
                            JSONObject interval = (JSONObject) item;
                            captureInterval(p, interval, "start", "end", interval.getJSONArray("starts"));
                        }
                    if (suffix.equals("fans")) assertFalse(body.getJSONObject("fans").getJSONArray("points").isEmpty());
                    if (suffix.equals("calendar") && days == 180) {
                        var states = body.getJSONObject("calendar").getJSONArray("items").stream()
                                .map(rawDay -> ((JSONObject) rawDay).getString("status")).collect(java.util.stream.Collectors.toSet());
                        assertTrue(states.containsAll(java.util.Set.of("recorded", "off", "unknown")), "three calendar states: " + states);
                    }
                }
                for (String period : java.util.List.of("day", "week", "month")) {
                    JSONObject trend = capture(p + "/trend", "days", days, "period", period);
                    for (Object item : trend.getJSONObject("trend").getJSONArray("buckets")) {
                        JSONObject interval = (JSONObject) item;
                        captureInterval(p, interval, "start", "end", interval.getJSONArray("starts"));
                    }
                }
                for (String group : java.util.List.of("all", "first", "returning", "interaction"))
                    for (String sort : java.util.List.of("attendance", "revenue", "recent"))
                        capture(p + "/audience", "days", days, "group", group, "sort", sort, "page", 1);
                JSONObject scope = data.getJSONObject("insights");
                if (scope != null) {
                    capturePages(p, "from", scope.get("from"), "to", scope.get("to"));
                    capturePages(p, "dateFrom", scope.get("from"), "dateTo", scope.get("to"));
                }
            }
            for (int days : new int[]{90, 180}) {
                JSONObject rhythm = capture(p + "/start-slots", "days", days);
                JSONObject slots = rhythm.getJSONObject("startSlots");
                for (Object area : rhythm.getJSONArray("areas"))
                    captureInterval(p, slots, "from", "to", ((JSONObject) area).getJSONArray("starts"));
                for (Object item : slots.getJSONArray("cells")) {
                    JSONObject cell = (JSONObject) item;
                    JSONArray starts = new JSONArray();
                    for (Object sessionItem : cell.getJSONArray("sessionItems"))
                        starts.add(((JSONObject) sessionItem).get("startTime"));
                    captureInterval(p, slots, "from", "to", starts);
                }
            }
            capture(p + "/sessions");
            capturePages(p);
            for (Object row : archives) {
                JSONObject archive = (JSONObject) row;
                if (!uid.equals(archive.getString("uid")) || !platform.equals(archive.getString("platform"))) continue;
                String report = p + "/sessions/" + archive.getString("startTime") + "/report";
                boolean hasDetail = Files.isRegularFile(Path.of("details", platform + "-" + uid + "-"
                        + archive.getString("startTime"), "detail.json"));
                if (!hasDetail) expiredReports.add(report);
                int before = entries.size();
                capture(report);
                assertEquals(hasDetail ? 200 : 404, entries.getJSONObject(before).getIntValue("status"),
                        "report follows persisted detail availability");
                capture(p + "/sessions", "starts", archive.getString("startTime"), "page", 1, "size", 10);
            }
            for (Object row : user.getJSONArray("targets")) {
                JSONObject target = (JSONObject) row;
                Object[] query = {"pushPlatform", target.get("platform"), "type", target.get("type"), "num", target.get("num")};
                String wp = "/weekly-report/" + platform + "/" + uid;
                JSONObject status = capture(wp + "/status", query);
                assertTrue(status.getString("status").contains("已发出"), "previous weekly delivery");
                if (user.getBooleanValue("enabled")) for (boolean visible : new boolean[]{false, true}) {
                    var body = JSONObject.of("platform", target.get("platform"), "type", target.get("type"),
                            "num", target.get("num"), "revenueVisible", visible);
                    var draft = org.frostnova.nova.core.service.WeeklyReportLayout.defaults();
                    draft.putAll(body);
                    draft.put("streamerPlatform", platform); draft.put("streamerUid", user.get("uid"));
                    draft.put("reportPalette", target.get("reportPalette"));
                    captureRequest("POST", wp + "/preview", new JSONObject(draft));
                    if (String.valueOf(uid).equals("990000000101") && target.getLongValue("num") == 990000000301L && !visible) {
                        draft.put("audience", false);
                        captureRequest("POST", wp + "/preview", draft);
                    }
                }
            }
        }
        System.out.println("RECORDED " + entries.size() + " responses (MockMvc bodies; local config path replaced)");
        System.out.println("UNIQUE " + bodies.size() + " response files (SHA-256 deduplicated)");
        long bytes;
        try (var files = Files.list(output)) {
            bytes = files.mapToLong(file -> {
                try { return Files.size(file); }
                catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            }).sum();
        }
        System.out.println("SIZE " + bytes + " bytes (unique response bodies; index excluded)");
        System.out.println("MISS " + (misses.isEmpty() ? "(none)" : String.join("\n", misses)));
        if (!misses.isEmpty()) throw new IllegalStateException("Some endpoints could not be recorded");
        long imageBytes;
        try (var files = Files.list(output)) {
            imageBytes = files.filter(file -> file.toString().endsWith(".png")).mapToLong(file -> {
                try { return Files.size(file); }
                catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            }).sum();
        }
        assertTrue(imageBytes <= 2_500_000, "PNG budget: " + imageBytes);
        meta.put("responses", entries);
        Files.writeString(output.resolve("index.json"), JSON.toJSONString(sortedJson(meta), com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat) + "\n");
        assertTrue(bytes + Files.size(output.resolve("index.json")) <= 8_000_000, "data budget");
    }

    private void captureInterval(String p, JSONObject scope, String from, String to, JSONArray starts) throws Exception {
        String joined = starts == null ? "" : starts.stream().map(Object::toString)
                .collect(java.util.stream.Collectors.joining(","));
        capturePages(p, "from", scope.get(from), "to", scope.get(to), "starts", joined);
    }

    private void capturePages(String p, Object... filters) throws Exception {
        Object[] query = java.util.Arrays.copyOf(filters, filters.length + 4);
        query[filters.length] = "page";
        query[filters.length + 1] = 1;
        query[filters.length + 2] = "size";
        query[filters.length + 3] = 10;
        JSONObject first = capture(p + "/sessions", query);
        if (first == null) return;
        for (int page = 2; page <= first.getJSONObject("sessions").getIntValue("pages"); page++) {
            query[filters.length + 1] = page;
            capture(p + "/sessions", query);
        }
    }

    private void replayPushes() throws Exception {
        for (Object raw : JSON.parseArray(Files.readString(Path.of("push-replay.json")))) {
            JSONObject row = (JSONObject) raw;
            Instant at = Instant.ofEpochMilli(row.getLongValue("at"));
            // Only the synchronous send's clock and transport are substituted.
            // The sender, adapter service, history and timeline execute normally.
            try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                time.when(Instant::now).thenReturn(at);
                failSend = row.getBooleanValue("fail");
                try {
                    JSONObject result = sender.sendNow(Message.create("qq-onebot", PushTargetType.GROUP,
                            row.getLong("num"), row.getString("summary")).get(0));
                    assertEquals(!failSend, result.getIntValue("code") == 0, "transport outcome");
                } finally { failSend = false; }
            }
        }
        JSONObject history = capture("/push-history");
        assertEquals(4, history.getJSONArray("records").size(), "real sender history");
        assertTrue(history.getJSONArray("records").stream()
                .anyMatch(raw -> !((JSONObject) raw).getBooleanValue("success")), "failed delivery visible");
    }
}
