package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 切通道之后：旧编辑器的金额草稿监听要摘掉，旧预览晚回来不许撤销当前预览图
 * <p>
 * 夹具真跑整条通道页：换屏、版式编辑器、本群设置的金额开关、预览地址的撤销，
 * 用的都是产品码。只看源码里有没有那句注销是不行的——注销写了、挂错时机，照样绿。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}；
 * 这里没直接用它的 run，是因为要多透传一个参数：掰断时以
 * {@code -Dnova.push.pages=<一份 config-ui-pages 拷贝>} 把量程指向拷贝，
 * 平常不设该属性，即量源码树里那一份。
 */
@DisplayName("切通道后旧编辑器的金额草稿监听与晚到的预览回应")
class PushChannelSwitchPreviewTest {

    @Test
    @DisplayName("切走后拨金额不发旧通道的预览；旧回应晚回来不撤销当前预览图")
    void staleEditorNeitherListensNorRevokesAfterSwitch() throws IOException, InterruptedException {
        Path fixture = Path.of("src", "test", "resources", "frontend",
                "push-channel-switch-preview-fixture.mjs").toAbsolutePath();
        if (!Files.exists(fixture)) {
            fail("夹具不见了，当时工作目录是 " + Path.of("").toAbsolutePath());
        }
        List<String> command = new ArrayList<>(List.of("node", fixture.toString()));
        String pages = System.getProperty("nova.push.pages", "").strip();
        if (!pages.isEmpty()) command.add(pages);

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(FrontendFixture.repoRoot().toFile())
                .redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            fail("起不动 node，这一跑一格没量。本项目的前端判据需要 node 在 PATH 上：" + e.getMessage());
            return;
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "夹具跑了 60 秒还没结束:\n" + output);

        System.out.println("切通道后的旧编辑器与晚到预览夹具：" + output);

        assertEquals(0, process.exitValue(), "切通道后的旧编辑器与晚到预览与预期不符:\n" + output);
        assertTrue(output.contains("跑了") && !output.contains("跑了 0 格"),
                "夹具没报出跑了几格，或者一格都没跑:\n" + output);
    }
}
