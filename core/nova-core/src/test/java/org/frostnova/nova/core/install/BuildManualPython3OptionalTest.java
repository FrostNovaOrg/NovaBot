package org.frostnova.nova.core.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 没装 python3 的机器上，构建不能因为随包手册停下
 * <p>
 * 抓的用户故障：手册第 3 章列的构建前提只有 JDK 17 与 Maven，install.sh 也不装
 * python3；而打包那一步曾无条件生成手册，于是在没装 python3 的机器上（Debian 精简
 * 镜像、Alpine 一类），构建编完就停在半路，只报一句 {@code python3: command not found}。
 * <p>
 * 这里把 build.sh 拷进沙箱，PATH 上摆上假的 java 与 mvn（打包那一次就地退 42，把
 * 构建停在被测的那一步之后），PATH 里不放 python3，把这台机器扮成那副样子，看四件事：
 * 默认构建印一行说明后照常走到打包；打发布包时在第 4 步停下并说清原因；装着
 * python3 时先把上一次留下的 manual/dist 清掉再生成；生成器报错时照旧停下。
 */
@DisplayName("没装 python3 时的构建：不带随包手册也不停，发布包除外")
class BuildManualPython3OptionalTest {

    /** 没装 python3 时默认构建印的那行说明的头几个字。build.sh 改了说法这里就红——这一行正是要钉住的 */
    private static final String SKIP_LINE = "这次构建不带随包手册";

    /** 没装 python3 还要打发布包时，停下句的头几个字 */
    private static final String PACKAGE_STOP = "打发布包需要";

    /** 假 mvn 记录里，打可运行包那一次调用的标志参数 */
    private static final String PACKAGE_CALL = "-Ppackage";

    private static final String STUB_JAVA = """
            #!/bin/sh
            printf 'openjdk version "17.0.0" 2026-01-01\\n' >&2
            """;

    /** 假 mvn：记下每次调用；打可运行包那一次（-Ppackage）就地退 42，把构建停在那一步之后 */
    private static final String STUB_MVN = """
            #!/bin/sh
            echo "mvn $*" >> "${NOVABOT_STUB_LOG}/mvn.log"
            echo "[INFO] stub-mvn ok"
            case " $* " in
              *" -Ppackage "*) exit 42 ;;
            esac
            exit 0
            """;

    /**
     * 假 python3：记下参数，与被调那一刻 manual/dist 里还剩什么（上一次构建的残留该已
     * 被清掉）；按 NOVABOT_STUB_PYTHON3_RC 决定成败，失败时往屏上报一句自己的话
     */
    private static final String STUB_PYTHON3 = """
            #!/bin/sh
            echo "python3 $*" >> "${NOVABOT_STUB_LOG}/python3.log"
            if [ -d manual/dist ]; then
                ls manual/dist
            else
                echo "(manual/dist is not there)"
            fi > "${NOVABOT_STUB_LOG}/manual-dist-at-build.txt"
            rc="${NOVABOT_STUB_PYTHON3_RC:-0}"
            [ "$rc" = "0" ] && exit 0
            echo "stub build.py failed on purpose" >&2
            exit "$rc"
            """;

    /**
     * PATH 上只留一个自备的命令目录，才扮得出「这台机器没装 python3」——
     * 系统目录里真有 /usr/bin/python3，不能把系统目录放进 PATH。build.sh 在被测那一步
     * 之前要用到的系统命令逐个链进来。
     */
    private static Path linkBasics(Path sandbox) throws IOException {
        Path bin = Files.createDirectories(sandbox.resolve("path-bin"));
        for (String name : new String[]{"sh", "bash", "sed", "grep", "rm", "ls", "cat", "dirname", "basename", "pwd"}) {
            Path target = Path.of("/bin", name);
            if (!Files.exists(target)) {
                target = Path.of("/usr/bin", name);
            }
            Files.createSymbolicLink(bin.resolve(name), target);
        }
        return bin;
    }

    private static void writeSandbox(Path sandbox, boolean withPython3) throws IOException {
        Files.createDirectories(sandbox.resolve("logs"));
        Files.createDirectories(sandbox.resolve("work"));
        Path stubBin = Files.createDirectories(sandbox.resolve("stub-bin"));
        writeExecutable(stubBin.resolve("java"), STUB_JAVA);
        writeExecutable(stubBin.resolve("mvn"), STUB_MVN);
        if (withPython3) {
            writeExecutable(stubBin.resolve("python3"), STUB_PYTHON3);
        }
        linkBasics(sandbox);
    }

    /**
     * 把 build.sh 拷进沙箱跑一遍：脚本按自己所在目录认仓库根，不拷一份的话会跑到
     * 真仓库里去，真把整个工程构建起来。跑之前先摆一份「上一次构建留下的手册」，
     * 这一次构建该怎么处置它，正是要钉住的行为之一。
     */
    private int runBuild(Path sandbox, boolean withPython3, String python3Rc, String... args)
            throws IOException, InterruptedException {
        Path work = sandbox.resolve("work");
        Files.copy(repoRoot().resolve("build.sh"), work.resolve("build.sh"),
                StandardCopyOption.REPLACE_EXISTING);
        Path stale = work.resolve("manual").resolve("dist").resolve("stale-from-last-build.html");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "<html>previous build</html>\n", StandardCharsets.UTF_8);

