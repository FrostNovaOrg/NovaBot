package org.frostnova.nova.core.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动脚本在两种布局下交给 java 的工作目录和路径。
 * <p>
 * 程序放进 {@code releases/版本/} 之后，若仍先进入脚本自己的目录，
 * 锁、配置、凭据和日志都会落进版本目录：两版各拿一把锁，等于一起跑，
 * 新版也读不到原来的配置。扁平布局（容器、手动安装、演示）的命令行则一个字都不能变。
 * java 换成只记下工作目录和参数的替身，不起真的虚拟机，也不占端口。
 */
@DisplayName("启动脚本认分目录布局")
class SplitLayoutStartTest {

    private static final String TRIM = "-XX:TrimNativeHeapInterval=60000";
    private static final String TRIM_OFF = "-XX:TrimNativeHeapInterval=0";
    private static final String GC_LOG = "-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags:filecount=20,filesize=5m";

    /**
     * 扁平布局下假 java 收到的默认参数，用于对照两种布局的启动命令。
     */
    private static final String FLAT_ARGS = """
            -Xms64m
            -Xmx512m
            -XX:+UseSerialGC
            -XX:MinHeapFreeRatio=20
            -XX:MaxHeapFreeRatio=40
            -Xss256k
            -XX:MaxMetaspaceSize=192m
            -XX:+ExitOnOutOfMemoryError
            -Djava.awt.headless=true
            -Duser.timezone=Asia/Shanghai
            -Dfile.encoding=UTF-8
            %s
            -Dloader.path=lib,plugins,plugins-lib
            -jar
            NovaBot.jar""".formatted(GC_LOG);

    @Test
    @DisplayName("程序在 releases/版本 下时，工作目录是安装目录，程序用版本目录的绝对路径")
    void splitLayoutKeepsDataDirectory(@TempDir Path dir) throws Exception {
        Layout layout = layout(dir, true);
        Run run = run(layout);
        assertEquals(0, run.code, "脚本应把假 java 跑起来。标准错误:\n" + run.stderr);
        assertSplit(layout, run);
    }

    @Test
    @DisplayName("分目录那支若仍进入脚本目录，工作目录会落在版本目录")
    void splitBranchThatStaysInScriptDirectoryIsRed(@TempDir Path dir) throws Exception {
        Layout layout = layout(dir, true);
        String original = Files.readString(layout.script, StandardCharsets.UTF_8);
        String mutated = original.replace("cd \"$data_dir\"", "cd \"$script_dir\"");
        assertNotEquals(original, mutated, "变异没有落到分目录那一支的进入目录上");
        Files.writeString(layout.script, mutated, StandardCharsets.UTF_8);
        Run run = run(layout);
        AssertionError error = assertThrows(AssertionError.class, () -> assertSplit(layout, run));
        assertTrue(error.getMessage().contains("工作目录应是安装目录"), error.getMessage());
        System.out.println("变异后仍进版本目录:\n" + error.getMessage());
    }

    @Test
    @DisplayName("扁平那支若也改用绝对路径，命令行就和原先对不上")
    void flatBranchThatUsesAbsolutePathsIsRed(@TempDir Path dir) throws Exception {
        Layout layout = layout(dir, false);
        String original = Files.readString(layout.script, StandardCharsets.UTF_8);
        String from = "java $JVM_OPTS -Dloader.path=lib,plugins,plugins-lib -jar NovaBot.jar \"$@\" &";
        String to = "java $JVM_OPTS \"-Dloader.path=$app_dir/lib,$app_dir/plugins,$app_dir/plugins-lib\" -jar \"$app_dir/NovaBot.jar\" \"$@\" &";
        assertTrue(original.contains(from), "扁平那一支的原句不在了");
        Files.writeString(layout.script, original.replace(from, to), StandardCharsets.UTF_8);
        Run run = run(layout);
        AssertionError error = assertThrows(AssertionError.class,
                () -> assertEquals(FLAT_ARGS, run.args.strip(),
                        "扁平布局下命令行应与原先一字不差。实际:\n" + run.args));
        assertTrue(error.getMessage().contains("一字不差"), error.getMessage());
        System.out.println("变异后扁平也走绝对路径:\n" + error.getMessage());
    }

    private static void assertSplit(Layout layout, Run run) throws Exception {
        assertEquals(0, run.code, "脚本应把假 java 跑起来。标准错误:\n" + run.stderr);
        Path cwd = Path.of(run.cwd.trim()).toRealPath();
        String loader = argument(run.args, "-Dloader.path=");
        String jar = argumentAfter(run.args, "-jar");
        String app = layout.version.toRealPath().toString();
        String data = layout.install.toRealPath().toString();
        assertAll(
                () -> assertEquals(layout.install.toRealPath(), cwd,
                        "分目录布局下工作目录应是安装目录，假 java 记下的却是版本目录"),
                () -> assertEquals(app + "/lib," + app + "/plugins," + app + "/plugins-lib,"
                                + data + "/plugins," + data + "/plugins-lib",
                        loader,
                        "loader.path 应先写版本目录里的程序，再接上安装目录里使用者自己的插件"),
                () -> assertEquals(app + "/NovaBot.jar", jar,
                        "应启动版本目录里的程序"));
    }

