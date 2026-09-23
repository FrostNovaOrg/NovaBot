package org.frostnova.nova.report.controller;

import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.report.painter.BilibiliLiveReportPreviewPainter;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 控制台关掉后，下播报告版式接口也不再对外
 *
 * <h2>这把尺量的是什么用户故障</h2>
 * 主人关掉控制台，以为 {@code /config} 下只剩公开页；
 * {@code BilibiliReportLayoutController} 原先只靠控制台安全过滤器罩着，开关一关它还在，
 * 谁够得着端口谁就能读走版式项、或者让服务端渲一张 PNG 白耗 CPU。
 *
 * <h2>为什么用反射取关闭过滤器</h2>
 * 那道过滤器是新建件，直接 import 的话编译就钉死在具体类名上。
 * 按类名取、取不到按「第 1 层不在」办。
 *
 * <h2>开着时的登录由谁钉</h2>
 * 开关开着时这两条路径在 {@code ConfigUiSecurityFilter} 射程内，
 * 已由 {@code ConfigUiPublicApiTest}、{@code OperatorTokenSwitchTest}、
 * {@code ConfigUiAgreementGateTest}、{@code PasskeyEndpointAccessTest} 钉着，这里不重钉。
 */
@DisplayName("照文档关掉控制台后，下播报告版式接口也一并关闭")
class ConfigUiClosedHidesReportLayoutTest {

    private static final String SWITCH = "novabot.core.config-ui.enabled";

    @RestController
    static class UnmarkedProbe {
        static final String PATH = "/config/api/report-closed-gate-probe";

        @GetMapping(PATH)
        String leak() {
            return "still-here";
        }
    }

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
        List<Object> controllers = new ArrayList<>();
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
    @DisplayName("关掉后 GET /config/api/report/layout-options 回 404，版式项也别想读走")
    void switchOffHidesLayoutOptions() throws Exception {
        BilibiliLiveReportPreviewPainter painter = mock(BilibiliLiveReportPreviewPainter.class);
        RevenueVisibilityService revenueVisibility = mock(RevenueVisibilityService.class);
        MockMvc mvc = stack(false,
                new BilibiliReportLayoutController(painter, revenueVisibility));

        mvc.perform(get(BilibiliReportLayoutController.LAYOUT_OPTIONS_PATH))
                .andExpect(status().isNotFound());

        verifyNoInteractions(painter, revenueVisibility);
    }

    @Test
    @DisplayName("下播报告版式控制器挂着控制台开关条件，关时不登记")
    void bilibiliReportLayoutControllerHasSwitchGate() {
        ConditionalOnProperty gate =
                BilibiliReportLayoutController.class.getAnnotation(ConditionalOnProperty.class);

        assertNotNull(gate, "没挂开关条件的控制器，关掉控制台后仍在渲图");
        assertEquals(1, gate.name().length);
        assertEquals(SWITCH, gate.name()[0]);
        assertEquals("true", gate.havingValue());
        assertTrue(gate.matchIfMissing());
    }

    @Test
    @DisplayName("关掉后 /config 下忘挂开关的新路径也回 404")
    void switchOffHidesUnmarkedNewEndpoint() throws Exception {
        MockMvc mvc = stack(false, null);

        mvc.perform(get(UnmarkedProbe.PATH)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("阴性对照：开着的时候忘挂开关的接口照旧在，尺子看得见「不是 404」")
    void switchOnKeepsUnmarkedNewEndpointReachable() throws Exception {
        MockMvc mvc = stack(true, null);

        mvc.perform(get(UnmarkedProbe.PATH)).andExpect(status().isOk());
    }
}
