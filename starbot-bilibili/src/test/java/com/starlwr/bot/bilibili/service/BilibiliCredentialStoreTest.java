package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.Cookies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("登录凭据存储")
class BilibiliCredentialStoreTest {
    private static final String SESS_DATA = "aa11bb22%2Ccc33dd44%2Cee55ff66";
    private static final String BILI_JCT = "0123456789abcdef0123456789abcdef";
    private static final String BUVID3 = "ABCDEF01-2345-6789-ABCD-EF0123456789infoc";

    @TempDir
    Path dir;

    private StarBotBilibiliProperties.Account properties;
    private Path cookiePath;
    private Path keyPath;

    @BeforeEach
    void setUp() {
        cookiePath = dir.resolve("cookies.json");
        keyPath = dir.resolve("cookies.key");

        properties = new StarBotBilibiliProperties.Account();
        properties.setCookiePath(cookiePath.toString());
        properties.setKeyPath(keyPath.toString());
    }

    private BilibiliCredentialStore store() {
        return new BilibiliCredentialStore(properties);
    }

    private Cookies sample() {
        return new Cookies(SESS_DATA, BILI_JCT, BUVID3);
    }

    private void assertRoundTrip(Cookies loaded) {
        assertEquals(SESS_DATA, loaded.getSessData());
        assertEquals(BILI_JCT, loaded.getBiliJct());
        assertEquals(BUVID3, loaded.getBuvid3());
    }

    @Test
    @DisplayName("尚未保存过凭据时读取结果为空")
    void loadWhenAbsent() {
        assertTrue(store().load().isEmpty());
    }

    @Test
    @DisplayName("加密保存后可原样读回")
    void encryptedRoundTrip() {
        store().save(sample());

        Optional<Cookies> loaded = store().load();
        assertTrue(loaded.isPresent());
        assertRoundTrip(loaded.get());
    }

    @Test
    @DisplayName("落盘内容不含任何明文凭据")
    void storedContentHasNoPlaintext() throws Exception {
        store().save(sample());

        String content = Files.readString(cookiePath, StandardCharsets.UTF_8);
        assertFalse(content.contains(SESS_DATA), "SESSDATA 不应以明文出现在文件中");
        assertFalse(content.contains(BILI_JCT), "bili_jct 不应以明文出现在文件中");
        assertFalse(content.contains(BUVID3), "buvid3 不应以明文出现在文件中");

        JSONObject json = JSON.parseObject(content);
        assertTrue(json.containsKey("payload"));
        assertTrue(json.containsKey("iv"));
    }

    @Test
    @DisplayName("每次加密使用不同的 IV")
    void ivIsRandomPerSave() throws Exception {
        store().save(sample());
        String first = JSON.parseObject(Files.readString(cookiePath)).getString("iv");

        store().save(sample());
        String second = JSON.parseObject(Files.readString(cookiePath)).getString("iv");

        assertFalse(first.equals(second), "重复保存应使用不同的 IV");
    }

    @Test
    @DisplayName("凭据文件与密钥文件均为仅属主可读写")
    void filePermissionsAreRestricted() throws Exception {
        store().save(sample());

        for (Path path : new Path[]{cookiePath, keyPath}) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions,
                    path.getFileName() + " 的权限应为 0600");
        }
    }

    @Test
    @DisplayName("密钥文件丢失时解密失败且不抛出异常")
    void missingKeyYieldsEmpty() throws Exception {
        store().save(sample());
        Files.delete(keyPath);

        assertTrue(store().load().isEmpty());
    }

    @Test
    @DisplayName("密文被篡改时解密失败，GCM 完整性校验生效")
    void tamperedPayloadIsRejected() throws Exception {
        store().save(sample());

        JSONObject json = JSON.parseObject(Files.readString(cookiePath));
        String payload = json.getString("payload");
        // 翻转密文中的一个字符，模拟篡改
        char[] chars = payload.toCharArray();
        chars[0] = chars[0] == 'A' ? 'B' : 'A';
        json.put("payload", new String(chars));
        Files.writeString(cookiePath, json.toJSONString());

        assertTrue(store().load().isEmpty());
    }

    @Test
    @DisplayName("既有明文凭据被自动迁移为加密存储并备份原文件")
    void migratesLegacyPlaintext() throws Exception {
        JSONObject plain = new JSONObject();
        plain.put("sessData", SESS_DATA);
        plain.put("biliJct", BILI_JCT);
        plain.put("buvid3", BUVID3);
        Files.writeString(cookiePath, plain.toJSONString());

        Optional<Cookies> loaded = store().load();
        assertTrue(loaded.isPresent());
        assertRoundTrip(loaded.get());

        String migrated = Files.readString(cookiePath, StandardCharsets.UTF_8);
        assertFalse(migrated.contains(SESS_DATA), "迁移后凭据文件不应再含明文");
        assertTrue(JSON.parseObject(migrated).containsKey("payload"));

        Path backup = dir.resolve("cookies.json.plain.bak");
        assertTrue(Files.exists(backup), "原明文文件应被备份");

        // 迁移后应能继续正常读取
        assertRoundTrip(store().load().orElseThrow());
    }

    @Test
    @DisplayName("兼容下划线命名的历史明文字段")
    void readsLegacyUnderscoreKeys() throws Exception {
        JSONObject plain = new JSONObject();
        plain.put("SESSDATA", SESS_DATA);
        plain.put("bili_jct", BILI_JCT);
        plain.put("BUVID3", BUVID3);
        Files.writeString(cookiePath, plain.toJSONString());

        assertRoundTrip(store().load().orElseThrow());
    }

    @Test
    @DisplayName("关闭加密时以明文保存，且仍收紧文件权限")
    void plaintextModeStillRestrictsPermissions() throws Exception {
        properties.setEncrypt(false);
        store().save(sample());

        String content = Files.readString(cookiePath, StandardCharsets.UTF_8);
        assertTrue(content.contains(SESS_DATA));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(cookiePath));

        assertRoundTrip(store().load().orElseThrow());
    }

    @Test
    @DisplayName("损坏的凭据文件不会导致启动失败")
    void corruptedFileYieldsEmpty() throws Exception {
        Files.writeString(cookiePath, "这不是 JSON");

        assertTrue(store().load().isEmpty());
    }

    @Test
    @DisplayName("清除后凭据文件不再存在")
    void clearRemovesFile() {
        store().save(sample());
        assertTrue(Files.exists(cookiePath));

        store().clear();
        assertFalse(Files.exists(cookiePath));
    }

    @Test
    @DisplayName("凭据的 toString 不泄露内容")
    void toStringIsRedacted() {
        String text = sample().toString();

        assertFalse(text.contains(SESS_DATA));
        assertFalse(text.contains(BILI_JCT));
        assertFalse(text.contains(BUVID3));
    }
}
