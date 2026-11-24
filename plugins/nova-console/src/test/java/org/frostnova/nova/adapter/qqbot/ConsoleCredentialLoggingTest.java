package org.frostnova.nova.adapter.qqbot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.assistant.AssistantClient;
import org.frostnova.nova.core.assistant.AssistantController;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.ConfigUiAuthController;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.config.ui.ConfigUiOidcController;
import org.frostnova.nova.core.config.ui.ConfigUiPasskeyController;
import org.frostnova.nova.core.config.ui.ConsoleCredentialLogAdvice;
import org.frostnova.nova.core.config.ui.ConsoleCredentialLogFilter;
import org.frostnova.nova.core.config.ui.ConfigurationMetadataService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcClient;
import org.frostnova.nova.core.config.ui.auth.passkey.PasskeyService;
import org.frostnova.nova.core.web.ReadOnlyTokenController;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class ConsoleCredentialLoggingTest {
    private static final String OFFICIAL_SECRET_PREFIX = "fixture-official-secret";
    private static final String OFFICIAL_SECRET = OFFICIAL_SECRET_PREFIX + "-extended".repeat(16);
    private static final String MODEL_KEY = "fixture-model-credential";
    private static final String PUBLIC_APP_ID = "12349876";
    private static final String PUBLIC_QUERY = "fixture-public-query";
    private static final String PUBLIC_SETTING = "novabot.core.event-stream.require-token";

    @Test
    void submittedCredentialsStayOutOfDebugAndTraceLogs() throws Exception {
        var properties = new NovaCoreProperties();
        var auth = mock(ConfigUiAuthService.class);
        when(auth.oidcEnabled()).thenReturn(true);
        when(auth.isEnabled()).thenReturn(true);
        when(auth.login(any(), anyString(), anyString()))
                .thenReturn(new ConfigUiAuthService.LoginResult(null, "请求无效", Duration.ZERO, true));
        when(auth.remainingLockout(anyString())).thenReturn(Duration.ZERO);
        when(auth.checkCurrentPassword(any(), nullable(String.class), anyString())).thenReturn(
                new ConfigUiAuthService.CurrentPasswordCheck(ConfigUiAuthService.CurrentPasswordVerdict.MISMATCH, 2));
        var assistant = mock(AssistantClient.class);
        var credentials = mock(QqBotCredentials.class);
        var metadata = mock(ConfigurationMetadataService.class);
        var config = mock(ConfigUiController.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(config, "metadataService", metadata);
        ReflectionTestUtils.setField(config, "connectionTesters", mock(ObjectProvider.class));
        ReflectionTestUtils.setField(config, "aliasContributors", mock(ObjectProvider.class));
        when(metadata.getKnownTypes()).thenReturn(Map.of(PUBLIC_SETTING, "boolean"));
        var passkeys = mock(PasskeyService.class);
        when(passkeys.loginVerify(any(), any(), any()))
                .thenReturn(new PasskeyService.PasskeyLogin(null, "请求无效"));
        var mvc = MockMvcBuilders.standaloneSetup(
                new QqBotConsoleController(credentials, null, null, null, null),
                new AssistantController(assistant, null, null),
                new ConfigUiAuthController(auth, null, properties),
                new ConfigUiPasskeyController(passkeys, properties, auth),
                new ConfigUiOidcController(mock(OidcClient.class), auth, null, properties),
                new ReadOnlyTokenController(mock(ObjectProvider.class), null),
                config).setControllerAdvice(new ConsoleCredentialLogAdvice(metadata)).build();
        mvc.getDispatcherServlet().setEnableLoggingRequestDetails(true);

        try (var logging = new AnnotationConfigApplicationContext(ConsoleCredentialLogFilter.class)) {
            var leaks = new LinkedHashSet<String>();
            for (Level level : List.of(Level.TRACE, Level.DEBUG)) {
                try (var logs = new CapturedLogs(level)) {
                    for (var submission : credentialSubmissions()) {
                        logs.appender.list.clear();
                        var response = mvc.perform(submission.request()
                                .header("Host", "localhost")
                                .requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))
                                .requestAttr(ConfigUiAccess.FRESH_LOCAL, true)).andReturn();
                        assertEquals(submission.status(), response.getResponse().getStatus(), submission.path());
                        logs.appender.list.stream()
                                .filter(event -> submission.values().stream().anyMatch(rendered(event)::contains))
                                .map(event -> level + " " + submission.path() + " (" + event.getLoggerName() + ")")
                                .forEach(leaks::add);
                        if (submission.visible() != null) {
                            assertTrue(logs.appender.list.stream().anyMatch(event ->
                                    rendered(event).contains(submission.visible())), submission.path() + " public log value");
                        }
                    }
                }
            }
            verify(credentials, times(2)).save(PUBLIC_APP_ID, OFFICIAL_SECRET);
            verify(assistant, times(2)).validateAndSaveKey(ConfigUiAccount.LOCAL_ID, MODEL_KEY);
            assertTrue(leaks.isEmpty(), () -> "Credentials reached logs for " + leaks);
        }
    }

    /** Keep all of the credential submissions exercised by this test in this list. */
    private static List<Submission> credentialSubmissions() {
        return List.of(
                new Submission("/config/api/qqbot/credentials", post("/config/api/qqbot/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSONObject.of("appId", PUBLIC_APP_ID, "appSecret", OFFICIAL_SECRET).toJSONString()),
                        200, List.of(OFFICIAL_SECRET_PREFIX, OFFICIAL_SECRET), PUBLIC_APP_ID),
                new Submission("/config/api/qqbot/credentials", post("/config/api/qqbot/credentials")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"appSecret\":fixtureMalformedSecret}"),
                        400, List.of("fixtureMalformedSecret"), null),
                json("/config/api/assistant/key", 200, new JSONObject(), "key", MODEL_KEY),
                json("/config/api/values", 400, JSONObject.of(PUBLIC_SETTING, "true"),
                        "novabot.adapter.onebot.napcat.token", "fixture-webui-token",
                        "novabot.adapter.onebot.napcat.to_ken", "fixture-relaxed-webui-token",
                        "novabot.adapter.onebot.napcat.totp-secret", "fixture-webui-seed",
                        "spring.mail.password", "fixture-mail-password",
                        "novabot.core.config-ui.oidc.client-secret", "fixture-oidc-client-secret",
                        "novabot.core.alert.webhook-url", "https://fixture.invalid/fixture-hook",
                        "novabot.core.alert.webhook-headers.Authorization", "fixture-hook-auth",
                        "settings.apiKey", "fixture-future-api-key"),
                json("/config/api/setup/test-bot", 200, new JSONObject(), "httpToken", "fixture-test-bot-token"),
                json("/config/api/setup/bot", 200, new JSONObject(),
                        "httpToken", "fixture-bot-http-token", "websocketToken", "fixture-bot-socket-token"),
                json("/config/api/auth/login", 401, new JSONObject(),
                        "password", "fixture-login-password", "code", "618453"),
                json("/config/api/auth/password/set", 400, new JSONObject(), "next", "fixture-set-password"),
                json("/config/api/auth/password/change", 400, new JSONObject(),
                        "current", "fixture-old-password", "next", "fixture-new-password"),
                json("/config/api/auth/password/reset", 403, new JSONObject(), "next", "fixture-reset-password"),
                json("/config/api/auth/totp/enroll", 400, new JSONObject(),
                        "current", "fixture-enroll-password", "code", "946123"),
                json("/config/api/auth/totp/disable", 400, new JSONObject(),
                        "current", "fixture-disable-password", "code", "710958"),
                json("/config/api/auth/passkey/register/options", 400, new JSONObject(),
                        "current", "fixture-passkey-password"),
                json("/config/api/auth/passkey/register/verify", 200, JSONObject.of("name", "fixture-passkey-name"),
                        "response", JSONObject.of("attestationObject", "fixture-passkey-attestation")),
                json("/config/api/auth/passkey/login/verify", 401, new JSONObject(),
                        "response", JSONObject.of("signature", "fixture-passkey-assertion")),
                json("/nova/readonly-token", 400, JSONObject.of("label", "fixture-panel"),
                        "password", "fixture-readonly-password", "code", "493157"),
                new Submission("/config/api/oidc/callback", get("/config/api/oidc/callback")
                        .queryParam("code", "fixture-authorization-code")
                        .queryParam("state", "fixture-browser-state").queryParam("view", PUBLIC_QUERY)
                        .header("Authorization", "Bearer fixture-header-token")
                        .header("Cookie", "console=fixture-cookie")
                        .header("X-CSRF-Token", "fixture-csrf-token"),
                        302, List.of("fixture-authorization-code", "fixture-browser-state", "fixture-header-token",
                                "fixture-cookie", "fixture-csrf-token"), PUBLIC_QUERY),
                new Submission("/config/api/oidc/backchannel-logout", post("/config/api/oidc/backchannel-logout")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("logout_token", "fixture-logout-token"),
                        200, List.of("fixture-logout-token"), null),
                new Submission("/config/api/assistant/status", get("/config/api/assistant/status")
                        .queryParam("token", "fixture-console-access-token"),
                        200, List.of("fixture-console-access-token"), null));
    }

    private static Submission json(String path, int status, JSONObject body, Object... fields) {
        List<String> secrets = new ArrayList<>();
        for (int i = 0; i < fields.length; i += 2) {
            Object value = fields[i + 1];
            body.put((String) fields[i], value);
            if (value instanceof JSONObject nested) {
                nested.values().forEach(item -> secrets.add(item.toString()));
            } else {
                secrets.add(value.toString());
            }
        }
        return new Submission(path, post(path).contentType(MediaType.APPLICATION_JSON).content(body.toJSONString()),
                status, secrets, body.containsKey(PUBLIC_SETTING) ? PUBLIC_SETTING + "=true" : null);
    }

    private static String rendered(ILoggingEvent event) {
        var throwable = event.getThrowableProxy();
        return event.getFormattedMessage() + (throwable == null ? "" : ThrowableProxyUtil.asString(throwable));
    }

    private record Submission(String path, MockHttpServletRequestBuilder request, int status, List<String> values,
                              String visible) {}

    private static final class CapturedLogs implements AutoCloseable {
        private final Map<Logger, Level> previous = new LinkedHashMap<>();
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private CapturedLogs(Level level) {
            appender.start();
            for (String name : List.of(Logger.ROOT_LOGGER_NAME, "org.springframework", "org.springframework.web",
                    "org.frostnova.nova", "NetworkLogger", "EventLogger")) {
                Logger logger = (Logger) LoggerFactory.getLogger(name);
                previous.put(logger, logger.getLevel());
                logger.setLevel(level);
                logger.addAppender(appender);
            }
        }

        @Override
        public void close() {
            previous.forEach((logger, level) -> {
                logger.detachAppender(appender);
                logger.setLevel(level);
            });
            appender.stop();
        }
    }
}
