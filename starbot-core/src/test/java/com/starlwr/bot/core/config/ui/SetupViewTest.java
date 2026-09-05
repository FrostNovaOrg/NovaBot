package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 初始设置第 4 步「去主播页」链接的落点
 * <p>
 * 查到人、还没保存时出这个链接，点进去是空页：详情页读的是已经落盘的名单。
 * 夹具读源码树里那一份，不是构建产物里的副本。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("初始设置去主播页链接")
class SetupViewTest {
    private static final String FIXTURE =
            "starbot-core/src/test/resources/frontend/setup-view-fixture.mjs";

    @Test
    @DisplayName("lookup 成功未保存时不出链接，完成页有这位主播才出")
    void goStreamerLinkOnlyAfterSave() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "初始设置去主播页链接");
    }
}
