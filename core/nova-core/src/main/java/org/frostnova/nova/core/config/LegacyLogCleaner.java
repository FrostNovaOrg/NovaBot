package org.frostnova.nova.core.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 清掉改名前落下的旧日志
 * <p>
 * 5.4 起日志改用 {@code novabot-} 前缀，改名前落下的 {@code starbot-*.log} 从此没人写、也没人清：
 * 新名走 logback 自己的 {@code maxHistory}，旧名一天都不删。一台跑了两年的机器上，
 * 目录里会躺着几百件旧日志占着盘，也一直把观众的昵称与 uid 留在盘上。
 * <p>
 * <b>保留天数不另抄一份</b>：从正在写的滚动策略上读同一个 {@code maxHistory}，
 * 日志根目录也从滚动策略写出的路径上认，配置改了这里跟着改。
 * <p>
 * <b>只认完整的旧名</b>：树名对得上、日期段是两位月两位日、后缀恰是 {@code .log}，
 * 整个文件名全等才算。名字相近的（{@code .log.gz}、少补零的日期、非法日期）一律不碰——
 * 把「像」当成「是」，删掉的就是不该删的东西。
 * <b>只认改名前写出的那一层</b>：{@code 树/年-月/旧名}。备份目录、更深或更浅的层级、
 * 别的子目录里的同名件一律不碰。旧件删空后的月份目录一并去掉，里面还有东西的目录不删。
 * <b>不跟符号链接</b>：链接与它指向的文件都留着，那多半不是本机的日志。
 * 也<b>不离开日志根目录</b>：删之前再核一遍整条路径还在根下面。
 * 读不了的目录、删不掉的件记一条警告后跳过，接着清别的；清理出错也不让启动失败。
 */
@Slf4j
@Component
public class LegacyLogCleaner {

    /**
     * 一天的毫秒数。注解要编译期常量，不能写 {@code Duration.ofDays(1)}
     */
    private static final long DAY_MILLIS = 86_400_000L;

    /**
     * 一兆的字节数，只用于把清出来的体积写成人话
     */
    private static final long MEGABYTE = 1024L * 1024L;

    /**
     * 改名前的旧文件名：树名 → 整名匹配的正则。
     * <p>
     * 形状取自改名那次提交，三条各对应一棵树——日志树的旧名不带中缀，
     * 排障那两棵带 {@code -event-}／{@code -network-}，不能按树名去凑。
     * 只列认得清的树：<b>没列进来的树一律不清</b>，那是保守的一侧，
     * 清错地方比留着旧件代价大。
     */
    private static final Map<String, Pattern> LEGACY_NAME_BY_TREE = Map.of(
            "logs", Pattern.compile("starbot-(\\d{4}-\\d{2}-\\d{2})\\.log"),
            "EventDebug", Pattern.compile("starbot-event-(\\d{4}-\\d{2}-\\d{2})\\.log"),
            "NetworkDebug", Pattern.compile("starbot-network-(\\d{4}-\\d{2}-\\d{2})\\.log"));

    /**
     * 启动时清一次，之后每天清一次
     */
    @PostConstruct
    public void cleanAtStartup() {
        cleanNow();
    }

    @Scheduled(fixedDelay = DAY_MILLIS, initialDelay = DAY_MILLIS)
    public void cleanDaily() {
        cleanNow();
    }

    private void cleanNow() {
        try {
            Boot boot = boot(liveContext());
            if (boot == null) {
                return;
            }
            sweep(boot.logHome(), boot.retentionByTree(), LocalDate.now());
        } catch (RuntimeException e) {
            log.warn("清理改名前的旧日志时出错，已跳过", e);
        }
    }

    /**
     * 开机要读的那一份：日志根目录，以及每棵树各自保留几天
     * @param logHome 日志根目录，认不出树时为 null
     * @param retentionByTree 树名 → 保留天数
     */
    record Boot(Path logHome, Map<String, Integer> retentionByTree) {
    }

