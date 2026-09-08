package com.starlwr.bot.bilibili.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.health.BilibiliRiskMetrics;
import com.starlwr.bot.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 各端点夹具：键＝该端点解析方法实际取用的 data 顶层键。
     * 这格是「已知键表抄漏」的闸——夹具原样不得记未知字段。
     */
    private static final Map<String, String[]> ENDPOINT_FIXTURES = fixtures();

    @Test
    @DisplayName("多余顶层键 0→1 且键形对；同应答×20 计数 20 种数 1；全部端点夹具恒 0；空/非对象不记不抛；缺字段 detail 含端点:键")
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
            BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
            HttpUtil http = mock(HttpUtil.class);
            when(http.getJson(any(), any())).thenAnswer(invocation -> {
                String url = invocation.getArgument(0);
                String[] known = matchFixture(url);
                if (known == null) {
                    throw new IllegalStateException("夹具未覆盖端点: " + url);
                }
                return wrap(keys(known));
            });
            BilibiliApiUtil api = new BilibiliApiUtil(http, new StarBotBilibiliProperties(), metrics);
            for (String url : ENDPOINT_FIXTURES.keySet()) {
                api.requestBilibiliApi(url);
            }
            assertEquals(0, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW),
                    "全部端点夹具原样应恒 0，实际 "
                            + metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD, WINDOW)
                            + " detail="
                            + metrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_FIELD).orElse(""));
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

    private static Map<String, String[]> fixtures() {
        Map<String, String[]> map = new LinkedHashMap<>();
        map.put("https://api.bilibili.com/x/frontend/finger/spi",
                new String[] {"b_3", "b_4"});
        map.put("https://api.bilibili.com/x/web-frontend/getbuvid",
                new String[] {"buvid"});
        map.put("https://passport.bilibili.com/x/passport-login/web/qrcode/generate",
                new String[] {"url", "qrcode_key"});
        map.put("https://api.bilibili.com/x/space/v2/myinfo",
                new String[] {"profile"});
        map.put("https://passport.bilibili.com/x/passport-login/web/cookie/info",
                new String[] {"refresh", "timestamp"});
        map.put("https://passport.bilibili.com/x/passport-login/web/cookie/refresh",
                new String[] {"refresh_token"});
        map.put("https://api.live.bilibili.com/live_user/v1/Master/info?uid=1",
                new String[] {"info", "room_id", "follower_num"});
        map.put("https://api.live.bilibili.com/xlive/general-interface/v1/rank/getFansMembersRank?page=1&page_size=1&ruid=1",
                new String[] {"num"});
        map.put("https://api.live.bilibili.com/xlive/app-room/v2/guardTab/topListNew",
                new String[] {"info", "top3", "list"});
        map.put(ROOM_INFO,
                new String[] {"uid", "live_status", "live_time", "title", "user_cover"});
        map.put("https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo",
                new String[] {"host_list", "token"});
        map.put("https://api.live.bilibili.com/xlive/web-room/v1/dM/gethistory?roomid=1",
                new String[] {"room"});
        map.put("https://api.live.bilibili.com/xlive/web-room/v1/giftPanel/roomGiftConfig?platform=pc",
                new String[] {"global_config", "list", "guard_resources"});
        map.put("https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?features=itemOpusStyle",
                new String[] {"items"});
        map.put("https://api.bilibili.com/x/relation/followings?vmid=1",
                new String[] {"list"});
        map.put("https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket",
                new String[] {"ticket", "created_at", "ttl", "nav"});
        return Map.copyOf(map);
    }

    private static String[] matchFixture(String url) {
        String path = url == null ? "" : (url.indexOf('?') < 0 ? url : url.substring(0, url.indexOf('?')));
        for (Map.Entry<String, String[]> entry : ENDPOINT_FIXTURES.entrySet()) {
            String known = entry.getKey();
            String knownPath = known.indexOf('?') < 0 ? known : known.substring(0, known.indexOf('?'));
            if (path.equals(knownPath)) {
                return entry.getValue();
            }
        }
        return null;
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

    private static BilibiliApiUtil apiReturning(String url, JSONObject body, BilibiliRiskMetrics metrics) {
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(any(), any())).thenReturn(body);
        return new BilibiliApiUtil(http, new StarBotBilibiliProperties(), metrics);
    }
}
