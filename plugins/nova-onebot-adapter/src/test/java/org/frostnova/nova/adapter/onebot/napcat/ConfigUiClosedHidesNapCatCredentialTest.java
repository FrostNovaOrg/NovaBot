package org.frostnova.nova.adapter.onebot.napcat;

import org.frostnova.nova.adapter.onebot.controller.OneBotTargetController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 照文档关掉控制台后，别人不能从 /config/api/napcat/credential 拿走 NapCat 登录凭据
 *
 * <h2>这把尺量的是什么用户故障</h2>
 * 主人把 {@code novabot.core.config-ui.enabled} 关掉，以为管理接口跟着控制台一起不在了。
 * 实际上 {@code NapCatBootstrapController} 原先只靠控制台安全过滤器罩着——开关一关，
 * 过滤器跟注册器一起不装配，{@code POST /config/api/napcat/credential} 仍回一份
 * NapCat WebUI 会话凭据的明文。那凭据是管理页的登录口，等于把整台 NapCat 交出去。
 *
 * <h2>为什么用反射取关闭过滤器</h2>
 * 那道过滤器是另立的新件，直接 import 的话编译就钉死在具体类名上。
 * 按类名取、取不到按「第 1 层不在」办，过滤器缺席时也能表达预期。
 * 第 2 层（控制器上的开关条件）同理：没有注解按「始终登记」办。
 *
 * <h2>开着时的登录由谁钉</h2>
 * 开关开着时这条路径在 {@code ConfigUiSecurityFilter} 射程内（{@code /config/*}），
 * 那条路已由 {@code ConfigUiPublicApiTest}、{@code OperatorTokenSwitchTest}、
 * {@code ConfigUiAgreementGateTest}、{@code PasskeyEndpointAccessTest} 钉着，这里不重钉。
 */
@DisplayName("照文档关掉控制台后，别人不能从 /config/api/napcat/credential 拿走 NapCat 登录凭据")
class ConfigUiClosedHidesNapCatCredentialTest {

    private static final String SWITCH = "novabot.core.config-ui.enabled";

    /**
     * 忘挂开关的那一种接口，永远出现在栈里，好把两层分开量。
     */
    @RestController
    static class UnmarkedProbe {
        static final String PATH = "/config/api/onebot-closed-gate-probe";

        @GetMapping(PATH)
        String leak() {
            return "still-here";
        }
    }

    /**
     * 开关为 false 时登记得上的那道关闭过滤器的登记（含登记口）。
     * <p>
     * 认的是登记这一动作而不只是类还在：类在、{@code @Bean} 没了，回 null。
     * 返回 {@link FilterRegistrationBean} 本身，测试按它登记的 URL pattern 挂过滤器。
     */
    private static FilterRegistrationBean<?> closedFilterWhen(boolean switchOn) {
        if (switchOn) {
            return null;
        }
        Class<?> registrarType;
        try {
            registrarType = Class.forName("org.frostnova.nova.core.config.ui.ConfigUiClosedRegistrar");
        } catch (ClassNotFoundException absent) {
            return null;
        }
        ConditionalOnProperty gate = registrarType.getAnnotation(ConditionalOnProperty.class);
        if (gate == null || !"false".equals(gate.havingValue())) {
            return null;
        }
        for (Method method : registrarType.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Bean.class) || method.getParameterCount() != 0) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object bean = method.invoke(registrarType.getDeclaredConstructor().newInstance());
                if (bean instanceof FilterRegistrationBean<?> registration) {
                    return registration;
                }
            } catch (ReflectiveOperationException broken) {
                return null;
            }
        }
        return null;
    }

    /**
     * 控制器在给定开关状态下会不会出现在栈里。
     * <p>
     * 没挂开关条件的类始终登记——那正是第 1 层要兜的漏网之鱼。
     */
    private static boolean routed(Class<?> type, boolean switchOn) {
        ConditionalOnProperty gate = type.getAnnotation(ConditionalOnProperty.class);
        if (gate == null) {
            return true;
        }
        for (String name : gate.name()) {
            if (!SWITCH.equals(name)) {
                continue;
            }
            if (switchOn) {
                return "true".equals(gate.havingValue());
            }
            return "false".equals(gate.havingValue());
        }
        return true;
    }

    private static MockMvc stack(boolean switchOn, Object whenRouted) throws Exception {
        java.util.List<Object> controllers = new java.util.ArrayList<>();
        if (whenRouted != null && routed(whenRouted.getClass(), switchOn)) {
            controllers.add(whenRouted);
        }
        controllers.add(new UnmarkedProbe());
        StandaloneMockMvcBuilder builder = MockMvcBuilders.standaloneSetup(controllers.toArray());
        FilterRegistrationBean<?> registration = closedFilterWhen(switchOn);
        if (registration != null) {
            builder.addFilter(registration.getFilter(),
                    registration.getUrlPatterns().toArray(new String[0]));
        }
        return builder.build();
    }

    @Test
    @DisplayName("关掉后 POST /config/api/napcat/credential 回 404，而且真的不会去取凭据")
    void switchOffHidesCredentialAndDoesNotIssue() throws Exception {
        NapCatCredentialService credentials = mock(NapCatCredentialService.class);
        MockMvc mvc = stack(false, new NapCatBootstrapController(credentials));

        mvc.perform(post(NapCatBootstrapController.CREDENTIAL_PATH).param("renew", "false"))
                .andExpect(status().isNotFound());

        verify(credentials, never()).issue(anyBoolean());
        verifyNoInteractions(credentials);
    }

    @Test
    @DisplayName("关掉后 GET /config/api/bot/console 也回 404，控制台地址别想读走")
    void switchOffHidesBotConsole() throws Exception {
        NapCatCredentialService credentials = mock(NapCatCredentialService.class);
        MockMvc mvc = stack(false, new NapCatBootstrapController(credentials));

        mvc.perform(get(NapCatBootstrapController.CONSOLE_PATH)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("NapCat 启动引导控制器挂着控制台开关条件，关时不登记")
    void napCatBootstrapControllerHasSwitchGate() {
        ConditionalOnProperty gate = NapCatBootstrapController.class.getAnnotation(ConditionalOnProperty.class);

        assertNotNull(gate, "没挂开关条件的控制器，关掉控制台后仍在对外发凭据");
        assertEquals(1, gate.name().length);
        assertEquals(SWITCH, gate.name()[0]);
        assertEquals("true", gate.havingValue());
        assertTrue(gate.matchIfMissing());
    }

    @Test
    @DisplayName("推送目标控制器挂着同一个开关条件，群号与好友名单同样关时不登记")
    void oneBotTargetControllerHasSwitchGate() {
        ConditionalOnProperty gate = OneBotTargetController.class.getAnnotation(ConditionalOnProperty.class);

        assertNotNull(gate, "没挂开关条件的控制器，关掉控制台后仍在吐群号与好友账号");
        assertEquals(1, gate.name().length);
        assertEquals(SWITCH, gate.name()[0]);
        assertEquals("true", gate.havingValue());
        assertTrue(gate.matchIfMissing());
    }

    @Test
    @DisplayName("关掉后 /config 下忘挂开关的新路径也回 404，靠的是关闭过滤器而不是控制器自觉")
    void switchOffHidesUnmarkedNewEndpoint() throws Exception {
        MockMvc mvc = stack(false, null);

        mvc.perform(get(UnmarkedProbe.PATH)).andExpect(status().isNotFound());
    }

    /**
     * 尺子取不到「不是 404」，全红和全绿长得一样。
     * 开着时探针回 200，证明上面那些 404 真是挡住的。
     */
    @Test
    @DisplayName("阴性对照：开着的时候忘挂开关的接口照旧在，尺子看得见「不是 404」")
    void switchOnKeepsUnmarkedNewEndpointReachable() throws Exception {
        MockMvc mvc = stack(true, null);

        mvc.perform(get(UnmarkedProbe.PATH)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("阴性对照：开着时凭据接口是通的，会真的去取一份凭据")
    void switchOnKeepsCredentialReachable() throws Exception {
        NapCatCredentialService credentials = mock(NapCatCredentialService.class);
        when(credentials.issue(false)).thenReturn(
                new NapCatCredentialService.Issued(NapCatCredentialService.Outcome.OK, "secret"));
        MockMvc mvc = stack(true, new NapCatBootstrapController(credentials));

        mvc.perform(post(NapCatBootstrapController.CREDENTIAL_PATH).param("renew", "false"))
                .andExpect(status().isOk());

        verify(credentials).issue(false);
    }

    @Test
    @DisplayName("开着时不装配那道关闭过滤器，否则开着也是关着")
    void switchOnDoesNotInstallClosedFilter() {
        assertNull(closedFilterWhen(true), "开着时装上关闭过滤器，等于把控制台一并打死");
    }
}
