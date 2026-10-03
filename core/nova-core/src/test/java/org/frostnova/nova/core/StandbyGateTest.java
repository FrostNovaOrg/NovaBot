package org.frostnova.nova.core;

import org.frostnova.nova.core.alert.LoopbackPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 候命开关开着时，这个目录已经有一份在跑：新的一份不该马上退出，也不该去绑端口、
 * 不该报就绪；旧的那份退出后才接手。等的时候若收到停机信号，应马上退，不去接手。
 * <p>
 * 配置写坏、锁取不到、等得太久，这三种在开关开着时都不该进安全模式、不该绑端口。
 * 开关关着时，配置写坏仍进安全模式，锁取不到仍照常启动。
 */
@DisplayName("候命门")
class StandbyGateTest {

    /**
     * 还没过门就起不来
     */
    private static final int EXIT_BEFORE_GATE = 74;

    /**
     * 锁取不到，分不清旧的那份还在不在
     */
    private static final int EXIT_LOCK_UNKNOWN = 75;

    /**
     * 等锁到点
     */
    private static final int EXIT_WAIT_EXPIRED = 76;

    /**
     * 收到停机信号
     */
    private static final int EXIT_SIGNAL = 143;

    /**
     * 开关关着、锁被占
     */
    private static final int EXIT_HELD = 1;

    private static final String CONSTRUCTED = "候命对象已建立";

    private static final String WAITING = "正在等这个目录里正在运行的那一份退出，再接手";

    private static final String PASSED = "候命门已过：门前用了 ";

    private static final String READY = "已就绪：过门到就绪用了 ";

    private static final String STANDBY_HINT = "先准备好，等它退出再接手";

    private static final String HELD_HINT = "已有一份 NovaBot 在运行";

    private static final String UNKNOWN_HINT = "不往下接手";

    private static final String EXPIRED_HINT = "先不接手";

    private static final String SAFE_MODE = "已进入安全模式";

    private static final String USUAL_START = "照常启动";

    private static final String STATE_WRITE_WARN = "状态件写不进";

