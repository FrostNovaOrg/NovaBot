package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 数据卡片拖走、拿掉再放回之后，改动条认不认得「回到了原样」
 * <p>
 * 版式项清单不在夹具里手抄：这里把下播报告真正的那张表照 {@code /api/handlers} 的序列化交给夹具，
 * 表里少了哪张卡、子项没带它跟着的那一项，夹具当场红。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("数据卡片放回原处不记改动")
class PushCardRoundTripTest {
    private static final Path FIXTURE = Path.of("src", "test", "resources", "frontend", "push-card-round-trip-fixture.mjs");

    @Test
    @DisplayName("分两下回到原样改动条 0（两种快照都对上），真改与一下放回照旧")
    void roundTripBackToWhereItWasCountsNothing() throws IOException, InterruptedException {
        FrontendFixture.runFromModule(FIXTURE.toString(), "数据卡片放回原处不记改动",
                Map.of("LAYOUT_OPTIONS", JSON.toJSONString(BilibiliLiveReportOptions.layoutOptions())));
    }
}
