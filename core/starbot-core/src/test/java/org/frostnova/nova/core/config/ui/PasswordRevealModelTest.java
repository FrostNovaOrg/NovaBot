package org.frostnova.nova.core.config.ui;

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
 * 口令框显示／隐藏的行为判据
 * <p>
 * 登录页一口、设置页改口令三栏、设置页机密行与签发口令页共用同一份构件：
 * 默认藏着，点一下揭开，再点藏回去。六处各自一份状态——登录页点了「显示」
 * 不该把别处一起揭开。
 * <p>
 * 判定全是纯函数，因此喂值直接跑。{@link ConfigUiFrontendTest} 那几格看得见
 * 「有没有写」，看不见「算得对不对」。
 *
 * <h2>找不到 node 时红，不是跳过</h2>
 *
 * 跳过与通过在构建日志上长得一样，而这一格跳过意味着口令框那几格这一跑一格没量。
 */
@DisplayName("口令框显示隐藏")
class PasswordRevealModelTest {
    private static final String FIXTURE =
            "core/starbot-core/src/test/resources/frontend/password-reveal-fixture.mjs";

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
    @DisplayName("默认隐藏、切换、六处共用同一份构件、生产 id 闭集与桩 DOM 翻面")
    void revealTogglesAndSixSitesShareOneModule() throws IOException, InterruptedException {
        Path fixture = repoRoot().resolve(FIXTURE);
        assertTrue(Files.exists(fixture), "夹具不见了，这一格此刻什么也没量: " + fixture);

        ProcessBuilder builder = new ProcessBuilder("node", fixture.toString());
        builder.directory(repoRoot().toFile());
        builder.redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("起不动 node，口令框显示隐藏这一跑一格没量。"
                    + "本项目的前端判据需要 node 在 PATH 上", e);
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "夹具跑了 60 秒还没结束:\n" + output);

        System.out.println("口令框显示隐藏夹具：" + output);

        assertEquals(0, process.exitValue(), "口令框显示隐藏与预期不符:\n" + output);
        assertTrue(output.contains("跑了") && !output.contains("跑了 0 格"),
                "夹具没报出跑了几格，或者一格都没跑:\n" + output);
    }

    @Test
    @DisplayName("点眼睛 type 恰翻一次；双份 toggle 会回到原状")
    void clickFlipsTypeOnceOnEachProductionId() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "点眼睛 type 恰翻一次");
    }
}
