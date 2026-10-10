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
 * 推送页：点保存之后、回包之前又改的那一处，不许被当成已存
 * <p>
 * 用户那头的故障：点保存，接口还没回来，这时又关掉一类通知。回包一到改动条就说全存上了；
 * 再点保存什么也不发。这一处从没进过 datasource.json，刷新页面就没了。
 * <p>
 * 夹具真跑：假 DOM 与假 fetch 之外，改动走的是产品码那一份 save 与真开关，
 * 回包放不放行由夹具攥着——真机上凑齐「回包之前又改」那个窗口要试很多回。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}；
 * 这里没直接用它的 run，是因为要多透传一个参数：量一份改过的拷贝时以
 * {@code -Dnova.push.pages=<一份 config-ui-pages 拷贝>} 把量程指向拷贝，
 * 平常不设该属性，即量源码树里那一份。
 */
@DisplayName("推送页保存回包前又改的东西")
class PushSavePendingEditTest {

    @Test
    @DisplayName("回包之前又关掉一类通知：改动条仍有 1 处，再点保存发出去")
    void editDuringSaveStaysUnsaved() throws IOException, InterruptedException {
        Path fixture = Path.of("src", "test", "resources", "frontend",
                "push-save-pending-edit-fixture.mjs").toAbsolutePath();
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

        System.out.println("推送页保存回包前又改夹具：" + output);

        assertEquals(0, process.exitValue(), "推送页保存回包前又改与预期不符:\n" + output);
        assertTrue(output.contains("跑了") && !output.contains("跑了 0 格"),
                "夹具没报出跑了几格，或者一格都没跑:\n" + output);
    }
}
