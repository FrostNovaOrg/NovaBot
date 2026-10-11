package org.frostnova.nova.adapter.onebot.napcat;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 配了部署前缀时，NapCat 入口与引导页的地址带前缀
 * <p>
 * 抓的用户故障：配了 {@code server.servlet.context-path=/novabot}，点控制台里的 NapCat 入口，
 * 跳到没前缀的 {@code /config/napcat-bootstrap} 上 404；进了引导页，续登脚本、取凭据、
 * 问 NapCat 登没登录那几条地址也都打到没前缀的路径上。
 * <p>
 * 另一种：有人在请求头里塞 {@code X-Forwarded-Prefix}，改掉请求上的 context path。
 * 写进入口与引导页的只许是本程序配的前缀。
 */
@DisplayName("配了部署前缀时，NapCat 入口与引导页的地址带上前缀")
class NapCatBootstrapPrefixTest {

    private static final String FORGED = "/evil\"'<script>alert(1)</script>";

    private static String perform(String path, String configured, String onRequest) throws Exception {
        NapCatCredentialService credentials = mock(NapCatCredentialService.class);
        when(credentials.isConfigured()).thenReturn(true);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new NapCatBootstrapController(credentials)).build();
        return mvc.perform(get(path).with(request -> {
                    ((MockServletContext) request.getServletContext()).setContextPath(configured);
                    request.setContextPath(onRequest);
                    request.setRequestURI(onRequest + path);
                    return request;
                }))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String href(String configured, String onRequest) throws Exception {
        return JSONObject.parseObject(perform(NapCatBootstrapController.CONSOLE_PATH, configured, onRequest))
                .getString("href");
    }

    private static String page(String configured, String onRequest) throws Exception {
        return perform(NapCatBootstrapController.PAGE_PATH, configured, onRequest);
    }

    private static List<String> matches(String regex, String html) {
        Matcher matcher = Pattern.compile(regex).matcher(html);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    @Test
    @DisplayName("入口的 href 带前缀；没配前缀时与改前相同")
    void consoleHrefCarriesPrefix() throws Exception {
        assertEquals("/novabot/config/napcat-bootstrap", href("/novabot", "/novabot"),
                "配了前缀时入口还指向没前缀的地址，点进去 404");
        assertEquals(NapCatBootstrapController.PAGE_PATH, href("", ""), "没配前缀时 href 与改前逐字相同");
    }

    @Test
    @DisplayName("引导页里不剩写死的 /config 地址，前缀写进属性与脚本")
    void pageAddressesCarryPrefix() throws Exception {
        String html = page("/novabot", "/novabot");

        assertEquals(List.of(), matches("[\"'`]/config[^\"'`]*", html), "引导页里还剩写死的地址");
        assertTrue(html.contains("src=\"/novabot/config/napcat-bootstrap/napcat-resume.js\""), "续登脚本地址带前缀");
        assertTrue(html.contains("\"/novabot\""), "脚本里要有那一份写进去的前缀");
    }

    @Test
    @DisplayName("没配前缀时引导页的续登脚本地址与改前相同，不留占位")
    void pageUnchangedWithoutPrefix() throws Exception {
        String html = page("", "");

        assertTrue(html.contains("<script src=\"/config/napcat-bootstrap/napcat-resume.js\"></script>"));
        assertFalse(html.contains("{{nova-prefix"), "没配前缀时也不许留下没顶替的占位");
    }

    @Test
    @DisplayName("请求上的 context path 被改成别的时，入口与引导页里只有配置的前缀")
    void requestContextPathIsIgnored() throws Exception {
        assertEquals("/novabot/config/napcat-bootstrap", href("/novabot", FORGED), "入口认了请求上那一份前缀");

        String html = page("/novabot", FORGED);
        assertFalse(html.contains("evil"), "引导页里出现了请求上那一份前缀");
        assertTrue(html.contains("src=\"/novabot/config/napcat-bootstrap/napcat-resume.js\""));
    }
}
