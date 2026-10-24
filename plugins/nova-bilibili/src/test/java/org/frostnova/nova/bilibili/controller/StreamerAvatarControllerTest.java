package org.frostnova.nova.bilibili.controller;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.config.ui.auth.*;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.properties.LogProperties;
import org.frostnova.nova.core.service.StreamerDirectory;
import org.frostnova.nova.core.util.HttpUtil;
import org.frostnova.nova.core.util.IpMatcher;
import org.frostnova.nova.core.web.SecurityHeadersFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 控制台没有同源头像可读；成功、隐藏未授权主播和取图失败必须走真实 HTTP 处理链。 */
@DisplayName("主播头像同源读取")
class StreamerAvatarControllerTest {
    private static final String URL = "https://i0.hdslb.com/bfs/face/avatar.png";
    private static final String PATH = "/config/api/streamers/bilibili/1/avatar";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jV1sAAAAASUVORK5CYII=");
    private static final ConfigUiAccount MEMBER = new ConfigUiAccount("member", ConfigUiAccount.Role.MEMBER,
            ConfigUiAccount.Status.ENABLED, "Member", null, null, null, List.of("bilibili:1"), Instant.EPOCH, null, true);
    private final AbstractDataSource source = mock(AbstractDataSource.class);
    private final StreamerDirectory directory = mock(StreamerDirectory.class);
    private final ConfigUiAuthService auth = mock(ConfigUiAuthService.class);
    private final DataSourceServiceRegistry platforms = mock(DataSourceServiceRegistry.class);
    private MockMvc mvc;
    private MockRestServiceServer upstream;
    private Object[] controllers;
    private PushUser user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        when(platforms.platforms()).thenReturn(List.of("bilibili"));
        when(directory.entries()).thenReturn(List.of(new StreamerDirectory.Entry("bilibili", 1L, true, false),
                new StreamerDirectory.Entry("bilibili", 2L, true, false)));
        user = new PushUser();
        user.setPlatform("bilibili"); user.setUid(1L); user.setFace(URL);
        when(source.getUser("bilibili", 1L)).thenReturn(Optional.of(user));
        when(source.getUser("bilibili", 2L)).thenReturn(Optional.of(user));
        var template = new RestTemplate();
        upstream = MockRestServiceServer.bindTo(template).build();
        var http = new HttpUtil(null, template, new LogProperties());
        var timed = (Map<Duration, RestTemplate>) ReflectionTestUtils.getField(http, "timedTemplates");
        timed.put(Duration.ofSeconds(5), template);
        var api = new BilibiliApiUtil(http, new NovaBilibiliProperties(), new BilibiliRiskMetrics());
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("source", source);
        beans.registerSingleton("directory", directory);
        beans.registerSingleton("api", api);
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        var found = new ArrayList<Object>();
        for (var bean : scanner.findCandidateComponents("org.frostnova.nova.bilibili.controller")) {
            found.add(beans.createBean(Class.forName(bean.getBeanClassName())));
        }
        controllers = found.toArray();
        mvc = MockMvcBuilders.standaloneSetup(controllers)
                .addFilters(new SecurityHeadersFilter(List.of("/config")))
                .addInterceptors(new ConfigUiApiInterceptor(auth, null, platforms)).build();
    }

    @AfterEach void verifyUpstream() { upstream.verify(); }

    private MockHttpServletRequestBuilder as(ConfigUiAccount account, String path) {
        return get(path).requestAttr(ConfigUiAccess.ACCOUNT, account);
    }

    private void image() {
        upstream.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .header(HttpHeaders.REFERER, "https://www.bilibili.com"))
                .andRespond(withSuccess(PNG, MediaType.IMAGE_PNG));
    }

    // 管理员需要任一已配主播的原始图片和正确响应头；再次读取应命中服务端缓存。
    @Test void administratorReadsConfiguredAvatarAndCachesIt() throws Exception {
        image();
        for (int i = 0; i < 2; i++) mvc.perform(as(ConfigUiAccount.local(Instant.EPOCH), PATH))
                .andExpect(status().isOk()).andExpect(content().bytes(PNG))
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, max-age=3600"));
    }

    @Test void administratorCanReadAnotherConfiguredStreamer() throws Exception {
        image();
        mvc.perform(as(ConfigUiAccount.local(Instant.EPOCH), PATH.replace("/1/", "/2/")))
                .andExpect(status().isOk()).andExpect(content().bytes(PNG));
    }

    // SVG 可在直接打开头像地址时执行同源脚本，拒绝后也必须缓存失败。
    @Test void svgAvatarIsNotFoundAndCached() throws Exception {
        upstream.expect(requestTo(URL)).andRespond(withSuccess(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>",
                MediaType.parseMediaType("image/svg+xml")));
        for (int i = 0; i < 2; i++) mvc.perform(as(MEMBER, PATH)).andExpect(status().isNotFound());
    }

    // 即使成功返回位图，直接打开时也应禁止脚本和其他资源。
    @Test void successfulAvatarHasRestrictiveContentSecurityPolicy() throws Exception {
        image();
        mvc.perform(as(MEMBER, PATH)).andExpect(status().isOk()).andExpect(content().bytes(PNG))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
    }

    // 漏登记权限会让已授权成员也拿不到头像。
    @Test void authorizedMemberReadsAvatarAndIgnoresRequestUrl() throws Exception {
        image();
        mvc.perform(as(MEMBER, PATH).param("url", "https://untrusted.example/avatar"))
                .andExpect(status().isOk()).andExpect(content().bytes(PNG));
    }

    @Test void memberCannotReadAnotherStreamerEvenAfterAdministratorWarmedCache() throws Exception {
        image();
        String other = PATH.replace("/1/", "/2/");
        mvc.perform(as(ConfigUiAccount.local(Instant.EPOCH), other)).andExpect(status().isOk());
        mvc.perform(as(MEMBER, other)).andExpect(status().isNotFound());
    }

    @Test void unauthenticatedRequestIsRejectedByExistingFilter() throws Exception {
        when(auth.isEnabled()).thenReturn(true);
        var filter = new ConfigUiSecurityFilter("test-token", new IpMatcher(List.of("127.0.0.1")), auth,
                new NovaCoreProperties.ConfigUi.Auth(), new NovaCoreProperties.ConfigUi.Agreement());
        var filtered = MockMvcBuilders.standaloneSetup(controllers).addFilters(filter)
                .addInterceptors(new ConfigUiApiInterceptor(auth, null, platforms)).build();
        filtered.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test void unconfiguredStreamerCannotUseLoadedProfileOrCachedImage() throws Exception {
        image();
        mvc.perform(as(MEMBER, PATH)).andExpect(status().isOk());
        when(directory.entries()).thenReturn(List.of());
        mvc.perform(as(ConfigUiAccount.local(Instant.EPOCH), PATH)).andExpect(status().isNotFound());
    }

    @ParameterizedTest @ValueSource(strings = {"", " "})
    void missingAvatarIsNotFound(String face) throws Exception {
        user.setFace(face);
        mvc.perform(as(MEMBER, PATH)).andExpect(status().isNotFound());
    }

    @Test void missingProfileIsNotFound() throws Exception {
        when(source.getUser("bilibili", 1L)).thenReturn(Optional.empty());
        mvc.perform(as(MEMBER, PATH)).andExpect(status().isNotFound());
    }

    // 源站超时和错误也要短暂缓存，刷新不能连续访问源站。
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void upstreamFailureIsNegativelyCached(boolean timeout) throws Exception {
        var expectation = upstream.expect(requestTo(URL));
        if (timeout) expectation.andRespond(withException(new java.net.SocketTimeoutException("timeout")));
        else expectation.andRespond(withServerError());
        for (int i = 0; i < 2; i++) mvc.perform(as(MEMBER, PATH)).andExpect(status().isNotFound());
    }

    @ParameterizedTest @ValueSource(strings = {"text/html", "application/octet-stream", ""})
    void nonImageContentTypeIsNotFoundAndCached(String type) throws Exception {
        upstream.expect(requestTo(URL)).andRespond(type.isEmpty() ? withSuccess().body(PNG)
                : withSuccess(PNG, MediaType.parseMediaType(type)));
        for (int i = 0; i < 2; i++) mvc.perform(as(MEMBER, PATH)).andExpect(status().isNotFound());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void oversizedImageIsNotFoundAndCached(boolean declaredLength) throws Exception {
        var response = withSuccess(new byte[2 * 1024 * 1024 + 1], MediaType.IMAGE_PNG);
        if (declaredLength) response.header(HttpHeaders.CONTENT_LENGTH, "2097153");
        upstream.expect(requestTo(URL)).andRespond(response);
        for (int i = 0; i < 2; i++) mvc.perform(as(MEMBER, PATH)).andExpect(status().isNotFound());
    }

    @Test void emptyImageIsNotFound() throws Exception {
        upstream.expect(requestTo(URL)).andRespond(withSuccess(new byte[0], MediaType.IMAGE_PNG));
        mvc.perform(as(MEMBER, PATH)).andExpect(status().isNotFound());
    }

    @Test void otherPlatformsAreNotFound() throws Exception {
        when(directory.entries()).thenReturn(List.of(new StreamerDirectory.Entry("other", 1L, true, false)));
        when(source.getUser("other", 1L)).thenReturn(Optional.of(user));
        mvc.perform(as(ConfigUiAccount.local(Instant.EPOCH), PATH.replace("bilibili", "other")))
                .andExpect(status().isNotFound());
    }

    @Test void disabledConsoleDoesNotRegisterAvatarEndpoint() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        scanner.setEnvironment(new MockEnvironment().withProperty("novabot.core.config-ui.enabled", "false"));
        assertTrue(scanner.findCandidateComponents("org.frostnova.nova.bilibili.controller").isEmpty());
    }

    @Test void malformedUidKeepsExistingInterceptorAndMvcBehavior() throws Exception {
        String malformed = PATH.replace("/1/", "/bad/");
        mvc.perform(as(MEMBER, malformed)).andExpect(status().isNotFound());
        mvc.perform(as(ConfigUiAccount.local(Instant.EPOCH), malformed)).andExpect(status().isBadRequest());
    }
}
