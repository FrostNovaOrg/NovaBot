package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.alert.AlertService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 控制台关掉后 /config 下的接口也要一并消失
 *
 * <h2>这把尺量的是什么用户故障</h2>
 * 主人照文档把 {@code novabot.core.config-ui.enabled} 关掉，以为控制台连同它底下那几个
 * 管理接口都不在了；实际上那几个接口原先只靠控制台安全过滤器罩着——开关一关，
 * 过滤器跟整个注册器一起不装配，接口本身还在，谁够得着端口谁就能调。
 * 最贵的那一条是 {@code /config/api/napcat/credential}：不用密码就能拿走 NapCat 管理页的登录凭据。
 *
 * <h2>为什么要两层</h2>
 * 第 2 层：四个控制器补上开关条件，关时不登记——保证已经知道的接口真的不在。
 * 第 1 层：另立一道只在开关为 false 时装配的过滤器，对 {@code /config}、{@code /config/*}
 * 一律回 404——兜住以后新加、忘了挂开关的接口。
 * 只做第 2 层，下一个人加一个忘挂开关的控制器就又漏了；只做第 1 层，
 * 接口只是被挡住、bean 还在，而且过滤器谁漏改一个 URL pattern 就漏一片。
 *
 * <h2>格里为什么用反射取第 1 层的件</h2>
 * 那道过滤器与它的登记器是另立的新件。测试若直接 import，编译就钉死在具体类名上；
 * 改成按类名取、取不到按「第 1 层不在」办，过滤器缺席时也能表达预期。
 * 第 2 层同理：读控制器类上的开关条件，没有注解按「始终登记」办。
 *
 * <h2>开着时的登录由谁钉</h2>
 * 开关开着时这几个接口仍在 {@code ConfigUiSecurityFilter} 射程内，那条路已由
 * {@code ConfigUiPublicApiTest}、{@code OperatorTokenSwitchTest}、
 * {@code ConfigUiAgreementGateTest}、{@code PasskeyEndpointAccessTest} 钉着，这里不重钉；
 * 本类只钉「关掉之后」那一半，外加一条阴性对照证明尺子看得见「不是 404」。
 */
@DisplayName("照文档关掉控制台后，/config 下的接口不再对任何人开放")
class ConfigUiClosedGateTest {

    private static final String SWITCH = "novabot.core.config-ui.enabled";

    /**
     * 临时控制器：新加的、忘挂开关的那一种。
     * <p>
     * 它永远出现在栈里，好把「第 1 层兜不兜得住」和「第 2 层注解在不在」分开量：
     * 注解只能管住自己这个类，兜住它的必须是那道过滤器。
     */
    @RestController
    static class UnmarkedProbe {
        static final String PATH = ConfigUiController.BASE_PATH + "/api/closed-gate-probe";

        @GetMapping(PATH)
        String leak() {
            return "still-here";
        }
    }

    /**
     * {@code /config} 以外的接口：关掉控制台也不该跟着消失。
     * <p>
     * 路径特意写成 {@code /configx}——与 {@code /config} 前缀相邻，
     * 登记口圈大一圈（{@code /*}、{@code /config*}）就误伤到这里。
     */
    @RestController
    static class OutsideProbe {
        static final String PATH = "/configx";

        @GetMapping(PATH)
        String stillServed() {
            return "outside-still-here";
        }
    }

    // ---------- 取件：第 1 层（过滤器）与第 2 层（控制器开关条件） ----------

    /**
     * 开关处于给定状态时，生产代码会交到栈上的那道关闭过滤器的登记（含登记口）。
     * <p>
     * 认的是「登记」这一动作而不只是类还在：类在、但登记的 {@code @Bean} 没了，这里回 null。
     * 开关开着时按设计不登记，回 null。返回 {@link FilterRegistrationBean} 本身，
     * 测试按它登记的 URL pattern 挂过滤器，而不是挂在所有路径上。
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
     * 这个控制器在开关拨到 on/off 时会不会出现在栈里。
     * <p>
     * 没挂开关条件的类始终登记——那正是第 1 层要兜的漏网之鱼。
     * 挂了、且键就是控制台开关、值是 {@code true} 的，只在开着时登记。
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
                return "true".equals(gate.havingValue()) || "true".equals(String.valueOf(gate.matchIfMissing()));
            }
            return "false".equals(gate.havingValue());
        }
        return true;
    }

    /**
     * 照开关给定状态拼一条栈：第 1 层按登记结果与登记口挂过滤器，第 2 层按开关条件决定挂不挂控制器。
     * {@code always} 是不带开关的那类（探针），永远在。
     */
    private static MockMvc stack(boolean switchOn, Object whenRouted) throws Exception {
        List<Object> controllers = new ArrayList<>();
        if (whenRouted != null && routed(whenRouted.getClass(), switchOn)) {
            controllers.add(whenRouted);
        }
        controllers.add(new UnmarkedProbe());
        controllers.add(new OutsideProbe());
        StandaloneMockMvcBuilder builder = MockMvcBuilders.standaloneSetup(controllers.toArray());
        FilterRegistrationBean<?> registration = closedFilterWhen(switchOn);
        if (registration != null) {
            builder.addFilter(registration.getFilter(),
                    registration.getUrlPatterns().toArray(new String[0]));
        }
        return builder.build();
    }

    @Nested
    @DisplayName("第一层：连新加的、忘挂开关的接口也兜住")
    class LayerOneCoversNewPaths {
        @Test
        @DisplayName("关掉后 /config 下任意新路径也回 404，靠的是那道关闭过滤器而不是控制器自觉")
        void switchOffHidesUnmarkedNewEndpoint() throws Exception {
            MockMvc mvc = stack(false, null);

            mvc.perform(get(UnmarkedProbe.PATH)).andExpect(status().isNotFound());
            mvc.perform(post(UnmarkedProbe.PATH)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("关掉控制台把别的接口也关了——/config 以外的路径仍回 200")
        void switchOffKeepsOutsidePathsReachable() throws Exception {
            MockMvc mvc = stack(false, null);

            mvc.perform(get(OutsideProbe.PATH)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("关闭过滤器本身对 /config 下的请求一律写 404，不放给后面的链")
        void closedFilterItselfRepliesNotFound() throws Exception {
            FilterRegistrationBean<?> registration = closedFilterWhen(false);
            assertNotNull(registration, "开关为 false 时应当登记得上那道关闭过滤器");
            assertNotNull(registration.getFilter(), "登记里要有那道关闭过滤器");

            StandaloneMockMvcBuilder builder =
                    MockMvcBuilders.standaloneSetup(new UnmarkedProbe());
            builder.addFilter(registration.getFilter(),
                    registration.getUrlPatterns().toArray(new String[0]));
            MockMvc mvc = builder.build();

            mvc.perform(get(UnmarkedProbe.PATH)).andExpect(status().isNotFound());
        }
    }

    /**
     * 主人以为关了控制台就没人动得了告警通道；实际 POST 仍会真发一条测试告警出去。
     * 与凭据那条合看，就是「关掉之后 /config 一律不在」这件事的用户可见面。
     */
    @Nested
    @DisplayName("关掉后告警测试接口不再对外")
    class LayerTwoDropsKnownControllers {
        @Test
        @DisplayName("关掉后 POST /config/api/alert/test 回 404，而且真的不会发出那条测试告警")
        void switchOffHidesAlertTestAndDoesNotSend() throws Exception {
            AlertService alertService = mock(AlertService.class);
            MockMvc mvc = stack(false, new AlertTestController(alertService));

            mvc.perform(post(AlertTestController.TEST_PATH)).andExpect(status().isNotFound());

            verify(alertService, never()).test(anyString());
        }

        @Test
        @DisplayName("关掉后 GET /config/api/alert/channels 同样回 404，通道清单也别想读走")
        void switchOffHidesAlertChannels() throws Exception {
            AlertService alertService = mock(AlertService.class);
            MockMvc mvc = stack(false, new AlertTestController(alertService));

            mvc.perform(get(AlertTestController.CHANNELS_PATH)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("告警测试控制器挂着控制台开关条件，键与默认值与注册器一字不差")
        void alertControllerHasSwitchGate() {
            ConditionalOnProperty gate = AlertTestController.class.getAnnotation(ConditionalOnProperty.class);

            assertNotNull(gate, "没挂开关条件的控制器，关掉控制台后仍在对外");
            assertEquals(1, gate.name().length);
            assertEquals(SWITCH, gate.name()[0]);
            assertEquals("true", gate.havingValue());
            assertTrue(gate.matchIfMissing(), "没配过这一项时默认要开着，跟注册器同侧");
        }
    }

    /**
     * 尺子取不到「不是 404」，全红和全绿长得一样。
     * 这两格把开着时的路走一遍：探针回 200、告警接口是通的，证明上面那些 404 真是挡住的。
     */
    @Nested
    @DisplayName("阴性对照：开着的时候路是通的")
    class NegativeControls {
        @Test
        @DisplayName("开着时忘挂开关的接口照旧在，尺子看得见「不是 404」")
        void switchOnKeepsUnmarkedNewEndpointReachable() throws Exception {
            MockMvc mvc = stack(true, null);

            mvc.perform(get(UnmarkedProbe.PATH)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("开着时 POST /config/api/alert/test 是通的，会真的交给告警服务")
        void switchOnKeepsAlertTestReachable() throws Exception {
            AlertService alertService = mock(AlertService.class);
            when(alertService.test(anyString())).thenReturn(
                    new AlertService.TestResult(AlertService.TestResult.Status.DELIVERED, "qq", "QQ", "sent"));
            MockMvc mvc = stack(true, new AlertTestController(alertService));

            mvc.perform(post(AlertTestController.TEST_PATH).param("channel", "qq"))
                    .andExpect(status().isOk());

            verify(alertService).test("qq");
        }

        @Test
        @DisplayName("开着时不装配那道关闭过滤器，否则开着也是关着")
        void switchOnDoesNotInstallClosedFilter() {
            assertNull(closedFilterWhen(true), "开着时装上关闭过滤器，等于把控制台一并打死");
        }
    }

    @Nested
    @DisplayName("接线")
    class Wiring {
        @Test
        @DisplayName("关闭过滤器的登记器只在开关为 false 时装配，键与控制台开关同一个")
        void closedRegistrarAssemblesOnlyWhenSwitchIsOff() throws Exception {
            Class<?> registrarType = Class.forName("org.frostnova.nova.core.config.ui.ConfigUiClosedRegistrar");
            ConditionalOnProperty gate = registrarType.getAnnotation(ConditionalOnProperty.class);

            assertNotNull(gate, "登记器不带开关条件时，开着也会把 /config 一律打死");
            assertEquals(1, gate.name().length);
            assertEquals(SWITCH, gate.name()[0]);
            assertEquals("false", gate.havingValue());
            assertTrue(closedFilterWhen(false) != null);
            assertTrue(closedFilterWhen(true) == null);
        }

        @Test
        @DisplayName("告警测试接口的路径仍挂在控制台根路径之下，开着时落在安全过滤器射程内")
        void alertPathsStayUnderConsoleRoot() {
            assertTrue(AlertTestController.TEST_PATH.startsWith(ConfigUiController.BASE_PATH + "/"),
                    "路径一旦离开 /config，开着时就不在安全过滤器射程内了");
            assertTrue(AlertTestController.CHANNELS_PATH.startsWith(ConfigUiController.BASE_PATH + "/"));
        }
    }
}