        List<String> command = new ArrayList<>();
        command.add("/bin/bash");
        command.add(work.resolve("build.sh").toString());
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> env = builder.environment();
        env.put("PATH", sandbox.resolve("stub-bin") + File.pathSeparator + sandbox.resolve("path-bin"));
        env.remove("JAVA_HOME");
        env.put("NOVABOT_STUB_LOG", sandbox.resolve("logs").toString());
        env.put("NOVABOT_STUB_PYTHON3_RC", python3Rc);
        env.put("NOVABOT_ALLOW_DIRTY_BUILD", "1");
        env.put("NOVABOT_DIRTY_REASON", "测试沙箱里跑构建脚本，量的是手册这一步的行为");
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "build.sh 跑了 60 秒还没结束:\n" + output);
        Files.writeString(sandbox.resolve("logs").resolve("out.txt"), output, StandardCharsets.UTF_8);
        return process.exitValue();
    }

    @Test
    @DisplayName("没装 python3 的默认构建：印一行说明后照常走到打包，也不带旧手册")
    void defaultBuildWithoutPython3ContinuesAndDropsStaleManual(@TempDir Path sandbox) throws Exception {
        writeSandbox(sandbox, false);
        int exit = runBuild(sandbox, false, "0", "--skip-tests", "--no-smoke");

        String output = readLog(sandbox, "out.txt");
        String mvnLog = readLog(sandbox, "mvn.log");

        assertTrue(output.contains(SKIP_LINE), "屏上没说这次不带手册，输出:\n" + output);
        assertEquals(42, exit, "构建该越过手册这一步、一直走到打可运行包（桩在那里停下），实际退码 "
                + exit + "，输出:\n" + output);
        assertTrue(mvnLog.contains(PACKAGE_CALL), "没走到打可运行包那一步，mvn 记录:\n" + mvnLog);
        assertFalse(output.contains("python3: command not found"),
                "默认构建不该停在缺 python3 上，输出:\n" + output);
        assertFalse(Files.exists(sandbox.resolve("work").resolve("manual").resolve("dist")),
                "上一次留下的 manual/dist 没被清掉：没有 python3 的这次构建会把它当手册打进包");
    }

    @Test
    @DisplayName("没装 python3 还要打发布包：在第 4 步停下并说清，不落到 command not found")
    void releasePackageWithoutPython3StopsAtManualStep(@TempDir Path sandbox) throws Exception {
        writeSandbox(sandbox, false);
        int exit = runBuild(sandbox, false, "0", "--package", "--skip-tests", "--no-smoke");

        String output = readLog(sandbox, "out.txt");
        String mvnLog = readLog(sandbox, "mvn.log");

        assertTrue(exit != 0, "打发布包没有 python3 应当停下（退码非 0），实际退码 " + exit + "，输出:\n" + output);
        assertTrue(output.contains("==> [4/9]"), "没走到手册那一步就停了，输出:\n" + output);
        assertFalse(output.contains("==> [5/9]"), "该停在第 4 步，却走到了汇总产物，输出:\n" + output);
        assertTrue(output.contains(PACKAGE_STOP), "停下时没说清打发布包要 python3，输出:\n" + output);
        assertFalse(output.contains("python3: command not found"),
                "停下不该只报一句 command not found，输出:\n" + output);
        assertFalse(mvnLog.contains(PACKAGE_CALL), "停下之前不该已经打了可运行包，mvn 记录:\n" + mvnLog);
    }

    @Test
    @DisplayName("装着 python3：生成之前先把上一次的 manual/dist 清掉")
    void withPython3CleansStaleDistBeforeGenerating(@TempDir Path sandbox) throws Exception {
        writeSandbox(sandbox, true);
        int exit = runBuild(sandbox, true, "0", "--package", "--skip-tests", "--no-smoke");

        String python3Log = readLog(sandbox, "python3.log");
        String distAtBuild = readLog(sandbox, "manual-dist-at-build.txt");

        assertEquals(42, exit, "装着 python3 时构建该照常走到打可运行包（桩在那里停下），实际退码 "
                + exit + "，输出:\n" + readLog(sandbox, "out.txt"));
        assertTrue(python3Log.contains("manual/build.py"), "手册生成器没被调用，python3 记录:\n" + python3Log);
        assertEquals("(manual/dist is not there)", distAtBuild.trim(),
                "生成器启动那一刻 manual/dist 里还有上一次的残留，会被当成这次的手册打进包。那一刻目录内容:\n"
                        + distAtBuild);
    }

    @Test
    @DisplayName("装着 python3 而生成器报错：照旧停下，不悄悄出一份不带手册的包")
    void manualGenerationFailureStillStopsTheBuild(@TempDir Path sandbox) throws Exception {
        writeSandbox(sandbox, true);
        int exit = runBuild(sandbox, true, "9", "--skip-tests", "--no-smoke");

        String output = readLog(sandbox, "out.txt");
        String mvnLog = readLog(sandbox, "mvn.log");

        assertTrue(exit != 0, "生成器报错时构建应当停下（退码非 0），实际退码 " + exit + "，输出:\n" + output);
        assertFalse(output.contains(SKIP_LINE), "生成失败不该印「这次不带手册」那句，输出:\n" + output);
        assertFalse(mvnLog.contains(PACKAGE_CALL), "生成失败后不该继续打包，mvn 记录:\n" + mvnLog);
        assertTrue(output.contains("stub build.py failed on purpose"),
                "生成器的报错没原样带到屏上，输出:\n" + output);
    }

    private static String readLog(Path sandbox, String name) throws IOException {
        Path file = sandbox.resolve("logs").resolve(name);
        return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    private static void writeExecutable(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(file));
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        perms.add(PosixFilePermission.GROUP_EXECUTE);
        perms.add(PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(file, perms);
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
}
