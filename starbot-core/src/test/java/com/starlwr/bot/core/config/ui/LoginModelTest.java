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
 * 登录页显隐矩阵的行为判据
 * <p>
 * 登录页上摆什么由三个互不相干的条件决定：这台机器登记过通行密钥没有、二次验证开着没有、
 * 这个来源此刻是不是被锁着。三条各有两档，一共八格，而<b>使用者日常见到的永远只有一格</b>——
 * 手点点不出其余七格，尤其点不出「锁定中」那一列（要先连错五次口令）。
 * <p>
 * 判定全是纯函数，因此喂值直接跑。{@link ConfigUiFrontendTest} 那几格是静态检查，
 * 看得见「有没有写」，看不见「算得对不对」。
 *
 * <h2>找不到 node 时红，不是跳过</h2>
 *
 * 跳过与通过在构建日志上长得一样，而这一格跳过意味着<b>登录页那八格这一跑一格没量</b>。
 * 本项目的前端是不经构建的 ES module，node 是它唯一的可执行环境。
 */
@DisplayName("登录页显隐矩阵")
class LoginModelTest {
    /**
     * 夹具在仓库里的位置。引用的是源码树里的 login-model.js，不是构建产物里的副本
     */
    private static final String FIXTURE = "starbot-core/src/test/resources/frontend/login-model-fixture.mjs";

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
    @DisplayName("八格显隐矩阵与锁定文案逐格与预期相同")
    void loginMatrixBehavesAsSpecified() throws IOException, InterruptedException {
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
            throw new IOException("起不动 node，登录页那八格这一跑一格没量。"
                    + "本项目的前端判据需要 node 在 PATH 上", e);
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "夹具跑了 60 秒还没结束:\n" + output);

        // 绿的时候也要留下跑了几格：只印失败的话，「全绿」与「一格没跑」在日志上长得一样
        System.out.println("登录页矩阵夹具：" + output);

        assertEquals(0, process.exitValue(), "登录页显隐矩阵与预期不符:\n" + output);
        // 夹具自己报跑了几格。它要是一格都没跑（比如 import 断了却没抛），退码同样是 0
        assertTrue(output.contains("跑了") && !output.contains("跑了 0 格"),
                "夹具没报出跑了几格，或者一格都没跑:\n" + output);
    }
}
