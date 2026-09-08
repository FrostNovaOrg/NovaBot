package com.starlwr.bot.core.config.ui.auth.passkey;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 这套面板在通行密钥眼里叫什么
 * <p>
 * rpId 与 origin 这一对现算自请求头，而<b>算错的表现全是「验证失败」</b>：
 * 与按错指纹、与设备不支持长得一模一样。因此这里逐种进法各钉一条。
 */
@DisplayName("通行密钥的适用范围")
class PasskeyRelyingPartyTest extends PasskeyTestSupport {
    private PasskeyRelyingParty from(String host, String forwardedHost, String forwardedProto) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/config/api/auth/passkey");
        request.addHeader(HttpHeaders.HOST, host);
        if (forwardedHost != null) {
            request.addHeader("X-Forwarded-Host", forwardedHost);
        }
        if (forwardedProto != null) {
            request.addHeader("X-Forwarded-Proto", forwardedProto);
        }
        return PasskeyRelyingParty.of(request);
    }

    @Test
    @DisplayName("rpId 去端口，origin 留端口")
    void splitsHostAndPort() {
        PasskeyRelyingParty relyingParty = from("localhost:7827", null, null);

        assertEquals("localhost", relyingParty.rpId(), "rpId 带上端口的话, 浏览器一把钥匙也建不出来");
        assertEquals("http://localhost:7827", relyingParty.origin(), "origin 少了端口就与浏览器写下的那一串对不上");
    }

    @Test
    @DisplayName("反代之后认转发头，认的是最靠近浏览器的那一个")
    void prefersForwardedHeaders() {
        PasskeyRelyingParty relyingParty = from("127.0.0.1:7827", "panel.example.com, proxy.internal", "https");

        assertEquals("panel.example.com", relyingParty.rpId());
        assertEquals("https://panel.example.com", relyingParty.origin());
    }

    @Test
    @DisplayName("IPv6 字面量不会被当成「主机:端口」截断")
    void keepsIpv6Literal() {
        assertEquals("[::1]", from("[::1]:7827", null, null).rpId());
    }

    @Test
    @DisplayName("用 IP 地址访问时，如实说这条路走不通")
    void refusesIpLiteral() {
        // 阳性对照：域名进来该是可用的
        assertTrue(from("localhost:7827", null, null).usable());
        assertNull(from("localhost:7827", null, null).unusableReason());

        // 浏览器按规范直接拒绝以 IP 作 rpId，而它抛出来的异常与「地址是个 IP」毫无关系。
        // 不在这一侧说清楚的话，使用者看到的就只是「登记按钮点了没反应」
        assertFalse(from("192.168.1.10:7827", null, null).usable());
        assertFalse(from("127.0.0.1:7827", null, null).usable());
        assertFalse(from("[::1]:7827", null, null).usable());
        assertTrue(from("192.168.1.10:7827", null, null).unusableReason().contains("192.168.1.10"),
                "要说清是哪个地址不行, 否则使用者不知道该换哪一处");
    }

    @Test
    @DisplayName("IP 地址进来时，登记与登录两条路都当场回一句人话")
    void endpointsExplainWhyItCannotWork() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/config/api/auth/passkey");
        request.addHeader(HttpHeaders.HOST, "192.168.1.10:7827");
        request.setRemoteAddr(CLIENT_IP);

        JSONObject register = controller.registerOptions(request);
        assertFalse(register.getBooleanValue("success"));
        assertTrue(register.getString("message").contains("域名"));

        JSONObject login = controller.loginOptions(request);
        assertFalse(login.getBooleanValue("success"));
        assertTrue(login.getString("message").contains("域名"));
    }
}
