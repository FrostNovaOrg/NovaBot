package org.frostnova.nova.core.config;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改名前的旧日志按保留天数清掉
 * <p>
 * 5.4 起日志改用 {@code novabot-} 前缀，改名前落下的 {@code starbot-*.log} 从此没人写、也没人清。
 * 新名走 logback 自己的 {@code maxHistory}，旧名却一天都不删：一台跑了两年的机器上，
 * 目录里躺着几百件带着观众昵称与 uid 的旧日志，占着盘，也在一台机器上越攒越多他人的身份数据。
 * <p>
 * 清法只认<b>完整</b>的旧名：树名对得上、日期段是两位月两位日、后缀恰是 {@code .log}。
 * 名字相近的（{@code .log.gz}、少补零的日期、非法日期）一律不碰——把「像」当成「是」，
 * 删掉的就是不该删的东西。位置也要完整：只认「树/年-月/旧名」这一层。
 * 备份目录、更深一层、直接放在树下的同名件都不碰。软链不跟随：链接与它指向的文件都留着。
 * 有一个月目录读不了就跳过，记一条警告，别的过期件照删。
 * <p>
 * 保留天数<b>不另抄一份</b>，从正在写的滚动策略上读；这把尺有一条专门掰这一点。
 */
@DisplayName("改名前的旧日志按保留天数清")
class LegacyLogCleanupTest {