    @Test
    @DisplayName("扁平布局下假 java 的命令行与工作目录与原先一字不差")
    void flatLayoutCommandUnchanged(@TempDir Path dir) throws Exception {
        Layout layout = layout(dir, false);
        Run run = run(layout);
        assertEquals(0, run.code, "脚本应把假 java 跑起来。标准错误:\n" + run.stderr);
        assertEquals(layout.version.toRealPath(), Path.of(run.cwd.trim()).toRealPath(),
                "扁平布局下工作目录仍应是脚本自己的目录");
        assertEquals(FLAT_ARGS, run.args.strip(),
                "扁平布局下命令行应与原先一字不差。实际:\n" + run.args);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Linux 支持时默认定时归还，避免空闲的原生内存长期常驻")
    void linuxSupportedAddsTrimAfterDefaults(boolean split, @TempDir Path dir) throws Exception {
        Layout layout = layout(dir, split);
        Run run = run(layout, "Linux", 0, "-Xmx768m");
        assertStarted(run);
        assertEquals(TRIM + "\n-version\n", run.probe, "应由同一个 java 试跑完整选项");
        assertEquals("probe\nlaunch\n", run.calls, "只试跑一次，并在试跑后启动");
        assertTrue(run.args.contains("-Dfile.encoding=UTF-8\n" + TRIM + "\n" + GC_LOG + "\n-Xmx768m\n"),
                "定时归还应排在默认参数之后、用户参数之前:\n" + run.args);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("旧 Java 不认得定时归还时跳过，避免升级后无法启动")
    void linuxUnsupportedStillStarts(boolean split, @TempDir Path dir) throws Exception {
        Layout layout = layout(dir, split);
        Run run = run(layout, "Linux", 1, "");
        assertStarted(run);
        assertEquals(TRIM + "\n-version\n", run.probe, "必须按试跑退码决定是否添加");
        assertEquals("probe\nlaunch\n", run.calls, "试跑失败也应继续启动");
        assertFalse(run.args.contains("TrimNativeHeapInterval"), "旧 Java 不应收到不认识的选项");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Darwin 不试跑也不加定时归还，避免无效的平台选项")
    void darwinSkipsTrimAndProbe(boolean split, @TempDir Path dir) throws Exception {
        Layout layout = layout(dir, split);
        Run run = run(layout, "Darwin", 0, "");
        assertStarted(run);
        assertEquals("", run.probe, "非 Linux 不应试跑 java");
        assertEquals("launch\n", run.calls, "非 Linux 只应正式启动一次");
        assertFalse(run.args.contains("TrimNativeHeapInterval"), "非 Linux 不应添加归还选项");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("用户关闭定时归还的参数放在默认值后，避免关闭设置被覆盖")
    void userCanDisableTrimAfterDefault(boolean split, @TempDir Path dir) throws Exception {
        Layout layout = layout(dir, split);
        Run run = run(layout, "Linux", 0, TRIM_OFF);
        assertStarted(run);
        assertEquals(TRIM + "\n-version\n", run.probe, "试跑只用默认选项");
        assertTrue(run.args.contains(TRIM + "\n" + GC_LOG + "\n" + TRIM_OFF + "\n"),
                "用户关闭选项必须在默认值后面:\n" + run.args);
    }

    @ParameterizedTest
    @CsvSource({
            "false, Linux, 0", "true, Linux, 0",
            "false, Linux, 1", "true, Linux, 1",
            "false, Darwin, 0", "true, Darwin, 0",
            "false, Darwin, 1", "true, Darwin, 1"
    })
    @DisplayName("两种布局只按支持情况加归还选项，避免原有 JVM 参数或程序路径漂移")
    void bothLayoutsPreserveOtherArguments(boolean split, String os, int probeExit,
                                          @TempDir Path dir) throws Exception {
        Layout layout = layout(dir, split);
        Run run = run(layout, os, probeExit, "");
        assertStarted(run);
        String expected = FLAT_ARGS;
        if ("Linux".equals(os) && probeExit == 0) {
            expected = expected.replace("-Dfile.encoding=UTF-8\n", "-Dfile.encoding=UTF-8\n" + TRIM + "\n");
        }
        if (split) {
            assertSplit(layout, run);
            String app = layout.version.toRealPath().toString();
            String data = layout.install.toRealPath().toString();
            expected = expected.replace("-Dloader.path=lib,plugins,plugins-lib",
                    "-Dloader.path=" + app + "/lib," + app + "/plugins," + app + "/plugins-lib,"
                            + data + "/plugins," + data + "/plugins-lib")
                    .replace("\nNovaBot.jar", "\n" + app + "/NovaBot.jar");
        }
        assertEquals(layout.install.toRealPath(), Path.of(run.cwd.trim()).toRealPath(), "工作目录应保持不变");
        assertEquals(expected, run.args.strip(), "其余参数与路径应一字不差");
    }

    private static void assertStarted(Run run) {
        assertEquals(0, run.code, "NovaBot 应照常启动。标准错误:\n" + run.stderr);
        assertFalse(run.cwd.isBlank(), "应真正调用 java 启动程序");
        assertEquals("", run.stdout, "试跑的标准输出应丢掉");
        assertEquals("", run.stderr, "试跑的诊断应丢掉，不能冒充启动失败");
    }

    private static Layout layout(Path dir, boolean split) throws IOException {
        Layout layout = new Layout();
        layout.install = split ? dir.resolve("install") : dir.resolve("app");
        layout.version = split ? layout.install.resolve("releases").resolve("9.9.9") : layout.install;
        layout.bin = dir.resolve("bin");
        layout.record = dir.resolve("record");
        Files.createDirectories(layout.version);
        Files.createDirectories(layout.bin);
        Files.createDirectories(layout.record);
        layout.script = layout.version.resolve("start.sh");
        Files.copy(repoRoot().resolve("dist/templates/start.sh"), layout.script);
        writeExecutable(layout.bin.resolve("java"), """
                #!/usr/bin/env bash
                if [ "$#" -eq 2 ] && [ "$1" = "-XX:TrimNativeHeapInterval=60000" ] && [ "$2" = "-version" ]; then
                    printf 'probe\\n' >> "$RECORD/calls"
                    printf '%s\\n' "$@" >> "$RECORD/probe"
                    printf 'probe output\\n'
                    printf 'probe diagnostic\\n' >&2
                    exit "$PROBE_EXIT"
                fi
                printf 'launch\\n' >> "$RECORD/calls"
                for arg in "$@"; do
                    if [ "$PROBE_EXIT" -ne 0 ] && [[ "$arg" == -XX:TrimNativeHeapInterval=* ]]; then
                        printf 'Unrecognized VM option\\n' >&2
                        exit 1
                    fi
                done
                pwd > "$RECORD/cwd"
                printf '%s\\n' "$@" > "$RECORD/args"
                exit 0
                """);
        writeExecutable(layout.bin.resolve("uname"), """
                #!/usr/bin/env bash
                [ "$#" -eq 1 ] && [ "$1" = "-s" ] || exit 1
                printf '%s\\n' "$TEST_OS"
                """);
        return layout;
    }

    private static Run run(Layout layout) throws Exception {
        return run(layout, "Darwin", 0, "");
    }

    private static Run run(Layout layout, String os, int probeExit, String javaOpts) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("bash", layout.script.toString());
        builder.environment().put("PATH",
                layout.bin.toAbsolutePath() + java.io.File.pathSeparator + builder.environment().getOrDefault("PATH", ""));
        builder.environment().remove("TZ");
        builder.environment().put("JAVA_OPTS", javaOpts);
        builder.environment().put("TEST_OS", os);
        builder.environment().put("PROBE_EXIT", Integer.toString(probeExit));
        builder.environment().put("RECORD", layout.record.toAbsolutePath().toString());
        builder.redirectErrorStream(false);
        Process process = builder.start();
        Run run = new Run();
        byte[] out = process.getInputStream().readAllBytes();
        byte[] err = process.getErrorStream().readAllBytes();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            run.code = -1;
            run.stderr = "超时\n" + new String(err, StandardCharsets.UTF_8);
            return run;
        }
        run.code = process.exitValue();
        run.stdout = new String(out, StandardCharsets.UTF_8);
        run.stderr = new String(err, StandardCharsets.UTF_8);
        run.cwd = readIfExists(layout.record.resolve("cwd"));
        run.args = readIfExists(layout.record.resolve("args"));
        run.probe = readIfExists(layout.record.resolve("probe"));
        run.calls = readIfExists(layout.record.resolve("calls"));
        return run;
    }

    private static String argument(String args, String prefix) {
        for (String line : args.split("\n", -1)) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
        return "";
    }

    private static String argumentAfter(String args, String flag) {
        List<String> lines = List.of(args.split("\n", -1));
        for (int i = 0; i < lines.size(); i++) {
            if (flag.equals(lines.get(i)) && i + 1 < lines.size()) {
                return lines.get(i + 1);
            }
        }
        return "";
    }

    private static void writeExecutable(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
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

    private static final class Layout {
        Path install;
        Path version;
        Path bin;
        Path record;
        Path script;
    }

    private static final class Run {
        int code;
        String stdout = "";
        String stderr = "";
        String cwd = "";
        String args = "";
        String probe = "";
        String calls = "";
    }
}
