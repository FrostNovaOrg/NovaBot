package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置页那几个判定的行为判据
 * <p>
 * 搜索、只看改过、默认值、危险项围栏——这四件事决定了使用者在设置页上看见什么、
 * 以及哪一次改动会先被拦下来问一句。它们全是纯函数，因此可以喂值直接跑，
 * 而 {@link ConfigUiFrontendTest} 那几格是静态检查，看得见「有没有写」，看不见「算得对不对」。
 *
 * <h2>为什么由 JUnit 拉起 node，而不是单独跑一个脚本</h2>
 *
 * 单独跑的脚本，它的读数只活在跑过它的那个人的终端里。接进构建之后，
 * 「危险项还是那四个吗」「搜索还认不认键名」每一次整盘都会被问一遍。
 *
 * <h2>找不到 node 时红，不是跳过</h2>
 *
 * 跳过与通过在构建日志上长得一样，而这一格跳过意味着<b>前端那四个判定这一跑一格没量</b>。
 * 本项目的前端是不经构建的 ES module，node 是它唯一的可执行环境。
 */
@DisplayName("设置页判定")
class SettingsModelTest {
    /**
     * 夹具在仓库里的位置。引用的是源码树里的 settings-model.js，不是构建产物里的副本
     */
    private static final String FIXTURE = "starbot-core/src/test/resources/frontend/settings-model-fixture.mjs";

    /**
     * 定位仓库根目录。测试既可能由 Maven 在模块目录下执行，也可能由 IDE 在仓库根目录下执行
     */
    private Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }

    @Test
    @DisplayName("搜索、只看改过、默认值、危险项围栏四组判定逐格与预期相同")
    void pureFunctionsBehaveAsSpecified() throws IOException, InterruptedException {
        Path fixture = repoRoot().resolve(FIXTURE);
        assertTrue(Files.exists(fixture), "夹具不见了，这一格此刻什么也没量: " + fixture);

        ProcessBuilder builder = new ProcessBuilder("node", fixture.toString());
        builder.directory(repoRoot().toFile());
        builder.redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            // 起不来就红。前端是不经构建的 ES module，node 是它唯一的可执行环境，
            // 缺了它这一格量不到任何东西——而「量不到」不许读成「没问题」
            throw new IOException("起不动 node，设置页那四组判定这一跑一格没量。"
                    + "本项目的前端判据需要 node 在 PATH 上", e);
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "夹具跑了 60 秒还没结束:\n" + output);

        // 绿的时候也要留下跑了几格：只印失败的话，「全绿」与「一格没跑」在日志上长得一样
        System.out.println("设置页判定夹具：" + output);

        assertEquals(0, process.exitValue(), "设置页判定与预期不符:\n" + output);
        // 夹具自己报跑了几格。它要是一格都没跑（比如 import 断了却没抛），退码同样是 0
        assertTrue(output.contains("跑了") && !output.contains("跑了 0 格"),
                "夹具没报出跑了几格，或者一格都没跑:\n" + output);
    }
}
