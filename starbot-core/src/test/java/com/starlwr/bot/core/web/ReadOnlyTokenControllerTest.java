package com.starlwr.bot.core.web;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSessionStore;
import com.starlwr.bot.core.config.ui.auth.LoginThrottle;
import com.starlwr.bot.core.config.ui.auth.TotpGenerator;
import com.starlwr.bot.core.service.EventStreamTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 代签发只读口令端点（跨项目契约 §五①）
 * <p>
 * 这一组守的大多不是「功能能用」，而是<b>契约里那几位不能被实现悄悄改掉</b>：
 * 响应里不许有 Cookie、四种失败要分得开、口令过期字段必须发出去。
 * 它们一旦错了，功能上完全看不出来。
 */
@DisplayName("代签发只读口令端点")
class ReadOnlyTokenControllerTest {
    private static final String PASSWORD = "correct horse battery staple";

    private static final String IP = "1.2.3.4";

    @TempDir
    Path dir;

    private EventStreamTokenService tokens;

    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        tokens = new EventStreamTokenService(properties.getLive());
    }

    /**
     * @param password 配置里的登录口令，空串表示没配
     * @param totpSecret 已绑定的验证器密钥，空串表示没绑
     */
    private ReadOnlyTokenController controller(String password, String totpSecret) {
        StarBotCoreProperties.ConfigUi.Auth auth = new StarBotCoreProperties.ConfigUi.Auth();
        auth.setPassword(password);
        auth.setTotpSecret(totpSecret);
        auth.setTotp(!totpSecret.isEmpty());

        throttle = new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15));
        ConfigUiAuthService service = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)), throttle, null);

        @SuppressWarnings("unchecked")
        ObjectProvider<ConfigUiAuthService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);

        return new ReadOnlyTokenController(provider, tokens);
    }

    private ResponseEntity<String> post(ReadOnlyTokenController controller, JSONObject body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ReadOnlyTokenController.PATH);
        request.setRemoteAddr(IP);
        return controller.issue(body, request);
    }

    private JSONObject body(String password, String label) {
        JSONObject body = new JSONObject();
        body.put("password", password);
        if (label != null) {
            body.put("label", label);
        }
        return body;
    }

    @Test
    @DisplayName("尺子先过阳性对照：密码对就能换到口令")
    void issuesTokenForCorrectPassword() {
        ResponseEntity<String> response = post(controller(PASSWORD, ""), body(PASSWORD, "VRDash 面板"));

        assertEquals(200, response.getStatusCode().value(),
                "阳性对照：这一条不过，下面那些「拒绝」就说明不了任何事");

        JSONObject result = JSONObject.parseObject(response.getBody());
        assertNotNull(result.getString("token"));
        assertEquals(1, tokens.list().size());
        assertEquals("VRDash 面板", tokens.list().get(0).label(), "签给谁要原样留着，否则不知道该撤哪一把");
    }

    @Test
    @DisplayName("🔴 响应里绝不能有 Set-Cookie")
    void neverIssuesASession() {
        ResponseEntity<String> response = post(controller(PASSWORD, ""), body(PASSWORD, "VRDash 面板"));

        // 有 Cookie 就意味着只读通道被升级成了完整控制台权限，
        // 而这种错在功能上完全看不出来——口令照样能用。对侧也会主动检查这一位
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE),
                "代签发走的必须是「只校验不签会话」那条路");
    }

    @Test
    @DisplayName("🔴 expiresAt 必须作为一个值为 null 的字段出现在响应体里")
    void keepsExpiresAtFieldEvenWhenNull() {
        ResponseEntity<String> response = post(controller(PASSWORD, ""), body(PASSWORD, null));

        // 默认序列化会把 null 字段整个丢掉。字段缺席与「值为 null」对客户端不是一回事：
        // 对侧那条「口令过期了」的分支靠它活着，字段没了那条分支就等着主播来撞第一次
        assertTrue(response.getBody().contains("\"expiresAt\":null"),
                "契约要的是字段在、值为 null，不是字段消失");
    }

    @Test
    @DisplayName("不填 label 时兜底，不是报错")
    void fallsBackToDefaultLabel() {
        post(controller(PASSWORD, ""), body(PASSWORD, null));

        // 对侧会给「VRDash 面板」这类中性默认值，正常走不到这里；
        // 兜底是对侧改错时的安全网——没有标签就只能一次全撤
        assertEquals(ReadOnlyTokenController.DEFAULT_LABEL, tokens.list().get(0).label());
    }

    @Test
    @DisplayName("密码不对回 bad_credentials，且不留下半把口令")
    void rejectsWrongPassword() {
        ResponseEntity<String> response = post(controller(PASSWORD, ""), body("wrong", "VRDash 面板"));

        assertEquals(401, response.getStatusCode().value());
        assertEquals("bad_credentials", JSONObject.parseObject(response.getBody()).getString("reason"));
        assertTrue(tokens.list().isEmpty(), "拒绝之后不该留下任何痕迹");
    }

    @Test
    @DisplayName("没配登录口令时这条路必须不可用")
    void refusesWhenPasswordNotConfigured() {
        ResponseEntity<String> response = post(controller("", ""), body("whatever", "VRDash 面板"));

        // 没有校验对象却照签，它就是个人人可取的口令水龙头
        assertEquals(400, response.getStatusCode().value());
        assertEquals("auth_disabled", JSONObject.parseObject(response.getBody()).getString("reason"));
        assertTrue(tokens.list().isEmpty());
    }

    @Test
    @DisplayName("控制台整个没启用时同样回 auth_disabled，而不是 404")
    void refusesWhenConsoleBeanAbsent() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ConfigUiAuthService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        ResponseEntity<String> response =
                post(new ReadOnlyTokenController(provider, tokens), body(PASSWORD, "VRDash 面板"));

        // 404 那一位在契约里已经归给「反代没放行这条路径」了，不能再拿来表达第二件事
        assertEquals(400, response.getStatusCode().value());
        assertEquals("auth_disabled", JSONObject.parseObject(response.getBody()).getString("reason"));
    }

    @Test
    @DisplayName("🔴 锁定必须与「密码不对」分得开")
    void reportsLockoutSeparately() {
        ReadOnlyTokenController controller = controller(PASSWORD, "");
        StarBotCoreProperties.ConfigUi.Auth defaults = new StarBotCoreProperties.ConfigUi.Auth();

        for (int i = 0; i < defaults.getMaxFailures(); i++) {
            post(controller, body("wrong", "VRDash 面板"));
        }

        // 锁定期内，即使密码是对的也会被拒——此时若回 bad_credentials，
        // 主播会去重置一个根本没问题的密码
        ResponseEntity<String> response = post(controller, body(PASSWORD, "VRDash 面板"));

        assertEquals(429, response.getStatusCode().value());
        JSONObject result = JSONObject.parseObject(response.getBody());
        assertEquals("locked_out", result.getString("reason"));
        assertTrue(result.getLongValue("retryAfterSeconds") > 0, "得告诉人还要等多久，否则只能瞎试");
        assertTrue(tokens.list().isEmpty());
    }

    @Test
    @DisplayName("并发闸门拦下时回 busy，与锁定不是一回事")
    void reportsBusySeparately() {
        ReadOnlyTokenController controller = controller(PASSWORD, "");

        // 把校验名额占满：这是瞬时拥塞，重试就好，与「你被锁了」要分开说
        assertTrue(throttle.tryAcquireSlot());
        assertTrue(throttle.tryAcquireSlot());

        ResponseEntity<String> response = post(controller, body(PASSWORD, "VRDash 面板"));

        assertEquals(503, response.getStatusCode().value());
        assertEquals("busy", JSONObject.parseObject(response.getBody()).getString("reason"));
        assertNull(JSONObject.parseObject(response.getBody()).get("retryAfterSeconds"),
                "只有 locked_out 那一支才带等待秒数");
    }

    @Test
    @DisplayName("🔴 开了两步验证而不传验证码，必须拒——对照组是同一个请求在没开时能过")
    void missingCodeIsRejectedOnlyWhenTotpIsOn() {
        JSONObject sameRequest = body(PASSWORD, "VRDash 面板");

        // 对照组：没开两步验证时，同一个「不带 code」的请求是能过的
        assertEquals(200, post(controller(PASSWORD, ""), sameRequest).getStatusCode().value(),
                "对照组不通过的话，下面那条拒绝就可能是别的原因造成的");

        // 实验组：只改「有没有绑验证器」这一个变量
        ResponseEntity<String> response =
                post(controller(PASSWORD, TotpGenerator.generateSecret()), body(PASSWORD, "VRDash 面板"));

        assertEquals(401, response.getStatusCode().value());
        assertEquals("bad_credentials", JSONObject.parseObject(response.getBody()).getString("reason"),
                "验证码缺席与密码错回同一句话——分开说等于告诉爆破者密码那一半已经对了");
    }

    @Test
    @DisplayName("签发写不进磁盘时回人话错误，不把明文交出去")
    void issueWriteFailureIsAHumanError() throws Exception {
        Path blocker = dir.resolve("not-a-directory");
        Files.writeString(blocker, "occupied");

        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(blocker.resolve("data.json").toString());
        EventStreamTokenService brokenTokens = new EventStreamTokenService(properties.getLive());

        StarBotCoreProperties.ConfigUi.Auth auth = new StarBotCoreProperties.ConfigUi.Auth();
        auth.setPassword(PASSWORD);
        ConfigUiAuthService service = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), null);
        @SuppressWarnings("unchecked")
        ObjectProvider<ConfigUiAuthService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);

        ReadOnlyTokenController broken = new ReadOnlyTokenController(provider, brokenTokens);
        ResponseEntity<String> response = post(broken, body(PASSWORD, "面板-丁"));

        assertEquals(500, response.getStatusCode().value(),
                "写盘失败应当回结构化错误，而不是未捕获的服务器错误");
        JSONObject result = JSONObject.parseObject(response.getBody());
        assertNull(result.get("token"), "写盘失败不该交出明文");
        assertTrue(result.getString("message").contains("磁盘"),
                () -> "应当用人话说明写盘失败, 实际: " + result.getString("message"));
    }
}
