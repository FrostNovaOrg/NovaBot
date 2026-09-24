package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 本群设置与上方三段同一条保存纪律：改了先进草稿，按「保存」才写，按「放弃」全部还原
 * <p>
 * 这一行从前是「拨一下就发请求」：改了没点保存，状态文件已经写上了；点「放弃」也撤不回来。
 * 与上方三段（改了进底部改动条）不一致，使用者会在同一页上碰到两套相反的规矩。
 * <p>
 * 夹具<b>真跑</b>：假 DOM 与假 fetch 之外，{@code sessionSettings} 的点击、
 * {@code changeCount}／{@code save}／{@code reload} 用的都是产品码那一份。
 * 只 includes 一段字样是不行的——请求早发了一拍、放弃撤不回，字样照样在。
 * 假接口把 /state/ 的写口<b>真的记进自己那份状态</b>：
 * 不记的话「放弃了改动还在」在改前码上也会绿，因为重取会把开关拨回原样。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("本群设置按保存才生效")
class SessionSettingsDraftTest {

    @Test
    @DisplayName("没点保存不写状态文件，点放弃改动能撤回")
    void sessionSettingsWaitsForSave() throws IOException, InterruptedException {
        Path fixture = Path.of("src", "test", "resources", "frontend",
                "session-settings-draft-fixture.mjs").toAbsolutePath();
        if (!Files.exists(fixture)) {
            fail("夹具不见了，当时工作目录是 " + Path.of("").toAbsolutePath());
        }
        String relative = FrontendFixture.repoRoot().relativize(fixture).toString();
        FrontendFixture.run(relative, "本群设置按保存才生效");
    }
}
