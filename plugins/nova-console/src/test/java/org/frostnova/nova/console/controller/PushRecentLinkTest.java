package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 推送页「这个通道最近推送」：日志链接带全通道串，空表句写明本次启动以来
 * <p>
 * 空表数据只是本次启动以来内存里那一份，链接若只传裸号，日志页按「群 12345」全串
 * 全等筛会落到空表。夹具读源码树里那一份，不是构建产物里的副本；
 * {@code sectionNotices} 按花括号配平切出整块后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("推送页最近推送的日志链接")
class PushRecentLinkTest {

    @Test
    @DisplayName("空表链接带全通道串、空表句写明本次启动以来；无会话不出链接；有记录出表")
    void recentLinkUsesFullChannelAndScopedEmptyCopy() throws IOException, InterruptedException {
        Path fixture = Path.of("src", "test", "resources", "frontend",
                "push-recent-link-fixture.mjs").toAbsolutePath();
        if (!Files.exists(fixture)) {
            fail("夹具不见了，当时工作目录是 " + Path.of("").toAbsolutePath());
        }
        String relative = FrontendFixture.repoRoot().relativize(fixture).toString();
        FrontendFixture.run(relative, "推送页最近推送的日志链接");
    }
}
