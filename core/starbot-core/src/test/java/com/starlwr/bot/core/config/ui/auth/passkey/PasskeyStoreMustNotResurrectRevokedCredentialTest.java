package com.starlwr.bot.core.config.ui.auth.passkey;

import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.service.StarBotStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录途中把钥匙撤掉之后，计数器更新不得把它写回去
 * <p>
 * {@link PasskeyStore#updateIfSignCount} 在钥匙已经不在时必须回 false，
 * 并且不得插入一条新记录。否则「我已经把丢了的那台设备撤销了」这句话
 * 会被一次还在路上的登录当场推翻。
 */
@DisplayName("通行密钥已撤销不得被登录更新复活")
class PasskeyStoreMustNotResurrectRevokedCredentialTest {
    @TempDir
    Path directory;

    @Test
    @DisplayName("更新前把这把钥匙撤掉，返回失败且库里不再出现它")
    void updateAfterRemoveDoesNotResurrect() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("data.json").toString());
        PasskeyStore store = new PasskeyStore(new StarBotStateStore(properties));

        Instant created = Instant.parse("2026-09-05T00:00:00Z");
        PasskeyCredential original = new PasskeyCredential(
                "cred-revoked", "我的手机", "AAAA", -7, 7L, created, null);
        store.save(original);
        assertTrue(store.remove("cred-revoked"), "台面没搭起来: 刚写入的钥匙应当能删掉");

        PasskeyCredential attempted = original.used(8L, Instant.parse("2026-09-05T00:01:00Z"));
        assertFalse(store.updateIfSignCount("cred-revoked", 7L, attempted),
                "钥匙已经不在时，计数器更新必须失败");
        assertTrue(store.find("cred-revoked").isEmpty(),
                "撤掉的钥匙不得被登录更新写回去");
        assertTrue(store.list().isEmpty(),
                "库里不该留下被复活的条目");
    }
}
