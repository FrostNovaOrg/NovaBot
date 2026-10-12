package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 推送页这一批改完之后，三处不能跟着没了
 * <p>
 * 用户那头的毛病：窄屏上「这个通道最近推送」那张表把整页撑宽；页顶、通道一览、通道页头
 * 那三段长说明与第 4 段里那句重复，删的时候容易把讲清「共用」的那句一起删掉；左树上
 * 「N 类关着」「N 条命令被关」两颗小标一删，就看不出哪个群关了命令。
 * <p>
 * 夹具<b>真跑</b>：假 DOM 与假 fetch 之外，整条通道页与左树走的都是产品码那一份。
 * 只查源码里有没有某段字是不行的——字在、画不出来，照样绿。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("推送页：最近推送表的滚动层、第 4 段共用说明、左树小标")
class PushPageStructureFixtureTest {
    /**
     * 夹具按模块内相对位置认，不写死模块目录名——写死目录名的旧写法只减不增
     */
    private static final String FIXTURE =
            "src/test/resources/frontend/push-page-structure-fixture.mjs";

    @Test
    @DisplayName("表包在卡片里滚的那一层内，共用说明与左树两颗小标都还在")
    void pushPageKeepsItsGuardRails() throws IOException, InterruptedException {
        FrontendFixture.runFromModule(FIXTURE, "推送页三处护栏");
    }
}