    /**
     * 从正在写的滚动策略上读出日志根目录与每棵树的保留天数
     * <p>
     * 滚动器是埋在异步件底下的，得拆开那层才看得见。同一棵树被几个 logger 共用时只记一次。
     * 只认 {@link #LEGACY_NAME_BY_TREE} 里那三棵：路径上凑出来的别的目录名不当成树，
     * 否则临时目录、日志根自己的最后一段也会被拿去清。
     * {@code maxHistory} 落 0（＝不按天删）的树整个跳过：这把尺跟着不删，也不该比它更凶。
     * @param context 日志上下文
     * @return 读出来的那份；一棵树都没认出来时 logHome 为 null
     */
    static Boot boot(LoggerContext context) {
        Map<String, Integer> retention = new LinkedHashMap<>();
        Path logHome = null;
        for (Logger logger : context.getLoggerList()) {
            for (RollingFileAppender<?> rolling : rollingFileAppenders(logger)) {
                if (!(rolling.getRollingPolicy() instanceof TimeBasedRollingPolicy<?> policy)) {
                    continue;
                }
                int days = policy.getMaxHistory();
                if (days <= 0) {
                    continue;
                }
                Tree tree = treeOf(policy.getFileNamePattern());
                if (tree == null || !LEGACY_NAME_BY_TREE.containsKey(tree.name())) {
                    continue;
                }
                if (logHome == null) {
                    logHome = tree.logHome();
                }
                retention.putIfAbsent(tree.name(), days);
            }
        }
        return new Boot(logHome, retention);
    }

