package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.secret.SecretStore;
import org.frostnova.nova.core.util.DurableFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

@NovaComponent
final class QqBotSettings {
    private static final Pattern APP_ID = Pattern.compile("[0-9]{1,20}");
    private static final long DEFAULT_INTERVAL_MILLIS = 2000;
    private static final LinkOption NO_LINKS = LinkOption.NOFOLLOW_LINKS;

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

    void save(String appId, String secret) {
        if (appId == null || !APP_ID.matcher(appId).matches()) {
            throw new IllegalArgumentException("AppID 须为 1 至 20 位数字");
        }
        boolean replaceSecret = secret != null && !secret.isEmpty();
        if (!replaceSecret && !secrets.isSet()) {
            throw new IllegalArgumentException("请填写 AppSecret");
        }
        long interval = snapshot.intervalMillis();
        String original;
        try {
            if (Files.notExists(directory, NO_LINKS)) {
                try {
                    Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
                } catch (UnsupportedOperationException e) {
                    Files.createDirectories(directory);
                }
            }
            protectStorage();
            original = Files.notExists(settingsPath) ? null : Files.readString(settingsPath);
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
            snapshot = new Snapshot(appId, interval, true, maskedSecret());
        } catch (IOException | RuntimeException e) {
            throw storageError();
        }
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
                return new Snapshot("", DEFAULT_INTERVAL_MILLIS, false, maskedSecret());
            }
            JSONObject json = JSON.parseObject(Files.readString(settingsPath));
            if (json == null) {
                throw storageError();
            }
            String appId = json.getString("appId");
            long interval = json.getLongValue("intervalMillis", DEFAULT_INTERVAL_MILLIS);
            boolean validId = appId != null && APP_ID.matcher(appId).matches();
            return new Snapshot(validId ? appId : "", interval, validId && secrets.isSet(), maskedSecret());
        } catch (IOException | RuntimeException e) {
            throw storageError();
        }
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

    private void restoreSettings(String original) {
        try {
            if (original == null) {
                Files.deleteIfExists(settingsPath);
            } else {
                writeSettings(original);
            }
        } catch (IOException | RuntimeException e) {
            throw storageError();
        }
    }

    private void protectStorage() throws IOException {
        if (!Files.isDirectory(directory, NO_LINKS)) {
            throw storageError();
        }
        restrict(directory, true);
        for (Path path : paths) {
            protectFile(path);
            protectFile(DurableFiles.temporary(path));
        }
    }

    private static void protectFile(Path path) throws IOException {
        if (!Files.notExists(path, NO_LINKS)) {
            if (!Files.isRegularFile(path, NO_LINKS)) {
                throw storageError();
            }
            restrict(path, false);
        }
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, NO_LINKS);
        if (posix != null) {
            posix.setPermissions(directory ? PosixFilePermissions.fromString("rwx------") : DurableFiles.OWNER_ONLY);
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, NO_LINKS);
        if (acl == null) {
            throw storageError();
        }
        var ownerAccess = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) {
            ownerAccess.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        acl.setAcl(List.of(ownerAccess.build()));
    }

    private static IllegalStateException storageError() {
        return new IllegalStateException("QQ 官方机器人设置文件无法读写，请检查工作目录权限或重新填写凭据");
    }

    record Snapshot(String appId, long intervalMillis, boolean configured, String maskedSecret) {
    }
}
