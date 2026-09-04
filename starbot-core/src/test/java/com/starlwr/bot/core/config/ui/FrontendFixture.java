package com.starlwr.bot.core.config.ui;

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
 * 前端是不经构建的 ES module，纯函数那几组只能在 node 上喂值跑。单独跑的脚本，
 * 它的读数只活在跑过它的那个人的终端里；接进构建之后，每一次整盘都会把那些格子问一遍。
 *
 * <h2>找不到 node 时红，不是跳过</h2>
 *
 * 跳过与通过在构建日志上长得一样，而这一格跳过意味着<b>那一组纯函数这一跑一格没量</b>。
 * <p>
 * 这几条规矩收在一处而不是每个夹具测试各抄一遍：抄两份之后，
 * 「起不动 node 算红还是算跳过」就有了两个答案，而分叉的那一天没有任何东西会提起它。
 */
final class FrontendFixture {
    private FrontendFixture() {
    }

    /**
     * 定位仓库根目录。测试既可能由 Maven 在模块目录下执行，也可能由 IDE 在仓库根目录下执行
     */
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

    /**
     * 跑一份夹具，退码非 0 即判红
     * @param fixture 夹具在仓库里的相对路径
     * @param what 这一组判定叫什么，进红时那句话与构建日志
     */
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
            // 起不来就红。前端是不经构建的 ES module，node 是它唯一的可执行环境，
            // 缺了它这一格量不到任何东西——而「量不到」不许读成「没问题」
            throw new IOException("起不动 node，" + what + "这一跑一格没量。"
                    + "本项目的前端判据需要 node 在 PATH 上", e);
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "夹具跑了 60 秒还没结束:\n" + output);

        // 绿的时候也要留下跑了几格：只印失败的话，「全绿」与「一格没跑」在日志上长得一样
        System.out.println(what + "夹具：" + output);

        assertEquals(0, process.exitValue(), what + "与预期不符:\n" + output);
        // 夹具自己报跑了几格。它要是一格都没跑（比如 import 断了却没抛），退码同样是 0
        assertTrue(output.contains("跑了") && !output.contains("跑了 0 格"),
                "夹具没报出跑了几格，或者一格都没跑:\n" + output);
    }
}
