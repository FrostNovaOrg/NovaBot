package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 通道页按通知类型重排之后，点了才看得出来的那几件
 * <p>
 * 用户那头的毛病：两行编辑同时摊开、换一行编辑后刚改的门槛没存下去；没选「自定义」也摆着
 * 两个填不了的时间框；好友通道底下写着「本群」「群成员」；收进「没开的 n 种」的通知展开后开不了；
 * 周报关着工具照摆；页头摘要点了没反应。
 * <p>
 * 夹具<b>真跑</b>：假 DOM 与假 fetch 之外，整条通道页走的都是产品码那一份。
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("通道页：行内编辑一次一行、静音时间框、好友通道说法、没开的通知能开")
class PushChannelLayoutFixtureTest {
    /**
     * 夹具按模块内相对位置认，不写死模块目录名——写死目录名的旧写法只减不增
     */
    private static final String FIXTURE =
            "src/test/resources/frontend/push-channel-layout-fixture.mjs";

    @Test
    @DisplayName("一次只展开一行且改了存得住，自定义才出时间框，好友通道无群的说法，没开的能开")
    void channelPageBehavesRowByRow() throws IOException, InterruptedException {
        FrontendFixture.runFromModule(FIXTURE, "通道页行内编辑");
    }
}
