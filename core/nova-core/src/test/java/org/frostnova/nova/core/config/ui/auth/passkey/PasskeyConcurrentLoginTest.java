package org.frostnova.nova.core.config.ui.auth.passkey;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.Cookie;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiPasskeyController;
import org.frostnova.nova.core.config.ui.ConfigUiSecurityFilter;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 同一把钥匙、同一个计数，两路同时登录只能成一个
 * <p>
 * find 与写入之间若没有条件更新，两路都会看见旧计数、都会当成「往前走了」写回去。
 * 闸卡在写入前：两边都拿到同一份旧记录、都走到 updateIfSignCount 门口再一起进，
 * 才能量到这条缝。
 */
@DisplayName("通行密钥并发登录")
class PasskeyConcurrentLoginTest {
    @TempDir
    Path directory;

    @Test
    @DisplayName("同一把钥匙、同一个计数，两路同时登录只能成一个")
    void onlyOneConcurrentLoginWithTheSameCountSucceeds() throws Exception {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("data.json").toString());
        NovaCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        auth.setPassword(PasskeyTestSupport.PASSWORD);
        auth.setTotp(false);

        CyclicBarrier beforeUpdate = new CyclicBarrier(2);
        BarrierBeforeUpdateStore store = new BarrierBeforeUpdateStore(new NovaStateStore(properties), beforeUpdate);
        ConfigUiAuthService authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), null);
        ConfigUiPasskeyController controller = new ConfigUiPasskeyController(
                new PasskeyService(store, authService), properties, authService);

        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        String sessionId = authService.login(PasskeyTestSupport.PASSWORD.toCharArray(), null,
                PasskeyTestSupport.CLIENT_IP).session().getId();
        MockHttpServletRequest request = request();
        request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, sessionId));
        JSONObject body = new JSONObject();
        body.put("current", PasskeyTestSupport.PASSWORD);
        JSONObject registerOptions = controller.registerOptions(body, request).getBody();
        JSONObject registered = controller.registerVerify(
                authenticator.register(registerOptions.getString("challenge"),
                        PasskeyTestSupport.ORIGIN, PasskeyTestSupport.RP_ID, "我的手机", 7),
                request);
        assertEquals(true, registered.getBooleanValue("success"), registered.getString("message"));
        store.arm();

        String firstChallenge = controller.loginOptions(request()).getString("challenge");
        String secondChallenge = controller.loginOptions(request()).getString("challenge");
        JSONObject first = authenticator.assertion(firstChallenge,
                PasskeyTestSupport.ORIGIN, PasskeyTestSupport.RP_ID, 8);
        JSONObject second = authenticator.assertion(secondChallenge,
                PasskeyTestSupport.ORIGIN, PasskeyTestSupport.RP_ID, 8);

        AtomicInteger successes = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> one = pool.submit(() -> status(controller, first));
            Future<Integer> two = pool.submit(() -> status(controller, second));
            int firstStatus = one.get(10, TimeUnit.SECONDS);
            int secondStatus = two.get(10, TimeUnit.SECONDS);
            if (firstStatus == 200) {
                successes.incrementAndGet();
            }
            if (secondStatus == 200) {
                successes.incrementAndGet();
            }
            assertEquals(1, successes.get(),
                    "同计数并发登录只能成一个, 实际 " + firstStatus + " 与 " + secondStatus);
        } finally {
            store.disarm();
            pool.shutdownNow();
        }

        assertEquals(8, store.find(authenticator.credentialId()).orElseThrow().signCount(),
                "留下的应当是往前走过的那一次计数");
    }

    private static int status(ConfigUiPasskeyController controller, JSONObject body) {
        ResponseEntity<JSONObject> response = controller.loginVerify(body, request());
        return response.getStatusCode().value();
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/config/api/auth/passkey");
        request.addHeader("Host", PasskeyTestSupport.HOST);
        request.setRemoteAddr(PasskeyTestSupport.CLIENT_IP);
        return request;
    }

    /**
     * 写入计数器之前两边对齐，再让写入继续往下走，把「读完再写」那条缝敞开放出来
     * <p>
     * 闸原先卡在 find 之后。登录路上签发完会话还会再 find 一次核钥匙还在不在
     * （见 {@code PasskeyService#loginVerify}），那一趟也会撞进栅栏，而对家早已分出胜负走了，
     * 于是胜者在栅栏上等到超时、两路都回了失败。改成卡在写入前：两路都必然走到
     * {@link #updateIfSignCount}，同样量到「两边读到同一份旧计数再一起写」那条缝，
     * 而多出来的那趟 find 不受影响。
     */
    private static final class BarrierBeforeUpdateStore extends PasskeyStore {
        private final CyclicBarrier barrier;

        private volatile boolean armed;

        private BarrierBeforeUpdateStore(NovaStateStore state, CyclicBarrier barrier) {
            super(state);
            this.barrier = barrier;
        }

        private void arm() {
            armed = true;
        }

        private void disarm() {
            armed = false;
        }

        @Override
        public boolean updateIfSignCount(String id, long expectedSignCount, PasskeyCredential credential) {
            if (armed) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return super.updateIfSignCount(id, expectedSignCount, credential);
        }
    }
}
