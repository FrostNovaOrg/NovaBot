package org.frostnova.nova.core.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容器入口要把镜像里 plugins-lib 的依赖铺到卷上。
 * <p>
 * 抓的用户故障：照手册装法三新装容器版，入口只建出空的 plugins-lib，
 * 内置插件要用的运行期依赖（caffeine、jieba-analysis 等）不在卷上的类路径里，
 * 程序起两秒就 Application run failed（NoClassDefFoundError），按 --restart 反复重启，
 * 控制台打不开，日志里也没有登录二维码。
 * <p>
 * 规则与上面 plugins 的同一套、也与 install.sh 处理 plugins-lib 的规则一致：
 * 镜像自带的依赖按构件名删旧拷新，构件名对不上的——使用者自己放进卷的——原样留下。
 * <p>
 * java 与 start.sh 都换成替身，SRC、DST 指到临时目录。入口脚本默认取仓库里的
 * dist/templates/docker-entrypoint.sh；-DdockerEntrypoint.script=… 指到改动过的副本，
 * 用来核对入口被改坏时这组测试会红。
 */
@DisplayName("容器入口把镜像里自带的插件依赖铺到卷上，按构件名换新、自己放的留下")
class DockerEntrypointPluginsLibTest {

    /**
     * 抓的用户故障：新卷上第一次起，镜像里的依赖一个都没铺过去，程序起不来。
     */
    @Test
    @DisplayName("新卷：镜像侧的两个依赖都铺到卷上的 plugins-lib")
    void freshVolumeGetsImageDeps(@TempDir Path dir) throws Exception {
        Layout layout = layout(dir);

        Run run = runEntry(layout);

        assertEquals(0, run.code, "入口应铺好程序并交给 start.sh。标准错误:\n" + run.stderr);
        assertEquals("caffeine-3.2.0.jar jieba-analysis-1.0.2.jar",
                listing(layout.dst.resolve("plugins-lib")),
                "新卷上的 plugins-lib 应有镜像里的两个依赖");
        assertEquals("img-caffeine", read(layout.dst.resolve("plugins-lib/caffeine-3.2.0.jar")),
                "铺过去的应是镜像里那份");
        assertEquals("img-jieba", read(layout.dst.resolve("plugins-lib/jieba-analysis-1.0.2.jar")),
                "铺过去的应是镜像里那份");
    }

    /**
     * 抓的用户故障：换镜像升级后，卷上还压着旧版依赖跟新版程序混跑；
     * 反过来整个替换 plugins-lib，又会把使用者自己放的依赖也卸掉，第三方插件跟着起不来。
     */
    @Test
    @DisplayName("换镜像：旧版按构件名清掉，自己放的留下")
    void imageChangeSwapsByArtifactNameKeepsUserJars(@TempDir Path dir) throws Exception {
        Layout layout = layout(dir);
        // 换镜像前的卷：上一版镜像自带的旧版 caffeine，另有使用者自己放的一个依赖
        Files.createDirectories(layout.dst.resolve("plugins-lib"));
        write(layout.dst.resolve("plugins-lib/caffeine-3.1.0.jar"), "old-caffeine");
        write(layout.dst.resolve("plugins-lib/user-dep-2.0.jar"), "user-dep");

        Run run = runEntry(layout);

        assertEquals(0, run.code, "入口应铺好程序并交给 start.sh。标准错误:\n" + run.stderr);
        assertEquals("caffeine-3.2.0.jar jieba-analysis-1.0.2.jar user-dep-2.0.jar",
                listing(layout.dst.resolve("plugins-lib")),
                "换镜像后卷上的 plugins-lib 应是本版镜像里的依赖与自己放的，旧版不在");
        assertEquals("img-caffeine", read(layout.dst.resolve("plugins-lib/caffeine-3.2.0.jar")),
                "旧版应换成本版镜像里的新版");
        assertEquals("user-dep", read(layout.dst.resolve("plugins-lib/user-dep-2.0.jar")),
                "卷上自放的依赖应原样留着");
    }

    private static Layout layout(Path dir) throws IOException, InterruptedException {
        Layout layout = new Layout();
        layout.src = dir.resolve("src");
        layout.dst = dir.resolve("dst");
        layout.bin = dir.resolve("bin");
        layout.script = dir.resolve("docker-entrypoint.sh");
        Files.createDirectories(layout.src.resolve("lib"));
        Files.createDirectories(layout.src.resolve("plugins"));
        Files.createDirectories(layout.src.resolve("plugins-lib"));
        Files.createDirectories(layout.bin);

        write(layout.src.resolve("NovaBot.jar"), "new-jar");
        write(layout.src.resolve("lib/core.jar"), "new-lib");
        write(layout.src.resolve("plugins/nova-demo-2.0.0.jar"), "new-plugin");
        write(layout.src.resolve("plugins-lib/caffeine-3.2.0.jar"), "img-caffeine");
        write(layout.src.resolve("plugins-lib/jieba-analysis-1.0.2.jar"), "img-jieba");
        writeExecutable(layout.src.resolve("start.sh"), ""
                + "#!/usr/bin/env bash\n"
                + "exec java\n");

        writeExecutable(layout.bin.resolve("java"), ""
                + "#!/usr/bin/env bash\n"
                + "exit 0\n");

        String override = System.getProperty("dockerEntrypoint.script");
        Path source = override == null
                ? repoRoot().resolve("dist/templates/docker-entrypoint.sh")
                : Path.of(override);
        String text = Files.readString(source, StandardCharsets.UTF_8);
        if (text.contains("\nSRC=/opt/starbot\n")) {
            text = text.replace("\nSRC=/opt/starbot\n",
                    "\nSRC='" + layout.src.toAbsolutePath() + "'\n");
            text = text.replace("\nDST=/app\n",
                    "\nDST='" + layout.dst.toAbsolutePath() + "'\n");
        }
        Files.writeString(layout.script, text, StandardCharsets.UTF_8);
        return layout;
    }

    private static Run runEntry(Layout layout) throws IOException, InterruptedException {
        Path outFile = layout.bin.resolve("out.txt");
        Path errFile = layout.bin.resolve("err.txt");
        ProcessBuilder builder = new ProcessBuilder("bash", layout.script.toAbsolutePath().toString());
        builder.environment().put("SRC", layout.src.toAbsolutePath().toString());
        builder.environment().put("DST", layout.dst.toAbsolutePath().toString());
        builder.environment().put("PATH", layout.bin.toAbsolutePath() + java.io.File.pathSeparator
                + builder.environment().getOrDefault("PATH", ""));
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
        assertTrue(run.finished, "入口 30 秒还没结束。标准输出:\n" + run.stdout + "\n标准错误:\n" + run.stderr);
        return run;
    }

    private static String listing(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString())
                    .sorted()
                    .collect(Collectors.joining(" "));
        }
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

    private static final class Layout {
        Path src;
        Path dst;
        Path bin;
        Path script;
    }

    private static final class Run {
        boolean finished;
        int code;
        String stdout = "";
        String stderr = "";
    }
}
