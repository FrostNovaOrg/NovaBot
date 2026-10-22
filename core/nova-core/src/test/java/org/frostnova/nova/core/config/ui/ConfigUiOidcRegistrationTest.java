package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.*;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigUiOidcRegistrationTest extends OidcTestSupport {
    @Test
    void registrationPromptsCreateAndOrdinaryLoginKeepsOriginalUrl() throws Exception {
        provider.prompts = List.of("login", "create");
        var auth = mock(ConfigUiAuthService.class);
        when(auth.oidcEnabled()).thenReturn(true);
        var controller = new ConfigUiOidcController(client, auth, null, new NovaCoreProperties());
        var ordinary = new MockHttpServletResponse();
        controller.login(request(), ordinary);
        String url = ordinary.getRedirectedUrl();
        var query = query(java.net.URI.create(url).getRawQuery());
        assertEquals(ISSUER + "/authorize?response_type=code&client_id=console-client"
                + "&redirect_uri=https%3A%2F%2Fconsole.example.com%2Fapp%2Fconfig%2Fapi%2Foidc%2Fcallback"
                + "&scope=openid%20profile%20email%20offline_access&state=" + query.get("state")
                + "&nonce=" + query.get("nonce") + "&code_challenge=" + query.get("code_challenge")
                + "&code_challenge_method=S256", url);
        var request = request();
        request.setParameter("create", "1");
        var registration = new MockHttpServletResponse();
        controller.login(request, registration);
        assertEquals("create", query(java.net.URI.create(registration.getRedirectedUrl()).getRawQuery()).get("prompt"));
    }
    @Test
    void unsupportedOrRemovedCreateFallsBackToOrdinaryLogin() throws Exception {
        var auth = mock(ConfigUiAuthService.class);
        when(auth.oidcEnabled()).thenReturn(true);
        var controller = new ConfigUiOidcController(client, auth, null, new NovaCoreProperties());
        provider.prompts = List.of("create");
        begin();
        provider.prompts = List.of("login");
        clock.now = clock.now.plus(Duration.ofHours(2));
        var request = request();
        request.setParameter("create", "1");
        var response = new MockHttpServletResponse();
        controller.login(request, response);
        assertFalse(query(java.net.URI.create(response.getRedirectedUrl()).getRawQuery()).containsKey("prompt"));
        assertEquals(2, provider.discoveryRequests);
    }

    @Test
    void bindingIgnoresRegistrationParameter() throws Exception {
        provider.prompts = List.of("create");
        var auth = localAuth();
        var request = request();
        request.setParameter("create", "1");
        request.setAttribute(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(clock.instant()));
        request.setAttribute(ConfigUiAccess.FRESH_LOCAL, true);
        request.setAttribute(ConfigUiAccess.SESSION, auth.issueForPassword("127.0.0.1"));
        var response = new MockHttpServletResponse();
        new ConfigUiOidcController(client, auth, null, new NovaCoreProperties()).bind(request, response);
        String url = JSONObject.parseObject(response.getContentAsString()).getString("authorizationUrl");
        assertFalse(query(java.net.URI.create(url).getRawQuery()).containsKey("prompt"));
    }

    private ConfigUiAuthService localAuth() {
        var settings = new NovaCoreProperties().getConfigUi().getAuth();
        settings.setPassword(LowIterationPasswordHash.of("example password"));
        settings.setTotp(false);
        var auth = new ConfigUiAuthService(settings,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(24)),
                new LoginThrottle(5, Duration.ofMinutes(15)), null);
        auth.setOidcClient(client);
        return auth;
    }

    @Test
    void unavailableDiscoveryDoesNotBlockStateOrPasswordLogin() throws Exception {
        var auth = localAuth();
        var controller = new ConfigUiAuthController(auth, null, new NovaCoreProperties());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var loader = new AtomicReference<Thread>();
        provider.discoveryUnavailable = true;
        provider.discoveryHook = () -> {
            loader.set(Thread.currentThread());
            entered.countDown();
            try { assertTrue(release.await(20, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new AssertionError(error); }
        };
        try {
            assertFalse(controller.state(request()).getBooleanValue("oidcCreateSupported"));
            assertTrue(entered.await(20, TimeUnit.SECONDS));
            long started = System.nanoTime();
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                for (int i = 0; i < 20; i++) {
                    assertFalse(controller.state(request()).getBooleanValue("oidcCreateSupported"));
                }
                assertTrue(auth.login("example password".toCharArray(), null, "127.0.0.1").success());
            });
            System.out.printf("Unavailable discovery: 20 auth/state requests and password login = %.3f ms%n",
                    (System.nanoTime() - started) / 1_000_000.0);
            assertEquals(1, provider.discoveryRequests);
        } finally {
            release.countDown();
            if (loader.get() != null) {
                loader.get().join(20_000);
                assertFalse(loader.get().isAlive());
            }
        }
        long started = System.nanoTime();
        assertFalse(controller.state(request()).getBooleanValue("oidcCreateSupported"));
        System.out.printf("Unavailable discovery: auth/state = %.3f ms%n", (System.nanoTime() - started) / 1_000_000.0);
        assertEquals(1, provider.discoveryRequests);
    }

    @Test
    void backgroundDiscoveryEnablesRegistrationAndExpiredMetadataDoesNotWaitForRefresh() throws Exception {
        provider.prompts = List.of("create");
        var auth = localAuth();
        var controller = new ConfigUiAuthController(auth, null, new NovaCoreProperties());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var loader = new AtomicReference<Thread>();
        provider.discoveryHook = () -> {
            loader.set(Thread.currentThread());
            entered.countDown();
            try { assertTrue(release.await(20, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new AssertionError(error); }
        };
        try {
            assertFalse(controller.state(request()).getBooleanValue("oidcCreateSupported"));
            assertTrue(entered.await(20, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) assertFalse(controller.state(request()).getBooleanValue("oidcCreateSupported"));
            assertEquals(1, provider.discoveryRequests);
        } finally {
            release.countDown();
            if (loader.get() != null) {
                loader.get().join(20_000);
                assertFalse(loader.get().isAlive());
            }
        }
        assertTrue(controller.state(request()).getBooleanValue("oidcCreateSupported"));
        clock.now = clock.now.plus(Duration.ofHours(2));
        var refreshing = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        provider.discoveryHook = () -> {
            refreshing.countDown();
            try { assertTrue(finish.await(20, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new AssertionError(error); }
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var login = executor.submit(this::begin);
            assertTrue(refreshing.await(20, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertTrue(controller.state(request()).getBooleanValue("oidcCreateSupported")));
            finish.countDown();
            login.get(20, TimeUnit.SECONDS);
        } finally {
            finish.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
        assertEquals(2, provider.discoveryRequests);
    }
}
