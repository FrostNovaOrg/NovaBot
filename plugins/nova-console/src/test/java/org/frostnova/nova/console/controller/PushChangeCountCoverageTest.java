package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 推送页那个 N 算得全不全
 * <p>
 * 底部改动条现在按「有没有话说」显隐，未保存的改动数是其中一条。<b>N 算漏一类，
 * 那一页的保存这条路就被堵死了</b>：草稿态改了，条不出来，按钮也就无从按起——
 * 而屏幕上不会有任何异常，改动会在下一次载入时安静地没掉。
 * <p>
 * 夹具按「本页有哪几类写口」逐类喂一处改动读 {@code changeCount()}：算进去的各报 1，
 * 只给人看的那三栏报 0（阴性对照，少了它「恒回 1」照样全绿）。末一格是分母闸——
 * {@code markDirty()} 的调用点数一变就红，逼着新开的那条路回来判一句进不进得了 N。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("推送页的改动数算得全")
class PushChangeCountCoverageTest {
    private static final String FIXTURE =
            "plugins/nova-console/src/test/resources/frontend/push-change-count-coverage-fixture.mjs";

    @Test
    @DisplayName("每一类可改的字段各算进 N，只给人看的三栏不算，写口数没悄悄长出来")
    void everyEditableFieldClassIsCounted() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "推送页的改动数算得全");
    }
}