    /**
     * 按每棵树的保留天数，清掉「树/年-月/」那一层里过期的旧名件
     * @param logHome 日志根目录
     * @param retentionByTree 树名 → 保留天数
     * @param today 今天；写进来而不是就地取，好让跨零点跑也不变色
     */
    static void sweep(Path logHome, Map<String, Integer> retentionByTree, LocalDate today) {
        if (logHome == null || retentionByTree.isEmpty()) {
            return;
        }
        Path root = logHome.toAbsolutePath().normalize();
        int deleted = 0;
        long bytes = 0;
        for (Map.Entry<String, Integer> entry : retentionByTree.entrySet()) {
            Pattern legacyName = LEGACY_NAME_BY_TREE.get(entry.getKey());
            if (legacyName == null) {
                continue;
            }
            Path treeDir = root.resolve(entry.getKey());
            if (Files.isSymbolicLink(treeDir) || !Files.isDirectory(treeDir, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            LocalDate keepFrom = today.minusDays(entry.getValue());
            List<Path> months;
            try {
                months = listChildren(treeDir);
            } catch (UncheckedIOException e) {
                log.warn("读不到 {}，已跳过", entry.getKey(), e);
                continue;
            }
            for (Path month : months) {
                if (!isLegacyMonthDir(month, treeDir)) {
                    continue;
                }
                List<Path> files;
                try {
                    files = listChildren(month);
                } catch (UncheckedIOException e) {
                    log.warn("读不到 {}/{}，已跳过", entry.getKey(), month.getFileName(), e);
                    continue;
                }
                int removedHere = 0;
                for (Path path : files) {
                    if (!isPlainFile(path)) {
                        continue;
                    }
                    LocalDate written = legacyWrittenOn(path.getFileName().toString(), legacyName);
                    if (written == null || !written.isBefore(keepFrom)) {
                        continue;
                    }
                    // 保险带：整条路径必须还在日志根下面。走到这儿本该不会出根，
                    // 但删东西这一步值得再核一遍
                    if (!path.toAbsolutePath().normalize().startsWith(root)) {
                        continue;
                    }
                    try {
                        long size = Files.size(path);
                        Files.delete(path);
                        deleted++;
                        bytes += size;
                        removedHere++;
                    } catch (IOException e) {
                        log.warn("删不掉 {}，已跳过", path.getFileName(), e);
                    }
                }
                if (removedHere > 0) {
                    removeMonthDirIfEmpty(month);
                }
            }
        }
        if (deleted > 0) {
            log.info("已清理改名前的旧日志 {} 件, 共 {} MB", deleted, bytes / MEGABYTE);
        }
    }

    /**
     * 改名前写出的位置是「树/年-月/」。年-月必须直接位于这棵树下，再深一层不算。
     */
    private static boolean isLegacyMonthDir(Path month, Path treeDir) {
        Path parent = month.getParent();
        if (parent == null || !parent.equals(treeDir)) {
            return false;
        }
        if (Files.isSymbolicLink(month) || !Files.isDirectory(month, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        String name = month.getFileName().toString();
        if (!name.matches("\\d{4}-\\d{2}")) {
            return false;
        }
        try {
            YearMonth.parse(name);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static List<Path> listChildren(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 旧件已经删光、目录里什么都不剩时，去掉这个空的月份目录。删不掉就跳过。
     */
    private static void removeMonthDirIfEmpty(Path month) {
        if (Files.isSymbolicLink(month) || !Files.isDirectory(month, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> left = Files.list(month)) {
            if (left.findAny().isPresent()) {
                return;
            }
        } catch (IOException e) {
            log.warn("读不到 {}，已跳过", month.getFileName(), e);
            return;
        }
        try {
            Files.deleteIfExists(month);
        } catch (IOException e) {
            log.warn("删不掉 {}，已跳过", month.getFileName(), e);
        }
    }

    /**
     * 只有普通的真文件才删：软链与目录都不是旧日志，名字再像也不碰
     */
    private static boolean isPlainFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * 从旧文件名里读出它是哪天落下的
     * @param fileName 文件名
     * @param legacyName 这棵树的旧名整名正则
     * @return 那一天；不是完整旧名、或日期读不成日子时为 null（＝不删）
     */
    private static LocalDate legacyWrittenOn(String fileName, Pattern legacyName) {
        var matcher = legacyName.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return LocalDate.parse(matcher.group(1));
        } catch (DateTimeParseException e) {
            // 形状对、日子不对（例如 99 月 99 日）：宁可留着
            return null;
        }
    }

    /**
     * 从滚动策略写出的文件名形状上认出这棵树
     * <p>
     * 路径里最后一段<b>不含 {@code %}</b> 的是树的目录名，它的父目录就是日志根。
     * 文件名那段必含 {@code %}（那是滚动出来的），认不出目录、或文件名不含占位符的
     * 写法整个跳过——宁可不清，也不清错地方。
     * @param fileNamePattern 滚动策略写出的文件名形状（配置替换之后的）
     * @return 树名与日志根；认不出来为 null
     */
    private static Tree treeOf(String fileNamePattern) {
        if (fileNamePattern == null) {
            return null;
        }
        Path patternPath;
        try {
            patternPath = Path.of(fileNamePattern);
        } catch (InvalidPathException e) {
            return null;
        }
        int components = patternPath.getNameCount();
        if (components == 0 || !patternPath.getName(components - 1).toString().contains("%")) {
            return null;
        }
        int treeIndex = -1;
        for (int i = components - 2; i >= 0; i--) {
            if (!patternPath.getName(i).toString().contains("%")) {
                treeIndex = i;
                break;
            }
        }
        if (treeIndex < 0) {
            return null;
        }
        Path home;
        if (treeIndex > 0) {
            Path sub = patternPath.subpath(0, treeIndex);
            Path root = patternPath.getRoot();
            home = root == null ? sub : root.resolve(sub);
        } else {
            Path root = patternPath.getRoot();
            home = root != null ? root : Path.of(".");
        }
        return new Tree(patternPath.getName(treeIndex).toString(), home);
    }

    private record Tree(String name, Path logHome) {
    }

    /**
     * 拆开异步件，找出它底下埋着的滚动器
     * <p>
     * 不拆这层就什么都读不到，而「一棵树也没认出来」跟「配置干净」长得一样。
     */
    private static List<RollingFileAppender<?>> rollingFileAppenders(Object node) {
        List<RollingFileAppender<?>> found = new ArrayList<>();
        collect(node, found);
        return found;
    }

    private static void collect(Object node, List<RollingFileAppender<?>> found) {
        if (node instanceof RollingFileAppender<?> rolling) {
            found.add(rolling);
        }
        if (node instanceof AppenderAttachable<?> attachable) {
            Iterator<?> nested = attachable.iteratorForAppenders();
            while (nested.hasNext()) {
                Object next = nested.next();
                if (next instanceof Appender<?> || next instanceof AppenderAttachable<?>) {
                    collect(next, found);
                }
            }
        }
    }

    private static LoggerContext liveContext() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }
}
