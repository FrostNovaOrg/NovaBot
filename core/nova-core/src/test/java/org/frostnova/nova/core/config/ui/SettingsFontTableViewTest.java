package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置页成图字体表：旧默认表标明已按未设处理，空表时写出在用的默认表
 * <p>
 * 抓的用户故障：老实例设置页原样显示旧版写下的默认字体表、没有任何说明，
 * 使用者改一项再存，它就成了使用者自己的表，表情空白复发。
 * 夹具读源码树里那一份，不是构建产物里的副本。切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("设置页成图字体表的说明")
class SettingsFontTableViewTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-font-table-fixture.mjs");

    @Test
    @DisplayName("旧表出说明与默认表；空表只出默认表；使用者的表不加字；恢复默认即清空")
    void fontTableNotes() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "设置页成图字体表");
    }
}
