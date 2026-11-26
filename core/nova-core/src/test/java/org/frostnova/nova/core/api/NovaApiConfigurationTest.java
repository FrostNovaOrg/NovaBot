package org.frostnova.nova.core.api;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.Filter;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiClosedRegistrar;
import org.frostnova.nova.core.config.ui.ConfigUiRegistrar;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 统一接口面不随控制台开关装配
 */
class NovaApiConfigurationTest {
    @TempDir
    Path directory;

    @RestController
    static class Nothing {
        @GetMapping("/unused")
        String unused() {
            return "";
        }
    }

    // 抓的故障：主人关掉控制台之后，已经发出去的程序全部连不上了（与只读口令签发口、推送接口的现有行为不一致）。
    @Test
    void apiPrefixKeepsServingWithTheConsoleSwitchedOff() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        properties.getConfigUi().setEnabled(false);
        NovaStateStore state = new NovaStateStore(properties);

        new ApplicationContextRunner()
                .withPropertyValues("novabot.core.config-ui.enabled=false")
                .withBean(NovaCoreProperties.class, () -> properties)
                .withBean(NovaStateStore.class, () -> state)
                .withUserConfiguration(NovaApiConfiguration.class, ConfigUiRegistrar.class, ConfigUiClosedRegistrar.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(ConfigUiRegistrar.class).isEmpty(), "控制台开着那一套不该装上");

                    FilterRegistrationBean<?> api = context.getBean("novaApiFilterRegistration", FilterRegistrationBean.class);
                    assertTrue(api.getUrlPatterns().contains("/nova/api/*"));
                    assertTrue(api.isEnabled());
                    FilterRegistrationBean<?> closed = context.getBean("configUiClosedFilterRegistration", FilterRegistrationBean.class);
                    assertTrue(closed.getUrlPatterns().stream().noneMatch(pattern -> pattern.startsWith("/nova")),
                            "关闭控制台的那道过滤器不该罩到 /nova 上");

                    String token = context.getBean(NovaApiTokenStore.class)
                            .issue(ConfigUiAccount.LOCAL_ID, "关着控制台也要能用", List.of(), null).plaintext();
                    MockMvc mvc = MockMvcBuilders.standaloneSetup(new Nothing())
                            .addFilter((Filter) api.getFilter(), "/nova/api", "/nova/api/*").build();
                    MockHttpServletResponse response = mvc.perform(get("/nova/api/v1/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse();
                    assertEquals(200, response.getStatus(), response.getContentAsString());
                    assertEquals("本地管理员", JSONObject.parseObject(response.getContentAsString())
                            .getJSONObject("data").getJSONObject("account").getString("displayName"));
                });
    }
}
