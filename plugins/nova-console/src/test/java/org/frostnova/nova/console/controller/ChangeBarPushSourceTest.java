package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 推送页是底部改动条的供数方：这一份状态跟着推送页走
 * <p>
 * 「改了几处、按保存怎么存、整体载入时怎么重取」这三件事从前记在宿主的 store 上
 * （{@code pushData}／{@code pushSaved}），由宿主自己按主播逐条比。而推送页随本插件走，
 * 插件是可卸的——卸掉之后宿主还在按一份取不到的东西算数。现在这三件由本页自己答。
 * <p>
 * 夹具<b>真跑</b>：假 DOM 与假 fetch 之外，{@code reload}／{@code changeCount}／{@code save}
 * 用的都是产品码那一份。只 includes 一段字样是不行的——报数写错一位、存盘序列化换了把尺，
 * 字样照样在。末一问把本页登记进宿主的改动条，从「改一项」一路量到屏幕上那句「1 处改动」。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("推送页供改动条的数")
class ChangeBarPushSourceTest {
    private static final String FIXTURE =
            "plugins/nova-console/src/test/resources/frontend/change-bar-push-fixture.mjs";

    @Test
    @DisplayName("自己取自己存、按主播逐条报改动数，挂上宿主后改动条写得对")
    void pushPageFeedsTheChangeBar() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "推送页供改动条的数");
    }
}
