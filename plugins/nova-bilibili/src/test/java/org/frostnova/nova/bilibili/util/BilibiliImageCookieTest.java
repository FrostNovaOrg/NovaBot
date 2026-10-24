package org.frostnova.nova.bilibili.util;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片地址不是 B 站自己的主机时，登录凭据曾随 Cookie 发到了别的主机。
 * 截取所有取图入口交给 HTTP 工具的请求头，不访问网络。
 */
@DisplayName("B站图片地址不能把登录凭据带到其他主机")
class BilibiliImageCookieTest {
    private static final String TRUSTED = "https://i0.hdslb.com/bfs/face/x.jpg";
    private static final String UNTRUSTED = "https://evil.example/a.png";
    private static final String COOKIE = "SESSDATA=fake-session; bili_jct=fake-csrf";
    private static final Map<String, String> OTHER_HEADERS = Map.of(
            "User-Agent", "image-test-agent", "Referer", "https://www.bilibili.com",
            "Origin", "https://www.bilibili.com");
    // 同一个 Map 中不同大小写的 Cookie 都必须移除，也不能改动调用方的 Map。
    private static final Map<String, String> CUSTOM_HEADERS = Map.of(
            "User-Agent", "image-test-agent", "Referer", "https://www.bilibili.com",
            "Origin", "https://www.bilibili.com", "X-Image", "custom",
            "Cookie", COOKIE, "cookie", COOKIE, "cOoKiE", COOKIE);

    private RecordingHttp http;
    private BilibiliApiUtil api;

    @BeforeEach
    void setUp() {
        http = new RecordingHttp();
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getNetwork().setUserAgent("image-test-agent");
        api = new BilibiliApiUtil(http, properties, new BilibiliRiskMetrics());
        api.getCookies().setSessData("fake-session");
        api.getCookies().setBiliJct("fake-csrf");
    }

    @ParameterizedTest(name = "{0}: Cookie={1}")
    @CsvSource({
            "https://i0.hdslb.com/bfs/face/x.jpg, true",
            "https://i1.biliimg.com/a.png, true",
            "https://I0.HDSLB.COM/a.png, true",
            "https://evil.example/a.png, false",
            "https://hdslb.com.evil.example/a.png, false",
            "https://evilhdslb.com/a.png, false",
            "http://i0.hdslb.com/a.png, false",
            "not a url, false",
            "https://hdslb.com/a.png, true",
            "https://biliimg.com/a.png, true",
            "https://bilibili.com/a.png, true",
            "https://www.bilibili.com/a.png, true",
            "https://i0.hdslb.com@evil.example/a.png, false"
    })
    void imageCookieDependsOnHttpsAndExactHostSuffix(String url, boolean cookieExpected) {
        assertTrue(api.getBilibiliImage(url, CUSTOM_HEADERS).isPresent());
        assertHeaders(http.calls.get(0), url, cookieExpected, true);
    }

    private enum Entry {
        GET, GET_HEADERS, BYTES, FETCH, FETCH_HEADERS,
        ASYNC, ASYNC_HEADERS, BATCH, BATCH_HEADERS;

        boolean customHeaders() {
            return name().endsWith("_HEADERS");
        }

        void invoke(BilibiliApiUtil api, String url) {
            switch (this) {
                case GET -> assertTrue(api.getBilibiliImage(url).isPresent());
                case GET_HEADERS -> assertTrue(api.getBilibiliImage(url, CUSTOM_HEADERS).isPresent());
                case BYTES -> assertTrue(api.getBilibiliImageBytes(url, 1024).isPresent());
                case FETCH -> assertTrue(api.fetchBilibiliImage(url).isPresent());
                case FETCH_HEADERS -> assertTrue(api.fetchBilibiliImage(url, CUSTOM_HEADERS).isPresent());
                case ASYNC -> assertTrue(api.asyncGetBilibiliImage(url).join().isPresent());
                case ASYNC_HEADERS -> assertTrue(api.asyncGetBilibiliImage(url, CUSTOM_HEADERS).join().isPresent());
                case BATCH -> assertTrue(api.asyncGetBilibiliImages(List.of(url)).join().get(0).isPresent());
                case BATCH_HEADERS -> assertTrue(api.asyncGetBilibiliImages(List.of(url), CUSTOM_HEADERS)
                        .join().get(0).isPresent());
            }
        }
    }

