package org.frostnova.nova.core.config.ui.auth.passkey;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 别人反复要登录挑战，挤不掉主人手上那一条
 * <p>
 * 抓的故障：面板开到公网（或放在反向代理后面、放宽了来源白名单）时，
 * 有人在几分钟里反复要登录挑战，主人这时用通行密钥登录、或在设置页登记新钥匙，
 * 按完指纹却被告知挑战已失效——挑战表满了，主人那条被当成最早的挤掉了。
 * <p>
 * 要登录挑战不需要登录，所以这一侧只能靠来源地址把人分开；
 * 登记挑战只有已登录、又核过现在密码的人拿得到，不该和登录挑战抢位置。
 */
@DisplayName("通行密钥挑战表防挤")
class PasskeyChallengeCrowdingTest extends PasskeyTestSupport {
    /**
     * 挤的人要多少次：比改前整张表的上限（64）再多一倍，改前这个数足以把表翻一遍
     */
    private static final int FLOOD = 128;

    private static final String STRANGER_IP = "203.0.113.9";

    @Test
    @DisplayName("别的地址反复要登录挑战，主人手上的登录挑战照样能用")
    void loginChallengeSurvivesFloodFromAnotherAddress() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        register(authenticator, "我的手机", 7);

        String mine = loginChallenge();
        flood();

        assertEquals(200, controller.loginVerify(
                        authenticator.assertion(mine, ORIGIN, RP_ID, 8), request()).getStatusCode().value(),
                "别人要了 " + FLOOD + " 次登录挑战之后, 主人先拿到的那条该还能登进来");
    }

    @Test
    @DisplayName("反复要登录挑战，已登录主人手上的登记挑战照样能用")
    void registerChallengeSurvivesLoginFlood() {
        // 先登记一把，登录挑战才会真发出去（一把都没有时这条路直接说用不了）
        register(new TestAuthenticator(TestAuthenticator.RS256), "旧的那把", 0);

        String mine = registerOptions().getString("challenge");
        flood();

        TestAuthenticator fresh = new TestAuthenticator(TestAuthenticator.ES256);
        JSONObject result = controller.registerVerify(
                fresh.register(mine, ORIGIN, RP_ID, "新手机", 0), request());

        assertTrue(result.getBooleanValue("success"),
                "别人要了 " + FLOOD + " 次登录挑战之后, 主人的登记挑战该还能用: " + result.getString("message"));
    }

    /**
     * 抓的故障：公网实例上有人握着一整段 IPv6（一台机器通常分到整个 /64），每要一次换一个地址，
     * 每个地址都只占一条。主人开着两个登录框（占 2 条）时，不按前缀归并的话主人就是占得最多的那个，
     * 表一满先挤主人最早那条，主人在先开的框里按完指纹报挑战失效
     */
    @Test
    @DisplayName("同一 /64 前缀下换着地址反复要登录挑战，开着两个框的主人先开的那条照样能用")
    void loginChallengeSurvivesRotatingAddressesInOnePrefix() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        register(authenticator, "我的手机", 7);

        String mine = loginChallenge();
        loginChallenge();
        for (int i = 1; i <= 1000; i++) {
            askAsStranger("2001:db8:1:2::" + Integer.toHexString(i));
        }

        assertEquals(200, controller.loginVerify(
                        authenticator.assertion(mine, ORIGIN, RP_ID, 8), request()).getStatusCode().value(),
                "同一 /64 下换了 1000 个地址之后, 主人先拿到的那条该还能登进来");
    }

    /**
     * 抓的故障同上，换成手里有许多段前缀的人：主人先要到挑战，之后几百段前缀各要一次，
     * 大家都只占一条，表满时该淘汰的是后来的，不是主人先到手的那条
     */
    @Test
    @DisplayName("主人先要到登录挑战，之后几百个不同 /64 前缀各要一次，主人那条照样能用")
    void loginChallengeSurvivesManyPrefixesAskingOnceEach() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        register(authenticator, "我的手机", 7);

        String mine = loginChallenge();
        for (int i = 1; i <= 500; i++) {
            askAsStranger("2001:db8:" + Integer.toHexString(i) + ":1::1");
        }

        assertEquals(200, controller.loginVerify(
                        authenticator.assertion(mine, ORIGIN, RP_ID, 8), request()).getStatusCode().value(),
                "500 段前缀各要一次之后, 主人先拿到的那条该还能登进来");
    }

    private void askAsStranger(String address) {
        MockHttpServletRequest stranger = new MockHttpServletRequest("POST", "/config/api/auth/passkey/login/options");
        stranger.addHeader("Host", HOST);
        stranger.setRemoteAddr(address);
        assertTrue(controller.loginOptions(stranger).getBooleanValue("success"), "台面：陌生地址该要得到登录挑战");
    }

    private void flood() {
        for (int i = 0; i < FLOOD; i++) {
            MockHttpServletRequest stranger = new MockHttpServletRequest("POST", "/config/api/auth/passkey/login/options");
            stranger.addHeader("Host", HOST);
            stranger.setRemoteAddr(STRANGER_IP);
            assertTrue(controller.loginOptions(stranger).getBooleanValue("success"), "台面：陌生地址该要得到登录挑战");
        }
    }
}
