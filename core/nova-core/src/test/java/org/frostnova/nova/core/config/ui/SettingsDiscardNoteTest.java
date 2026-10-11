package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置页放弃改动后，那句提示要自己消失
 * <p>
 * 抓的用户故障：设置页放弃改动后，「已放弃这一页的改动」一直挂在底部改动条上不消失——
 * 切到别的页它还挂着，一直到下一次有人写状态栏才没。say 只有类别是 ok 时才四秒后自清，
 * 而放弃这句没带类别。
 * <p>
 * 夹具用假时钟量三件：刚放弃后这句话在、类别是 ok；过四秒以上它变成空的；
 * 另一句类别是 err 的话过同样时间仍在。设置页与插件页两条放弃的路各量一遍。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("放弃改动的提示过几秒自己消失")
class SettingsDiscardNoteTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-discard-note-fixture.mjs");

    @Test
    @DisplayName("放弃后那句四秒后自清；err 那句过同样时间仍在；插件页那一支也一样")
    void discardNoteClearsItself() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "放弃后的提示");
    }
}
