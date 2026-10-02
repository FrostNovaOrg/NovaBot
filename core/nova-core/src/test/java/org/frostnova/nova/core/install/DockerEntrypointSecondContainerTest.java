package org.frostnova.nova.core.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 同一个数据卷上再起一个容器时，入口不该先换掉第一份正在用的程序文件。
 * <p>
 * 抓的是第二个容器换掉第一份正在用的程序件：卷上这把锁还被另一个进程占着，
 * 再跑一遍入口，NovaBot.jar、start.sh、lib、plugins、plugins-lib 已经被盖掉。
 * 换掉发生在程序自己拿锁之前，正在跑的那一份会加载不到类，或者新旧文件混在一起。
 * <p>
 * 锁空的那一格是对照：没有人占着时，入口照旧把程序文件铺好，再交给 start.sh。
 * java 与 start.sh 都换成替身，SRC、DST 指到临时目录。
 * <p>
 * 另两格：入口这把锁若落成整件字节区锁，也不该挡住程序自己的 novabot.lock；
 * flock 报的不是「锁被占」时，入口说明一句后照旧铺件。
 * 没有 flock 这条命令，或锁件打不开，同样说明一句后照旧铺件。
 */
@DisplayName("同一个卷上第二个容器不再换掉第一份正在用的程序件")
class DockerEntrypointSecondContainerTest {

    private static final String HELD_MESSAGE = "这个目录已有一份 NovaBot 在运行";

    private static final String STOP_FIRST = "要换版本请先停掉它";

    private static final String ENTRY_LOCK_NAME = "novabot-container.lock";

    private static final String UNAVAILABLE =
            "没法确认这个卷是不是已有一份在跑，照常启动。";

