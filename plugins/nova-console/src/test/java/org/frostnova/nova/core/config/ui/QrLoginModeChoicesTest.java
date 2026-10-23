package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 「扫码登录方式」的两个候选写法真的送到了设置页
 * <p>
 * 抓的用户故障：设置页的两项选择框只在服务端给了候选写法时才摆出来（见 settings-choices-fixture.mjs，
 * 那份夹具喂的是自己编的字段）。真字段上的候选写法要是没读出来，页面上仍是自由文本框，
 * 手一滑填出 tv、web 以外的字，保存后扫码登录当场走不通。这里量的是插件里那个真字段。
 * <p>
 * 同一节里没标候选写法的文本项（凭据文件路径）不该带出候选表，否则路径框也会变成选择框。
 */
@DisplayName("扫码登录方式的候选写法送到设置页")
class QrLoginModeChoicesTest {
    @Test
    @DisplayName("扫码登录方式带出 tv、web 两项，凭据文件路径不带")
    void qrLoginModeCarriesItsChoices() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(NovaBilibiliProperties.class);
            context.refresh();

            Map<String, List<String>> choices = new ConfigurationLabelResolver(context).getChoices();

            assertEquals(List.of("tv", "web"), choices.get("novabot.bilibili.account.qr-code-login-mode"),
                    "扫码登录方式的候选写法没送到设置页，页面上仍会是自由文本框");
            assertNull(choices.get("novabot.bilibili.account.cookie-path"),
                    "没标候选写法的文本项不该带出候选表");
        }
    }
}
