package com.starlwr.bot.bilibili.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.health.BilibiliRiskMetrics;
import com.starlwr.bot.bilibili.model.Cookies;
import com.starlwr.bot.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP 应答顶层未知键与缺字段 detail 形
 */
@DisplayName("HTTP 应答顶层未知键")
class BilibiliApiUtilUnknownFieldTest {
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private static final String ROOM_INFO =
            "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=1";

    @Test
    @DisplayName("多余顶层键 0→1 且键形对；同应答×20 计数 20 种数 1；表内每路已知键恒 0、已知+x_extra 恰 1；空/非对象不记不抛；缺字段 detail 含端点:键")
    void unknownTopLevelKeysAndMissingFieldDetail() {
        List<String> reds = new ArrayList<>();

        try {
            BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
            JSONObject data = keys("uid", "live_status", "live_time", "title", "user_cover");
            data.put("x_extra", 0);
            BilibiliApiUtil api = apiReturning(ROOM_INFO, wrap(data), metrics);
            assertEquals(0, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW),
                    "开工前未知字段应是 0");
            api.requestBilibiliApi(ROOM_INFO);
            assertEquals(1, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW),
                    "多一个顶层键应 0→1，实际 "
                            + metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW));
            String detail = metrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD).orElse("");
            assertTrue(detail.contains("ROOM_INFO_API:x_extra"),
                    "键形应为端点常量名:键名，实际: " + detail);
            assertFalse(detail.contains("x_extra="), "detail 不得写取值，实际: " + detail);
        } catch (AssertionError | RuntimeException e) {
            reds.add("① " + e.getMessage());
        }

        try {
            BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
            JSONObject data = keys("uid", "live_status", "live_time", "title", "user_cover");
            data.put("x_extra", 0);
            BilibiliApiUtil api = apiReturning(ROOM_INFO, wrap(data), metrics);
            for (int i = 0; i < 20; i++) {
                api.requestBilibiliApi(ROOM_INFO);
            }
            assertEquals(20, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW),
                    "同一应答喂 20 次应记 20，实际 "
                            + metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW));
            String detail = metrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD).orElse("");
            assertTrue(detail.contains("unique=1"), "种数应是 1，实际: " + detail);
            assertTrue(detail.contains("count=10"), "样本只在量级处换，实际: " + detail);
        } catch (AssertionError | RuntimeException e) {
            reds.add("② " + e.getMessage());
        }

        try {
            BilibiliRiskMetrics zeroMetrics = new BilibiliRiskMetrics();
            BilibiliApiUtil zeroApi = new BilibiliApiUtil(mock(HttpUtil.class),
                    new StarBotBilibiliProperties(), zeroMetrics);
            for (Map.Entry<String, BilibiliApiUtil.KnownDataKeys> entry
                    : BilibiliApiUtil.KNOWN_DATA_KEYS_BY_PATH.entrySet()) {
                JSONObject data = keys(entry.getValue().keys().toArray(String[]::new));
                feedKnownKeys(zeroApi, entry, data);
            }
            assertEquals(0, zeroMetrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW),
                    "表内每一路只含已知键应恒 0，实际 "
                            + zeroMetrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW)
                            + " detail="
                            + zeroMetrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD).orElse(""));
            for (Map.Entry<String, BilibiliApiUtil.KnownDataKeys> entry
                    : BilibiliApiUtil.KNOWN_DATA_KEYS_BY_PATH.entrySet()) {
                BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
                BilibiliApiUtil api = new BilibiliApiUtil(mock(HttpUtil.class),
                        new StarBotBilibiliProperties(), metrics);
                JSONObject data = keys(entry.getValue().keys().toArray(String[]::new));
                data.put("x_extra", 0);
                feedKnownKeys(api, entry, data);
                String name = entry.getValue().constantName();
                assertEquals(1, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW),
                        name + " 已知键＋x_extra 应恰 1，实际 "
                                + metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW));
                String detail = metrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD).orElse("");
                assertTrue(detail.contains(name + ":x_extra"),
                        name + " detail 应为常量名:x_extra，实际: " + detail);
            }
        } catch (AssertionError | RuntimeException e) {
            reds.add("③ " + e.getMessage());
        }

        try {
            BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
            JSONObject emptyBody = wrap(new JSONObject());
            BilibiliApiUtil emptyApi = apiReturning(ROOM_INFO, emptyBody, metrics);
            emptyApi.requestBilibiliApi(ROOM_INFO);
            JSONObject arrayBody = new JSONObject();
            arrayBody.put("code", 0);
            arrayBody.put("data", new JSONArray());
            BilibiliApiUtil arrayApi = apiReturning(ROOM_INFO, arrayBody, metrics);
            arrayApi.requestBilibiliApi(ROOM_INFO);
            assertEquals(0, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW),
                    "空对象／非对象不得记未知字段，实际 "
                            + metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW));
        } catch (AssertionError | RuntimeException e) {
            reds.add("④ " + e.getMessage());
        }

        try {
            BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
            JSONObject missing = new JSONObject();
            missing.put("code", 0);
            BilibiliApiUtil api = apiReturning(ROOM_INFO, missing, metrics);
            api.requestBilibiliApi(ROOM_INFO);
            String detail = metrics.lastDetail(BilibiliRiskMetrics.Kind.API_DATA_MISSING).orElse("");
            assertTrue(detail.contains("/get_info:data") || detail.contains("ROOM_INFO_API:data"),
                    "缺字段 detail 应含端点:键，实际: " + detail);
            ConcurrentHashMap<String, AtomicLong> ledger = new ConcurrentHashMap<>();
            BilibiliApiUtil.noteDataMissing(
                    "https://api.example.com/room/v1/Info?roomid=1", metrics, ledger);
            String staticDetail = metrics.lastDetail(BilibiliRiskMetrics.Kind.API_DATA_MISSING).orElse("");
            assertTrue(staticDetail.contains("/Info:data"),
                    "静态记账 detail 应含端点:键，实际: " + staticDetail);
        } catch (AssertionError | RuntimeException e) {
            reds.add("⑤ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "五问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    @Test
    @DisplayName("CONFIRM_REFRESH 进表后未知键 0→1；ROOM_STATUS／NAV／扫码轮询／TV／OAUTH2 解析处给未知键应记；心跳取用为空不记")
    void confirmRefreshAndEndpointsOutsideExtractData() {
        List<String> reds = new ArrayList<>();

        try {
            BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
            HttpUtil http = mock(HttpUtil.class);
            when(http.postJsonAsForm(any(), any(), any())).thenReturn(wrap(keys("x_extra")));
            BilibiliApiUtil api = new BilibiliApiUtil(http, new StarBotBilibiliProperties(), metrics);
            Cookies cookies = new Cookies();
            cookies.setBiliJct("csrf");
            api.setCookies(cookies);
            api.confirmCookieRefresh("old-token");
            assertEquals(1, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW),
                    "CONFIRM_REFRESH 多一个顶层键应 0→1，实际 "
                            + metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW));
            String detail = metrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD).orElse("");
            assertTrue(detail.contains("CONFIRM_REFRESH_API:x_extra"),
                    "键形应为 CONFIRM_REFRESH_API:x_extra，实际: " + detail);
        } catch (AssertionError | RuntimeException e) {
            reds.add("CONFIRM_REFRESH " + e.getMessage());
        }

        try {
            BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
            JSONObject inner = keys("live_status", "live_time", "title", "cover_from_user");
            inner.put("x_extra", 0);
            JSONObject data = new JSONObject();
            data.put("1", inner);
            BilibiliApiUtil api = apiReturning(
                    "https://api.live.bilibili.com/room/v1/Room/get_status_info_by_uids",
                    wrap(data), metrics);
            api.getLiveInfoByUids(Set.of(1L));
            assertEquals(1, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW),
                    "ROOM_STATUS 内层多一个键应 0→1，实际 "
                            + metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW));
            String detail = metrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD).orElse("");
            assertTrue(detail.contains("ROOM_STATUS_API:x_extra"),
                    "键形应为 ROOM_STATUS_API:x_extra，实际: " + detail);
        } catch (AssertionError | RuntimeException e) {
            reds.add("ROOM_STATUS " + e.getMessage());
        }

        try {
            BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
            HttpUtil http = mock(HttpUtil.class);
            when(http.postJsonWithHeaders(any(), any())).thenThrow(new RuntimeException("no ticket"));
            JSONObject nav = new JSONObject();
            JSONObject wbi = new JSONObject();
            wbi.put("img_url", "https://example.invalid/i.png");
            wbi.put("sub_url", "https://example.invalid/s.png");
            nav.put("wbi_img", wbi);
            nav.put("x_extra", 0);
            when(http.getJson(any(), any())).thenReturn(wrap(nav));
            BilibiliApiUtil api = new BilibiliApiUtil(http, new StarBotBilibiliProperties(), metrics);
            api.generateWebSign();
            assertEquals(1, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW),
                    "NAV 多一个顶层键应 0→1，实际 "
                            + metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW));
            String detail = metrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD).orElse("");
            assertTrue(detail.contains("NAV_API:x_extra"),
                    "键形应为 NAV_API:x_extra，实际: " + detail);
        } catch (AssertionError | RuntimeException e) {
            reds.add("NAV " + e.getMessage());
        }

        try {
            BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
            HttpUtil http = mock(HttpUtil.class);
            JSONObject poll = keys("code");
            poll.put("code", 86101);
            poll.put("x_extra", 0);
            when(http.getForEntity(any(), any())).thenReturn(ResponseEntity.ok(wrap(poll).toJSONString()));
            BilibiliApiUtil api = new BilibiliApiUtil(http, new StarBotBilibiliProperties(), metrics);
            api.getQrCodeLoginStatus("poll-key");
            assertEquals(1, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW),
                    "扫码轮询多一个顶层键应 0→1，实际 "
                            + metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW));
            String detail = metrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD).orElse("");
            assertTrue(detail.contains("QR_CODE_POLL_API:x_extra"),
                    "键形应为 QR_CODE_POLL_API:x_extra，实际: " + detail);
        } catch (AssertionError | RuntimeException e) {
            reds.add("扫码轮询 " + e.getMessage());
        }

        try {
            BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
            HttpUtil http = mock(HttpUtil.class);
            JSONObject tvData = keys("url", "auth_code");
            tvData.put("x_extra", 0);
            when(http.postAsForm(any(), any(), any())).thenReturn(wrap(tvData).toJSONString());
            BilibiliApiUtil api = new BilibiliApiUtil(http, new StarBotBilibiliProperties(), metrics);
            api.getTvQrCodeLoginInfo();
            assertEquals(1, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW),
                    "TV 生成多一个顶层键应 0→1，实际 "
                            + metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW));
            String detail = metrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD).orElse("");
            assertTrue(detail.contains("TV_QR_CODE_GENERATE_API:x_extra"),
                    "键形应为 TV_QR_CODE_GENERATE_API:x_extra，实际: " + detail);

            BilibiliRiskMetrics pollMetrics = new BilibiliRiskMetrics();
            HttpUtil pollHttp = mock(HttpUtil.class);
            JSONObject pollData = new JSONObject();
            pollData.put("x_extra", 0);
            JSONObject pollBody = new JSONObject();
            pollBody.put("code", 86039);
            pollBody.put("data", pollData);
            when(pollHttp.postAsForm(any(), any(), any())).thenReturn(pollBody.toJSONString());
            BilibiliApiUtil pollApi = new BilibiliApiUtil(pollHttp, new StarBotBilibiliProperties(), pollMetrics);
            pollApi.getTvQrCodeLoginStatus("auth");
            assertEquals(1, pollMetrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW),
                    "TV 轮询多一个顶层键应 0→1，实际 "
                            + pollMetrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW));
            String pollDetail = pollMetrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD).orElse("");
            assertTrue(pollDetail.contains("TV_QR_CODE_POLL_API:x_extra"),
                    "键形应为 TV_QR_CODE_POLL_API:x_extra，实际: " + pollDetail);
        } catch (AssertionError | RuntimeException e) {
            reds.add("TV " + e.getMessage());
        }

        try {
            BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
            HttpUtil http = mock(HttpUtil.class);
            JSONObject hb = new JSONObject();
            hb.put("next_interval", 60);
            hb.put("x_extra", 0);
            when(http.getJson(any(), any())).thenReturn(wrap(hb));
            BilibiliApiUtil api = new BilibiliApiUtil(http, new StarBotBilibiliProperties(), metrics);
            api.liveRoomHeartbeat(1L, 60);
            assertEquals(0, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW),
                    "心跳取用为空整路不记，实际 "
                            + metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW));
        } catch (AssertionError | RuntimeException e) {
            reds.add("心跳 " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "六问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    @Test
    @DisplayName("OAUTH2 续期解析处给未知键应记")
    void oauth2RefreshUnknownTopKey() {
        BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
        HttpUtil http = mock(HttpUtil.class);
        JSONObject data = new JSONObject();
        JSONObject cookieInfo = new JSONObject();
        JSONArray cookiesArr = new JSONArray();
        JSONObject sess = new JSONObject();
        sess.put("name", "SESSDATA");
        sess.put("value", "s");
        JSONObject jct = new JSONObject();
        jct.put("name", "bili_jct");
        jct.put("value", "j");
        cookiesArr.add(sess);
        cookiesArr.add(jct);
        cookieInfo.put("cookies", cookiesArr);
        data.put("cookie_info", cookieInfo);
        data.put("access_token", "a");
        data.put("refresh_token", "r");
        data.put("expires_in", 1);
        data.put("x_extra", 0);
        when(http.postAsForm(any(), any(), any())).thenReturn(wrap(data).toJSONString());
        BilibiliApiUtil api = new BilibiliApiUtil(http, new StarBotBilibiliProperties(), metrics);
        Cookies cookies = new Cookies();
        cookies.setAccessToken("old-a");
        cookies.setRefreshToken("old-r");
        api.setCookies(cookies);
        api.refreshAppToken();
        assertEquals(1, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW),
                "OAUTH2 多一个顶层键应 0→1，实际 "
                        + metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW));
        String detail = metrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD).orElse("");
        assertTrue(detail.contains("OAUTH2_REFRESH_TOKEN_API:x_extra"),
                "键形应为 OAUTH2_REFRESH_TOKEN_API:x_extra，实际: " + detail);
    }

    private static JSONObject keys(String... names) {
        JSONObject data = new JSONObject();
        for (String name : names) {
            data.put(name, 0);
        }
        return data;
    }

    private static JSONObject wrap(JSONObject data) {
        JSONObject body = new JSONObject();
        body.put("code", 0);
        if (data != null) {
            body.put("data", data);
        }
        return body;
    }

    private static final Set<String> OUTSIDE_EXTRACT_DATA = Set.of(
            "NAV_API",
            "TV_QR_CODE_GENERATE_API",
            "TV_QR_CODE_POLL_API",
            "QR_CODE_POLL_API",
            "LIVE_HEARTBEAT_API",
            "OAUTH2_REFRESH_TOKEN_API"
    );

    /**
     * 走 {@code extractData} 的路喂整段应答；其余经 {@code noteUnknownTopKeys}。
     */
    private static void feedKnownKeys(BilibiliApiUtil api,
            Map.Entry<String, BilibiliApiUtil.KnownDataKeys> entry, JSONObject data) {
        if (entry.getValue().nested() || OUTSIDE_EXTRACT_DATA.contains(entry.getValue().constantName())) {
            api.noteUnknownTopKeys(entry.getKey(), data);
            return;
        }
        api.extractData(wrap(data), entry.getKey());
    }

    private static BilibiliApiUtil apiReturning(String url, JSONObject body, BilibiliRiskMetrics metrics) {
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(any(), any())).thenReturn(body);
        return new BilibiliApiUtil(http, new StarBotBilibiliProperties(), metrics);
    }
}
