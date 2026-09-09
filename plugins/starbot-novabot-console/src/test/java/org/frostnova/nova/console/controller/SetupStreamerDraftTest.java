package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 向导主播步：改了 uid 或平台后先前找到的那位必须作废，且没填完不许过
 * <p>
 * 找到 A 再把格子改成 B、不点「找一下」就下一步时，落盘的必须不能还是 A。
 * <p>
 * 后半截奔着搬家这一趟最容易丢的那件事去：<b>放行由这一步自己判</b>。
 * 宿主对插件步一律放行，它不知道「找到人了没有」是什么意思——这一步不自己拦的话，
 * 「下一步」会变成点一下就过，落盘那一趟根本没发生，而屏幕上不会有任何异常。
 * <p>
 * 夹具读源码树里那一份，不是构建产物里的副本。切段按花括号配平截到块尾后真执行。
 */
@DisplayName("向导主播步改 uid 或平台即作废已找到的主播")
class SetupStreamerDraftTest {
    private static final String FIXTURE =
            "plugins/starbot-novabot-console/src/test/resources/frontend/setup-streamer-draft-fixture.mjs";

    @Test
    @DisplayName("invalidateStreamer 清空草稿；uid 与平台变更都接到它；没找到人或没选目标不许过")
    void changingUidOrPlatformInvalidatesFoundStreamer() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "向导主播步改 uid 或平台即作废已找到的主播");
    }
}
