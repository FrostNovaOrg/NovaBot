package org.frostnova.nova.core;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 按给定路径写状态件，报四个阶段（等锁、过门、就绪、安全模式），给换版工具看新版本走到哪一步。
 * <p>
 * 路径由系统属性 {@code novabot.state-file} 或环境变量 {@code NOVABOT_STATE_FILE} 给（属性在就只认属性）；
 * 空串（去首尾空白后为空）当没给。都没给就不写、不打日志。
 * 写不进（路径带非法字符、目标是目录、目录不在、没权限、盘满）只打一行 WARN 照常往下起，
 * 不抛、不改退码、不打栈。目录不由程序建。
 */
@Slf4j
final class StateFile {

    /**
     * 状态件路径：系统属性。
     */
    static final String PATH_PROPERTY = "novabot.state-file";

    /**
     * 状态件路径：环境变量。
     */
    static final String PATH_ENV = "NOVABOT_STATE_FILE";

    private StateFile() {
    }

    /**
     * 写状态件：两行 {@code phase=<阶段>}、{@code pid=<java 进程号>}，UTF-8，每行换行结尾。
     * 同目录先写临时名再原子改名，读的一方只会读到完整的一份。
     */
    static void write(String phase) {
        String raw = given();
        if (raw == null) {
            return;
        }
        Path path;
        try {
            path = Path.of(raw);
        } catch (RuntimeException e) {
            warn(raw, e);
            return;
        }
        Path name = path.getFileName();
        if (name == null) {
            warn(raw, null);
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                warn(raw, null);
                return;
            }
        } catch (RuntimeException e) {
            warn(raw, e);
            return;
        }
        long pid = ProcessHandle.current().pid();
        String content = "phase=" + phase + "\npid=" + pid + "\n";
        Path tmp = path.resolveSibling(name + ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            warn(raw, e);
        }
    }

    /**
     * 状态件路径：属性在就只认属性；这一项是空串（去首尾空白后为空）当没给，也不去看环境变量。
     */
    private static String given() {
        String property = System.getProperty(PATH_PROPERTY);
        if (property != null) {
            return blank(property) ? null : property.trim();
        }
        String env = System.getenv(PATH_ENV);
        if (env != null) {
            return blank(env) ? null : env.trim();
        }
        return null;
    }

    private static boolean blank(String value) {
        return value.trim().isEmpty();
    }

    /**
     * 警告只打一行：路径、原因类名与一句话。不打栈——写不进是常态故障，栈会把日志刷满。
     */
    private static void warn(String raw, Exception e) {
        if (e == null) {
            log.warn("状态件写不进：{}（{}）", raw, reason(raw));
            return;
        }
        log.warn("状态件写不进：{}（{}: {}）", raw, e.getClass().getSimpleName(), e.getMessage());
    }

    private static String reason(String raw) {
        Path path;
        try {
            path = Path.of(raw);
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
        return path.getFileName() == null ? "路径没有文件名" : "目标已是目录";
    }

    private static void deleteQuietly(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException | RuntimeException ignored) {
            // 删不掉也不再报一行，上面那句警告已经打过
        }
    }
}
