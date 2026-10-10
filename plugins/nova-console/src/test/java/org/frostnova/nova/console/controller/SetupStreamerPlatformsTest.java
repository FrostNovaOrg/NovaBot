package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 向导主播步：平台清单还没到、到了但一个都没装、到了有货，三种情形各说各的话
 * <p>
 * 抓的用户故障：首屏那趟整体载入先画向导、后取平台清单，停在「第一位主播」那一步的人
 * 会先看到「没有装任何直播平台插件，加不了主播。」——其实插件装着，清单只是还在路上。
 * 「下一步」也一样：清单还没到时它一声不吭地放行，这一步什么都没存就过去了。
 * <p>
 * 夹具把 setup-streamer.js 的 render／next 连同它用的零件按花括号配平切出函数体来真执行，
 * 三态各量一遍：还没到说正在载入且拦「下一步」、一个都没装照旧说没装且放行、
 * 有货照旧画平台下拉。②③⑤⑥是改前就绿的对照。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("向导主播步平台清单三态")
class SetupStreamerPlatformsTest {
    private static final Path FIXTURE = Path.of("src", "test", "resources", "frontend",
        "setup-streamer-platforms-fixture.mjs");

    @Test
    @DisplayName("清单还没到说正在载入并拦下一步，空清单说没装且放行，有货画平台下拉")
    void threePlatformStatesSpeakForThemselves() throws IOException, InterruptedException {
        FrontendFixture.runFromModule(FIXTURE.toString(), "向导主播步平台清单三态");
    }
}
