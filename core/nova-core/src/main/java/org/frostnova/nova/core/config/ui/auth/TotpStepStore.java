package org.frostnova.nova.core.config.ui.auth;

import org.frostnova.nova.core.util.DurableFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * 与主配置文件放在一起的二次验证进度。调用方在同一把锁内读写。
 */
final class TotpStepStore {
    private final Supplier<Path> configPath;
    private final long initialStep;
    private String keyId;
    private long lastStep;

    TotpStepStore(Supplier<Path> configPath, long initialStep) {
        this.configPath = configPath;
        this.initialStep = initialStep;
    }

    Path path() {
        Path configured = configPath.get();
        return configured.resolveSibling(configured.getFileName() + ".totp-state");
    }

    void initialize(String secret) throws IOException {
        String id = keyId(secret);
        if (id.equals(keyId)) {
            return;
        }
        long step = initialStep;
        try {
            String content = Files.readString(path());
            if (!content.matches("1\n[0-9a-f]{64}\n[0-9]+\n")) {
                throw new IOException("二次验证记录格式不正确");
            }
            String[] parts = content.split("\n");
            long savedStep;
            try {
                savedStep = Long.parseLong(parts[2]);
            } catch (NumberFormatException e) {
                throw new IOException("二次验证记录格式不正确");
            }
            if (id.equals(parts[1])) {
                step = Math.max(step, savedStep);
            }
        } catch (NoSuchFileException absent) {
            // 第一次使用时建立进度文件。
        }
        record(secret, step);
    }

    boolean consume(String secret, long step) throws IOException {
        initialize(secret);
        if (step <= lastStep) {
            return false;
        }
        record(secret, step);
        return true;
    }

    void record(String secret, long step) throws IOException {
        String id = keyId(secret);
        DurableFiles.replace(path(), "1\n" + id + "\n" + step + "\n", DurableFiles.OWNER_ONLY);
        keyId = id;
        lastStep = step;
    }

    private static String keyId(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(TotpGenerator.base32Decode(secret)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", e);
        }
    }
}
