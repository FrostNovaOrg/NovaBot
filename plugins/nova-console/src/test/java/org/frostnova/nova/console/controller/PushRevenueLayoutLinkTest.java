package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 版式区与「本群设置 · 金额可见」联动：不出图的项灰掉并写原因，草稿里拨了当场跟着变
 * <p>
 * 夹具真跑整条通道页：版式段的灰、本群设置的草稿、预览请求带草稿值，用的都是产品码。
 * 只查 /api/handlers 回包带没带栏位是不行的——栏位在、界面不灰，照样绿。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("版式区随金额可见联动")
class PushRevenueLayoutLinkTest {

    @Test
    @DisplayName("隐藏金额时不出图的项灰掉带原因，草稿拨了当场跟着变")
    void layoutGraysAndFollowsTheRevenueDraft() throws IOException, InterruptedException {
        Path fixture = Path.of("src", "test", "resources", "frontend",
                "push-revenue-layout-link-fixture.mjs").toAbsolutePath();
        if (!Files.exists(fixture)) {
            fail("夹具不见了，当时工作目录是 " + Path.of("").toAbsolutePath());
        }
        String relative = FrontendFixture.repoRoot().relativize(fixture).toString();
        FrontendFixture.run(relative, "版式区随金额可见联动");
    }
}
