package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.io.IOException;

@DisplayName("直接载入设置页：QQ 告警卡与配置项完整显示")
class SettingsAlertLoadTest {
    @Test
    @DisplayName("刷新设置页后 QQ 卡存在，平台项在卡内，定位告警时三卡展开")
    void alertCardsPresentAfterLoad() throws IOException, InterruptedException {
        FrontendFixture.run(FrontendFixture.fixture("settings-alert-load-fixture.mjs"), "设置页载入告警卡");
    }
}
