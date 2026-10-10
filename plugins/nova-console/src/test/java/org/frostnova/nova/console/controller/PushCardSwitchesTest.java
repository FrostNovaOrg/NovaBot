package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 版式区里的数据卡片：「数据卡片」下面两区小块，拖着排、× 拿掉、点一下放回，键盘做得完，锁着时动不了，
 * 卡片小块与消息模板的占位符互不串；恢复默认与自定义判法跟得上
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
    @DisplayName("两区小块拖放、拿掉放回、键盘、锁住与两个编辑器不串，恢复默认删新键，改一张即自定义")
    void cardSwitchesFollowTheBlockSwitch() throws IOException, InterruptedException {
        FrontendFixture.runFromModule(FIXTURE.toString(), "版式区数据卡片逐张开关",
                Map.of("LAYOUT_OPTIONS", JSON.toJSONString(BilibiliLiveReportOptions.layoutOptions())));
    }
}
