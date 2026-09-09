package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 告警卡收件人栏按通道申报渲染
 * <p>
 * 核心不再写死任何一家的配置键。空表时只剩 Webhook 与邮件两张卡；
 * 桩通道申报三键时多一张卡，选名单后草稿落到桩键上而不是写死的适配器键。
 * 夹具读源码树里那一份，切段按花括号配平截到块尾后真执行。
 */
@DisplayName("告警卡收件人栏按申报渲染")
class SettingsAlertRecipientTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-alert-recipient-fixture.mjs");

    @Test
    @DisplayName("空表九键两张卡；桩通道十二键三张卡且草稿落到桩键")
    void recipientFieldsDriveCardsAndDirtyKeys() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "告警卡收件人栏按申报渲染");
    }
}
