package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.util.IpMatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 部署前缀那几组判据共用的台面：按给定的前缀把控制台页与登录页各吐一次
 * <p>
 * {@code configured} 是本程序配的前缀，即 servlet 上下文自己的 context path；
 * {@code onRequest} 是请求上的 context path，平时与前者相同，转发头能把它改成别的。
 */
final class ConfigUiPrefixPages {
    private ConfigUiPrefixPages() {
    }

    /**
     * 控制台页，经 MockMvc 走控制器
     */
    static String index(String configured, String onRequest) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        return mvc.perform(get(ConfigUiController.BASE_PATH).with(request -> {
                    ((MockServletContext) request.getServletContext()).setContextPath(configured);
                    request.setContextPath(onRequest);
                    request.setRequestURI(onRequest + ConfigUiController.BASE_PATH);
                    return request;
                }))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 登录页：口令形态下没带会话访问控制台首页，由安全过滤器原地吐出
     */
    static String login(String configured, String onRequest) throws Exception {
        ConfigUiSecurityFilter filter = new ConfigUiSecurityFilter("operator-token-for-test-0123456789",
                new IpMatcher(List.of("0.0.0.0/0", "::/0")), ConfigUiPrefixCookieTest.passwordAuth(),
                new NovaCoreProperties.ConfigUi.Auth(),
                ConfigUiPrefixCookieTest.accepted(ConfigUiSession.Channel.PASSWORD));

        MockHttpServletRequest request = ConfigUiPrefixCookieTest.request("GET", configured, onRequest,
                ConfigUiController.BASE_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertNull(chain.getRequest(), "前提：没带会话，这一趟不放行");
        assertEquals(200, response.getStatus(), "前提：吐的是登录页");
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 页面里写在属性上的本站地址（以「/」起头、不是「//」的那些）
     */
    static List<String> siteAttributes(String html) {
        Matcher matcher = Pattern.compile("\\b(?:href|src|action|content)=\"(/(?!/)[^\"]*)\"").matcher(html);
        List<String> found = new java.util.ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /**
     * 页面里以引号起头、写死的 {@code /config} 或 {@code /nova/} 地址
     */
    static List<String> bareAddresses(String html) {
        Matcher matcher = Pattern.compile("[\"'`](/config|/nova/)[^\"'`]*").matcher(html);
        List<String> found = new java.util.ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private static ConfigUiController controller() {
        NovaCoreProperties properties = new NovaCoreProperties();
        return new ConfigUiController(
                mock(ConfigurationMetadataService.class),
                mock(ConfigurationFileService.class),
                properties,
                mock(AbstractDataSource.class),
                mock(ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(NovaSenderService.class),
                mock(org.frostnova.nova.core.sender.NovaMessageSender.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationLabelResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
                RuntimeConfigurationApplier.bench(properties).build(),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.protocol.EventStreamTokenService.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.sender.PushGate.class),
                mock(org.frostnova.nova.core.service.LiveDataService.class),
                mock(org.frostnova.nova.core.timeline.TimelineStore.class),
                mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService.class),
                new PushTemplateDefaults(new NovaCoreProperties()),
                mock(UpdateCheckService.class));
    }
}