    /** 量这把尺的「今天」。写死它，跨零点跑也不会变色 */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);

    /** 真 logback.xml 里那棵树的形状：${LOG_HOME} 后头紧跟着的是树名 */
    private static final Pattern TREE_OF_XML = Pattern.compile(
            "<fileNamePattern>\\$\\{LOG_HOME}/([^/]+)/");

    private static final Pattern ROLLING_POLICY = Pattern.compile(
            "<rollingPolicy[^>]*>(.*?)</rollingPolicy>", Pattern.DOTALL);

    private static final Pattern FILE_NAME_PATTERN = Pattern.compile(
            "<fileNamePattern>([^<]+)</fileNamePattern>");

    private static final Pattern MAX_HISTORY = Pattern.compile(
            "<maxHistory>(\\d+)</maxHistory>");

    private ListAppender<ILoggingEvent> appender;

    private ch.qos.logback.classic.Logger logger;

    private Level originalLevel;

    @BeforeEach
    void attach() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LegacyLogCleaner.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    private void sweep(Path logHome, Map<String, Integer> retention) {
        LegacyLogCleaner.sweep(logHome, retention, TODAY);
    }

    private Path file(Path logHome, String relative, long size) throws IOException {
        Path path = logHome.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[(int) size]);
        return path;
    }

    private Path emptyFile(Path logHome, String relative) throws IOException {
        Path path = logHome.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.createFile(path);
        return path;
    }

    private List<String> infoLines() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private List<String> warnLines() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("三棵树里过期的旧名件都删掉")
    void pastRetentionIsRemovedFromEveryTree(@TempDir Path logHome) throws IOException {
        Path inLogs = emptyFile(logHome, "logs/2026-08/starbot-2026-08-01.log");
        Path inEvent = emptyFile(logHome, "EventDebug/2026-09/starbot-event-2026-09-01.log");
        Path inNetwork = emptyFile(logHome, "NetworkDebug/2026-08/starbot-network-2026-08-01.log");

        sweep(logHome, Map.of("logs", 30, "EventDebug", 7, "NetworkDebug", 30));

        assertFalse(Files.exists(inLogs), "logs 树里过期的旧名件该删掉");
        assertFalse(Files.exists(inEvent), "EventDebug 树里过期的旧名件该删掉");
        assertFalse(Files.exists(inNetwork), "NetworkDebug 树里过期的旧名件该删掉");
    }

    @Test
    @DisplayName("名字像旧名的别的文件一件都不许碰")
    void namesThatOnlyLookOldAreLeftAlone(@TempDir Path logHome) throws IOException {
        Map<String, Integer> retention = Map.of("logs", 30, "EventDebug", 7, "NetworkDebug", 30);
        List<Path> kept = List.of(
                // 还在保留期内的旧名件
                emptyFile(logHome, "logs/2026-09/starbot-2026-09-20.log"),
                // 新名件是 logback 自己的活，这把尺不插手
                emptyFile(logHome, "logs/2026-08/novabot-2026-08-01.log"),
                // 树不对：EventDebug 的旧名带 -event-
                emptyFile(logHome, "EventDebug/2026-08/starbot-2026-08-01.log"),
                // 压缩过的、后缀不完整
                emptyFile(logHome, "logs/2026-08/starbot-2026-08-01.log.gz"),
                // 日期段读不成日子
                emptyFile(logHome, "logs/2026-08/starbot-2026-99-99.log"),
                // 少补零，不是改名前那种写法
                emptyFile(logHome, "logs/2026-08/starbot-2026-8-1.log"),
                // 不在三棵树里
                emptyFile(logHome, "starbot-2026-08-01.log"),
                emptyFile(logHome, "OtherTree/2026-08/starbot-2026-08-01.log"));

        sweep(logHome, retention);

        for (Path path : kept) {
            assertTrue(Files.exists(path), "不该删却删了: " + logHome.relativize(path));
        }
    }

    @Test
    @DisplayName("留几天就是几天：界上留一天，界外删一天")
    void deletionStopsExactlyAtTheRetentionLine(@TempDir Path logHome) throws IOException {
        Path eventKept = emptyFile(logHome, "EventDebug/2026-09/starbot-event-2026-09-18.log");
        Path eventGone = emptyFile(logHome, "EventDebug/2026-09/starbot-event-2026-09-17.log");
        Path logsKept = emptyFile(logHome, "logs/2026-08/starbot-2026-08-26.log");
        Path logsGone = emptyFile(logHome, "logs/2026-08/starbot-2026-08-25.log");

        sweep(logHome, Map.of("logs", 30, "EventDebug", 7));

        assertTrue(Files.exists(eventKept), "7 天档：日子 == 今天减 7 的那件要留着");
        assertFalse(Files.exists(eventGone), "7 天档：再早一天的该删");
        assertTrue(Files.exists(logsKept), "30 天档：日子 == 今天减 30 的那件要留着");
        assertFalse(Files.exists(logsGone), "30 天档：再早一天的该删");
    }

    @Test
    @DisplayName("软链不跟随：链接与它指向的文件都留着")
    void symlinkedOldNameIsLeftAlone(@TempDir Path logHome, @TempDir Path outside) throws IOException {
        Path target = outside.resolve("somewhere-else.log");
        Files.write(target, new byte[32]);
        Path link = logHome.resolve("logs/2026-08/starbot-2026-08-01.log");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, target);

        sweep(logHome, Map.of("logs", 30));

        assertTrue(Files.isSymbolicLink(link), "链接本身要留着");
        assertTrue(Files.exists(target), "链接指向的文件更要留着——那多半不是本机的日志");
    }

    @Test
    @DisplayName("叫旧名的目录不删")
    void directoryWithAnOldNameIsLeftAlone(@TempDir Path logHome) throws IOException {
        Path directory = logHome.resolve("logs/2026-08/starbot-2026-08-01.log");
        Files.createDirectories(directory);

        sweep(logHome, Map.of("logs", 30));

        assertTrue(Files.isDirectory(directory), "目录不是旧日志，名字再像也不删");
    }

    /**
     * 改名前只往「树/年-月/」这一层写。人把旧件挪进备份、再套一层，或直接丢在树下，都要留着。
     * 阴性对照是仍在月份目录里的过期件，那一件照删；删空了的月份目录一并去掉，里面还有东西的不删。
     */
    @Test
    @DisplayName("挪走的旧名件留着：备份、更深一层、直接放在树下都不删")
    void oldLogsMovedOutOfTheMonthFolderAreLeftAlone(@TempDir Path logHome) throws IOException {
        Path archived = emptyFile(logHome, "logs/archive/starbot-2020-01-02.log");
        Path deeper = emptyFile(logHome, "logs/2026-08/deeper/starbot-2020-01-01.log");
        Path nestedMonth = emptyFile(logHome, "logs/2026-08/backup/2020-01/starbot-2020-01-07.log");
        Path shallow = emptyFile(logHome, "logs/starbot-2020-01-03.log");
        Path eventBackup = emptyFile(logHome, "EventDebug/backup/starbot-event-2020-01-05.log");
        Path inPlace = emptyFile(logHome, "logs/2026-07/starbot-2020-01-06.log");
        Path keptBeside = emptyFile(logHome, "logs/2026-08/novabot-2026-08-01.log");

        sweep(logHome, Map.of("logs", 30, "EventDebug", 7));

        assertTrue(Files.exists(archived), "备份目录里的旧名件要留着");
        assertTrue(Files.exists(deeper), "月份目录再往里一层的旧名件要留着");
        assertTrue(Files.exists(nestedMonth), "更深一层里即使父目录像年-月，也不该删");
        assertTrue(Files.exists(shallow), "直接放在树下的旧名件要留着");
        assertTrue(Files.exists(eventBackup), "排障目录的备份里的旧名件要留着");
        assertFalse(Files.exists(inPlace), "月份目录里过期的旧名件照删");
        assertFalse(Files.exists(inPlace.getParent()), "旧件删空后的月份目录一并去掉");
        assertTrue(Files.isDirectory(logHome.resolve("logs/2026-08")), "里面还有别的文件，月份目录要留着");
        assertTrue(Files.exists(keptBeside), "同目录里的新名件不插手");
        assertTrue(Files.isDirectory(archived.getParent()), "备份目录本身不删");
        assertTrue(Files.isDirectory(deeper.getParent()), "没删空的子目录不删");
    }

    /**
     * 某个月份目录读不了时，这一趟不能抛出去，同树别的月份和别的树照清，并记一条警告。
     * 读得了权限为 000 的目录时（例如以 root 跑）本格不成立，跳过。
     */
    @Test
    @DisplayName("有一个月目录读不了：跳过它，别的过期件照删，记一条警告")
    void unreadableMonthDirectoryIsSkippedAndTheRestAreRemoved(@TempDir Path logHome) throws IOException {
        Path blocked = logHome.resolve("logs/2026-08");
        Files.createDirectories(blocked);
        Path hidden = blocked.resolve("starbot-2020-01-01.log");
        Files.writeString(hidden, "leave-this");
        Path sameTree = emptyFile(logHome, "logs/2026-07/starbot-2020-01-02.log");
        Path otherTree = emptyFile(logHome, "EventDebug/2026-01/starbot-event-2020-01-03.log");

        Files.setPosixFilePermissions(blocked, EnumSet.noneOf(PosixFilePermission.class));
        try {
            boolean readableDespiteMode = false;
            try (var probe = Files.list(blocked)) {
                probe.count();
                readableDespiteMode = true;
            } catch (IOException ignored) {
                readableDespiteMode = false;
            }
            Assumptions.assumeFalse(readableDespiteMode, "当前用户读得了权限为 000 的目录，本格跳过");

            Map<String, Integer> retention = new LinkedHashMap<>();
            retention.put("logs", 30);
            retention.put("EventDebug", 7);
            sweep(logHome, retention);

            assertFalse(Files.exists(sameTree), "同一棵树里别的月份照删");
            assertFalse(Files.exists(otherTree), "别的树照删");
            assertEquals(1, warnLines().size(), "读不了要记一条警告, 实际: " + warnLines());
        } finally {
            Files.setPosixFilePermissions(blocked, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
        assertTrue(Files.exists(hidden), "读不了的月份目录里的旧名件不该被删掉");
    }

    @Test
    @DisplayName("删了几件就报几件共几 MB；一件没删时不占一行")
    void reportsWhatItDeletedOnlyWhenItDeletedSomething(@TempDir Path logHome) throws IOException {
        long oneFile = 1024 * 1024 + 10;
        file(logHome, "logs/2026-08/starbot-2026-08-01.log", oneFile);
        file(logHome, "logs/2026-08/starbot-2026-08-02.log", oneFile);

        sweep(logHome, Map.of("logs", 30));

        List<String> lines = infoLines();
        assertEquals(1, lines.size(), "删了就报一行, 实际: " + lines);
        String report = lines.get(0);
        assertTrue(report.contains("2 件"), "件数要写出来: " + report);
        assertTrue(report.contains("2 MB"), "体积要按 MB 写出来: " + report);
        assertTrue(report.contains("旧日志"), "要看得出清的是哪一类: " + report);

        appender.list.clear();
        sweep(logHome, Map.of("logs", 30));
        assertEquals(List.of(), infoLines(), "一件没删就不该占日志的行");
    }

    /**
     * 保留天数从正在写的滚动策略上读，不另抄一份。
     * <p>
     * 先照真 {@code logback.xml} 起一遍（树名与天数都从那份文件里取），
     * 再把天数换成另一组数起一遍：天数若是在代码里写死的，后一遍就会答出前一遍的数。
     */
    @Test
    @DisplayName("保留天数读的是正在写的滚动策略，不是另抄的一份数字")
    void retentionDaysComeFromTheLiveAppenders(@TempDir Path logHome) throws IOException {
        List<Shape> shapes = realShapes(logHome);
        assertEquals(3, shapes.size(), "真日志配置里该恰有三棵树，判据自己先数得出: " + shapes);

        LoggerContext fromRealXml = contextWith(shapes);
        LegacyLogCleaner.Boot boot = LegacyLogCleaner.boot(fromRealXml);
        assertEquals(logHome, boot.logHome(), "日志根目录要从滚动策略的路径上认出来");
        assertEquals(daysOf(shapes), boot.retentionByTree(),
                "天数要与真配置里的一致；对不上就是这把尺在抄另一份数字");

        List<Shape> otherDays = shapes.stream()
                .map(s -> new Shape(s.tree(), s.fileNamePattern(), s.maxHistory() + 2))
                .toList();
        LegacyLogCleaner.Boot remeasured = LegacyLogCleaner.boot(contextWith(otherDays));
        assertEquals(daysOf(otherDays), remeasured.retentionByTree(),
                "天数改了读数就要跟着改；还答出旧的数，说明读的是写死的那份: " + remeasured.retentionByTree());
    }

    @Test
    @DisplayName("路径里认不出日志树的那种写法整个跳过")
    void patternWithoutATreeDirectoryIsIgnored(@TempDir Path logHome) {
        LoggerContext context = new LoggerContext();
        attach(context, "Loose", logHome.resolve("novabot-%d{yyyy-MM-dd}.log").toString(), 30);

        LegacyLogCleaner.Boot boot = LegacyLogCleaner.boot(context);

        assertEquals(Map.of(), boot.retentionByTree(), "认不出树就什么都不清，好过清错地方");
    }

    /** 一棵日志树上要读的两件事：树名、它的滚动策略写出的文件名形状与保留天数 */
    private record Shape(String tree, String fileNamePattern, int maxHistory) {
    }

    private static Map<String, Integer> daysOf(List<Shape> shapes) {
        Map<String, Integer> days = new LinkedHashMap<>();
        for (Shape shape : shapes) {
            days.put(shape.tree(), shape.maxHistory());
        }
        return days;
    }

    /**
     * 从本模块 {@code src/main/resources/logback.xml} 取三棵树的形状，把其中的日志根目录换成沙盘。
     * 树名用「${LOG_HOME}/ 后头那一段」认，与被测那侧走的路径不是同一条，
     * 两边对得上才算量过了。
     * 按测试进程的工作目录去找：构建时工作目录就是本模块，不必把模块目录名写进源码。
     */
    private static List<Shape> realShapes(Path logHome) throws IOException {
        Path xmlPath = Path.of("src/main/resources/logback.xml");
        if (!Files.isRegularFile(xmlPath)) {
            throw new IllegalStateException("找不到 logback.xml，工作目录 " + Path.of("").toAbsolutePath());
        }
        String xml = Files.readString(xmlPath, StandardCharsets.UTF_8);
        List<Shape> shapes = new ArrayList<>();
        Matcher policy = ROLLING_POLICY.matcher(xml);
        while (policy.find()) {
            String body = policy.group(1);
            Matcher name = FILE_NAME_PATTERN.matcher(body);
            Matcher days = MAX_HISTORY.matcher(body);
            Matcher tree = TREE_OF_XML.matcher(body);
            if (!name.find() || !days.find() || !tree.find()) {
                continue;
            }
            shapes.add(new Shape(tree.group(1),
                    name.group(1).replace("${LOG_HOME}", logHome.toString()),
                    Integer.parseInt(days.group(1))));
        }
        return shapes;
    }

    private static LoggerContext contextWith(List<Shape> shapes) {
        LoggerContext context = new LoggerContext();
        for (Shape shape : shapes) {
            attach(context, shape.tree(), shape.fileNamePattern(), shape.maxHistory());
        }
        return context;
    }

    /**
     * 起法照 {@code logback.xml}：滚动器埋在异步件底下，不拆开这层就什么也读不到。
     * 附到不同名字的 logger 上，好让「同一个滚动器被几个 logger 共用」这条也被量到
     */
    private static void attach(LoggerContext context, String name, String fileNamePattern, int maxHistory) {
        RollingFileAppender<ILoggingEvent> rolling = new RollingFileAppender<>();
        rolling.setContext(context);
        rolling.setName(name + "-rolling");

        TimeBasedRollingPolicy<ILoggingEvent> policy = new TimeBasedRollingPolicy<>();
        policy.setContext(context);
        policy.setParent(rolling);
        policy.setFileNamePattern(fileNamePattern);
        policy.setMaxHistory(maxHistory);
        policy.start();
        rolling.setRollingPolicy(policy);

        AsyncAppender async = new AsyncAppender();
        async.setContext(context);
        async.setName(name + "-async");
        async.addAppender(rolling);
        async.start();

        context.getLogger("probe-" + name).addAppender(async);
    }

    /**
     * 让「拆异步层」这条走得通：探一下被测那侧真会往下拆
     */
    @Test
    @DisplayName("拆开异步层才读得到滚动器——少了这一拆，上面几条是空跑")
    void theRulerReachesTheRollingAppenderUnderTheAsyncOne(@TempDir Path logHome) {
        LoggerContext context = new LoggerContext();
        attach(context, "logs", logHome.resolve("logs/%d{yyyy-MM,aux}/novabot-%d{yyyy-MM-dd}.log").toString(), 30);

        List<Appender<ILoggingEvent>> fromRoot = new ArrayList<>();
        collect(context.getLogger("probe-logs"), fromRoot);
        assertTrue(fromRoot.size() == 1 && fromRoot.get(0) instanceof RollingFileAppender,
                "判据自己先拆得到异步层底下的滚动器，不然上面那条恒真");
    }

    private static void collect(Object node, List<Appender<ILoggingEvent>> found) {
        if (node instanceof RollingFileAppender) {
            @SuppressWarnings("unchecked")
            Appender<ILoggingEvent> rolling = (Appender<ILoggingEvent>) node;
            found.add(rolling);
        }
        if (node instanceof AppenderAttachable<?> attachable) {
            Iterator<?> nested = attachable.iteratorForAppenders();
            while (nested.hasNext()) {
                collect(nested.next(), found);
            }
        }
    }
}