    static Stream<Arguments> entries() {
        return Stream.of(Entry.values()).flatMap(entry -> Stream.of(
                Arguments.of(entry, UNTRUSTED, false), Arguments.of(entry, TRUSTED, true)));
    }

    @ParameterizedTest(name = "{0} {1}: Cookie={2}")
    @MethodSource("entries")
    void everyImageEntryRestrictsLoginCookie(Entry entry, String url, boolean cookieExpected) {
        entry.invoke(api, url);
        assertEquals(1, http.calls.size());
        assertHeaders(http.calls.get(0), url, cookieExpected, entry.customHeaders());
    }

    @Test
    void mixedBatchChecksEachHostAndKeepsCallerHeaders() {
        assertEquals(2, api.asyncGetBilibiliImages(List.of(UNTRUSTED, TRUSTED), CUSTOM_HEADERS).join().size());
        assertEquals(2, http.calls.size());
        assertHeaders(http.calls.get(0), UNTRUSTED, false, true);
        assertHeaders(http.calls.get(1), TRUSTED, true, true);
        assertEquals(COOKIE, CUSTOM_HEADERS.get("Cookie"));
        assertEquals(COOKIE, CUSTOM_HEADERS.get("cookie"));
        assertEquals(COOKIE, CUSTOM_HEADERS.get("cOoKiE"));
    }

    @Test
    void nonImageApiKeepsLoginCookie() {
        String url = "https://api.bilibili.com/x/web-interface/nav";
        api.requestBilibiliApi(url);
        assertEquals(1, http.calls.size());
        assertHeaders(http.calls.get(0), url, true, false);
    }

    private static void assertHeaders(Call call, String url, boolean cookieExpected, boolean custom) {
        assertEquals(url, call.url());
        Map<String, String> headers = call.headers();
        OTHER_HEADERS.forEach((name, value) -> assertEquals(value, headers.get(name), name));
        if (custom) assertEquals("custom", headers.get("X-Image"));
        if (cookieExpected) {
            if (custom) assertEquals(CUSTOM_HEADERS, headers);
            else assertEquals(COOKIE, headers.get("Cookie"));
        } else {
            assertTrue(headers.keySet().stream().noneMatch("Cookie"::equalsIgnoreCase),
                    "图片请求向其他主机泄露了登录 Cookie: " + url);
            assertEquals(OTHER_HEADERS.size() + (custom ? 1 : 0), headers.size());
        }
    }

    private record Call(String url, Map<String, String> headers) { }

    private static class RecordingHttp extends HttpUtil {
        private final List<Call> calls = new ArrayList<>();
        private final BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);

        RecordingHttp() {
            super(null, null, null);
        }

        @Override
        public Optional<BufferedImage> getBufferedImage(String url, Map<String, String> headers) {
            calls.add(new Call(url, Map.copyOf(headers)));
            return Optional.of(image);
        }

        @Override
        public Optional<ImageBytes> getImageBytes(String url, Map<String, String> headers,
                                                  Duration timeout, int maxBytes) {
            calls.add(new Call(url, Map.copyOf(headers)));
            return Optional.of(new ImageBytes(org.springframework.http.MediaType.IMAGE_PNG, new byte[]{1}));
        }

        @Override
        public BufferedImage fetchBufferedImage(String url, Map<String, String> headers, Duration timeout) {
            calls.add(new Call(url, Map.copyOf(headers)));
            return image;
        }

        @Override
        public CompletableFuture<Optional<BufferedImage>> asyncGetBufferedImage(String url,
                                                                               Map<String, String> headers) {
            return CompletableFuture.completedFuture(getBufferedImage(url, headers));
        }

        @Override
        public JSONObject getJson(String url, Map<String, String> headers) {
            calls.add(new Call(url, Map.copyOf(headers)));
            return JSONObject.of("code", 0, "data", new JSONObject());
        }
    }
}
