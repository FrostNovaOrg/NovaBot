package org.frostnova.nova.core.config.ui.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.Cookie;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiAuthController;
import org.frostnova.nova.core.config.ui.ConfigUiSecurityFilter;
import org.frostnova.nova.core.config.ui.ConfigurationFileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TotpStateContinuityTest {
    private static final String PASSWORD = "sample-password";
    private static final String HASH = LowIterationPasswordHash.of(PASSWORD);
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);

    @TempDir
    Path dir;

    private Path statePath() {
        return dir.resolve("application.yml.totp-state");
    }

    private ConfigUiAuthService service(String secret) {
        var properties = new NovaCoreProperties.ConfigUi.Auth();
        properties.setPassword(HASH);
        properties.setTotp(true);
        properties.setTotpSecret(secret);
        var files = mock(ConfigurationFileService.class);
        when(files.describeConfigPath()).thenReturn(dir.resolve("application.yml").toString());
        return new ConfigUiAuthService(properties,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(20, Duration.ofMinutes(15)), files, clock::get);
    }

    private String code(long seconds) {
        return TotpGenerator.currentCode(SECRET, NOW.plusSeconds(seconds));
    }

    private boolean login(ConfigUiAuthService service, String code) {
        return service.login(PASSWORD.toCharArray(), code, "192.0.2.1").success();
    }

    @Test
    void normalInput() {
        var auth = service(SECRET.toLowerCase(Locale.ROOT));
        auth.start();
        clock.set(NOW.plusSeconds(30));
        String value = code(30);
        assertTrue(login(auth, value.substring(0, 3) + " " + value.substring(3)));
        clock.set(NOW.plusSeconds(60));
        assertTrue(auth.checkCredentials(PASSWORD.toCharArray(), code(60), "192.0.2.1").ok());
    }

    @Test
    void retry() {
        var auth = service(SECRET);
        auth.start();
        assertFalse(auth.login("other-password".toCharArray(), code(30), "192.0.2.1").success());
        assertFalse(login(auth, ""));
        assertTrue(login(auth, code(30)));
    }

    @Test
    void restartNormal() {
        var auth = service(SECRET);
        auth.start();
        assertTrue(login(auth, code(30)));
        auth.stop();
        clock.set(NOW.plusSeconds(30));
        var restarted = service(SECRET);
        restarted.start();
        assertTrue(login(restarted, code(60)));
        clock.set(NOW.plusSeconds(90));
        assertTrue(login(restarted, code(90)));
    }

    @Test
    void case01() {
        var auth = service(SECRET);
        auth.start();
        assertTrue(login(auth, code(30)));
        assertFalse(login(auth, code(30)));
        auth.stop();
        var restarted = service(SECRET);
        restarted.start();
        assertFalse(login(restarted, code(30)));
    }

    @Test
    void case02() throws Exception {
        Files.createDirectory(statePath());
        var auth = service(SECRET);
        auth.start();
        assertFalse(login(auth, code(30)));
        Files.delete(statePath());
        assertTrue(login(auth, code(30)));
        assertFalse(login(service(SECRET), code(30)));
    }

    @Test
    void case03() throws Exception {
        service(SECRET).start();
        String saved = Files.exists(statePath()) ? Files.readString(statePath()) : "";
        Files.writeString(statePath(), "sample");
        var auth = service(SECRET);
        auth.start();
        assertFalse(login(auth, code(30)));
        Files.writeString(statePath(), saved);
        assertTrue(login(auth, code(30)));
    }

    @Test
    void case04() {
        var auth = service(null);
        var session = auth.issueForPassword("192.0.2.1");
        auth.activateTotp(session, SECRET, TotpGenerator.currentStep(NOW.plusSeconds(30)));
        var restarted = service(SECRET);
        restarted.start();
        assertFalse(login(restarted, code(30)));
        clock.set(NOW.plusSeconds(30));
        assertTrue(login(restarted, code(60)));
    }

    @Test
    void case05() {
        assertTrue(login(service(SECRET), code(30)));
        var restarted = service("  " + SECRET.toLowerCase(Locale.ROOT) + "==  ");
        restarted.start();
        assertFalse(login(restarted, code(30)));
    }

    @Test
    void case06() throws Exception {
        var auth = service(SECRET);
        auth.start();
        Files.deleteIfExists(statePath());
        Files.createDirectory(statePath());
        assertFalse(login(auth, code(30)));
        Files.delete(statePath());
        assertTrue(login(auth, code(30)));
        assertFalse(login(service(SECRET), code(30)));
    }

    @Test
    void case07() throws Exception {
        Files.createDirectory(statePath());
        var auth = service(null);
        var session = auth.issueForPassword("192.0.2.1");
        assertThrows(IllegalStateException.class, () -> auth.activateTotp(session, SECRET,
                TotpGenerator.currentStep(NOW.plusSeconds(30))));
        assertTrue(auth.canEnrollTotp());
        Files.delete(statePath());
        auth.activateTotp(session, SECRET, TotpGenerator.currentStep(NOW.plusSeconds(30)));
        assertTrue(auth.totpRequired());
        assertFalse(login(service(SECRET), code(30)));
    }
    @Test
    void case08() throws Exception {
        var auth = service(null);
        var session = auth.issueForPassword("192.0.2.1");
        String secret = auth.issuePendingSecret(session);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.1");
        request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId()));
        var body = new JSONObject();
        body.put("current", PASSWORD);
        body.put("code", TotpGenerator.currentCode(secret, NOW.plusSeconds(30)));
        var files = mock(ConfigurationFileService.class);
        var controller = new ConfigUiAuthController(auth, files, new NovaCoreProperties());
        Files.createDirectory(statePath());
        var first = controller.totpEnroll(body, request);
        assertEquals(500, first.getStatusCode().value());
        assertFalse(first.getBody().getBooleanValue("success"));
        verify(files, never()).write(anyMap());
        Files.delete(statePath());
        var second = controller.totpEnroll(body, request);
        assertEquals(200, second.getStatusCode().value());
        assertTrue(second.getBody().getBooleanValue("success"));
        verify(files).write(anyMap());
    }

    @Test
    void case09() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ConfigUiAuthService.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        PrintStream original = System.err;
        var output = new ByteArrayOutputStream();
        try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            service(SECRET).start();
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            Files.deleteIfExists(statePath());
            Files.createDirectory(statePath());
            var auth = service(SECRET);
            auth.start();
            String first = output.toString(StandardCharsets.UTF_8);
            assertTrue(first.contains("可读写"));
            assertTrue(first.contains(statePath().toString()));
            assertEquals(1, events.list.stream().filter(event ->
                    event.getFormattedMessage().contains(statePath().toString())).count());
            assertFalse(login(auth, code(30)));
            assertEquals(first, output.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(original);
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void case10() throws Exception {
        var auth = service(SECRET);
        auth.start();
        assertTrue(Files.isRegularFile(statePath()));
        assertFalse(Files.readString(statePath()).contains(SECRET));
        assertFalse(login(auth, code(0)));
        assertTrue(login(auth, code(30)));
    }

    @Test
    void case11() {
        var auth = service(SECRET);
        assertTrue(auth.verifyCurrentCode(code(30)));
        var restarted = service(SECRET);
        restarted.start();
        assertFalse(restarted.verifyCurrentCode(code(30)));
    }

    @Test
    void case12() {
        var auth = service(SECRET);
        auth.start();
        assertTrue(login(auth, code(30)));
        clock.set(NOW.plusSeconds(300));
        var restarted = service(SECRET);
        restarted.start();
        assertFalse(login(restarted, code(300)));
        assertTrue(login(restarted, code(330)));
    }

    @Test
    void normalDisabled() {
        var auth = service(null);
        auth.start();
        assertFalse(Files.exists(statePath()));
        assertTrue(login(auth, null));
        auth.stop();
        var restarted = service(null);
        restarted.start();
        assertTrue(login(restarted, null));
        assertFalse(Files.exists(statePath()));
    }

    @Test
    void case13() throws Exception {
        service(SECRET).start();
        String saved = Files.exists(statePath()) ? Files.readString(statePath()) : "";
        Files.writeString(statePath(), saved + "sample");
        var auth = service(SECRET);
        auth.start();
        assertFalse(login(auth, code(30)));
    }

    @Test
    void normalReplacement() {
        String other = "JBSWY3DPEHPK3PXP";
        var first = service(other);
        first.start();
        assertTrue(login(first, TotpGenerator.currentCode(other, NOW.plusSeconds(30))));
        first.stop();
        var second = service(SECRET);
        second.start();
        assertTrue(login(second, code(30)));
    }

    @Test
    void normalPassword() {
        var properties = new NovaCoreProperties.ConfigUi.Auth();
        properties.setPassword(HASH);
        properties.setTotp(false);
        var files = mock(ConfigurationFileService.class);
        var auth = new ConfigUiAuthService(properties,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(20, Duration.ofMinutes(15)), files, clock::get);
        auth.start();
        assertTrue(login(auth, null));
        verify(files, never()).describeConfigPath();
    }
}
