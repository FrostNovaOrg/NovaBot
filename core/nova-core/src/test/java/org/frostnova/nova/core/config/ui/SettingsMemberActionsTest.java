package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 手机宽度下成员表的操作按钮看得见、点得到
 * <p>
 * 抓的用户故障：成员表在手机宽度下要横着滑，操作那几颗按钮被挤到屏幕外——
 * 名单看得到，按钮却够不着。每位成员改成一张小卡片，按钮在卡片底部。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("手机宽度下成员操作按钮够得着")
class SettingsMemberActionsTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-member-actions-fixture.mjs");

    @Test
    @DisplayName("手机宽度下每位成员一张卡片，操作按钮在卡片底部点得动")
    void memberActionButtonsReachableOnPhone() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "手机宽度成员操作按钮");
    }
}
