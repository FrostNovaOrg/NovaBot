package org.frostnova.nova.bilibili.console;

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
 * 哔哩哔哩连接卡只画自己那个账号
 * <p>
 * 抓的用户故障：装了发言账号之后，原来那张卡里多出一块永远等不到的「正在生成二维码，请稍候」，
 * 卡还因此每 3 秒刷新一次。判据在 node 夹具里，跑的是插件里真正交付的页面脚本；
 * 找不到 node 时红，不是跳过——与控制台那几份前端夹具同一条规矩。
 */
@DisplayName("哔哩哔哩连接卡的账号")
class BilibiliCardAccountsTest {
    private static final String FIXTURE = "src/test/resources/frontend/bilibili-card-accounts-fixture.mjs";

    @Test
    @DisplayName("同平台另有一个账号时，卡里只画自己的账号、不为它轮询、退出只退自己")
    void cardDrawsOnlyItsOwnAccount() throws IOException, InterruptedException {
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
        System.out.println("哔哩哔哩连接卡夹具：" + output);

        assertEquals(0, process.exitValue(), "连接卡与预期不符:\n" + output);
        assertTrue(output.contains("跑了") && !output.contains("跑了 0 格"), "夹具没报出跑了几格:\n" + output);
    }
}
