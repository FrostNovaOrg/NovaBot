package com.starlwr.bot.core.config.ui.auth.passkey;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通行密钥的登记
 * <p>
 * 登记这条路的产物是<b>一把永久有效、可以绕过二次验证的钥匙</b>，因此这里的每一条
 * 都不是「功能能用」，而是「不该收下的东西有没有被收下」。
 */
@DisplayName("通行密钥登记")
class PasskeyRegistrationTest extends PasskeyTestSupport {
    @Test
    @DisplayName("认证器建好的钥匙能被收下，并出现在列表里")
    void registersCredential() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        register(authenticator, "我的手机", 5);

        JSONArray list = controller.list().getJSONArray("passkeys");
        assertEquals(1, list.size());

        JSONObject entry = list.getJSONObject(0);
        assertEquals("我的手机", entry.getString("name"));
        assertEquals(authenticator.credentialId(), entry.getString("id"));
        assertNotNull(entry.getString("createdAt"), "登记时间要给出来, 界面上要显示它");
        assertNull(entry.get("lastUsedAt"), "还没用过, 上次使用时间就该是空的");
        // 公钥不是秘密，但列表接口没有任何理由把它摆出来——面板可能正开在直播画面上
        assertFalse(entry.containsKey("publicKey"), "列表里不该出现公钥");
    }

    @Test
    @DisplayName("名字最长 40 个字，第 41 个字拒收")
    void rejectsOverlongName() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);

        // 阳性对照先走一遍：正好 40 个字要收得下。少了这一条，
        // 一个把上限错写成 0 的实现也能让下面那条阴性判据全绿
        JSONObject options = controller.registerOptions(request());
        JSONObject accepted = controller.registerVerify(
                authenticator.register(options.getString("challenge"), ORIGIN, RP_ID, "字".repeat(40), 0), request());
        assertTrue(accepted.getBooleanValue("success"), "正好 40 个字该收下: " + accepted.getString("message"));

        TestAuthenticator second = new TestAuthenticator(TestAuthenticator.RS256);
        JSONObject next = controller.registerOptions(request());
        JSONObject rejected = controller.registerVerify(
                second.register(next.getString("challenge"), ORIGIN, RP_ID, "字".repeat(41), 0), request());

        assertFalse(rejected.getBooleanValue("success"), "41 个字该拒收");
        assertTrue(rejected.getString("message").contains("40"), "拒收时要说清上限是多少");
        assertEquals(1, controller.list().getJSONArray("passkeys").size(), "被拒的那一把不该落进列表");
    }

    @Test
    @DisplayName("一个挑战只能用一次")
    void rejectsReusedChallenge() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        JSONObject options = controller.registerOptions(request());
        String challenge = options.getString("challenge");

        JSONObject first = controller.registerVerify(
                authenticator.register(challenge, ORIGIN, RP_ID, "第一把", 0), request());
        assertTrue(first.getBooleanValue("success"), "同一个挑战第一次用该成功: " + first.getString("message"));

        // 换一把新钥匙、拿同一个挑战再来一次：挑战若不是一次性的，这一趟会成功，
        // 而那意味着截获过一次响应的人可以拿它反复登记新钥匙
        TestAuthenticator replay = new TestAuthenticator(TestAuthenticator.ES256);
        JSONObject second = controller.registerVerify(
                replay.register(challenge, ORIGIN, RP_ID, "第二把", 0), request());

        assertFalse(second.getBooleanValue("success"), "用过的挑战不该再认");
        assertEquals(1, controller.list().getJSONArray("passkeys").size());
    }

    @Test
    @DisplayName("同一把钥匙不重复登记")
    void rejectsDuplicate() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        register(authenticator, "我的手机", 0);

        JSONObject options = controller.registerOptions(request());
        JSONObject again = controller.registerVerify(
                authenticator.register(options.getString("challenge"), ORIGIN, RP_ID, "又一次", 0), request());

        assertFalse(again.getBooleanValue("success"));
        assertEquals(1, controller.list().getJSONArray("passkeys").size());
        assertEquals("我的手机", controller.list().getJSONArray("passkeys").getJSONObject(0).getString("name"),
                "重复登记不该把原来那把的名字改掉");
    }

    @Test
    @DisplayName("只收 none 形式的证明")
    void rejectsAttestationWithCertificateChain() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);

        JSONObject options = controller.registerOptions(request());
        JSONObject rejected = controller.registerVerify(
                authenticator.register(options.getString("challenge"), ORIGIN, RP_ID, "带证书的", 0, "packed"), request());

        assertFalse(rejected.getBooleanValue("success"), "别的证明形式要去验一条证书链, 这一侧没有验它的能力");
        assertEquals(0, controller.list().getJSONArray("passkeys").size());

        // 阳性对照：同一副台面上 none 该收得下
        JSONObject next = controller.registerOptions(request());
        assertTrue(controller.registerVerify(
                        authenticator.register(next.getString("challenge"), ORIGIN, RP_ID, "我的手机", 0, "none"), request())
                .getBooleanValue("success"));
    }

    @Test
    @DisplayName("为别的域建的钥匙登记不进来")
    void rejectsRegistrationForAnotherRpId() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);

        JSONObject options = controller.registerOptions(request());
        JSONObject rejected = controller.registerVerify(
                authenticator.register(options.getString("challenge"), ORIGIN, "phishing.example", "别处的", 0), request());

        assertFalse(rejected.getBooleanValue("success"));
        assertEquals(0, controller.list().getJSONArray("passkeys").size());
    }

    @Test
    @DisplayName("发参数时会把已登记的排除掉")
    void excludesRegistered() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);

        assertEquals(0, controller.registerOptions(request()).getJSONArray("excludeCredentials").size());
        register(authenticator, "我的手机", 0);

        JSONArray exclude = controller.registerOptions(request()).getJSONArray("excludeCredentials");
        assertEquals(1, exclude.size());
        assertEquals(authenticator.credentialId(), exclude.getJSONObject(0).getString("id"));
    }

    @Test
    @DisplayName("删掉之后就再也登不进来")
    void deleteRemovesCredential() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        register(authenticator, "丢了的那台", 3);

        ResponseEntity<JSONObject> removed = controller.delete(authenticator.credentialId());
        assertEquals(200, removed.getStatusCode().value());
        assertEquals(0, controller.list().getJSONArray("passkeys").size());

        // 删第二次要如实说「已经不在了」：回一句「成功」的话，
        // 使用者会以为自己刚刚撤销了一台设备，而实际上什么都没发生
        assertEquals(404, controller.delete(authenticator.credentialId()).getStatusCode().value());
    }
}
