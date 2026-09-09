package org.frostnova.nova.console.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 由 JUnit 拉起一份前端夹具，并把它的读数搬进构建日志
 * <p>
 * 与核心那一份同形：找不到 node 时红，不是跳过。
 */
final class FrontendFixture {
    private FrontendFixture() {
    }

    static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }

    static void run(String fixture, String what) throws IOException, InterruptedException {
        Path path = repoRoot().resolve(fixture);
        assertTrue(Files.exists(path), "夹具不见了，这一格此刻什么也没量: " + path);

        ProcessBuilder builder = new ProcessBuilder("node", path.toString());
        builder.directory(repoRoot().toFile());
        builder.redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("起不动 node，" + what + "这一跑一格没量。"
                    + "本项目的前端判据需要 node 在 PATH 上", e);
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "夹具跑了 60 秒还没结束:\n" + output);

        System.out.println(what + "夹具：" + output);

        assertEquals(0, process.exitValue(), what + "与预期不符:\n" + output);
        assertTrue(output.contains("跑了") && !output.contains("跑了 0 格"),
                "夹具没报出跑了几格，或者一格都没跑:\n" + output);
    }
}
