package org.frostnova.nova.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.frostnova.nova.core.config.ui.auth.PasswordHash;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 候命开关开着、锁被另一进程占着时，门前不该动盘、不该起刷新和心跳。
 * 候命期间改过、要重启才生效的配置，过门后应记进待重启，并有一句日志。
 */
@DisplayName("候命门后的写盘、线程与配置")
class StandbyAfterGateTest {

    private static final String WAITING = "正在等这个目录里正在运行的那一份退出，再接手";

    private static final String PASSED = "候命门已过：门前用了 ";

    private static final String READY = "已就绪：过门到就绪用了 ";

    private static final String CHANGED = "候命期间配置改过：";

    private static final String PROBE = "未配置累计数据存储";

    private static final String HEARTBEAT = "事件输出心跳已排上";

    private static final String ROOM = "事件输出房间统计已排上";

    private static final String RESTART_KEY = "server.tomcat.threads.max";

    private static final String PASSWORD_KEY = "novabot.core.config-ui.auth.password";

    /**
     * 假口令、假令牌。失败输出里换成占位，不把这两段带出去。
     */
    private static final String PLAIN_PASSWORD = "gate-plain-pw";

    private static final String PLAIN_TOKEN = "gate-plain-tk";

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private static final Pattern TOKEN = Pattern.compile("token=([^\\s&]+)");

    private static final String CACHE_NAME =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef.png.tmp-half";

