package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 外部程序触发规则待调整时，控制台顶上的提醒
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("外部程序触发规则待调整提醒")
class ExecNoticeFrontendTest {
    private static final String FIXTURE = FrontendFixture.fixture("exec-notice-fixture.mjs");

    @Test
    @DisplayName("有待调整的规则时出现并写明哪几条，没有时不出现")
    void noticeFollowsRulesToAdjust() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "外部程序触发规则待调整提醒");
    }
}
