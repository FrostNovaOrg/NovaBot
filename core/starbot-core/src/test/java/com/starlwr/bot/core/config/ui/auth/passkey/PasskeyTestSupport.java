package com.starlwr.bot.core.config.ui.auth.passkey;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.ConfigUiPasskeyController;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSessionStore;
import com.starlwr.bot.core.config.ui.auth.LoginThrottle;
import com.starlwr.bot.core.service.StarBotStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 通行密钥各组判据共用的台面
 * <p>
 * 走的是<b>控制器</b>而不是直接调服务：rpId 与 origin 是从请求头上现算出来的，
 * 绕过这一层就等于把「这套面板此刻叫什么」这件事自己填了一个答案，
 * 而那恰好是三条阴性判据里两条要量的东西。
 */
abstract class PasskeyTestSupport {
    static final String PASSWORD = "correct horse battery staple";

    /**
     * 判据里这套面板的地址。rpId 不带端口、origin 带——这一对差别本身就是被测的一部分
     */
    static final String HOST = "localhost:7827";

    static final String RP_ID = "localhost";

    static final String ORIGIN = "http://localhost:7827";

    static final String CLIENT_IP = "127.0.0.1";

    @TempDir
    Path directory;

    StarBotCoreProperties properties;

    PasskeyStore store;

    ConfigUiAuthService authService;

    ConfigUiPasskeyController controller;

    @BeforeEach
    void setUpPasskey() {
        properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("data.json").toString());

        StarBotCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        auth.setPassword(PASSWORD);
        // 二次验证与本组无关，且通行密钥这条路本来就不经它——开着只会让台面多一个变量
        auth.setTotp(false);

        store = new PasskeyStore(new StarBotStateStore(properties));
        // fileService 传 null：本组不把哈希写回配置文件
        authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), null);
        controller = new ConfigUiPasskeyController(new PasskeyService(store, authService), properties);
    }

    MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/config/api/auth/passkey");
        request.addHeader("Host", HOST);
        request.setRemoteAddr(CLIENT_IP);
        return request;
    }

    /**
     * 登记一把钥匙，返回它的初始计数器
     */
    long register(TestAuthenticator authenticator, String name, long signCount) {
        JSONObject options = controller.registerOptions(request());
        JSONObject result = controller.registerVerify(
                authenticator.register(options.getString("challenge"), ORIGIN, RP_ID, name, signCount), request());

        if (!result.getBooleanValue("success")) {
            throw new IllegalStateException("台面没搭起来, 登记就失败了: " + result.getString("message"));
        }

        return signCount;
    }

    /**
     * 取一个刚发出来的登录挑战
     */
    String loginChallenge() {
        JSONObject options = controller.loginOptions(request());
        if (!options.getBooleanValue("success")) {
            throw new IllegalStateException("台面没搭起来, 取不到登录挑战: " + options.getString("message"));
        }
        return options.getString("challenge");
    }
}