    @Test
    @DisplayName("锁被另一进程占着：不退出、端口不绑、就绪不发；放锁后 1 秒内过门，随后端口绑上、就绪；再起一份开关关着的以 1 退出")
    void heldLockWaitsThenTakesOver(@TempDir Path root) throws Exception {
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writePlainConfig(dir, port);
        try (FileChannel channel = FileChannel.open(dir.resolve("novabot.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            assertTrue(held != null, "本进程应先占住锁");
            Path stateFile = dir.resolve("state.txt");
            Running app = start(dir, port, true, null, stateFile);
            PortWatch watch = PortWatch.start(port);
            try {
                waitFor(app, 90_000, "应走到等锁、且进程还在",
                        running -> running.out().contains(WAITING) && running.alive());
                assertEquals("waiting", readPhase(stateFile), "等锁期间状态件应是 waiting。\n" + dump(app));
                assertEquals(app.process.pid(), readPid(stateFile), "状态件里的 pid 应是被测进程的。\n" + dump(app));
                assertFalse(runningPort(port), "等锁时端口不该绑上。\n" + dump(app));
                assertFalse(app.out().contains(READY), "等锁时不该就绪。\n" + dump(app));
                assertFalse(app.out().contains(PASSED), "等锁时不该过门。\n" + dump(app));
                assertTrue(app.err().contains(STANDBY_HINT), "应说明先准备好、等它退出再接手。\n" + dump(app));
                assertFalse(watch.opened.get(), "等锁期间端口曾被绑上");

                // 从等锁到就绪，每次读状态件都把读到的阶段记进一串，相邻重复只记一次
                PhaseRecorder recorder = PhaseRecorder.start(stateFile);
                long releasedAt = System.currentTimeMillis();
                held.release();
                waitFor(app, 1_000, "放锁后 1 秒内应过门",
                        running -> running.out().contains(PASSED));
                long passedAt = System.currentTimeMillis();
                waitFor(app, 1_000 - (passedAt - releasedAt), "放锁后 1 秒内端口应绑上",
                        running -> runningPort(port));
                long portAt = System.currentTimeMillis();
                waitFor(app, 1_000 - (portAt - releasedAt), "放锁后 1 秒内应就绪",
                        running -> running.out().contains(READY));
                assertEquals("ready", readPhase(stateFile), "就绪后状态件应是 ready。\n" + dump(app));
                assertPhaseSequence(recorder.finish());
                long readyAt = System.currentTimeMillis();
                System.out.println("开关开，放锁到过门 " + (passedAt - releasedAt)
                        + " 毫秒，到端口 " + (portAt - releasedAt)
                        + " 毫秒，到就绪 " + (readyAt - releasedAt) + " 毫秒");
                System.out.println(lineContaining(app.out(), PASSED).trim());
                System.out.println(lineContaining(app.out(), READY).trim());

                Running second = start(dir, freePort(), false, null);
                try {
                    assertTrue(second.process.waitFor(30, TimeUnit.SECONDS),
                            "锁已在第一份手里，开关关着的第二份应退出。\n" + dump(second));
                    assertEquals(EXIT_HELD, second.process.exitValue(),
                            "第二份应以 1 退出。\n" + dump(second));
                    assertTrue(second.err().contains(HELD_HINT),
                            "第二份应说明这个目录已有一份在运行。\n" + dump(second));
                } finally {
                    second.close();
                }
            } finally {
                watch.close();
                app.close();
            }
        }
    }

    @Test
    @DisplayName("在门里等锁时发停机信号：5 秒内以 143 退出；1 秒后放锁，全程端口不绑、不进安全模式。连跑 3 遍")
    void signalWhileWaiting(@TempDir Path root) throws Exception {
        for (int round = 1; round <= 3; round++) {
            Path dir = root.resolve("wait-" + round);
            Files.createDirectories(dir);
            signalWhileWaitingOnce(dir, round);
        }
    }

    @Test
    @DisplayName("建对象期间发停机信号：不等放锁就以 143 退出；1 秒后放锁，全程端口不绑、不进安全模式。连跑 3 遍")
    void signalWhileConstructing(@TempDir Path root) throws Exception {
        for (int round = 1; round <= 3; round++) {
            Path dir = root.resolve("build-" + round);
            Files.createDirectories(dir);
            signalWhileConstructingOnce(dir, round);
        }
    }

    @Test
    @DisplayName("开关开、锁被占、配置写坏：不进安全模式，端口不绑，以门前失败的退码退出，错误里有位置，没有「已有一份在运行」")
    void brokenConfigDoesNotEnterSafeMode(@TempDir Path root) throws Exception {
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writeBrokenConfig(dir, port);
        try (FileChannel channel = FileChannel.open(dir.resolve("novabot.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            assertTrue(channel.tryLock() != null, "本进程应先占住锁");
            PortWatch watch = PortWatch.start(port);
            Running app = start(dir, port, true, null);
            try {
                assertTrue(app.process.waitFor(90, TimeUnit.SECONDS),
                        "配置写坏应退出，不应停在安全模式。\n" + dump(app));
                assertEquals(EXIT_BEFORE_GATE, app.process.exitValue(),
                        "应以门前失败的退码退出。\n" + dump(app));
                assertTrue(app.err().contains("application.yml"),
                        "标准错误里应有配置出错的位置。\n" + dump(app));
                assertFalse(app.err().contains(HELD_HINT),
                        "标准错误里不该有直接退出的那句。\n" + dump(app));
                assertFalse(app.both().contains(SAFE_MODE), "不该进安全模式。\n" + dump(app));
                assertFalse(runningPort(port), "端口不该绑上。\n" + dump(app));
            } finally {
                watch.close();
                app.close();
            }
            assertFalse(watch.opened.get(), "全程端口不该绑上");
        }
    }

    @Test
    @DisplayName("开关关、配置写坏：照旧进安全模式，安全模式端口绑上")
    void brokenConfigStillEntersSafeMode(@TempDir Path root) throws Exception {
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writeBrokenConfig(dir, port);
        Path stateFile = dir.resolve("state.txt");
        PortWatch watch = PortWatch.start(port);
        Running app = start(dir, port, false, null, stateFile);
        try {
            waitFor(app, 90_000, "开关关着时配置写坏应进安全模式并把端口绑上",
                    running -> runningPort(port) && running.both().contains(SAFE_MODE));
            assertEquals("safe-mode", readPhase(stateFile), "进安全模式后状态件应是 safe-mode。\n" + dump(app));
        } finally {
            watch.close();
            app.close();
        }
    }

    @Test
    @DisplayName("状态件路径指到不存在的目录：照常起到就绪，输出里有警告")
    void stateFileInMissingDirectoryStillStarts(@TempDir Path root) throws Exception {
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writePlainConfig(dir, port);
        Path stateFile = root.resolve("no-such-dir/state.txt");
        Running app = start(dir, port, false, null, stateFile);
        try {
            waitFor(app, 90_000, "状态件写不进也应照常起到就绪",
                    running -> runningPort(port) && running.out().contains(READY));
            assertTrue(app.both().contains(STATE_WRITE_WARN),
                    "输出里应有状态件写不进的警告。\n" + dump(app));
        } finally {
            app.close();
        }
    }

    @Test
    @DisplayName("开关开、锁件取不到：说明一句，以锁不明的退码退出")
    void unavailableLockExits(@TempDir Path root) throws Exception {
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writePlainConfig(dir, port);
        Files.createDirectory(dir.resolve("novabot.lock"));
        PortWatch watch = PortWatch.start(port);
        Running app = start(dir, port, true, null);
        try {
            assertTrue(app.process.waitFor(30, TimeUnit.SECONDS),
                    "锁取不到应退出。\n" + dump(app));
            assertEquals(EXIT_LOCK_UNKNOWN, app.process.exitValue(),
                    "应以锁不明的退码退出。\n" + dump(app));
            assertTrue(app.err().contains(UNKNOWN_HINT),
                    "应说明不往下接手。\n" + dump(app));
            assertFalse(runningPort(port), "端口不该绑上。\n" + dump(app));
        } finally {
            watch.close();
            app.close();
        }
        assertFalse(watch.opened.get(), "全程端口不该绑上");
    }

    @Test
    @DisplayName("开关关、锁件取不到：照常启动，端口绑上")
    void unavailableLockStillServes(@TempDir Path root) throws Exception {
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writePlainConfig(dir, port);
        Files.createDirectory(dir.resolve("novabot.lock"));
        Running app = start(dir, port, false, null);
        try {
            waitFor(app, 90_000, "开关关着、锁取不到应照常把端口绑上，并记下已就绪",
                    running -> runningPort(port) && running.out().contains(READY));
            assertTrue(app.err().contains(USUAL_START),
                    "应说明照常启动。\n" + dump(app));
            assertTrue(app.out().contains(PASSED), "开关关着也应记下过门。\n" + dump(app));
            assertTrue(app.out().contains("等锁等了 0.0 秒"),
                    "开关关着时等锁应近 0。\n" + dump(app));
            assertTrue(app.out().contains(READY), "开关关着也应记下就绪。\n" + dump(app));
            System.out.println(lineContaining(app.out(), PASSED).trim());
            System.out.println(lineContaining(app.out(), READY).trim());
        } finally {
            app.close();
        }
    }

    @Test
    @DisplayName("开关开、等锁上限很短：到点说明一句，以到点的退码退出，端口不绑")
    void waitLimitExits(@TempDir Path root) throws Exception {
        Path dir = root.resolve("work");
        Files.createDirectories(dir);
        int port = freePort();
        writePlainConfig(dir, port);
        try (FileChannel channel = FileChannel.open(dir.resolve("novabot.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            assertTrue(channel.tryLock() != null, "本进程应先占住锁");
            PortWatch watch = PortWatch.start(port);
            Running app = start(dir, port, true, "2");
            try {
                assertTrue(app.process.waitFor(90, TimeUnit.SECONDS),
                        "到点应退出。\n" + dump(app));
                assertEquals(EXIT_WAIT_EXPIRED, app.process.exitValue(),
                        "应以到点的退码退出。\n" + dump(app));
                assertTrue(app.err().contains(EXPIRED_HINT),
                        "应说明先不接手。\n" + dump(app));
                assertFalse(app.both().contains(SAFE_MODE), "不该进安全模式。\n" + dump(app));
                assertFalse(runningPort(port), "端口不该绑上。\n" + dump(app));
            } finally {
                watch.close();
                app.close();
            }
            assertFalse(watch.opened.get(), "全程端口不该绑上");
        }
    }

    private static void signalWhileWaitingOnce(Path dir, int round) throws Exception {
        int port = freePort();
        writePlainConfig(dir, port);
        try (FileChannel channel = FileChannel.open(dir.resolve("novabot.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            assertTrue(held != null, "第 " + round + " 遍：本进程应先占住锁");
            PortWatch watch = PortWatch.start(port);
            Running app = start(dir, port, true, null);
            try {
                waitFor(app, 90_000, "第 " + round + " 遍：应先走到等锁",
                        running -> running.out().contains(WAITING) && running.alive());
                app.process.destroy();
                Thread.sleep(1_000);
                held.release();
                assertTrue(app.process.waitFor(5, TimeUnit.SECONDS),
                        "第 " + round + " 遍：发信号后 5 秒内应退出。\n" + dump(app));
                assertEquals(EXIT_SIGNAL, app.process.exitValue(),
                        "第 " + round + " 遍：应以 143 退出。\n" + dump(app));
                assertFalse(app.both().contains(SAFE_MODE),
                        "第 " + round + " 遍：不该进安全模式。\n" + dump(app));
                assertFalse(app.out().contains(PASSED),
                        "第 " + round + " 遍：不该过门。\n" + dump(app));
                assertFalse(runningPort(port), "第 " + round + " 遍：端口不该绑上");
            } finally {
                watch.close();
                app.close();
            }
            assertFalse(watch.opened.get(), "第 " + round + " 遍：全程端口不该绑上");
        }
    }

    private static void signalWhileConstructingOnce(Path dir, int round) throws Exception {
        int port = freePort();
        writePlainConfig(dir, port);
        try (FileChannel channel = FileChannel.open(dir.resolve("novabot.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            assertTrue(held != null, "第 " + round + " 遍：本进程应先占住锁");
            PortWatch watch = PortWatch.start(port);
            Running app = start(dir, port, true, null);
            try {
                waitFor(app, 90_000, "第 " + round + " 遍：应先建立候命对象、且还没开始等锁",
                        running -> running.out().contains(CONSTRUCTED)
                                && !running.out().contains(WAITING)
                                && running.alive());
                app.process.destroy();
                Thread.sleep(1_000);
                held.release();
                assertTrue(app.process.waitFor(5, TimeUnit.SECONDS),
                        "第 " + round + " 遍：发信号后 5 秒内应退出。\n" + dump(app));
                assertEquals(EXIT_SIGNAL, app.process.exitValue(),
                        "第 " + round + " 遍：应以 143 退出。\n" + dump(app));
                assertFalse(app.both().contains(SAFE_MODE),
                        "第 " + round + " 遍：不该进安全模式。\n" + dump(app));
                assertFalse(app.out().contains(PASSED),
                        "第 " + round + " 遍：不该过门。\n" + dump(app));
                assertFalse(runningPort(port), "第 " + round + " 遍：端口不该绑上");
            } finally {
                watch.close();
                app.close();
            }
            assertFalse(watch.opened.get(), "第 " + round + " 遍：全程端口不该绑上");
        }
    }

    private static void writePlainConfig(Path dir, int port) throws IOException {
        Files.writeString(dir.resolve("application.yml"), ""
                + "server:\n"
                + "  address: 127.0.0.1\n"
                + "  port: " + port + "\n", StandardCharsets.UTF_8);
    }

    /**
     * 端口写得对，线程数不是数字：文件能读出端口，主程序却因配置起不来。
     */
    private static void writeBrokenConfig(Path dir, int port) throws IOException {
        Files.writeString(dir.resolve("application.yml"), ""
                + "server:\n"
                + "  address: 127.0.0.1\n"
                + "  port: " + port + "\n"
                + "  tomcat:\n"
                + "    threads:\n"
                + "      max: not-a-number\n", StandardCharsets.UTF_8);
    }

    private static int freePort() throws IOException {
        LoopbackPort.assumeAllowed();
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static boolean runningPort(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 80);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Running start(Path dir, int port, boolean standby, String waitSeconds) throws IOException {
        return start(dir, port, standby, waitSeconds, null);
    }

    private static Running start(Path dir, int port, boolean standby, String waitSeconds, Path stateFile) throws IOException {
        Path out = dir.resolve("stdout.txt");
        Path err = dir.resolve("stderr.txt");
        Path args = dir.resolve("java-args.txt");
        StringBuilder text = new StringBuilder();
        text.append("-Dfile.encoding=UTF-8\n");
        text.append("-Dnovabot.standby=").append(standby).append('\n');
        if (waitSeconds != null) {
            text.append("-Dnovabot.standby.wait-seconds=").append(waitSeconds).append('\n');
        }
        if (stateFile != null) {
            text.append("-Dnovabot.state-file=").append(stateFile).append('\n');
        }
        text.append("-cp\n");
        text.append(System.getProperty("java.class.path")).append('\n');
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
        return new Running(process, out, err, port, dir);
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
            Thread.sleep(20);
        }
        if (check.ok(app)) {
            return;
        }
        fail(what + "\n" + dump(app));
    }

    private static String dump(Running app) throws IOException {
        String exited = app.process.isAlive() ? "还在跑" : ("退码 " + app.process.exitValue());
        return "进程" + exited + "\n标准输出：\n" + app.out() + "\n标准错误：\n" + app.err();
    }

    private static String lineContaining(String text, String needle) {
        for (String line : text.split("\n")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        return "";
    }

    @FunctionalInterface
    private interface Check {
        boolean ok(Running app) throws IOException;
    }

    private static final class Running implements AutoCloseable {
        final Process process;
        final Path out;
        final Path err;
        final int port;
        final Path dir;

        Running(Process process, Path out, Path err, int port, Path dir) {
            this.process = process;
            this.out = out;
            this.err = err;
            this.port = port;
            this.dir = dir;
        }

        String out() throws IOException {
            return read(out);
        }

        String err() throws IOException {
            return read(err);
        }

        String both() throws IOException {
            return out() + "\n" + err();
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

    private static final class PortWatch implements AutoCloseable {
        final AtomicBoolean opened = new AtomicBoolean(false);
        private final AtomicBoolean stop = new AtomicBoolean(false);
        private final Thread thread;

        private PortWatch(int port) {
            thread = new Thread(() -> {
                while (!stop.get()) {
                    if (runningPort(port)) {
                        opened.set(true);
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }, "port-watch");
            thread.setDaemon(true);
        }

        static PortWatch start(int port) {
            PortWatch watch = new PortWatch(port);
            threadStart(watch);
            return watch;
        }

        private static void threadStart(PortWatch watch) {
            watch.thread.start();
        }

        @Override
        public void close() {
            stop.set(true);
            thread.interrupt();
        }
    }

    private static String read(Path path) throws IOException {
        if (!Files.exists(path)) {
            return "";
        }
        return Files.readString(path);
    }

    /**
     * 阶段序列：以 waiting 开头、以 ready 收尾、只出现 waiting／passed／ready、顺序不倒退。
     * 过门到就绪只隔几十毫秒，passed 可见可不见，所以不断言「过门那一刻读到 passed」。
     */
    private static void assertPhaseSequence(List<String> phases) {
        List<String> order = List.of("waiting", "passed", "ready");
        assertFalse(phases.isEmpty(), "阶段序列不该是空的");
        assertEquals("waiting", phases.get(0), "阶段序列应以 waiting 开头：" + phases);
        assertEquals("ready", phases.get(phases.size() - 1), "阶段序列应以 ready 收尾：" + phases);
        for (int i = 0; i < phases.size(); i++) {
            assertTrue(order.contains(phases.get(i)), "只该出现 waiting／passed／ready：" + phases);
            if (i > 0) {
                assertTrue(order.indexOf(phases.get(i - 1)) <= order.indexOf(phases.get(i)),
                        "阶段顺序不该倒退：" + phases);
            }
        }
    }

    private static String readPhase(Path stateFile) throws IOException {
        if (!Files.exists(stateFile)) {
            return "";
        }
        for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
            if (line.startsWith("phase=")) {
                return line.substring("phase=".length());
            }
        }
        return "";
    }

    private static long readPid(Path stateFile) throws IOException {
        if (!Files.exists(stateFile)) {
            return -1;
        }
        for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
            if (line.startsWith("pid=")) {
                return Long.parseLong(line.substring("pid=".length()));
            }
        }
        return -1;
    }

    /**
     * 状态件阶段的旁观者：按固定间隔读一次，把读到的阶段记进一串，相邻重复只记一次。
     */
    private static final class PhaseRecorder {

        private final Path stateFile;

        private final List<String> seen = new ArrayList<>();

        private final AtomicBoolean stopping = new AtomicBoolean(false);

        private final Thread thread;

        private PhaseRecorder(Path stateFile) {
            this.stateFile = stateFile;
            thread = new Thread(this::run, "phase-record");
            thread.setDaemon(true);
            thread.start();
        }

        static PhaseRecorder start(Path stateFile) {
            return new PhaseRecorder(stateFile);
        }

        private void run() {
            while (!stopping.get()) {
                recordQuietly();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        List<String> finish() {
            stopping.set(true);
            thread.interrupt();
            try {
                thread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recordQuietly();
            return List.copyOf(seen);
        }

        private synchronized void recordQuietly() {
            try {
                String phase = readPhase(stateFile);
                if (!phase.isEmpty() && (seen.isEmpty() || !phase.equals(seen.get(seen.size() - 1)))) {
                    seen.add(phase);
                }
            } catch (IOException e) {
                // 读不到就跳过这一趟
            }
        }
    }
}
