package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.util.DurableFiles;
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
        return backup(keep, nextBackupFile());
    }

    /**
     * @param keep 打算留下的份数
     * @param target {@link #nextBackupFile()} 算出的这一份备份件
     * @return 本次删掉的备份文件名，按删除顺序；一份没删时为空表
     * @throws IOException 复制失败时抛出；裁剪失败不影响这次备份本身
     */
    private List<String> backup(int keep, Path target) throws IOException {
        Files.copy(file, target);
        log.debug("已备份 {} 至 {}", file.getFileName(), target.getFileName());

        return prune(clamp(keep));
    }

    /**
     * 算这一份备份件建在哪里。同一秒里的第二份起加序号后缀，而不是覆盖前一份：覆盖会把
     * 「连存了两次」抹成只存过一次，第一份保存前的内容就此找不回来。探测与落盘之间的
     * 窗口不加锁——两处调用方（配置写口、安全模式）各自串行，不会同时走到这里。
     */
    private Path nextBackupFile() {
        String stamp = STAMP.withZone(clock.getZone()).format(clock.instant());
        Path target = file.resolveSibling(backupFileName(stamp, ""));
        for (int seq = 2; Files.exists(target); seq++) {
            target = file.resolveSibling(backupFileName(stamp, "-" + seq));
        }
        return target;
    }

    private String backupFileName(String stamp, String suffix) {
        return file.getFileName() + "." + stamp + suffix + SUFFIX;
    }

    /**
     * 给一次保存先留备份；目录建不出新文件时跳过这一步，不把它当失败抛出。
     * <p>
     * 写盘那一步建不出临时件时会退回直接写（见 {@link DurableFiles}），于是「配置文件本身
     * 写得进、只是同目录里建不了新文件」的部署（只读的容器根上单独挂一个可写的配置文件）
     * 写得上；备份却建在同一个目录里，先前一遇这种部署就在备份这一步先抛出去，
     * 整次保存做不成。认法与退回直接写同一道
     * （{@link DurableFiles#onlyMeansDirectoryRefusesNewFiles}）；其余失败
     * （磁盘满这一类，那时保存本身也写不成）照旧抛出，不让「备份失败」被悄悄咽掉。
     *
     * @param keep 打算留下的份数
     * @return 备份结果：留成时带裁掉的旧份，跳过时带建不出来那一步的异常与没建出来的那一份
     * @throws IOException 目录建不出新文件之外的备份失败
     */
    public BackupOutcome backupForSave(int keep) throws IOException {
        if (!Files.exists(file)) {
            return new BackupOutcome(List.of(), null, null);
        }
        Path target = nextBackupFile();
        try {
            return new BackupOutcome(backup(keep, target), null, target);
        } catch (IOException cannotCreate) {
            if (!DurableFiles.onlyMeansDirectoryRefusesNewFiles(cannotCreate, directory())) {
                throw cannotCreate;
            }
            return new BackupOutcome(List.of(), cannotCreate, target);
        }
    }

    /**
     * 一次保存前的备份这一步的结果
     *
     * @param pruned 留成备份时裁掉的旧份文件名，按删除顺序；没留成时为空表
     * @param skippedFor 没留成的原因（目录建不出新文件那一步的异常）；留成了时为 {@code null}
     * @param intendedBackup 这次打算建的那一份备份件；文件本来不在、什么都没做时为 {@code null}
     */
    public record BackupOutcome(List<String> pruned, IOException skippedFor, Path intendedBackup) {

        /**
         * 这次保存有没有留成备份
         */
        public boolean skipped() {
            return skippedFor != null;
        }

        /**
         * 「没留备份」的日志页事件：保存走了下去、备份没留成是使用者要能看见的事——
         * 备份目录里从此不再多出新份，而没有任何地方说过为什么。只说没留备份和为什么，
         * 不说保存成没成：记下这一条时写盘还没开始，成不成由回话去说。能记日志页的两个
         * 调用方（配置、推送配置）共用这一份拼法；安全模式没有日志页，自己往工程日志记 WARN。
         *
         * @param what 备份的对象，如「配置」「推送配置」
         * @return 日志页事件
         */
        public TimelineEvent skippedEvent(String what) {
            return TimelineEvent.of(TimelineEventType.BACKUP_SKIPPED, TimelineEvent.Level.WARN)
                    .text(what + "没留备份：所在目录建不出新文件（没有权限或文件系统只读）")
                    .detail("reason", backupFileNotCreated())
                    .build();
        }

        /**
         * detail 里说人话：点出建不出的是哪一份备份件，不把 Java 类名带到日志页上。
         * 路径用备份那一步本来就算好的那一份，不从异常里取——Windows 上建件被拒时
         * 异常同时带源、目标两个路径，取到的是源，也就是配置文件本身，照它说就把
         * 配置文件说成了建不出的备份件。拿不到那一份时退回异常自己的消息
         */
        private String backupFileNotCreated() {
            if (intendedBackup != null) {
                return "建不出备份文件 " + intendedBackup;
            }
            String message = skippedFor.getMessage();
            return message == null ? "建不出备份文件" : "建不出备份文件 " + message;
        }
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
