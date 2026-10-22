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
 * 本群设置：点保存之后、回包之前又改的那一档，不许被当成已存
 * <p>
 * 用户那头的故障：静音时段选成「本通道不静音」，点保存，接口还没回来，又选成
 * 「本通道自己的时段」。回包一到改动条说存好了，再点保存也不发后面那一档。
 * 它从没进过状态文件，刷新页面就退回「跟全局」。
 * <p>
 * 另一种：收入可见、静音时段、命令开关点了保存，回包前又改回载入时的值。服务端存的是
 * 发出去的那一份，屏上却没了改动提示，「改回去」悄悄丢了。
 * <p>
 * 夹具真跑：假 DOM 与假 fetch 之外，静音那几档的点选与 save 用的都是产品码那一份，
 * 回包放不放行由夹具攥着。假接口把 /state/ 的写口真的记进自己那份状态。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}；
 * 这里没直接用它的 run，是因为要多透传一个参数：量一份改过的拷贝时以
 * {@code -Dnova.push.pages=<一份 config-ui-pages 拷贝>} 把量程指向拷贝，
 * 平常不设该属性，即量源码树里那一份。
 */
@DisplayName("本群设置保存回包前又改的东西")
class SessionSavePendingEditTest {

    @Test
    @DisplayName("回包之前又改的档位、改回的原值：都还是草稿，下次保存发出去")
    void quietEditDuringSaveStaysUnsaved() throws IOException, InterruptedException {
        Path fixture = Path.of("src", "test", "resources", "frontend",
                "session-save-pending-edit-fixture.mjs").toAbsolutePath();
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

        System.out.println("本群设置保存回包前又改夹具：" + output);

        assertEquals(0, process.exitValue(), "本群设置保存回包前又改与预期不符:\n" + output);
        assertTrue(output.contains("跑了") && !output.contains("跑了 0 格"),
                "夹具没报出跑了几格，或者一格都没跑:\n" + output);
    }
}
