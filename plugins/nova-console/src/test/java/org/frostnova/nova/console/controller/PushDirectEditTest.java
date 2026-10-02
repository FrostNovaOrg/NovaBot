package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 通道详情两段默认就能改：「恢复默认」改过才出现
 * <p>
 * 从前「消息长什么样」「报告长什么样」要先点「改为自定义」才解锁，解锁态不进配置、
 * 只活在当次页面里；改完一项「恢复默认」也不出现。夹具在假 DOM 上画出整页通道详情，
 * 从真编辑器的真控件事件里改一项、再改回去，量锁态、按钮的出现与收起、状态行与小字。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("推送页通道详情直接可改")
class PushDirectEditTest {
    /**
     * 夹具按模块内相对位置认，不写死模块目录名——写死目录名的旧写法只减不增
     */
    private static final String FIXTURE =
            "src/test/resources/frontend/push-direct-edit-fixture.mjs";

    @Test
    @DisplayName("两段不锁、改过才出恢复默认、改回即收起")
    void editableByDefaultRestoreAppearsOnlyAfterChange() throws IOException, InterruptedException {
        FrontendFixture.runFromModule(FIXTURE, "推送页通道详情直接可改");
    }
}