    @Test
    @DisplayName("锁已被占：卷上的程序件一个不动，非 0 退出，说明在标准错误")
    void heldLockLeavesProgramFilesUntouched(@TempDir Path dir) throws Exception {
        Layout layout = layout(dir);
        Path lockFile = layout.dst.resolve(ENTRY_LOCK_NAME);
        Files.writeString(lockFile, "", StandardCharsets.UTF_8);
        Process holder = holdLock(layout, lockFile);
        try {
            Map<String, String> before = snapshot(layout.dst);
            Run run = runEntry(layout, true);
            Map<String, String> after = snapshot(layout.dst);
            String diff = diff(before, after);

            assertTrue(diff.isEmpty(),
                    "锁已被占时，卷上的程序件应一个不动，实际改了:\n" + diff);
            assertNotEquals(0, run.code, "锁已被占应非 0 退出。标准错误:\n" + run.stderr);
            assertTrue(run.stderr.contains(HELD_MESSAGE),
                    "说明应写明这个目录已有一份在运行。标准错误:\n" + run.stderr);
            assertTrue(run.stderr.contains(lockFile.toAbsolutePath().toString()),
                    "说明应写出锁件路径。标准错误:\n" + run.stderr);
            assertTrue(run.stderr.contains(STOP_FIRST),
                    "说明应写明要换版本请先停掉正在运行的那一份。标准错误:\n" + run.stderr);
            assertFalse(Files.exists(layout.handoff),
                    "锁已被占时不应交到 start.sh。交接记录:\n" + readIfExists(layout.handoff));
        } finally {
            holder.destroyForcibly();
            holder.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("锁空：照旧铺好程序件并交给 start.sh")
    void freeLockLaysOutProgramAndHandsOff(@TempDir Path dir) throws Exception {
        Layout layout = layout(dir);
        Run run = runEntry(layout, false);

        assertEquals(0, run.code, "锁空时应把程序铺好并交出去。标准错误:\n" + run.stderr);
        assertEquals("new-jar", read(layout.dst.resolve("NovaBot.jar")));
        assertEquals(read(layout.src.resolve("start.sh")), read(layout.dst.resolve("start.sh")));
        assertEquals("new-lib", read(layout.dst.resolve("lib/core.jar")));
        assertFalse(Files.exists(layout.dst.resolve("lib/keep.txt")), "旧 lib 应被换掉");
        assertFalse(Files.exists(layout.dst.resolve("plugins/nova-demo-1.0.0.jar")),
                "内置插件旧版应清掉");
        assertEquals("new-plugin", read(layout.dst.resolve("plugins/nova-demo-2.0.0.jar")));
        assertEquals("user", read(layout.dst.resolve("plugins/user-extra.jar")),
                "卷上自带的插件应留着");
        assertEquals("example", read(layout.dst.resolve("application.example.yml")));
        String handoff = read(layout.handoff);
        assertTrue(handoff.contains("start"), "应交给 start.sh。交接记录:\n" + handoff);
        assertTrue(handoff.contains("java"), "start.sh 应再交到 java。交接记录:\n" + handoff);
        assertTrue(handoff.contains("fd9"),
                "锁的文件描述符应传到 java。交接记录:\n" + handoff);
        assertTrue(handoff.contains("lock-held"),
                "交到 java 时这把锁应还在手里。交接记录:\n" + handoff);
    }

    @Test
    @DisplayName("入口持着整件字节区锁时，程序的 novabot.lock 仍锁得上")
    void wholeFileLockOnEntryDoesNotBlockProgramLock(@TempDir Path dir) throws Exception {
        Layout layout = layout(dir);
        writeExecutable(layout.bin.resolve("flock"), wholeFileFlockStandIn());
        Path probe = layout.bin.resolve("probe-locks.py");
        write(probe, probeLocksScript());
        Run run = runEntry(layout, false, Map.of(
                "PROBE_PROGRAM_LOCK", layout.dst.resolve("novabot.lock").toAbsolutePath().toString(),
                "PROBE_ENTRY_LOCK", layout.dst.resolve(ENTRY_LOCK_NAME).toAbsolutePath().toString(),
                "PROBE_SCRIPT", probe.toAbsolutePath().toString()));

        assertEquals(0, run.code, "入口应占上自己的锁并交出去。标准错误:\n" + run.stderr);
        String handoff = read(layout.handoff);
        assertTrue(handoff.contains("program-free"),
                "入口持锁期间，另一进程用整件字节区锁去锁程序的 novabot.lock 应锁得上。交接记录:\n" + handoff);
        assertTrue(handoff.contains("entry-blocked"),
                "这段时间入口应占着自己的锁。交接记录:\n" + handoff);
    }

    @Test
    @DisplayName("锁确认不了：说明一句，照旧铺好程序件并交给 start.sh")
    void unavailableLockStillLaysOutAndHandsOff(@TempDir Path dir) throws Exception {
        Layout layout = layout(dir);
        writeExecutable(layout.bin.resolve("flock"), ""
                + "#!/usr/bin/env python3\n"
                + "import sys\n"
                + "sys.stderr.write('flock: unsupported\\n')\n"
                + "sys.exit(1)\n");
        Run run = runEntry(layout, false);

        assertEquals(0, run.code, "锁确认不了时应照常铺件并交出去。标准错误:\n" + run.stderr);
        assertTrue(run.stderr.contains(UNAVAILABLE),
                "应说明没法确认、照常启动。标准错误:\n" + run.stderr);
        assertEquals("new-jar", read(layout.dst.resolve("NovaBot.jar")));
        String handoff = read(layout.handoff);
        assertTrue(handoff.contains("start"), "应交给 start.sh。交接记录:\n" + handoff);
    }

    /**
     * 抓的用户故障：容器里没有 flock 这条命令时，入口不该起不来，程序文件也不该停在旧的。
     * 说明一句后照旧铺件，再交给 start.sh。
     */
    @Test
    @DisplayName("没有 flock：说明一句，照旧铺好程序件并交给 start.sh")
    void missingFlockStillLaysOutAndHandsOff(@TempDir Path dir) throws Exception {
        Layout layout = layout(dir);
        Files.deleteIfExists(layout.bin.resolve("flock"));
        Run run = runEntry(layout, false, Map.of(), pathWithoutFlock(layout));

        assertEquals(0, run.code, "没有 flock 时应照常铺件并交出去。标准错误:\n" + run.stderr);
        assertTrue(run.stderr.contains(UNAVAILABLE),
                "应说明没法确认、照常启动。标准错误:\n" + run.stderr);
        assertProgramLaidOut(layout);
        String handoff = read(layout.handoff);
        assertTrue(handoff.contains("start"), "应交给 start.sh。交接记录:\n" + handoff);
    }

    /**
     * 抓的用户故障：锁件打不开（锁件位置是个目录）时，入口不该起不来，程序文件也不该停在旧的。
     * 说明一句后照旧铺件，再交给 start.sh。
     */
    @Test
    @DisplayName("锁件打不开：说明一句，照旧铺好程序件并交给 start.sh")
    void lockFileUnopenableStillLaysOutAndHandsOff(@TempDir Path dir) throws Exception {
        Layout layout = layout(dir);
        Files.createDirectory(layout.dst.resolve(ENTRY_LOCK_NAME));
        Run run = runEntry(layout, false);

        assertEquals(0, run.code, "锁件打不开时应照常铺件并交出去。标准错误:\n" + run.stderr);
        assertTrue(run.stderr.contains(UNAVAILABLE),
                "应说明没法确认、照常启动。标准错误:\n" + run.stderr);
        assertProgramLaidOut(layout);
        String handoff = read(layout.handoff);
        assertTrue(handoff.contains("start"), "应交给 start.sh。交接记录:\n" + handoff);
    }

    private static Layout layout(Path dir) throws IOException, InterruptedException {
        Layout layout = new Layout();
        layout.src = dir.resolve("src");
        layout.dst = dir.resolve("dst");
        layout.bin = dir.resolve("bin");
        layout.handoff = dir.resolve("handoff.txt");
        layout.script = dir.resolve("docker-entrypoint.sh");
        Files.createDirectories(layout.src.resolve("lib"));
        Files.createDirectories(layout.src.resolve("plugins"));
        Files.createDirectories(layout.src.resolve("plugins-lib"));
        Files.createDirectories(layout.dst.resolve("lib"));
        Files.createDirectories(layout.dst.resolve("plugins"));
        Files.createDirectories(layout.dst.resolve("plugins-lib"));
        Files.createDirectories(layout.bin);

        write(layout.src.resolve("NovaBot.jar"), "new-jar");
        write(layout.src.resolve("lib/core.jar"), "new-lib");
        write(layout.src.resolve("plugins/nova-demo-2.0.0.jar"), "new-plugin");
        write(layout.src.resolve("plugins-lib/caffeine-9.9.9.jar"), "new-caffeine");
        write(layout.src.resolve("application.example.yml"), "example");
        writeExecutable(layout.src.resolve("start.sh"), ""
                + "#!/usr/bin/env bash\n"
                + "printf 'start\\n' >> \"$HANDOFF\"\n"
                + "exec java\n");

        write(layout.dst.resolve("NovaBot.jar"), "old-jar");
        writeExecutable(layout.dst.resolve("start.sh"), "old-start");
        write(layout.dst.resolve("lib/core.jar"), "old-lib");
        write(layout.dst.resolve("lib/keep.txt"), "keep");
        write(layout.dst.resolve("plugins/nova-demo-1.0.0.jar"), "old-plugin");
        write(layout.dst.resolve("plugins/user-extra.jar"), "user");
        write(layout.dst.resolve("plugins-lib/caffeine-3.2.0.jar"), "old-caffeine");
        write(layout.dst.resolve("plugins-lib/user-dep-1.0.jar"), "user-dep");

        writeExecutable(layout.bin.resolve("java"), ""
                + "#!/usr/bin/env bash\n"
                + "printf 'java\\n' >> \"$HANDOFF\"\n"
                + "if [ -e /dev/fd/9 ]; then printf 'fd9\\n' >> \"$HANDOFF\"; fi\n"
                + "python3 - \"$LOCKFILE\" \"$HANDOFF\" << 'PY'\n"
                + "import fcntl, sys\n"
                + "path, handoff = sys.argv[1], sys.argv[2]\n"
                + "try:\n"
                + "    handle = open(path, 'a+')\n"
                + "    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)\n"
                + "    mark = 'lock-free'\n"
                + "except BlockingIOError:\n"
                + "    mark = 'lock-held'\n"
                + "with open(handoff, 'a', encoding='utf-8') as out:\n"
                + "    out.write(mark + '\\n')\n"
                + "PY\n"
                + "if [ -n \"${PROBE_PROGRAM_LOCK:-}\" ]; then\n"
                + "  python3 \"$PROBE_SCRIPT\" \"$PROBE_PROGRAM_LOCK\" \"$PROBE_ENTRY_LOCK\" \"$HANDOFF\"\n"
                + "fi\n"
                + "exit 0\n");
        if (!commandExists("flock")) {
            writeExecutable(layout.bin.resolve("flock"), ""
                    + "#!/usr/bin/env python3\n"
                    + "import fcntl, sys\n"
                    + "nonblock = False\n"
                    + "conflict_exit = 1\n"
                    + "fd_number = None\n"
                    + "args = sys.argv[1:]\n"
                    + "index = 0\n"
                    + "while index < len(args):\n"
                    + "    arg = args[index]\n"
                    + "    if arg in ('-n', '--nb', '--nonblock'):\n"
                    + "        nonblock = True\n"
                    + "    elif arg in ('-x', '--exclusive'):\n"
                    + "        pass\n"
                    + "    elif arg in ('-E', '--conflict-exit-code'):\n"
                    + "        index += 1\n"
                    + "        if index >= len(args):\n"
                    + "            sys.stderr.write('flock: missing conflict exit code\\n')\n"
                    + "            sys.exit(2)\n"
                    + "        conflict_exit = int(args[index])\n"
                    + "    elif arg.startswith('-E') and arg[2:].isdigit():\n"
                    + "        conflict_exit = int(arg[2:])\n"
                    + "    elif fd_number is None and arg.isdigit():\n"
                    + "        fd_number = int(arg)\n"
                    + "    else:\n"
                    + "        sys.stderr.write('flock: unsupported args\\n')\n"
                    + "        sys.exit(2)\n"
                    + "    index += 1\n"
                    + "if fd_number is None:\n"
                    + "    sys.stderr.write('flock: missing fd\\n')\n"
                    + "    sys.exit(2)\n"
                    + "operation = fcntl.LOCK_EX | (fcntl.LOCK_NB if nonblock else 0)\n"
                    + "try:\n"
                    + "    fcntl.flock(fd_number, operation)\n"
                    + "except BlockingIOError:\n"
                    + "    sys.exit(conflict_exit)\n"
                    + "except OSError as error:\n"
                    + "    sys.stderr.write('flock: %s\\n' % error)\n"
                    + "    sys.exit(1)\n");
        }

        String text = Files.readString(repoRoot().resolve("dist/templates/docker-entrypoint.sh"),
                StandardCharsets.UTF_8);
        if (text.contains("\nSRC=/opt/starbot\n")) {
            text = text.replace("\nSRC=/opt/starbot\n",
                    "\nSRC='" + layout.src.toAbsolutePath() + "'\n");
            text = text.replace("\nDST=/app\n",
                    "\nDST='" + layout.dst.toAbsolutePath() + "'\n");
        }
        Files.writeString(layout.script, text, StandardCharsets.UTF_8);
        return layout;
    }

    private static Process holdLock(Layout layout, Path lockFile) throws IOException, InterruptedException {
        Path program = layout.bin.resolve("hold-lock.py");
        write(program, ""
                + "import fcntl, sys, time\n"
                + "handle = open(sys.argv[1], 'a+')\n"
                + "try:\n"
                + "    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)\n"
                + "except OSError as error:\n"
                + "    sys.stderr.write('holder: %s\\n' % error)\n"
                + "    sys.exit(1)\n"
                + "with open(sys.argv[2], 'w', encoding='utf-8') as ready:\n"
                + "    ready.write('ready\\n')\n"
                + "time.sleep(120)\n");
        Path ready = layout.bin.resolve("holder-ready");
        Path holderErr = layout.bin.resolve("holder-err.txt");
        Process process = new ProcessBuilder("python3", program.toString(),
                lockFile.toAbsolutePath().toString(), ready.toAbsolutePath().toString())
                .redirectError(holderErr.toFile())
                .start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.isRegularFile(ready) && System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                fail("占锁进程提前退出，退码 " + process.exitValue() + ":\n" + readIfExists(holderErr));
            }
            Thread.sleep(50);
        }
        assertTrue(Files.isRegularFile(ready), "占锁进程没有在 5 秒内拿到锁:\n" + readIfExists(holderErr));
        return process;
    }

