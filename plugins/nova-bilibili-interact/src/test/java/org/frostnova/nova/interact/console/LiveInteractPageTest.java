package org.frostnova.nova.interact.console;

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
 * 「直播间互动」页
 * <p>
 * 抓的用户故障：打开页面就往直播间发了东西；点一次「发送」发出去两条；页面上没有「所有人都看得见」的提醒；
 * 改成监听账号时没人提醒风险、没确认就存了；字数上限与已输入字数不显示。判据在 node 夹具里，
 * 跑的是插件里真正交付的页面脚本；找不到 node 时红，不是跳过——与连接卡那份夹具同一条规矩。
 */
@DisplayName("直播间互动页")
class LiveInteractPageTest {
    private static final String FIXTURE = "src/test/resources/frontend/live-interact-fixture.mjs";

    @Test
    @DisplayName("进页不发、点一次只发一次、提醒与风险都在、改账号先确认、字数看得见")
    void pageBehaves() throws IOException, InterruptedException {
        Path fixture = Path.of(FIXTURE).toAbsolutePath();
        assertTrue(Files.exists(fixture), "夹具不见了，这一格此刻什么也没量: " + fixture);

        ProcessBuilder builder = new ProcessBuilder("node", fixture.toString());
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("起不动 node，这一格没量。前端判据需要 node 在 PATH 上", e);
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "夹具跑了 60 秒还没结束:\n" + output);
        System.out.println("直播间互动页夹具：" + output);

        assertEquals(0, process.exitValue(), "直播间互动页与预期不符:\n" + output);
        assertTrue(output.contains("跑了") && !output.contains("跑了 0 格"), "夹具没报出跑了几格:\n" + output);
    }
}
