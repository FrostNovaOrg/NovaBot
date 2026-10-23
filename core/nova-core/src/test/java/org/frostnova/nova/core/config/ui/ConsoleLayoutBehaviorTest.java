package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.io.IOException;

class ConsoleLayoutBehaviorTest {
    private void run(String mode) throws IOException, InterruptedException {
        FrontendFixture.run(java.nio.file.Path.of("src/test/resources/frontend/console-layout-behavior-fixture.mjs")
                .toAbsolutePath().toString(), mode, mode);
    }
    @Test @DisplayName("重复失败合并后次数与最近时间准确，普通事件倒序")
    void groupedFailures() throws Exception { run("home"); }
    @Test @DisplayName("鼠标取消退出后焦点不落到隐藏菜单内")
    void cancelLogout() throws Exception { run("mine"); }
    @Test @DisplayName("已配置的账号与主播先显示现状，仍能修改")
    void completedSetup() throws Exception { run("setup"); }
    @Test @DisplayName("展开其他登录方式后密码登录显眼，回车仍提交密码")
    void passwordSubmit() throws Exception { run("login"); }
}
