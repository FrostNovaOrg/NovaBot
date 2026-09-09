package org.frostnova.nova.core.alert;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.service.NovaMailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 邮件告警通道「配好了没有」
 * <p>
 * 口径与设置页药丸、首页 {@code /api/status} 的 {@code alerts.mail} 相同：
 * 收件邮箱与 SMTP 主机都有才算。只看收件的话，首页催人去配，点「发一条测试」却仍走发送。
 */
@DisplayName("邮件告警通道")
class MailAlertChannelTest {

    @Test
    @DisplayName("只填收件、没有主机仍不可用")
    void unavailableWithRecipientOnly() {
        assertFalse(channel("ops@example.invalid", "").isAvailable(),
                "只填收件与设置页药丸的「未配置」要长得一样");
    }

    @Test
    @DisplayName("收件与主机都有才可用")
    void availableWithRecipientAndHost() {
        assertTrue(channel("ops@example.invalid", "smtp.example.invalid").isAvailable());
    }

    @Test
    @DisplayName("只有主机、没有收件仍不可用")
    void unavailableWithHostOnly() {
        assertFalse(channel("", "smtp.example.invalid").isAvailable());
    }

    @Test
    @DisplayName("两栏都空着不可用")
    void unavailableWhenBlank() {
        assertFalse(channel("", "").isAvailable());
        assertFalse(channel("  ", "  ").isAvailable());
    }

    private MailAlertChannel channel(String to, String host) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getMail().setDefaultTo(to);
        return new MailAlertChannel(mock(NovaMailService.class), properties, host);
    }
}
