package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSession;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSessionStore;
import com.starlwr.bot.core.config.ui.auth.LoginThrottle;
import com.starlwr.bot.core.config.ui.auth.TotpGenerator;
import com.starlwr.bot.core.service.PushTemplateDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 通用配置保存不得充当改口令／关二次验证／打开启动令牌通道的旁路
 * <p>
 * 专用改口令要旧口令，关掉二次验证要当前动态码，成功后还要注销其余会话。
 * 通用写口若直接改这几项，一枚已登录会话就能换掉口令、卸掉二次验证，或打开一条绕过二者的直进通道。
 */
@DisplayName("通用配置保存不得在无复核时改口令或关掉二次验证")
class GenericConfigSaveMustNotChangeAuthWithoutReauthTest {
    private static final String OLD_PASSWORD = "correct horse battery staple";

    private static final String NEW_PASSWORD = "another horse another staple";

    private static final String SECRET = "JBSWY3DPEHPK3PXP";

    private static final String TEMPLATE = """
            starbot:
              core:
                config-ui:
                  enabled: true
                  auth:
                    password: %s
                    totp: true
                    totp-secret: %s
            """.formatted(OLD_PASSWORD, SECRET);

    @TempDir
    Path dir;

    private Path config;

    private ConfigurationFileService fileService;

    private ConfigUiAuthService authService;

    private ConfigUiController controller;

    private ConfigUiAuthController dedicated;

    @BeforeEach
    void setUp() throws IOException {
        config = dir.resolve("application.yml");
        Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);
        fileService = new ConfigurationFileService(config);

        StarBotCoreProperties properties = new StarBotCoreProperties();
        StarBotCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        auth.setPassword(OLD_PASSWORD);
        auth.setTotp(true);
        auth.setTotpSecret(SECRET);

        authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), fileService);
        dedicated = new ConfigUiAuthController(authService, fileService, properties);
        controller = controller(properties);
    }

    @SuppressWarnings("unchecked")
    private ConfigUiController controller(StarBotCoreProperties properties) {
        ConfigurationMetadataService metadata = mock(ConfigurationMetadataService.class);
        when(metadata.getKnownTypes()).thenReturn(Map.of(
                ConfigUiAuthService.PASSWORD_PROPERTY, "java.lang.String",
                "starbot.core.config-ui.auth.totp", "java.lang.Boolean",
                "starbot.core.config-ui.auth.operator-token", "java.lang.Boolean"));

        RuntimeConfigurationApplier applier = RuntimeConfigurationApplier.bench(properties).build();

        return new ConfigUiController(
                metadata,
                fileService,
                properties,
                mock(com.starlwr.bot.core.datasource.AbstractDataSource.class),
                mock(ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(com.starlwr.bot.core.service.StarBotSenderService.class),
                mock(com.starlwr.bot.core.sender.StarBotMessageSender.class),
                mock(ObjectProvider.class),
                mock(com.starlwr.bot.core.health.PushActivityRecorder.class),
                mock(com.starlwr.bot.core.service.StarBotEventHandlerService.class),
                mock(com.starlwr.bot.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationEffectResolver(mock(ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(ApplicationContext.class)),
                applier,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(com.starlwr.bot.core.protocol.EventStreamTokenService.class),
                mock(ObjectProvider.class),
                mock(com.starlwr.bot.core.sender.PushGate.class),
                mock(com.starlwr.bot.core.service.LiveDataService.class),
                mock(com.starlwr.bot.core.timeline.TimelineStore.class),
                authService,
                new PushTemplateDefaults(properties),
                mock(UpdateCheckService.class));
    }

    private String totpNow() {
        return TotpGenerator.currentCode(SECRET, Instant.now());
    }

    private ConfigUiSession login() {
        ConfigUiAuthService.LoginResult result = authService.login(OLD_PASSWORD.toCharArray(), totpNow(), "127.0.0.1");
        assertTrue(result.success(), "台面：旧口令加动态码应能登入, " + result.message());
        return result.session();
    }

    @Test
    @DisplayName("对照：专用改口令口没有旧口令必须拒")
    void dedicatedPasswordChangeStillRequiresTheCurrentPassword() {
        login();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/config/api/auth/password/change");
        request.setRemoteAddr("127.0.0.1");
        JSONObject body = new JSONObject();
        body.put("next", NEW_PASSWORD);

        ResponseEntity<JSONObject> response = dedicated.changePassword(body, request);

        assertFalse(Boolean.TRUE.equals(response.getBody().getBoolean("success")),
                "专用口不该在没给旧口令时改掉");
        assertTrue(authService.matchesPassword(OLD_PASSWORD.toCharArray()), "旧口令仍应有效");
    }

    @Test
    @DisplayName("通用保存不得只凭已有会话就换掉登录口令；若已改掉则旧会话必须失效")
    void genericSaveMustNotChangePasswordWithoutCurrentPassword() {
        ConfigUiSession session = login();

        Map<String, String> body = new LinkedHashMap<>();
        body.put(ConfigUiAuthService.PASSWORD_PROPERTY, NEW_PASSWORD);
        JSONObject result = controller.save(body);

        boolean newPasswordTook = authService.matchesPassword(NEW_PASSWORD.toCharArray());
        boolean oldSessionAlive = authService.validate(session.getId()).isPresent();

        assertFalse(result.getBooleanValue("success") && newPasswordTook,
                "通用写口不该在没有旧口令复核时改口令, 实际 success=" + result.getBooleanValue("success")
                        + " message=" + result.getString("message")
                        + " newPasswordTook=" + newPasswordTook
                        + " oldSessionAlive=" + oldSessionAlive);
        if (newPasswordTook) {
            assertFalse(oldSessionAlive,
                    "口令已被通用写口改掉时旧会话必须失效, session=" + session.getId());
        }
    }

    @Test
    @DisplayName("通用保存不得在没有动态码复核时关掉二次验证，且不得留下旧会话")
    void genericSaveMustNotDisableTotpWithoutCode() {
        ConfigUiSession session = login();

        Map<String, String> body = new LinkedHashMap<>();
        body.put("starbot.core.config-ui.auth.totp", "false");
        JSONObject result = controller.save(body);

        assertTrue(authService.totpRequired(),
                "通用写口不该在没有动态码复核时关掉二次验证, 实际 success="
                        + result.getBooleanValue("success") + " message=" + result.getString("message")
                        + " totpRequired=" + authService.totpRequired()
                        + " oldSessionAlive=" + authService.validate(session.getId()).isPresent());
    }

    @Test
    @DisplayName("🔴 通用保存带认证键必须整单拒，配置文件一个字不动")
    void genericSaveMustRejectAuthKeysAndLeaveTheFileUntouched() throws IOException {
        login();
        String before = Files.readString(config, StandardCharsets.UTF_8);

        Map<String, String> body = new LinkedHashMap<>();
        body.put(ConfigUiAuthService.PASSWORD_PROPERTY, NEW_PASSWORD);
        JSONObject result = controller.save(body);

        assertAll(
                () -> assertFalse(result.getBooleanValue("success"),
                        "success 必须为 false：放行就会把口令明文写进配置文件, 重启即换门, 实际 message="
                                + result.getString("message")),
                () -> assertTrue(result.getString("message") != null
                                && result.getString("message").contains("登录与安全"),
                        "应把人领去专用口, 实际 message=" + result.getString("message")),
                () -> assertEquals(before, Files.readString(config, StandardCharsets.UTF_8),
                        "yml 含明文口令等于门已经换了, 拒了配置文件就该逐字同"));
    }

    @Test
    @DisplayName("🔴 通用保存带启动令牌通道开关必须整单拒，配置文件一个字不动")
    void genericSaveMustRejectOperatorTokenAndLeaveTheFileUntouched() throws IOException {
        login();
        String before = Files.readString(config, StandardCharsets.UTF_8);

        Map<String, String> body = new LinkedHashMap<>();
        body.put(ConfigUiAuthService.OPERATOR_TOKEN_PROPERTY, "true");
        JSONObject result = controller.save(body);

        assertAll(
                () -> assertFalse(result.getBooleanValue("success"),
                        "success 必须为 false：放行就会把忘记口令的启动令牌通道打开, 重启后打印一个绕过二次验证的直进地址, 实际 message="
                                + result.getString("message")),
                () -> assertEquals(before, Files.readString(config, StandardCharsets.UTF_8),
                        "yml 含明文口令等于门已经换了, 拒了配置文件就该逐字同"));
    }

}
