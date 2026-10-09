package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 主播页只认自己的地址：推送页那类三段地址不得被读成一位主播、拿去问接口
 * <p>
 * 宿主整体载入与首页重画会把所有插件页各刷一遍，落在推送页上时主播页也被叫到；
 * 从前它的解析不看第一段，把 {@code #/push/<uid>/<群号>} 的后两段读成平台与 uid，
 * 每开一次推送页白挨两个 404。夹具在假 DOM 与只记账的假 fetch 上真跑这一页，
 * 数它发出的请求；落在主播页自己地址上的取数是对照格。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("主播页只认自己的地址")
class StreamersOffPageFetchTest {

    @Test
    @DisplayName("推送页的地址不被读成一位主播去问接口；落在主播页时照常取")
    void pushPageAddressIsNotReadAsStreamer() throws IOException, InterruptedException {
        Path fixture = Path.of("src", "test", "resources", "frontend",
                "streamers-off-page-fetch-fixture.mjs").toAbsolutePath();
        if (!Files.exists(fixture)) {
            fail("夹具不见了，当时工作目录是 " + Path.of("").toAbsolutePath());
        }
        String relative = FrontendFixture.repoRoot().relativize(fixture).toString();
        FrontendFixture.run(relative, "主播页只认自己的地址");
    }
}
