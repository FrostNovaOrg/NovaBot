package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 一页上保存或放弃，只动这一页的草稿
 * <p>
 * 抓的用户故障：推送页改了几处没存，去设置页存一项（或点「放弃」），回到推送页那几处全没了；
 * 反过来，设置页改了几项没存，去推送页点「放弃」，设置页那几项也一起没了。两条路都走整体载入，
 * 而整体载入会清掉设置页草稿、并让每一页重取——重取就是拿磁盘上的现值盖掉草稿。
 * <p>
 * 夹具真走保存、放弃那两条路（切出 save／discard／load／callPage／refreshPages 的函数体来跑，
 * 供数方那一套用真 core.js），用假供数方数 reload／refresh 被调了几次、看草稿还在不在。
 * ④ 是垫底的对照：留草稿那一手收得太宽时它先红。⑦ 抓的是插件页放弃后不重画——改动条没了，
 * 屏上却还是改过的样子，接着改的是旧对象。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("保存与放弃只动当前页的草稿")
class PageDraftKeepTest {
    private static final String FIXTURE = FrontendFixture.fixture("page-draft-keep-fixture.mjs");

    @Test
    @DisplayName("设置页保存与放弃不丢别页草稿，插件页放弃不丢设置页草稿且放弃后重画这一页")
    void pageDraftsSurviveOtherPagesSaveOrDiscard() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "各页草稿互不连带");
    }
}
