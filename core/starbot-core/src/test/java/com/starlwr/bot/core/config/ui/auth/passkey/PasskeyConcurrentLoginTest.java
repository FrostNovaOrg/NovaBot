package com.starlwr.bot.core.config.ui.auth.passkey;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.config.ui.ConfigUiPasskeyController;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSessionStore;
import com.starlwr.bot.core.config.ui.auth.LoginThrottle;
import com.starlwr.bot.core.service.StarBotStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
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
 * 闸在 find 之后：两边都拿到同一份旧记录，再一起去登录，才能量到这条缝。
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

        CyclicBarrier afterFind = new CyclicBarrier(2);
        BarrierAfterFindStore store = new BarrierAfterFindStore(new StarBotStateStore(properties), afterFind);
        ConfigUiAuthService authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), null);
        ConfigUiPasskeyController controller = new ConfigUiPasskeyController(
                new PasskeyService(store, authService), properties);

        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        MockHttpServletRequest request = request();
        JSONObject registerOptions = controller.registerOptions(request);
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
     * find 之后两边对齐，再让登录继续往下走，把「读完再写」那条缝敞开放出来
     */
    private static final class BarrierAfterFindStore extends PasskeyStore {
        private final CyclicBarrier barrier;

        private volatile boolean armed;

        private BarrierAfterFindStore(StarBotStateStore state, CyclicBarrier barrier) {
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
        public Optional<PasskeyCredential> find(String id) {
            Optional<PasskeyCredential> found = super.find(id);
            if (!armed) {
                return found;
            }
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return found;
        }
    }
}
