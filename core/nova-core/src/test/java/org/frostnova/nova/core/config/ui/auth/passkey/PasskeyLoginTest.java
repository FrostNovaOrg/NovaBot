package org.frostnova.nova.core.config.ui.auth.passkey;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用通行密钥登录
 * <p>
 * 阳性三条按算法各走一遍（ES256／RS256／Ed25519），阴性三条各拆掉一处校验：
 * 换 origin、换 rpId、重放旧计数器。<b>每条阴性都先跑一遍同一副台面上的阳性</b>——
 * 少了那一步，一个「什么都拒」的实现同样能让三条阴性全绿。
 */
@DisplayName("通行密钥登录")
class PasskeyLoginTest extends PasskeyTestSupport {
    @ParameterizedTest(name = "算法 {0}")
    @ValueSource(ints = {TestAuthenticator.ES256, TestAuthenticator.RS256, TestAuthenticator.EDDSA})
    @DisplayName("三种算法各能登进来一次")
    void loginSucceedsForEachAlgorithm(int algorithm) {
        TestAuthenticator authenticator = new TestAuthenticator(algorithm);
        register(authenticator, "我的手机", 7);

        ResponseEntity<JSONObject> response = controller.loginVerify(
                authenticator.assertion(loginChallenge(), ORIGIN, RP_ID, 8), request());

        assertEquals(200, response.getStatusCode().value(), "算法 " + algorithm + " 该验得过");
        assertTrue(response.getBody().getBooleanValue("success"));
        assertNotNull(response.getBody().getString("csrfToken"), "登录成功要给出 CSRF 令牌, 否则之后一个写请求也发不出去");

        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie, "登录成功要下发会话 Cookie");
        assertTrue(cookie.contains("HttpOnly"), "会话 Cookie 必须挡住页面脚本读取");
        assertTrue(cookie.contains("SameSite=Strict"), "会话 Cookie 必须让跨站请求带不上它");

