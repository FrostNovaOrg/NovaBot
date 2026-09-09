package org.frostnova.nova.core.config.ui;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 给一份文件留带时间戳的备份，并裁掉超出保留份数的旧份。
 * <p>
 * 备份名形如 {@code 原文件名.yyyyMMdd-HHmmss.bak}；同一秒里连写的第二份起带序号后缀，
 * 形如 {@code 原文件名.yyyyMMdd-HHmmss-2.bak}，互不覆盖。旧的单份 {@code 原文件名.bak}
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
     * @param clock 命名用的钟；同一秒内连写的第二份起以序号后缀区分
     */
    public TimestampedFileBackup(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
        this.backupName = Pattern.compile("^" + Pattern.quote(file.getFileName().toString())
                + "\\.\\d{8}-\\d{6}(-\\d+)?\\.bak$");
    }

    /**
     * 把当前文件复制成一份带时间戳的备份，再裁掉超出 {@code keep} 的旧份。
     * <p>
     * 文件还不在时什么也不做。保留份数落到 [{@link #MIN_KEEP}, {@link #MAX_KEEP}] 后再裁。
     * <p>
     * <b>返回被裁掉的是哪几份</b>，而不是把删除咽在肚子里：这个类自己不该去写日志页
     * （它是个不认得 Spring 的工具类，安全模式下也在用它，那时时间线根本不在），
     * 但「旧备份被删了」是使用者要能看见的事——去翻备份目录却发现少了几份，
     * 而没有任何地方说过它们是被谁、什么时候删的。
     * @param keep 打算留下的份数
     * @return 本次删掉的备份文件名，按删除顺序；一份没删时为空表
     * @throws IOException 复制失败时抛出；裁剪失败不影响这次备份本身
     */
    public List<String> backup(int keep) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }

        String stamp = STAMP.withZone(clock.getZone()).format(clock.instant());
        Path target = file.resolveSibling(backupFileName(stamp, ""));
        // 同一秒里的第二份起加序号后缀，而不是覆盖前一份：覆盖会把「连存了两次」
        // 抹成只存过一次，第一份保存前的内容就此找不回来。探测与落盘之间的窗口
        // 不加锁——两处调用方（配置写口、安全模式）各自串行，不会同时走到这里。
        for (int seq = 2; Files.exists(target); seq++) {
            target = file.resolveSibling(backupFileName(stamp, "-" + seq));
        }
        Files.copy(file, target);
        log.debug("已备份 {} 至 {}", file.getFileName(), target.getFileName());

        return prune(clamp(keep));
    }

    private String backupFileName(String stamp, String suffix) {
        return file.getFileName() + "." + stamp + suffix + SUFFIX;
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

    /**
     * 裁掉超出保留份数的旧份
     * @return 真的删掉了的那几份的文件名；删不动的不算，它们还在盘上
     */
    private List<String> prune(int keep) {
        List<String> pruned = new ArrayList<>();

        try (Stream<Path> files = Files.list(directory())) {
            files.filter(this::isBackup)
                    .sorted(Comparator.comparing(this::stamp).reversed())
                    .skip(keep)
                    .forEach(path -> {
                        try {
                            // 只把「确实不在了」的记进去：deleteIfExists 答 false 的那一份
                            // 本来就没在，报它被删掉等于凭空多出一条
                            if (Files.deleteIfExists(path)) {
                                pruned.add(path.getFileName().toString());
                            }
                        } catch (IOException e) {
                            log.debug("删除旧备份 {} 失败: {}", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.debug("清理旧备份失败: {}", e.getMessage());
        }

        return pruned;
    }

    /**
     * 备份名里的时间戳与同秒序号，不带序号的首份视作序号 0
     * <p>
     * 排新旧的依据不能直接拿文件名字典序：同秒序号是变长数字，字典序里
     * {@code -2} 会排在 {@code -10} 之后，裁剪就会删错份。
     */
    private record Stamp(String stamp, int seq) implements Comparable<Stamp> {
        @Override
        public int compareTo(Stamp other) {
            int byStamp = stamp.compareTo(other.stamp);
            return byStamp != 0 ? byStamp : Integer.compare(seq, other.seq);
        }
    }

    /**
     * 认不出时间戳的名字排到最旧一端；正常情况下 {@link #isBackup} 已把它们拦在名单外
     */
    private static final Stamp OLDEST = new Stamp("", 0);

    private static final Pattern STAMP_WITH_SEQ =
            Pattern.compile(".*\\.(\\d{8}-\\d{6})(?:-(\\d+))?\\.bak$");

    private Stamp stamp(Path path) {
        Matcher matcher = STAMP_WITH_SEQ.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return OLDEST;
        }
        return new Stamp(matcher.group(1), matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2)));
    }

    private Path directory() {
        Path parent = file.toAbsolutePath().getParent();
        return parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
    }

    private boolean isBackup(Path path) {
        return Files.isRegularFile(path) && backupName.matcher(path.getFileName().toString()).matches();
    }
}