    @Test
    @DisplayName("等锁期间：过期时间线、报告图半成品、斜杠地址都不动；刷新和心跳都没起")
    void untouchedWhileWaiting(@TempDir Path root) throws Exception {
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writeConfig(dir, port);
        Fixtures fixtures = plant(dir);
        try (FileChannel channel = FileChannel.open(dir.resolve("novabot.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            assertTrue(held != null, "本进程应先占住锁");
            Running app = start(dir, port, true, false);
            try {
                waitFor(app, 90_000, "应走到等锁、且进程还在",
                        running -> running.out().contains(WAITING) && running.alive());
                assertFalse(app.out().contains(PASSED), "等锁时不该过门。\n" + dump(app));
                assertAll(
                        () -> assertTrue(Files.exists(fixtures.timeline),
                                "等锁期间时间线过期件不该被删"),
                        () -> assertTrue(Files.exists(fixtures.detail),
                                "等锁期间过期直播明细不该被删"),
                        () -> assertTrue(Files.exists(fixtures.legacyLog),
                                "等锁期间改名前的旧日志不该被删"),
                        () -> assertTrue(Files.exists(fixtures.image),
                                "等锁期间报告图缓存半成品不该被删"),
                        () -> assertTrue(Files.readString(fixtures.yml).contains("/127.0.0.1"),
                                "等锁期间 application.yml 不该被回写"),
                        () -> assertFalse(app.out().contains(PROBE),
                                "等锁期间刷新不该已经跑起来。\n" + dump(app)),
                        () -> assertFalse(app.out().contains(HEARTBEAT),
                                "等锁期间心跳不该已经排上。\n" + dump(app)),
                        () -> assertFalse(app.out().contains(ROOM),
                                "等锁期间房间统计不该已经排上。\n" + dump(app))
                );
            } finally {
                app.close();
            }
        }
    }

    @Test
    @DisplayName("等锁期间把要重启才生效的一项改掉，放锁后它在待重启里，日志有一句")
    void configChangedWhileWaiting(@TempDir Path root) throws Exception {
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writeConfig(dir, port);
        try (FileChannel channel = FileChannel.open(dir.resolve("novabot.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            assertTrue(held != null, "本进程应先占住锁");
            Running app = start(dir, port, true, false);
            try {
                waitFor(app, 90_000, "应走到等锁",
                        running -> running.out().contains(WAITING) && running.alive());
                Path yml = dir.resolve("application.yml");
                String text = Files.readString(yml);
                assertTrue(text.contains("max: 200"), "出发前配置里应有线程数上限 200");
                Files.writeString(yml, text.replace("max: 200", "max: 250"));
                held.release();
                waitFor(app, 90_000, "放锁后应就绪且令牌行出来",
                        running -> running.out().contains(READY) && TOKEN.matcher(running.out()).find());
                String status = status(port, tokenOf(app.out()));
                assertAll(
                        () -> assertTrue(restartPending(status).contains(RESTART_KEY),
                                "过门后待重启里应有 " + RESTART_KEY + "。状态片段：" + restartPending(status)),
                        () -> assertTrue(app.out().contains(CHANGED),
                                "日志应有一句候命期间配置改过。\n" + dump(app))
                );
            } finally {
                app.close();
            }
        }
    }

    @Test
    @DisplayName("绑定之后、构造之前改过要重启的一项，过门后它在待重启里")
    void configChangedAfterBindBeforeConstruct(@TempDir Path root) throws Exception {
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writeConfig(dir, port);
        Path source = dir.resolve("application.yml");
        Files.writeString(dir.resolve("application.yml.next"),
                Files.readString(source).replace("max: 200", "max: 201"));
        try (FileChannel channel = FileChannel.open(dir.resolve("novabot.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            assertTrue(held != null, "本进程应先占住锁");
            Running app = start(dir, port, true, true);
            try {
                waitFor(app, 90_000, "应走到等锁",
                        running -> running.out().contains(WAITING) && running.alive());
                String yml = Files.readString(dir.resolve("application.yml"));
                assertTrue(yml.contains("max: 201"), "构造前应已把线程数上限改成 201");
                held.release();
                waitFor(app, 90_000, "放锁后应就绪且令牌行出来",
                        running -> running.out().contains(READY) && TOKEN.matcher(running.out()).find());
                String status = status(port, tokenOf(app.out()));
                assertAll(
                        () -> assertTrue(restartPending(status).contains(RESTART_KEY),
                                "绑定之后才改的一项应在待重启里。状态片段：" + restartPending(status)),
                        () -> assertTrue(app.out().contains(CHANGED),
                                "日志应有一句候命期间配置改过。\n" + dump(app))
                );
            } finally {
                app.close();
            }
        }
    }

    @Test
    @DisplayName("开关关着：同一次启动里五处写盘和刷新、心跳都做了")
    void switchOffStillRuns(@TempDir Path root) throws Exception {
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writeConfig(dir, port);
        Fixtures fixtures = plant(dir);
        Running app = start(dir, port, false, false);
        try {
            waitFor(app, 90_000, "开关关着时应就绪",
                    running -> running.out().contains(READY));
            String yml = Files.readString(fixtures.yml);
            assertAll(
                    () -> assertFalse(Files.exists(fixtures.timeline), "时间线过期件应删掉"),
                    () -> assertFalse(Files.exists(fixtures.detail), "过期直播明细应删掉"),
                    () -> assertFalse(Files.exists(fixtures.legacyLog), "改名前的旧日志应删掉"),
                    () -> assertFalse(Files.exists(fixtures.image), "报告图缓存半成品应删掉"),
                    () -> assertFalse(yml.contains("/127.0.0.1"), "斜杠形态的监听地址应写回裸地址"),
                    () -> assertTrue(app.out().contains(PROBE), "刷新应在这次启动里跑过。\n" + dump(app)),
                    () -> assertTrue(app.out().contains(HEARTBEAT), "心跳应在这次启动里排上。\n" + dump(app)),
                    () -> assertTrue(app.out().contains(ROOM), "房间统计应在这次启动里排上。\n" + dump(app))
            );
        } finally {
            app.close();
        }
    }

    @Test
    @DisplayName("开关关着、配置里是明文登录口令：不报候命期间改过，待重启为空")
    void switchOffPlainPasswordNotPending(@TempDir Path root) throws Exception {
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writeConfig(dir, port);
        Path yml = dir.resolve("application.yml");
        String text = Files.readString(yml);
        String needle = "    config-ui:\n";
        assertTrue(text.contains(needle), "出发配置里应有控制台一节");
        Files.writeString(yml, text.replace(needle, needle
                + "      auth:\n"
                + "        password: \"local-plain-pass\"\n"
                + "        totp: false\n"
                + "        operator-token: true\n"));
        Running app = start(dir, port, false, false);
        try {
            waitFor(app, 90_000, "开关关着时应就绪且令牌行出来",
                    running -> running.out().contains(READY) && TOKEN.matcher(running.out()).find());
            String status = status(port, tokenOf(app.out()));
            String pending = restartPending(status);
            assertAll(
                    () -> assertFalse(app.out().contains(CHANGED),
                            "开关关着不该报候命期间配置改过。\n" + dump(app)),
                    () -> assertFalse(pending.contains(PASSWORD_KEY),
                            "明文口令换哈希不该进待重启。状态片段：" + pending),
                    () -> assertTrue(pending.contains("[]"),
                            "待重启应为空。状态片段：" + pending)
            );
        } finally {
            app.close();
        }
    }

    @Test
    @DisplayName("开关开、锁被占着、配置里是明文登录口令：候命期间配置文件逐字节不变")
    void plainPasswordUnchangedWhileWaiting(@TempDir Path root) throws Exception {
        // 抓的用户故障：升级时新版先起到门前候命，旧版还在跑。门前就把明文口令换成哈希写回，
        // 旧版这时若在设置页保存，两边互相盖掉，丢的那份没有备份可找。
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writeConfig(dir, port);
        plantPlainPassword(dir);
        byte[] before = Files.readAllBytes(dir.resolve("application.yml"));
        try (FileChannel channel = FileChannel.open(dir.resolve("novabot.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            assertTrue(held != null, "本进程应先占住锁");
            Running app = start(dir, port, true, false);
            try {
                waitFor(app, 90_000, "应走到等锁、且进程还在",
                        running -> running.out().contains(WAITING) && running.alive());
                assertFalse(app.out().contains(PASSED), "等锁时不该过门");
                assertTrue(Arrays.equals(before, Files.readAllBytes(dir.resolve("application.yml"))),
                        "候命期间 application.yml 应逐字节不变");
            } finally {
                app.close();
            }
        }
    }

    @Test
    @DisplayName("开关开、锁被占着、配置里是明文 NapCat 令牌：候命期间配置文件逐字节不变")
    void plainTokenUnchangedWhileWaiting(@TempDir Path root) throws Exception {
        // 抓的用户故障与登录口令那格相同：门前把明文令牌换成哈希写回，和还在跑的旧版互相盖文件。
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writeConfig(dir, port);
        plantPlainToken(dir);
        byte[] before = Files.readAllBytes(dir.resolve("application.yml"));
        try (FileChannel channel = FileChannel.open(dir.resolve("novabot.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            assertTrue(held != null, "本进程应先占住锁");
            Running app = start(dir, port, true, false);
            try {
                waitFor(app, 90_000, "应走到等锁、且进程还在",
                        running -> running.out().contains(WAITING) && running.alive());
                assertFalse(app.out().contains(PASSED), "等锁时不该过门");
                assertTrue(Arrays.equals(before, Files.readAllBytes(dir.resolve("application.yml"))),
                        "候命期间 application.yml 应逐字节不变");
            } finally {
                app.close();
            }
        }
    }

    @Test
    @DisplayName("开关开、真等过锁、配置里是明文登录口令：过门后不把这一项说成候命期间改过，待重启为空")
    void plainPasswordNotPendingAfterGate(@TempDir Path root) throws Exception {
        // 抓的用户故障：门前自己把明文换成哈希写回，过门核配置把这次写回当成候命期间别人改的，
        // 控制台叫用户重启，可谁也没改过。
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writeConfig(dir, port);
        plantPlainPassword(dir);
        try (FileChannel channel = FileChannel.open(dir.resolve("novabot.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            assertTrue(held != null, "本进程应先占住锁");
            Running app = start(dir, port, true, false);
            try {
                waitFor(app, 90_000, "应走到等锁",
                        running -> running.out().contains(WAITING) && running.alive());
                held.release();
                waitFor(app, 90_000, "放锁后应就绪且令牌行出来",
                        running -> running.out().contains(READY) && TOKEN.matcher(running.out()).find());
                String pending = restartPending(status(port, tokenOf(app.out())));
                assertAll(
                        () -> assertFalse(changedLineMentions(app.out(), PASSWORD_KEY),
                                "过门后不该把登录口令说成候命期间改过"),
                        () -> assertFalse(pending.contains(PASSWORD_KEY),
                                "登录口令不该进待重启。状态片段：" + pending),
                        () -> assertTrue(pending.contains("[]"),
                                "待重启应为空。状态片段：" + pending)
                );
            } finally {
                app.close();
            }
        }
    }

    @Test
    @DisplayName("开关关着、配置里是明文登录口令：起来后文件里是哈希、明文已不在")
    void switchOffPlainPasswordIsHashed(@TempDir Path root) throws Exception {
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writeConfig(dir, port);
        plantPlainPassword(dir);
        Running app = start(dir, port, false, false);
        try {
            waitFor(app, 90_000, "开关关着时应就绪",
                    running -> running.out().contains(READY));
            String text = Files.readString(dir.resolve("application.yml"));
            assertAll(
                    () -> assertFalse(text.contains(PLAIN_PASSWORD), "起来后文件里不该还有明文口令"),
                    () -> assertTrue(PasswordHash.isHashed(yamlScalar(text, "password:")),
                            "起来后登录口令应已是哈希")
            );
        } finally {
            app.close();
        }
    }

    @Test
    @DisplayName("开关关着、配置里是明文 NapCat 令牌：起来后文件里是哈希、明文已不在")
    void switchOffPlainTokenIsHashed(@TempDir Path root) throws Exception {
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writeConfig(dir, port);
        plantPlainToken(dir);
        Running app = start(dir, port, false, false);
        try {
            waitFor(app, 90_000, "开关关着时应就绪",
                    running -> running.out().contains(READY));
            String text = Files.readString(dir.resolve("application.yml"));
            assertAll(
                    () -> assertFalse(text.contains(PLAIN_TOKEN), "起来后文件里不该还有明文令牌"),
                    () -> assertTrue(SHA256_HEX.matcher(text).find(), "起来后应写有令牌哈希")
            );
        } finally {
            app.close();
        }
    }

    private static void plantPlainPassword(Path dir) throws IOException {
        Path yml = dir.resolve("application.yml");
        String text = Files.readString(yml);
        String needle = "    config-ui:\n";
        if (!text.contains(needle)) {
            throw new IOException("出发配置里没有控制台一节");
        }
        Files.writeString(yml, text.replace(needle, needle
                + "      auth:\n"
                + "        password: \"" + PLAIN_PASSWORD + "\"\n"
                + "        totp: false\n"
                + "        operator-token: true\n"));
    }

    private static void plantPlainToken(Path dir) throws IOException {
        Path yml = dir.resolve("application.yml");
        Files.writeString(yml, Files.readString(yml)
                + "  adapter:\n"
                + "    onebot:\n"
                + "      napcat:\n"
                + "        address: http://127.0.0.1:6099\n"
                + "        token: \"" + PLAIN_TOKEN + "\"\n"
                + "        token-hash: \"\"\n");
    }

    private static boolean changedLineMentions(String out, String key) {
        int at = 0;
        while (at >= 0) {
            at = out.indexOf(CHANGED, at);
            if (at < 0) {
                return false;
            }
            int end = out.indexOf('\n', at);
            String line = end < 0 ? out.substring(at) : out.substring(at, end);
            if (line.contains(key)) {
                return true;
            }
            at += CHANGED.length();
        }
        return false;
    }

    private static String yamlScalar(String yaml, String keyPrefix) {
        for (String line : yaml.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(keyPrefix)) {
                continue;
            }
            String value = trimmed.substring(keyPrefix.length()).trim();
            if (value.length() >= 2
                    && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                    || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }
        return "";
    }

    private static void writeConfig(Path dir, int port) throws IOException {
        Files.writeString(dir.resolve("application.yml"), ""
                + "server:\n"
                + "  address: \"/127.0.0.1\"\n"
                + "  port: " + port + "\n"
                + "  tomcat:\n"
                + "    threads:\n"
                + "      max: 200\n"
                + "novabot:\n"
                + "  core:\n"
                + "    event-stream:\n"
                + "      enabled: true\n"
                + "    timeline:\n"
                + "      retention-days: 14\n"
                + "    live:\n"
                + "      detail-retention-days: 1\n"
                + "    config-ui:\n"
                + "      agreement:\n"
                + "        accepted-version: 1\n"
                + "        accepted-by: local\n", StandardCharsets.UTF_8);
    }

    private static Fixtures plant(Path dir) throws IOException {
        Path timeline = dir.resolve("timeline/2000-01-01.jsonl");
        Files.createDirectories(timeline.getParent());
        Files.writeString(timeline, "{}\n");
        Path detail = dir.resolve("details/bilibili-1-1000/detail.json");
        Files.createDirectories(detail.getParent());
        Files.writeString(detail, "{}");
        Path legacy = dir.resolve("logs/2000-01/starbot-2000-01-01.log");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "old\n");
        Path image = dir.resolve("image-cache").resolve(CACHE_NAME);
        Files.createDirectories(image.getParent());
        Files.writeString(image, "half");
        Files.setLastModifiedTime(image, FileTime.from(Instant.now().minus(Duration.ofDays(40))));
        return new Fixtures(timeline, detail, legacy, image, dir.resolve("application.yml"));
    }

    private static int freePort() throws IOException {
        LoopbackPort.assumeAllowed();
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /**
     * 子进程自己带上构建信息。干净检出里没有旧的构建件，报告绘图器仍要这一份才能起来。
     * 要造「绑定之后才改文件」时，把换文件的登记也放在同一处，产品里不留这个口子。
     */
    private static Path bootInfo(Path dir, boolean shift) throws IOException {
        Path meta = dir.resolve("boot-info/META-INF");
        Files.createDirectories(meta);
        Files.writeString(meta.resolve("build-info.properties"), ""
                + "build.artifact=nova-core\n"
                + "build.group=org.frostnova.nova\n"
                + "build.name=NovaBot\n"
                + "build.version=0\n"
                + "build.time=2026-01-01T00\\:00\\:00Z\n", StandardCharsets.UTF_8);
        if (shift) {
            Files.writeString(meta.resolve("spring.factories"),
                    "org.springframework.context.ApplicationContextInitializer="
                            + ShiftBoundConfigBeforeBeans.class.getName() + "\n",
                    StandardCharsets.UTF_8);
        }
        return meta.getParent();
    }

    private static Running start(Path dir, int port, boolean standby, boolean shift) throws IOException {
        Path out = dir.resolve("stdout.txt");
        Path err = dir.resolve("stderr.txt");
        Path args = dir.resolve("java-args.txt");
        StringBuilder text = new StringBuilder();
        text.append("-Dfile.encoding=UTF-8\n");
        text.append("-Dlogging.config=").append(productionLogback()).append('\n');
        text.append("-Dnovabot.standby=").append(standby).append('\n');
        if (shift) {
            text.append("-D").append(ShiftBoundConfigBeforeBeans.PROPERTY).append("=true\n");
        }
        text.append("-cp\n");
        text.append(bootInfo(dir, shift).toAbsolutePath()).append(java.io.File.pathSeparator)
                .append(System.getProperty("java.class.path")).append('\n');
        text.append(NovaCoreApplication.class.getName()).append('\n');
        text.append("--server.address=127.0.0.1\n");
        text.append("--server.port=").append(port).append('\n');
        Files.writeString(args, text.toString(), StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + args.toAbsolutePath())
                .directory(dir.toFile())
                .redirectOutput(out.toFile())
                .redirectError(err.toFile())
                .start();
        return new Running(process, out, err);
    }

    private static void waitFor(Running app, long budgetMs, String what, Check check) throws Exception {
        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok(app)) {
                return;
            }
            if (!app.process.isAlive()) {
                break;
            }
            Thread.sleep(50);
        }
        if (check.ok(app)) {
            return;
        }
        fail(what + "\n" + dump(app));
    }

    private static String productionLogback() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        Path repo = null;
        for (Path cursor = dir; cursor != null; cursor = cursor.getParent()) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isDirectory(cursor.resolve("plugins"))
                    && Files.isDirectory(cursor.resolve("tools"))) {
                repo = cursor;
                break;
            }
        }
        if (repo == null) {
            throw new IOException("找不到仓库根，正式日志配置没法定位");
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(repo)) {
            return walk.filter(path -> "logback.xml".equals(path.getFileName().toString()))
                    .filter(path -> path.toString().contains("src" + java.io.File.separator + "main"))
                    .findFirst()
                    .orElseThrow(() -> new IOException("找不到正式日志配置"))
                    .toString();
        }
    }

    private static String tokenOf(String out) {
        Matcher matcher = TOKEN.matcher(out);
        assertTrue(matcher.find(), "就绪日志里应有进入地址");
        return matcher.group(1);
    }

    private static String status(int port, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/config/api/status?token=" + token))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertTrue(response.statusCode() == 200,
                "状态接口应返回 200，实际 " + response.statusCode() + " " + response.body());
        return response.body();
    }

    private static String restartPending(String status) {
        int at = status.indexOf("\"restartPending\"");
        if (at < 0) {
            return "";
        }
        int end = status.indexOf(']', at);
        if (end < 0) {
            return status.substring(at);
        }
        return status.substring(at, end + 1);
    }

    private static String dump(Running app) throws IOException {
        String exited = app.process.isAlive() ? "还在跑" : ("退码 " + app.process.exitValue());
        return "进程" + exited + "\n标准输出：\n" + redact(app.out()) + "\n标准错误：\n" + redact(app.err());
    }

    private static String redact(String text) {
        return TOKEN.matcher(text).replaceAll("token=(已隐去)")
                .replace(PLAIN_PASSWORD, "(口令已隐去)")
                .replace(PLAIN_TOKEN, "(令牌已隐去)");
    }

    @FunctionalInterface
    private interface Check {
        boolean ok(Running app) throws IOException;
    }

    private record Fixtures(Path timeline, Path detail, Path legacyLog, Path image, Path yml) {
    }

    private static final class Running implements AutoCloseable {
        final Process process;
        final Path out;
        final Path err;

        Running(Process process, Path out, Path err) {
            this.process = process;
            this.out = out;
            this.err = err;
        }

        String out() throws IOException {
            return Files.exists(out) ? Files.readString(out) : "";
        }

        String err() throws IOException {
            return Files.exists(err) ? Files.readString(err) : "";
        }

        boolean alive() {
            return process.isAlive();
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
