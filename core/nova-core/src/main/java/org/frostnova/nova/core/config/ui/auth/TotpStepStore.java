package org.frostnova.nova.core.config.ui.auth;

import org.frostnova.nova.core.util.DurableFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 与主配置文件放在一起的二次验证进度。调用方在同一把锁内读写。
 */
final class TotpStepStore {
    private final Supplier<Path> configPath;
    private final long initialStep;
    private final LongSupplier currentStep;
    private String keyId;
    private long lastStep;

    /**
     * @param configPath 主配置文件路径
     * @param initialStep 启动时的时间步
     * @param currentStep 当前时间步
     */
    TotpStepStore(Supplier<Path> configPath, long initialStep, LongSupplier currentStep) {
        this.configPath = configPath;
        this.initialStep = initialStep;
        this.currentStep = currentStep;
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
        String content = null;
        try {
            content = Files.readString(path());
        } catch (NoSuchFileException absent) {
            // 第一次使用时建立进度文件。
        }
        if (content != null) {
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
        Path path = path();
        DurableFiles.replace(path, "1\n" + id + "\n" + step + "\n", DurableFiles.OWNER_ONLY);
        restrictToOwner(path);
        keyId = id;
        lastStep = step;
    }

    /**
     * 记录的时间步晚于当前时间所能接受的最后一步时，返回验证器上的验证码重新可用的时刻；否则为 null。
     */
    Instant laterThanClockUntil() {
        if (keyId == null || lastStep <= currentStep.getAsLong() + TotpGenerator.WINDOW) {
            return null;
        }
        return Instant.ofEpochSecond((lastStep + 1) * TotpGenerator.STEP_SECONDS);
    }

    /**
     * 把文件权限收到仅属主可读写；文件系统不支持权限位时保持原样。
     */
    private static void restrictToOwner(Path path) throws IOException {
        try {
            if (Files.getPosixFilePermissions(path).equals(DurableFiles.OWNER_ONLY)) {
                return;
            }
        } catch (UnsupportedOperationException unchanged) {
            // 保持原样。
            return;
        }
        Files.setPosixFilePermissions(path, DurableFiles.OWNER_ONLY);
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
