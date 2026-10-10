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
 * 同一个号既是群又是好友时：点哪一个，右栏开的就是哪一个
 * <p>
 * 用户那头的故障：一位主播配了群 123456，又配了好友 123456。点左树上好友那一行，
 * 右栏开的是群那一个；窄屏下拉里选好友，开的也是群。一览表与通道行的「打开」同样只按号认。
 * <p>
 * 夹具真跑：假 DOM 与假 fetch 之外，左树点击、地址栏路由、右区重画走的都是产品码那一份；
 * 顺带量旧地址 #/push/&lt;uid&gt;/&lt;号&gt; 照样能打开。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}；
 * 这里没直接用它的 run，是因为要多透传一个参数：量一份改过的拷贝时以
 * {@code -Dnova.push.pages=<一份 config-ui-pages 拷贝>} 把量程指向拷贝，
 * 平常不设该属性，即量源码树里那一份。
 */
@DisplayName("同号的群与好友通道")
class PushChannelLinkTest {

    @Test
    @DisplayName("点好友那一行开的是好友那个；旧地址只带号的照样打开")
    void sameNumberGroupAndFriendOpenSeparately() throws IOException, InterruptedException {
        Path fixture = Path.of("src", "test", "resources", "frontend",
                "push-channel-link-fixture.mjs").toAbsolutePath();
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

        System.out.println("同号的群与好友通道夹具：" + output);

        assertEquals(0, process.exitValue(), "同号的群与好友通道与预期不符:\n" + output);
        assertTrue(output.contains("跑了") && !output.contains("跑了 0 格"),
                "夹具没报出跑了几格，或者一格都没跑:\n" + output);
    }
}
