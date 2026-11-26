package org.frostnova.nova.interact;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.service.BilibiliAccountService;
import org.frostnova.nova.bilibili.service.BilibiliCredentialStore;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.account.AccountLoginProvider;
import org.frostnova.nova.core.config.ui.page.ConsolePageProvider;
import org.frostnova.nova.core.health.HealthProbe;
import org.frostnova.nova.core.util.HttpUtil;
import org.frostnova.nova.interact.speaker.BilibiliSpeakerAccount;
import org.frostnova.nova.interact.speaker.BilibiliSpeakerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 插件装进容器之后
 * <p>
 * 抓的用户故障：装上这个插件 NovaBot 就起不来——发言账号的接口工具、账号服务或凭据存储被登记成了容器组件，
 * 哔哩哔哩插件里按类型取监听账号那一份的注入点一下有了两个候选，启动当场退出。
 * 另一头：只是装上、容器建好，就已经在向哔哩哔哩发请求。
 */
@DisplayName("直播间互动插件的装配")
class InteractPluginWiringTest {
    @Test
    @DisplayName("不往容器里多放监听账号那几类组件，自己的几样各一份，建好容器不发请求")
    void wiresWithoutTouchingListenerComponents() {
        HttpUtil http = mock(HttpUtil.class);
        BilibiliAccountService listener = mock(BilibiliAccountService.class);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(HttpUtil.class, () -> http);
            context.registerBean(NovaBilibiliProperties.class, NovaBilibiliProperties::new);
            context.registerBean(BilibiliAccountService.class, () -> listener);
            context.register(InteractPluginAutoConfiguration.class);
            context.refresh();

            assertEquals(1, context.getBeansOfType(BilibiliAccountService.class).size(),
                    "容器里只该有监听账号那一个账号服务");
            assertTrue(context.getBeansOfType(BilibiliApiUtil.class).isEmpty(), "发言账号的接口工具不该进容器");
            // 账号服务那个类自带一份登记监听账号凭据存储的配置，容器里能见到的只该是那一份
            assertTrue(Set.of("bilibiliCredentialStore").containsAll(
                            context.getBeansOfType(BilibiliCredentialStore.class).keySet()),
                    "发言账号的凭据存储不该进容器: " + context.getBeansOfType(BilibiliCredentialStore.class).keySet());

            assertEquals(1, context.getBeansOfType(BilibiliSpeakerAccount.class).size());
            assertEquals(1, context.getBeansOfType(BilibiliSpeakerService.class).size());
            assertEquals(BilibiliSpeakerAccount.ID, context.getBean(AccountLoginProvider.class).id());
            assertEquals(BilibiliSpeakerAccount.ID, context.getBean(ConsolePageProvider.class).id());
            assertEquals(BilibiliSpeakerAccount.ID, context.getBean(HealthProbe.class).card());

            verifyNoInteractions(http);
        }
    }
}
