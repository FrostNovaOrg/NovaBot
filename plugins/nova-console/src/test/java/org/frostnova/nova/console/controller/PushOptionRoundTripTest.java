package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 新建通道的数据卡片、模板与版式里其他选项改了又改回之后，改动条认不认得「回到了原样」
 * <p>
 * 版式项清单不在夹具里手抄：这里把下播报告真正的那张表照 {@code /api/handlers} 的序列化交给夹具，
 * 表里少了哪一行，夹具当场红。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("版式选项改回原值不记改动")
class PushOptionRoundTripTest {
    private static final Path FIXTURE = Path.of("src", "test", "resources", "frontend", "push-option-round-trip-fixture.mjs");

    @Test
    @DisplayName("新建通道的卡片与 @ 档、平铺选项改回原值改动条 0，真改照旧记 1")
    void optionRoundTripBackToWhereItWasCountsNothing() throws IOException, InterruptedException {
        FrontendFixture.runFromModule(FIXTURE.toString(), "版式选项改回原值不记改动",
                Map.of("LAYOUT_OPTIONS", JSON.toJSONString(BilibiliLiveReportOptions.layoutOptions())));
    }
}
