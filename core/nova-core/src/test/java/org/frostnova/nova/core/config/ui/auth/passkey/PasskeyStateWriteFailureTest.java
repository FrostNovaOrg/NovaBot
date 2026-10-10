package org.frostnova.nova.core.config.ui.auth.passkey;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiPasskeyController;
import org.frostnova.nova.core.config.ui.ConfigUiSecurityFilter;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态文件写不进时，删除与登记通行密钥都不得报成功
 * <p>
 * 「写不进」的造法：把运行状态所在的目录换成一个同名的普通文件，状态文件的路径于是落在
 * 一个文件「里面」，建不出也写不了。不靠改权限——以 root 跑时权限拦不住写。
 * 盘上原来那份状态文件原样不动，换回原目录就是「磁盘恢复」，另开一个状态存储去读它就是「重启」。
 */
@DisplayName("状态文件写不进时通行密钥的删除与登记")
class PasskeyStateWriteFailureTest extends PasskeyTestSupport {
    private NovaStateStore state;

    private String writableDataPath;

    @BeforeEach
    void useOwnStateStore() {
        // 台面上那份状态存储拿不到手；换一份自己握着的，好在「磁盘恢复」后亲手跑一次保存
        state = new NovaStateStore(properties);
        store = new PasskeyStore(state);
        controller = new ConfigUiPasskeyController(new PasskeyService(store, authService), properties, authService);
        writableDataPath = properties.getLive().getLiveDataPath();
    }

    @Test
    @DisplayName("丢了手机去删它的钥匙而状态文件写不进：不报已删除，本次运行里登不了，重启后钥匙还在")
    void deleteThatCannotBePersistedIsNotReportedAsDeleted() throws IOException {
        TestAuthenticator lostPhone = new TestAuthenticator(TestAuthenticator.ES256);
        TestAuthenticator laptop = new TestAuthenticator(TestAuthenticator.EDDSA);
        register(lostPhone, "丢了的手机", 3);
        register(laptop, "电脑", 3);

        blockStateFile();
        ResponseEntity<JSONObject> deleted = controller.delete(lostPhone.credentialId(), request());

        // 用户故障：控制台说「已删除」，使用者就当丢了的手机已经撤掉了
        JSONObject body = deleted.getBody();
        assertNotEquals(200, deleted.getStatusCode().value(), body.toJSONString());
        assertFalse(body.getBooleanValue("success"), body.toJSONString());
        assertFalse(body.getString("message").startsWith("已删除"), body.getString("message"));
        assertTrue(body.getString("message").contains("重启"),
                "提示没说重启会让这把钥匙恢复可用: " + body.getString("message"));

        // 用户故障：磁盘写不进就把钥匙放回来，丢了的手机当下就还能登录
        assertTrue(store.find(lostPhone.credentialId()).isEmpty(), "存盘失败后这把钥匙又回到了内存里");
        ResponseEntity<JSONObject> login = controller.loginVerify(
                lostPhone.assertion(loginChallenge(), ORIGIN, RP_ID, 4), request());
        assertEquals(401, login.getStatusCode().value(), "删过的钥匙在本次运行里还登得进来");

        // 钉住提示里那句「重启后会恢复可用」：盘上那份还带着这把钥匙
        assertTrue(reopenFromDisk().find(lostPhone.credentialId()).isPresent(),
                "盘上已经没有这把钥匙，提示里「重启后会恢复可用」说的就不是实情");
    }

    @Test
    @DisplayName("丢了手机去删它的钥匙而状态文件写不进：手机上已经登着的控制台照样被退出")
    void deleteThatCannotBePersistedStillRevokesItsSessions() throws IOException {
        TestAuthenticator lostPhone = new TestAuthenticator(TestAuthenticator.ES256);
        TestAuthenticator laptop = new TestAuthenticator(TestAuthenticator.EDDSA);
        register(lostPhone, "丢了的手机", 3);
        register(laptop, "电脑", 3);
        String lostPhoneSession = passkeyLogin(lostPhone, 4);

        blockStateFile();
        ResponseEntity<JSONObject> deleted = controller.delete(lostPhone.credentialId(), request());

        // 用户故障：钥匙停用了，手机上已经登着的控制台却还能继续用
        JSONObject body = deleted.getBody();
        assertFalse(body.getBooleanValue("success"), body.toJSONString());
        assertTrue(authService.validate(lostPhoneSession).isEmpty(),
                "删除没存上盘时，用这把钥匙登录的会话没有注销：丢了的手机还登着控制台");
        assertTrue(authService.validate(sessionId).isPresent(), "发起删除的会话被顺手踢掉了");
        assertTrue(body.getString("message").contains("1 个会话已退出"),
                "提示没说用它登录的会话已退出: " + body.getString("message"));
    }

