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
 * 容器入口在镜像的 plugins、plugins-lib 里一个 jar 都没有时照常起程序，不整个退出。
 * <p>
 * 抓的用户故障：自己拼镜像、拿掉全部内置插件或依赖时，入口在 cp 那句当场退出，
 * 程序不起，日志里只有一句 cp 报错（通配符没有匹配时原样留下，cp 找不到 *.jar）。
 * <p>
 * 起入口的办法与 {@link DockerEntrypointPluginsLibTest} 同一套：java 与 start.sh 换成替身，
 * SRC、DST 指到临时目录。start.sh 落一枚到过起程序那步的标记，好把「照常往下走到起程序」
 * 说成看得见的断言。入口脚本默认取仓库里的 dist/templates/docker-entrypoint.sh；
 * -DdockerEntrypoint.script=… 指到改动过的副本，用来核对入口被改坏时这组测试会红。
 */
@DisplayName("容器入口在镜像插件目录没有 jar 时不再整个退出")
class DockerEntrypointEmptyPluginDirTest {

    /**
     * 抓的用户故障：镜像的 plugins 里一个 jar 都没有，入口在铺 plugins 那句退出，程序不起。
     */
    @Test
    @DisplayName("plugins 里一个 jar 都没有：照常起程序，plugins-lib 照铺")
    void emptyImagePluginsStillHandsOff(@TempDir Path dir) throws Exception {
        Layout layout = layout(dir, false, true);

        Run run = runEntry(layout);

        assertEquals(0, run.code, "入口应照常起程序，不该在 cp 那句退出。标准错误:\n" + run.stderr);
        assertTrue(Files.isRegularFile(layout.dst.resolve("started.txt")),
                "入口应往下走到起程序那步。标准错误:\n" + run.stderr);
        assertEquals("caffeine-3.2.0.jar", listing(layout.dst.resolve("plugins-lib")),
                "plugins 空着也该把 plugins-lib 铺过去");
    }

    /**
     * 抓的用户故障：镜像的 plugins-lib 里一个 jar 都没有，入口在铺 plugins-lib 那句退出，程序不起。
     */
    @Test
    @DisplayName("plugins-lib 里一个 jar 都没有：照常起程序，plugins 照铺")
    void emptyImagePluginLibStillHandsOff(@TempDir Path dir) throws Exception {
        Layout layout = layout(dir, true, false);

        Run run = runEntry(layout);

        assertEquals(0, run.code, "入口应照常起程序，不该在 cp 那句退出。标准错误:\n" + run.stderr);
        assertTrue(Files.isRegularFile(layout.dst.resolve("started.txt")),
                "入口应往下走到起程序那步。标准错误:\n" + run.stderr);
        assertEquals("nova-demo-2.0.0.jar", listing(layout.dst.resolve("plugins")),
                "plugins-lib 空着也该把 plugins 铺过去");
    }

    /**
     * @param withPluginJars    镜像侧 plugins 里放不放 jar
     * @param withPluginLibJars 镜像侧 plugins-lib 里放不放 jar
     */
    private static Layout layout(Path dir, boolean withPluginJars, boolean withPluginLibJars)
            throws IOException {
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
        if (withPluginJars) {
            write(layout.src.resolve("plugins/nova-demo-2.0.0.jar"), "new-plugin");
        }
        if (withPluginLibJars) {
            write(layout.src.resolve("plugins-lib/caffeine-3.2.0.jar"), "img-caffeine");
        }
        writeExecutable(layout.src.resolve("start.sh"), ""
                + "#!/usr/bin/env bash\n"
                + "printf started > started.txt\n"
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