    private static Run runEntry(Layout layout, boolean lockHeld) throws IOException, InterruptedException {
        return runEntry(layout, lockHeld, Map.of());
    }

    private static Run runEntry(Layout layout, boolean lockHeld, Map<String, String> extra)
            throws IOException, InterruptedException {
        return runEntry(layout, lockHeld, extra, null);
    }

    private static Run runEntry(Layout layout, boolean lockHeld, Map<String, String> extra, String path)
            throws IOException, InterruptedException {
        Path outFile = layout.bin.resolve(lockHeld ? "held-out.txt" : "free-out.txt");
        Path errFile = layout.bin.resolve(lockHeld ? "held-err.txt" : "free-err.txt");
        ProcessBuilder builder = new ProcessBuilder("bash", layout.script.toString());
        Map<String, String> env = builder.environment();
        env.put("SRC", layout.src.toAbsolutePath().toString());
        env.put("DST", layout.dst.toAbsolutePath().toString());
        env.put("HANDOFF", layout.handoff.toAbsolutePath().toString());
        env.put("LOCKFILE", layout.dst.resolve(ENTRY_LOCK_NAME).toAbsolutePath().toString());
        extra.forEach(env::put);
        if (path == null) {
            env.put("PATH", layout.bin.toAbsolutePath() + java.io.File.pathSeparator
                    + env.getOrDefault("PATH", ""));
        } else {
            env.put("PATH", path);
        }
        builder.redirectOutput(outFile.toFile());
        builder.redirectError(errFile.toFile());
        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        Run run = new Run();
        run.finished = finished;
        run.code = finished ? process.exitValue() : -1;
        run.stdout = readIfExists(outFile);
        run.stderr = readIfExists(errFile);
        assertTrue(finished, "入口 30 秒还没结束。标准输出:\n" + run.stdout + "\n标准错误:\n" + run.stderr);
        return run;
    }

