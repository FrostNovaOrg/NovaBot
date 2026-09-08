package com.starlwr.bot.bilibili.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.exception.ResponseCodeException;
import com.starlwr.bot.bilibili.health.BilibiliRiskMetrics;
import com.starlwr.bot.bilibili.model.GuardMember;
import com.starlwr.bot.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP 侧登录凭据与成员 uid 的静默损失记账
 * <p>
 * 这几条路失败时都只留一条日志就返回 null／false：扫码明明成功、凭据却因缺一个键取不出来，
 * 大航海名单里缺 uid 的成员被悄悄跳过，取登录 uid 时把网络故障与未登录混在同一句日志里。
 * 计数上完全说得通，健康页上一格都不会动——本组用例把这些缺口逐条接到
 * {@link BilibiliRiskMetrics.Kind#API_DATA_MISSING}／{@link BilibiliRiskMetrics.Kind#PARSE_FAILURE} 上，
 * 并钉住「只补记数、返回值一个字不变」。
 */
@DisplayName("HTTP 侧静默损失记账")
class BilibiliApiUtilSilentLossCountTest {
    /** 与 BilibiliApiUtil 内同名常量一致；属 private，测试侧只能整串复写 */
    private static final String TV_POLL = "https://passport.bilibili.com/x/passport-tv-login/qrcode/poll";

    private static final String QR_POLL_KEY = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=testkey123";

    private static final Duration MIN = Duration.ofMinutes(1);

    @Test
    @DisplayName("TV 轮询缺 cookie_info／缺 SESSDATA 各记一次 API_DATA_MISSING，返回仍为 false")
    void tvPollNotesMissingCredentialKeys() {
        List<String> reds = new ArrayList<>();
        BilibiliRiskMetrics riskMetrics = new BilibiliRiskMetrics();
        HttpUtil http = mock(HttpUtil.class);
        when(http.postAsForm(eq(TV_POLL), any(), any())).thenReturn(bodyWithData(new JSONObject()).toJSONString());
        BilibiliApiUtil api = new BilibiliApiUtil(http, new StarBotBilibiliProperties(), riskMetrics);

        try {
            assertFalse(api.getTvQrCodeLoginStatus("auth-code"), "缺 cookie_info 时返回仍应是 false（行为不变）");
            assertEquals(1, riskMetrics.count(BilibiliRiskMetrics.Kind.API_DATA_MISSING, MIN),
                    "缺 cookie_info 应恰好记一次");
            String detail = riskMetrics.lastDetail(BilibiliRiskMetrics.Kind.API_DATA_MISSING).orElse("");
            assertTrue(detail.contains("passport-tv-login/qrcode/poll:cookie_info"),
                    "detail 应为 端点:实缺键，实际: " + detail);
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        // cookies 数组有条目，但没有任何 SESSDATA 条目
        JSONObject cookieInfo = new JSONObject();
        JSONArray entries = new JSONArray();
        entries.add(cookieEntry("bili_jct", "jct"));
        entries.add(cookieEntry("buvid3", "buvid"));
        cookieInfo.put("cookies", entries);
        JSONObject withCookies = new JSONObject();
        withCookies.put("cookie_info", cookieInfo);
        when(http.postAsForm(eq(TV_POLL), any(), any())).thenReturn(bodyWithData(withCookies).toJSONString());

        try {
            assertFalse(api.getTvQrCodeLoginStatus("auth-code"), "缺 SESSDATA 时返回仍应是 false（行为不变）");
            assertEquals(2, riskMetrics.count(BilibiliRiskMetrics.Kind.API_DATA_MISSING, MIN),
                    "缺 SESSDATA 应再记一次");
            String detail = riskMetrics.lastDetail(BilibiliRiskMetrics.Kind.API_DATA_MISSING).orElse("");
            assertTrue(detail.contains("passport-tv-login/qrcode/poll:SESSDATA"),
                    "detail 应为 端点:实缺键，实际: " + detail);
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "两问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    @Test
    @DisplayName("QR 轮询 url 查询串缺 SESSDATA 记一次 API_DATA_MISSING，返回仍为 false")
    void qrPollNotesMissingSessData() {
        List<String> reds = new ArrayList<>();
        BilibiliRiskMetrics riskMetrics = new BilibiliRiskMetrics();
        HttpUtil http = mock(HttpUtil.class);
        // 旧形态把凭据拼在跳转地址查询串里；这条 url 只有 bili_jct，没有 SESSDATA
        JSONObject data = new JSONObject();
        data.put("code", 0);
        data.put("url", "https://example.com/sync?bili_jct=jctonly&foo=bar");
        when(http.getForEntity(eq(QR_POLL_KEY), any()))
                .thenReturn(response(bodyWithData(data)));
        BilibiliApiUtil api = new BilibiliApiUtil(http, new StarBotBilibiliProperties(), riskMetrics);

        try {
            assertFalse(api.getQrCodeLoginStatus("testkey123"), "缺 SESSDATA 时返回仍应是 false（行为不变）");
            assertEquals(1, riskMetrics.count(BilibiliRiskMetrics.Kind.API_DATA_MISSING, MIN),
                    "url 查询串缺 SESSDATA 应恰好记一次");
            String detail = riskMetrics.lastDetail(BilibiliRiskMetrics.Kind.API_DATA_MISSING).orElse("");
            assertTrue(detail.contains("web/qrcode/poll:SESSDATA"),
                    "detail 应为 端点:实缺键，实际: " + detail);
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "一问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    @Test
    @DisplayName("url 拆两支：空 url 记缺键 url，畸形 url（不含 ?）不记")
    void qrPollUrlBranchesSplit() {
        List<String> reds = new ArrayList<>();

        // 空 url：应答没有 url 键，记 端点:url
        BilibiliRiskMetrics blankMetrics = new BilibiliRiskMetrics();
        HttpUtil blankHttp = mock(HttpUtil.class);
        JSONObject blankData = new JSONObject();
        blankData.put("code", 0);
        when(blankHttp.getForEntity(eq(QR_POLL_KEY), any())).thenReturn(response(bodyWithData(blankData)));
        BilibiliApiUtil blankApi = new BilibiliApiUtil(blankHttp, new StarBotBilibiliProperties(), blankMetrics);
        try {
            assertFalse(blankApi.getQrCodeLoginStatus("testkey123"), "空 url 时返回仍应是 false（行为不变）");
            assertEquals(1, blankMetrics.count(BilibiliRiskMetrics.Kind.API_DATA_MISSING, MIN),
                    "空 url 应恰好记一次");
            String detail = blankMetrics.lastDetail(BilibiliRiskMetrics.Kind.API_DATA_MISSING).orElse("");
            assertTrue(detail.contains("web/qrcode/poll:url"),
                    "detail 应为 端点:url，实际: " + detail);
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        // 畸形 url：地址里没有 ?，解析不出查询串——留日志说明，不记缺键
        BilibiliRiskMetrics malformedMetrics = new BilibiliRiskMetrics();
        HttpUtil malformedHttp = mock(HttpUtil.class);
        JSONObject malformedData = new JSONObject();
        malformedData.put("code", 0);
        malformedData.put("url", "https://www.bilibili.com/crossDomain");
        when(malformedHttp.getForEntity(eq(QR_POLL_KEY), any()))
                .thenReturn(response(bodyWithData(malformedData)));
        BilibiliApiUtil malformedApi =
                new BilibiliApiUtil(malformedHttp, new StarBotBilibiliProperties(), malformedMetrics);
        try {
            assertFalse(malformedApi.getQrCodeLoginStatus("testkey123"), "畸形 url 时返回仍应是 false（行为不变）");
            assertEquals(0, malformedMetrics.count(BilibiliRiskMetrics.Kind.API_DATA_MISSING, MIN),
                    "畸形 url 不含 ? 不算缺键，不该记");
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "两问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    @Test
    @DisplayName("大航海成员缺 uid 记一次 API_DATA_MISSING，名单照常返回（该缺键 extractData 段不覆盖）")
    void guardMemberWithoutUidNoted() {
        List<String> reds = new ArrayList<>();
        BilibiliRiskMetrics riskMetrics = new BilibiliRiskMetrics();
        BilibiliApiUtil api = new BilibiliApiUtil(mock(HttpUtil.class), new StarBotBilibiliProperties(), riskMetrics) {
            @Override
            public JSONObject requestBilibiliApi(String url) {
                // 旧形态成员：顶层没有 uid，parseGuardMember 应记 端点:uid 后跳过
                JSONObject data = new JSONObject();
                JSONObject info = new JSONObject();
                info.put("num", 1);
                info.put("page", 1);
                data.put("info", info);
                JSONArray list = new JSONArray();
                JSONObject member = new JSONObject();
                member.put("username", "no-uid-member");
                member.put("guard_level", 2);
                list.add(member);
                data.put("list", list);
                data.put("top3", new JSONArray());
                return data;
            }
        };

        try {
            Optional<List<GuardMember>> fetched = api.getGuardList(20002L, 10001L);
            assertTrue(fetched.isPresent(), "成员缺 uid 不该让整次拉取变空（行为不变）");
            assertTrue(fetched.get().isEmpty(), "缺 uid 的成员照旧被跳过");
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            assertEquals(1, riskMetrics.count(BilibiliRiskMetrics.Kind.API_DATA_MISSING, MIN),
                    "成员缺 uid 应恰好记一次");
            String detail = riskMetrics.lastDetail(BilibiliRiskMetrics.Kind.API_DATA_MISSING).orElse("");
            assertTrue(detail.contains("guardTab/topListNew:uid"),
                    "detail 应为 端点:uid，实际: " + detail);
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "两问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    @Test
    @DisplayName("getLoginUid 吞异常记账拆两支：RuntimeException 记 PARSE_FAILURE、ResponseCodeException 不记，返回都是 null")
    void getLoginUidNotesOnlyNonResponseCodeExceptions() {
        List<String> reds = new ArrayList<>();
        BilibiliRiskMetrics riskMetrics = new BilibiliRiskMetrics();

        BilibiliApiUtil runtime = new BilibiliApiUtil(mock(HttpUtil.class), new StarBotBilibiliProperties(), riskMetrics) {
            @Override
            public Long fetchLoginUid() {
                throw new IllegalStateException("boom");
            }
        };
        try {
            assertNull(runtime.getLoginUid(), "运行时异常时返回仍应是 null（行为不变）");
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }
        try {
            assertEquals(1, riskMetrics.count(BilibiliRiskMetrics.Kind.PARSE_FAILURE, MIN),
                    "非 ResponseCodeException 应记一次 PARSE_FAILURE");
            String detail = riskMetrics.lastDetail(BilibiliRiskMetrics.Kind.PARSE_FAILURE).orElse("");
            assertTrue(detail.contains("MY_INFO_API:exception:IllegalStateException"),
                    "detail 应为 MY_INFO_API:exception:类简名，实际: " + detail);
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        BilibiliApiUtil notLoggedIn =
                new BilibiliApiUtil(mock(HttpUtil.class), new StarBotBilibiliProperties(), riskMetrics) {
                    @Override
                    public Long fetchLoginUid() {
                        throw new ResponseCodeException(-101, "账号未登录");
                    }
                };
        try {
            assertNull(notLoggedIn.getLoginUid(), "未登录时返回仍应是 null（行为不变）");
            assertEquals(1, riskMetrics.count(BilibiliRiskMetrics.Kind.PARSE_FAILURE, MIN),
                    "未登录（业务码 ResponseCodeException）不该再记");
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    // ================ 夹具 ================

    private static JSONObject bodyWithData(JSONObject data) {
        JSONObject body = new JSONObject();
        body.put("code", 0);
        body.put("data", data);
        return body;
    }

    private static JSONObject cookieEntry(String name, String value) {
        JSONObject entry = new JSONObject();
        entry.put("name", name);
        entry.put("value", value);
        return entry;
    }

    private static ResponseEntity<String> response(JSONObject body) {
        return new ResponseEntity<>(body.toJSONString(), new HttpHeaders(), HttpStatus.OK);
    }
}
