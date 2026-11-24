package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.secret.SecretStore;
import org.frostnova.nova.core.util.DurableFiles;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

@Slf4j
@NovaComponent
final class QqBotSettings {
    private static final Pattern APP_ID = Pattern.compile("[0-9]{1,20}");
    private static final long DEFAULT_INTERVAL_MILLIS = 2000;
    private static final LinkOption NO_LINKS = LinkOption.NOFOLLOW_LINKS;
    private static final JsonMapper SETTINGS_JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final Path directory;
    private final Path settingsPath;
    private final List<Path> paths;
    private final SecretStore secrets;
    private volatile Snapshot snapshot;

    QqBotSettings() {
        this(Path.of("."));
    }

    QqBotSettings(Path root) {
        directory = root.toAbsolutePath().normalize().resolve("qqbot");
        settingsPath = directory.resolve("settings.json");
        paths = List.of(settingsPath, directory.resolve("app-secret.enc"), directory.resolve("app-secret-master.key"));
        secrets = new SecretStore(directory, "app-secret");
        snapshot = load();
    }

    Snapshot snapshot() {
        return snapshot;
    }

    Path directory() {
        return directory;
    }

    void save(String appId, String secret) {
        if (appId == null || !APP_ID.matcher(appId).matches()) {
            throw new IllegalArgumentException("AppID 须为 1 至 20 位数字");
        }
        if (appId.equals(secret)) {
            throw new IllegalArgumentException("AppSecret 和 AppID 一样，像是填错了栏，请到 QQ 开放平台复制 AppSecret");
        }
        boolean replaceSecret = secret != null && !secret.isEmpty();
        if (!replaceSecret && !secrets.isSet()) {
            throw new IllegalArgumentException("请填写 AppSecret");
        }
        long interval = snapshot.intervalMillis();
        byte[] original;
        try {
            QqBotStorage.prepareDirectory(directory);
            protectFiles();
            original = Files.notExists(settingsPath) ? null : Files.readAllBytes(settingsPath);
            writeSettings(JSONObject.of("appId", appId, "intervalMillis", interval).toJSONString());
        } catch (IOException | SecurityException e) {
            throw storageError();
        }

        try {
            if (replaceSecret) {
                secrets.save(secret);
            }
        } catch (RuntimeException e) {
            restoreSettings(original);
            throw e;
        }
        try {
            protectStorage();
            snapshot = new Snapshot(appId, interval, true, maskedSecret(), false);
        } catch (IOException | RuntimeException e) {
            throw storageError();
        }
    }

    void clear() {
        long interval = snapshot.intervalMillis();
        byte[] original = null;
        try {
            if (!Files.notExists(directory, NO_LINKS)) {
                protectStorage();
                if (!Files.notExists(settingsPath)) {
                    original = Files.readAllBytes(settingsPath);
                    writeSettings(JSONObject.of("intervalMillis", interval).toJSONString());
                }
            }
        } catch (IOException | SecurityException e) {
            throw storageError();
        }
        try {
            secrets.clear();
        } catch (RuntimeException e) {
            restoreSettings(original);
            throw e;
        }
        snapshot = new Snapshot("", interval, false, "", false);
    }

    <T> T useSecret(Function<String, T> action) {
        return secrets.use(action);
    }

    private Snapshot load() {
        try {
            if (!Files.notExists(directory, NO_LINKS)) {
                protectStorage();
            }
            if (Files.notExists(settingsPath)) {
                return new Snapshot("", DEFAULT_INTERVAL_MILLIS, false, maskedSecret(), false);
            }
        } catch (IOException | RuntimeException e) {
            throw storageError();
        }
        return readSettingsFile();
    }

    private Snapshot readSettingsFile() {
        try {
            JSONObject json = SETTINGS_JSON.readValue(Files.readString(settingsPath), JSONObject.class);
            if (json == null) {
                return unreadableSettings();
            }
            String appId = json.getString("appId");
            long interval = json.getLongValue("intervalMillis", DEFAULT_INTERVAL_MILLIS);
            boolean validId = appId != null && APP_ID.matcher(appId).matches();
            return new Snapshot(validId ? appId : "", interval, validId && secrets.isSet(), maskedSecret(), false);
        } catch (IOException | RuntimeException e) {
            return unreadableSettings();
        }
    }

    private Snapshot unreadableSettings() {
        log.warn("无法读取 QQ 官方机器人设置文件 {}", settingsPath);
        return new Snapshot("", DEFAULT_INTERVAL_MILLIS, false, "", true);
    }

    private String maskedSecret() {
        try {
            String masked = secrets.masked();
            return masked == null ? "…" : masked;
        } catch (IllegalStateException e) {
            // 保留已配状态，启动时由令牌验证报告无法解密。
            return "…";
        }
    }

    private void writeSettings(String json) throws IOException {
        DurableFiles.replace(settingsPath, json, DurableFiles.OWNER_ONLY);
    }

    private void restoreSettings(byte[] original) {
        try {
            if (original == null) {
                Files.deleteIfExists(settingsPath);
            } else {
                try {
                    writeSettings(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(original)).toString());
                } catch (CharacterCodingException e) {
                    Files.write(settingsPath, original);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw storageError();
        }
    }

    private void protectStorage() throws IOException {
        QqBotStorage.protectDirectory(directory);
        protectFiles();
    }

    private void protectFiles() throws IOException {
        for (Path path : paths) {
            QqBotStorage.protectFile(path);
            QqBotStorage.protectFile(DurableFiles.temporary(path));
        }
    }

    private static IllegalStateException storageError() {
        return new IllegalStateException("QQ 官方机器人设置文件无法读写，请检查工作目录权限或重新填写凭据");
    }

    record Snapshot(String appId, long intervalMillis, boolean configured, String maskedSecret, boolean unreadable) {
    }
}