    private static boolean commandExists(String name) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("bash", "-c", "command -v " + name).start();
        process.getInputStream().readAllBytes();
        process.getErrorStream().readAllBytes();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0;
    }

    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                files.put(root.relativize(file).toString(), Files.readString(file, StandardCharsets.UTF_8));
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static String diff(Map<String, String> before, Map<String, String> after) {
        List<String> lines = new ArrayList<>();
        for (String path : before.keySet()) {
            if (!after.containsKey(path)) {
                lines.add("删 " + path);
            } else if (!before.get(path).equals(after.get(path))) {
                lines.add("改 " + path);
            }
        }
        for (String path : after.keySet()) {
            if (!before.containsKey(path)) {
                lines.add("增 " + path);
            }
        }
        lines.sort(Comparator.naturalOrder());
        return String.join("\n", lines);
    }

    private static void assertProgramLaidOut(Layout layout) throws IOException {
        assertEquals("new-jar", read(layout.dst.resolve("NovaBot.jar")));
        assertEquals(read(layout.src.resolve("start.sh")), read(layout.dst.resolve("start.sh")));
        assertEquals("new-lib", read(layout.dst.resolve("lib/core.jar")));
        assertFalse(Files.exists(layout.dst.resolve("lib/keep.txt")), "旧 lib 应被换掉");
        assertFalse(Files.exists(layout.dst.resolve("plugins/nova-demo-1.0.0.jar")),
                "内置插件旧版应清掉");
        assertEquals("new-plugin", read(layout.dst.resolve("plugins/nova-demo-2.0.0.jar")));
        assertEquals("user", read(layout.dst.resolve("plugins/user-extra.jar")),
                "卷上自带的插件应留着");
        assertTrue(Files.exists(layout.dst.resolve("plugins-lib/caffeine-9.9.9.jar")),
                "镜像里的插件依赖应铺到卷上的 plugins-lib");
        assertEquals("new-caffeine", read(layout.dst.resolve("plugins-lib/caffeine-9.9.9.jar")));
        assertFalse(Files.exists(layout.dst.resolve("plugins-lib/caffeine-3.2.0.jar")),
                "镜像自带依赖的旧版应清掉");
        assertEquals("user-dep", read(layout.dst.resolve("plugins-lib/user-dep-1.0.jar")),
                "卷上自放的依赖应留着");
        assertEquals("example", read(layout.dst.resolve("application.example.yml")));
    }

    /**
     * 只留入口要用的命令，不留 flock。系统里若本来有 flock，也不放进这条路径。
     */
    private static String pathWithoutFlock(Layout layout) throws IOException, InterruptedException {
        Files.deleteIfExists(layout.bin.resolve("flock"));
        Path tools = layout.bin.resolve("tools");
        Files.createDirectories(tools);
        for (String name : new String[] {"bash", "mkdir", "cp", "rm", "find", "sed", "basename", "python3"}) {
            Path found = lookUp(name);
            if (found == null) {
                fail("找不到命令 " + name);
            }
            Path link = tools.resolve(name);
            if (!Files.exists(link)) {
                Files.createSymbolicLink(link, found);
            }
        }
        return layout.bin.toAbsolutePath() + java.io.File.pathSeparator + tools.toAbsolutePath();
    }

    private static Path lookUp(String name) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("bash", "-c", "command -v " + name).start();
        byte[] out = process.getInputStream().readAllBytes();
        process.getErrorStream().readAllBytes();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return null;
        }
        if (process.exitValue() != 0) {
            return null;
        }
        String text = new String(out, StandardCharsets.UTF_8).trim();
        if (text.isEmpty() || !text.startsWith("/")) {
            return null;
        }
        return Path.of(text);
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void writeExecutable(Path file, String content) throws IOException {
        write(file, content);
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String readIfExists(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("build.sh")) && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }

    /**
     * 把 flock 换成整件字节区锁。网络盘上 flock 会被当成这种锁，和程序用的那种互相挡；
     * 替身直接上这种锁，锁的是描述符 9 指着的那一件。
     */
    private static String wholeFileFlockStandIn() {
        return """
                #!/usr/bin/env python3
                import fcntl, struct, sys
                nonblock = False
                conflict_exit = 1
                fd_number = None
                args = sys.argv[1:]
                index = 0
                while index < len(args):
                    arg = args[index]
                    if arg in ("-n", "--nb", "--nonblock"):
                        nonblock = True
                    elif arg in ("-x", "--exclusive"):
                        pass
                    elif arg in ("-E", "--conflict-exit-code"):
                        index += 1
                        if index >= len(args):
                            sys.stderr.write("flock: missing conflict exit code\\n")
                            sys.exit(2)
                        conflict_exit = int(args[index])
                    elif arg.startswith("-E") and arg[2:].isdigit():
                        conflict_exit = int(arg[2:])
                    elif fd_number is None and arg.isdigit():
                        fd_number = int(arg)
                    else:
                        sys.stderr.write("flock: unsupported args\\n")
                        sys.exit(2)
                    index += 1
                if fd_number is None:
                    sys.stderr.write("flock: missing fd\\n")
                    sys.exit(2)
                if sys.platform == "darwin":
                    data = struct.pack("qqihh", 0, 0, 0, fcntl.F_WRLCK, 0)
                else:
                    data = struct.pack("hh4xqqi4x", fcntl.F_WRLCK, 0, 0, 0, 0)
                operation = fcntl.F_OFD_SETLK if nonblock else fcntl.F_OFD_SETLKW
                try:
                    fcntl.fcntl(fd_number, operation, data)
                except BlockingIOError:
                    sys.exit(conflict_exit)
                except OSError as error:
                    sys.stderr.write("flock: %s\\n" % error)
                    sys.exit(1)
                """;
    }

    private static String probeLocksScript() {
        return """
                import fcntl, sys
                program, entry, handoff = sys.argv[1], sys.argv[2], sys.argv[3]

                def mark(path, label):
                    with open(path, "a+") as handle:
                        try:
                            fcntl.lockf(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                            return label + "-free"
                        except BlockingIOError:
                            return label + "-blocked"
                        except OSError:
                            return label + "-error"

                with open(handoff, "a", encoding="utf-8") as out:
                    out.write(mark(program, "program") + "\\n")
                    out.write(mark(entry, "entry") + "\\n")
                """;
    }

    private static final class Layout {
        Path src;
        Path dst;
        Path bin;
        Path handoff;
        Path script;
    }

    private static final class Run {
        boolean finished;
        int code;
        String stdout = "";
        String stderr = "";
    }
}
