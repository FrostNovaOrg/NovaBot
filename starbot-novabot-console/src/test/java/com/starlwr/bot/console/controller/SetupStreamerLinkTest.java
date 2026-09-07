package com.starlwr.bot.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 向导走完那一页上的「去主播页看看」
 * <p>
 * 查到人、还没落盘时不出这个链接：详情页读的是已经落盘的名单，点进去是一张空页。
 * 这个去处由本步自己给（{@code doneLink}），宿主只负责把它摆上去——
 * 宿主自己拼一份地址的话，两份分叉的那天点进去会落到别处，而屏幕上看不出任何异常。
 * <p>
 * 夹具切出 doneLink 后真执行三态，不是只 includes：「含 detailHash」证明不了
 * 它在没落盘时会不会照样给出一个地址。
 */
@DisplayName("向导主播步的「去主播页看看」")
class SetupStreamerLinkTest {
    private static final String FIXTURE =
            "starbot-novabot-console/src/test/resources/frontend/setup-streamer-link-fixture.mjs";

    @Test
    @DisplayName("没查过人／查到未落盘都不出链接，已落盘才出且地址经 detailHash")
    void goStreamerLinkOnlyAfterSave() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "向导主播步的「去主播页看看」");
    }
}