        // 用过一次之后「上次使用」要有值：界面上靠它判断哪一条是能删的
        assertNotNull(controller.list().getJSONArray("passkeys").getJSONObject(0).getString("lastUsedAt"));
    }

    @Test
    @DisplayName("换成别人的站点发起，验不过")
    void rejectsTamperedOrigin() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        register(authenticator, "我的手机", 7);

        assertEquals(200, controller.loginVerify(
                        authenticator.assertion(loginChallenge(), ORIGIN, RP_ID, 8), request()).getStatusCode().value(),
                "阳性对照：地址对的时候该进得来");

        ResponseEntity<JSONObject> response = controller.loginVerify(
                authenticator.assertion(loginChallenge(), "https://phishing.example", RP_ID, 9), request());

        assertEquals(401, response.getStatusCode().value(), "客户端数据里的来源地址不是本站, 该拒");
        assertFalse(response.getBody().getBooleanValue("success"));
    }

    @Test
    @DisplayName("为别的域建的钥匙，验不过")
    void rejectsTamperedRpId() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        register(authenticator, "我的手机", 7);

        assertEquals(200, controller.loginVerify(
                        authenticator.assertion(loginChallenge(), ORIGIN, RP_ID, 8), request()).getStatusCode().value(),
                "阳性对照：域对的时候该进得来");

        // origin 照旧是本站，只有认证器签在数据里的那个域被换掉——
        // 这一条与上一条量的不是同一件事：上一条信的是浏览器，这一条信的是认证器
        ResponseEntity<JSONObject> response = controller.loginVerify(
                authenticator.assertion(loginChallenge(), ORIGIN, "phishing.example", 9), request());

        assertEquals(401, response.getStatusCode().value(), "认证器数据里的 rpId 不是本站, 该拒");
    }

    @Test
    @DisplayName("重放旧的签名计数器，验不过")
    void rejectsReplayedSignCount() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        register(authenticator, "我的手机", 7);

        assertEquals(200, controller.loginVerify(
                        authenticator.assertion(loginChallenge(), ORIGIN, RP_ID, 8), request()).getStatusCode().value(),
                "阳性对照：计数器往前走的时候该进得来");

        // 换一个新挑战、其余照旧，只把计数器退回上一次的值：
        // 计数器是发现凭据被克隆的唯一线索，克隆件不知道原件数到哪儿了
        ResponseEntity<JSONObject> response = controller.loginVerify(
                authenticator.assertion(loginChallenge(), ORIGIN, RP_ID, 8), request());

        assertEquals(401, response.getStatusCode().value(), "计数器没有前进, 该拒");
    }

    @Test
    @DisplayName("不维护计数器的认证器（一直报 0）照样能反复登录")
    void acceptsAuthenticatorWithoutCounter() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        register(authenticator, "系统钥匙串", 0);

        // 今天最常见的一类通行密钥就是这样：手机、电脑上的系统钥匙串不维护计数器，永远报 0。
        // 「小于等于存值就拒」照字面办的话，这类钥匙第一次登录之后就再也用不了
        assertEquals(200, controller.loginVerify(
                authenticator.assertion(loginChallenge(), ORIGIN, RP_ID, 0), request()).getStatusCode().value());
        assertEquals(200, controller.loginVerify(
                authenticator.assertion(loginChallenge(), ORIGIN, RP_ID, 0), request()).getStatusCode().value());
    }

    @Test
    @DisplayName("登录用的挑战只能用一次")
    void rejectsReusedLoginChallenge() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        register(authenticator, "我的手机", 7);

        String challenge = loginChallenge();
        assertEquals(200, controller.loginVerify(
                authenticator.assertion(challenge, ORIGIN, RP_ID, 8), request()).getStatusCode().value());

        assertEquals(401, controller.loginVerify(
                        authenticator.assertion(challenge, ORIGIN, RP_ID, 9), request()).getStatusCode().value(),
                "同一个挑战用第二次该拒, 否则截获过一次响应就能反复登录");
    }

    @Test
    @DisplayName("没登记过的凭据 ID 验不过")
    void rejectsUnknownCredential() {
        TestAuthenticator registered = new TestAuthenticator(TestAuthenticator.ES256);
        register(registered, "我的手机", 7);

        TestAuthenticator stranger = new TestAuthenticator(TestAuthenticator.RS256);
        assertEquals(401, controller.loginVerify(
                stranger.assertion(loginChallenge(), ORIGIN, RP_ID, 1), request()).getStatusCode().value());
    }

    @Test
    @DisplayName("签名被改过一个字节就验不过")
    void rejectsBadSignature() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        register(authenticator, "我的手机", 7);

        assertEquals(200, controller.loginVerify(
                        authenticator.assertion(loginChallenge(), ORIGIN, RP_ID, 8), request()).getStatusCode().value(),
                "阳性对照：签名没动过的时候该进得来");

        // 其余字段一字未改，只把签名末字节翻掉。少了这一条，一个「验签恒为真」的实现
        // 能让本文件里其余每一条都照旧全绿——那几条拒的都是别的东西
        JSONObject body = authenticator.assertion(loginChallenge(), ORIGIN, RP_ID, 9);
        JSONObject response = body.getJSONObject("response");
        byte[] signature = Base64.getUrlDecoder().decode(response.getString("signature"));
        signature[signature.length - 1] ^= 0x01;
        response.put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature));

        assertEquals(401, controller.loginVerify(body, request()).getStatusCode().value(), "签名不对该拒");
    }

    @Test
    @DisplayName("认证器没确认「人在场」就验不过")
    void rejectsMissingUserPresence() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        register(authenticator, "我的手机", 7);

        assertEquals(200, controller.loginVerify(
                        authenticator.assertion(loginChallenge(), ORIGIN, RP_ID, 8), request()).getStatusCode().value(),
                "阳性对照：标志位齐全的时候该进得来");

        // 抹掉「人在场」那一位，其余照旧且签名合法：这是一次没人按过的静默调用
        assertEquals(401, controller.loginVerify(
                        authenticator.assertion(loginChallenge(), ORIGIN, RP_ID, 9, TestAuthenticator.FLAG_USER_VERIFIED),
                        request()).getStatusCode().value(),
                "没人按过的那一次该拒");
    }

    @Test
    @DisplayName("失败的原因不告诉对方是哪一步不对")
    void failureMessageDoesNotLeakWhichStepFailed() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        register(authenticator, "我的手机", 7);

        String unknown = controller.loginVerify(
                new TestAuthenticator(TestAuthenticator.RS256).assertion(loginChallenge(), ORIGIN, RP_ID, 1), request())
                .getBody().getString("message");
        String tampered = controller.loginVerify(
                authenticator.assertion(loginChallenge(), "https://phishing.example", RP_ID, 8), request())
                .getBody().getString("message");

        assertEquals(unknown, tampered, "「没这把钥匙」与「地址不对」要回同一句, 分开说等于告诉对方走到哪一步了");
    }

    @Test
    @DisplayName("一把都没登记时如实说用不了")
    void loginOptionsSaySoWhenNothingRegistered() {
        JSONObject options = controller.loginOptions(request());

        assertFalse(options.getBooleanValue("success"));
        assertNotNull(options.getString("message"), "要给前端一句话, 好让它别摆一个点了必然失败的按钮");
    }
}
