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
 * 连接页上的发言账号卡
 * <p>
 * 抓的用户故障：没登录发言账号时卡在后台一直轮询；点「扫码登录」没反应；被拒的理由不出现；
 * 这张卡上的「退出登录」退掉的是监听账号。判据在 node 夹具里，跑的是插件里真正交付的页面脚本；
 * 找不到 node 时红，不是跳过——与控制台那几份前端夹具同一条规矩。
 */
@DisplayName("发言账号连接卡")
class SpeakerCardTest {
    private static final String FIXTURE = "src/test/resources/frontend/speaker-card-fixture.mjs";

    @Test
    @DisplayName("没登录不轮询、点了才扫码、被拒说明理由、退出只退自己")
    void speakerCardBehaves() throws IOException, InterruptedException {
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
        System.out.println("发言账号卡夹具：" + output);

        assertEquals(0, process.exitValue(), "发言账号卡与预期不符:\n" + output);
        assertTrue(output.contains("跑了") && !output.contains("跑了 0 格"), "夹具没报出跑了几格:\n" + output);
    }
}
