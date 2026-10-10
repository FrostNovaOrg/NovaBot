package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 版式区里的数据卡片逐张开关：缩进在「数据卡片」下面、总开关关着时灰掉，恢复默认与自定义判法跟得上
 * <p>
 * 版式项清单不在夹具里手抄：这里把下播报告真正的那张表照 {@code /api/handlers} 的序列化交给夹具，
 * 表里少了哪张卡、子项没带它跟着的那一项，夹具当场红。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("版式区数据卡片逐张开关")
class PushCardSwitchesTest {
    private static final Path FIXTURE = Path.of("src", "test", "resources", "frontend", "push-card-switches-fixture.mjs");

    @Test
    @DisplayName("子项缩进在数据卡片下、总开关关着时灰掉值不动，恢复默认删新键，改一张即自定义")
    void cardSwitchesFollowTheBlockSwitch() throws IOException, InterruptedException {
        FrontendFixture.runFromModule(FIXTURE.toString(), "版式区数据卡片逐张开关",
                Map.of("LAYOUT_OPTIONS", JSON.toJSONString(BilibiliLiveReportOptions.layoutOptions())));
    }
}