    @Test
    @DisplayName("删钥时写不进、之后磁盘恢复并保存一次：重启后钥匙不在了")
    void deleteIsPersistedOnceTheDiskRecovers() throws IOException {
        TestAuthenticator lostPhone = new TestAuthenticator(TestAuthenticator.ES256);
        TestAuthenticator laptop = new TestAuthenticator(TestAuthenticator.EDDSA);
        register(lostPhone, "丢了的手机", 3);
        register(laptop, "电脑", 3);

        blockStateFile();
        ResponseEntity<JSONObject> deleted = controller.delete(lostPhone.credentialId(), request());
        // 用户故障：写不进也报「已删除」
        assertFalse(deleted.getBody().getBooleanValue("success"), deleted.getBody().toJSONString());

        // 用户故障：提示说下一次自动保存会补上，补上之后重启钥匙却又回来了
        unblockStateFile();
        state.save();
        PasskeyStore reopened = reopenFromDisk();
        assertTrue(reopened.find(lostPhone.credentialId()).isEmpty(), "磁盘恢复并保存过之后，重启后钥匙还在");
        assertTrue(reopened.find(laptop.credentialId()).isPresent(), "另一把钥匙跟着没了");
    }

    @Test
    @DisplayName("登记时状态文件写不进：报失败，内存与盘上都没有这一把")
    void registrationThatCannotBePersistedFails() throws IOException {
        TestAuthenticator phone = new TestAuthenticator(TestAuthenticator.ES256);
        TestAuthenticator laptop = new TestAuthenticator(TestAuthenticator.EDDSA);
        register(phone, "手机", 3);

        blockStateFile();
        JSONObject options = registerOptions();
        JSONObject result = controller.registerVerify(
                laptop.register(options.getString("challenge"), ORIGIN, RP_ID, "电脑", 3), request());

        // 用户故障：控制台说「已登记」，重启后这把钥匙却不认了
        assertFalse(result.getBooleanValue("success"), result.toJSONString());
        assertTrue(store.find(laptop.credentialId()).isEmpty(), "没存上盘的钥匙留在了内存里，重启前后不一致");
        assertTrue(reopenFromDisk().find(laptop.credentialId()).isEmpty(), "没存上盘的钥匙出现在了盘上");
    }

    /**
     * 拿一把通行密钥登一次，回包 Set-Cookie 里那枚就是新会话
     */
    private String passkeyLogin(TestAuthenticator authenticator, long signCount) {
        ResponseEntity<JSONObject> response = controller.loginVerify(
                authenticator.assertion(loginChallenge(), ORIGIN, RP_ID, signCount), request());
        assertEquals(200, response.getStatusCode().value(), response.getBody().toJSONString());

        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "登录成功却没交回会话 Cookie");
        String prefix = ConfigUiSecurityFilter.SESSION_COOKIE + "=";
        for (String part : setCookie.split(";")) {
            String piece = part.strip();
            if (piece.startsWith(prefix)) {
                return piece.substring(prefix.length());
            }
        }
        throw new AssertionError("登录回包里没有会话 Cookie: " + setCookie);
    }

    /**
     * 运行状态所在的目录换成一个普通文件，此后的保存都写不进
     */
    private void blockStateFile() throws IOException {
        Path blocker = directory.resolve("blocked");
        Files.writeString(blocker, "not a directory");
        properties.getLive().setLiveDataPath(blocker.resolve("data.json").toString());
    }

    private void unblockStateFile() {
        properties.getLive().setLiveDataPath(writableDataPath);
    }

    /**
     * 另开一份状态存储从盘上读，等于重启一次
     */
    private PasskeyStore reopenFromDisk() {
        NovaCoreProperties fresh = new NovaCoreProperties();
        fresh.getLive().setLiveDataPath(writableDataPath);
        NovaStateStore reopened = new NovaStateStore(fresh);
        reopened.onApplicationReadyEvent();
        reopened.onContextClosedEvent();
        return new PasskeyStore(reopened);
    }
}
