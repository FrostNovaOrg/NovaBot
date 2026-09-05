package com.starlwr.bot.core.config.ui;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 给一份文件留带时间戳的备份，并裁掉超出保留份数的旧份。
 * <p>
 * 备份名形如 {@code 原文件名.yyyyMMdd-HHmmss.bak}。旧的单份 {@code 原文件名.bak}
 * 不认作本组件生成的备份：不写它，裁剪时也不动它。
 */
@Slf4j
public final class TimestampedFileBackup {

    /**
     * 未另行指定时保留的份数
     */
    public static final int DEFAULT_KEEP = 10;

    /**
     * 保留份数下限
     */
    public static final int MIN_KEEP = 1;

    /**
     * 保留份数上限
     */
    public static final int MAX_KEEP = 100;

    private static final String SUFFIX = ".bak";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path file;

    private final Clock clock;

    private final Pattern backupName;

    /**
     * 按系统默认时区的当前时刻来命名备份
     * @param file 被备份的文件
     */
    public TimestampedFileBackup(Path file) {
        this(file, Clock.systemDefaultZone());
    }

    /**
     * @param file 被备份的文件
     * @param clock 命名用的钟；同一秒内连写两份会撞名覆盖
     */
    public TimestampedFileBackup(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
        this.backupName = Pattern.compile("^" + Pattern.quote(file.getFileName().toString())
                + "\\.\\d{8}-\\d{6}\\.bak$");
    }

    /**
     * 把当前文件复制成一份带时间戳的备份，再裁掉超出 {@code keep} 的旧份。
     * <p>
     * 文件还不在时什么也不做。保留份数落到 [{@link #MIN_KEEP}, {@link #MAX_KEEP}] 后再裁。
     * @param keep 打算留下的份数
     * @throws IOException 复制失败时抛出；裁剪失败不影响这次备份本身
     */
    public void backup(int keep) throws IOException {
        if (!Files.exists(file)) {
            return;
        }

        String name = file.getFileName() + "."
                + STAMP.withZone(clock.getZone()).format(clock.instant()) + SUFFIX;
        Files.copy(file, file.resolveSibling(name), StandardCopyOption.REPLACE_EXISTING);
        log.debug("已备份 {} 至 {}", file.getFileName(), name);

        prune(clamp(keep));
    }

    /**
     * 把保留份数收到允许区间内
     * @param keep 调用方给出的份数
     * @return [{@link #MIN_KEEP}, {@link #MAX_KEEP}] 内的值
     */
    public static int clamp(int keep) {
        if (keep < MIN_KEEP) {
            return MIN_KEEP;
        }
        if (keep > MAX_KEEP) {
            return MAX_KEEP;
        }
        return keep;
    }

    private void prune(int keep) {
        try (Stream<Path> files = Files.list(directory())) {
            files.filter(this::isBackup)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .skip(keep)
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.debug("删除旧备份 {} 失败: {}", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.debug("清理旧备份失败: {}", e.getMessage());
        }
    }

    private Path directory() {
        Path parent = file.toAbsolutePath().getParent();
        return parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
    }

    private boolean isBackup(Path path) {
        return Files.isRegularFile(path) && backupName.matcher(path.getFileName().toString()).matches();
    }
}
