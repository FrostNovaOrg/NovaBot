package org.frostnova.nova.adapter.onebot.config;

import org.frostnova.nova.adapter.onebot.security.PushApiSecurityFilter;
import org.frostnova.nova.adapter.onebot.security.PushApiTokenStore;
import org.frostnova.nova.adapter.onebot.security.RateLimiter;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigurationMetadataService;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplier;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.frostnova.nova.adapter.onebot.config.OneBotRuntimeAppliers.ALLOW_IPS_KEY;
import static org.frostnova.nova.adapter.onebot.config.OneBotRuntimeAppliers.LOOPBACK_DROPPED_QUESTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 推送接口来源 IP 名单：设置页显示的默认值是本机两条，新名单不放行本机时存之前先问一句
 * <p>
 * 抓的用户故障：默认名单下设置页的框是空的，用户以为是「往里加一条」的框，只填一个外部地址就保存；
 * 这一项保存即生效，本机上的程序从下一个请求起就推不进来，而且没有任何提示。
 */
@DisplayName("推送接口来源 IP：默认值显示本机两条，挡掉本机前先问")
class PushAllowListLoopbackTest {
    private static final String PATH = "/onebot/send";
    private static final String TOKEN = "Xq7-Rt2_Kd9vLm4Zp0Ns";
    private static final List<String> LOOPBACK = List.of("127.0.0.1/32", "::1/128");

    private GenericApplicationContext context;
    private OneBotAdapterPluginProperties properties;
    private RuntimeConfigurationApplier applier;
    private PushApiSecurityFilter filter;

    @BeforeEach
    void start() {
        context = new GenericApplicationContext();
        context.registerBean(OneBotAdapterPluginProperties.class, OneBotAdapterPluginProperties::new);
        context.registerBean(OneBotRuntimeAppliers.class,
                () -> new OneBotRuntimeAppliers(context.getBean(OneBotAdapterPluginProperties.class)));
        context.refresh();
        properties = context.getBean(OneBotAdapterPluginProperties.class);
        applier = new RuntimeConfigurationApplier(new NovaCoreProperties(),
                context.getBeanProvider(RuntimeConfigurationApplierContributor.class), TimelineWriter.NONE);
        PushApiTokenStore tokens = new PushApiTokenStore();
        tokens.register(PATH, TOKEN);
        filter = new PushApiSecurityFilter(properties.getSecurity(), tokens, new RateLimiter(600, 100));
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    @DisplayName("配置文件没写这一项：设置页拿到的默认值就是程序真用的本机两条")
    void settingsPageDefaultIsTheLoopbackPair() {
        // 抓的故障：默认名单下框是空的，用户只填外部地址就把本机推送挡了；
        // 「默认：」那行写「空」，「恢复默认」把框清空、保存又被拒
        Object shown = new ConfigurationMetadataService().getFields().stream()
                .filter(field -> field.name().equals(ALLOW_IPS_KEY))
                .findFirst().orElseThrow().defaultValue();
        assertEquals(LOOPBACK, new OneBotAdapterPluginProperties().getSecurity().getAllowIps());
        assertEquals(LOOPBACK, shown);
    }

    @Test
    @DisplayName("整框换成只有外部地址：先问；点取消什么都不写，本机照旧放行")
    void externalOnlyListAsksAndCancelKeepsLoopback() throws Exception {
        // 抓的故障：只填外部地址就保存，本机推送从下一个请求起被挡，没有任何提示
        Map<String, String> change = Map.of(ALLOW_IPS_KEY, "203.0.113.5");
        assertEquals(List.of(), applier.validateValues(change), "名单本身写得对");

        assertEquals(Map.of(ALLOW_IPS_KEY, LOOPBACK_DROPPED_QUESTION), applier.confirmationsFor(change));

        // 点取消：保存口在问的那一步就回去了，套用器没碰到这一批
        assertEquals(LOOPBACK, properties.getSecurity().getAllowIps());
        assertEquals(200, send("127.0.0.1"), "取消之后本机照旧放行");
        assertEquals(200, send("::1"));
    }

    @Test
    @DisplayName("点确定：照常保存，从下一个请求起本机被挡、外部地址放行")
    void confirmedExternalOnlyListTakesEffect() throws Exception {
        assertEquals(List.of(), applier.applyAndTrack(Map.of(ALLOW_IPS_KEY, "203.0.113.5")));

        assertEquals(403, send("127.0.0.1"));
        assertEquals(200, send("203.0.113.5"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "127.0.0.1/32\n::1/128\n203.0.113.5",
            "127.0.0.0/8\n203.0.113.5",
            "::1/128\n203.0.113.5",
            "127.0.0.1\n203.0.113.5",
            "0.0.0.0/0",
            "::/0",
            "127.0.0.1/32\n::1/128"})
    @DisplayName("新名单放行 127.0.0.1 或 ::1（含网段）：不问，直接保存")
    void listsKeepingLoopbackAreSavedWithoutAsking(String value) throws Exception {
        // 抓的故障：留着本机的名单也被拦下来问，或者 127.0.0.0/8 这类网段没被认成放行本机
        Map<String, String> change = Map.of(ALLOW_IPS_KEY, value);
        assertEquals(List.of(), applier.validateValues(change));
        assertEquals(Map.of(), applier.confirmationsFor(change));
    }

    @Test
    @DisplayName("在默认两条下面加一行外部地址：不问，本机和外部地址都放行")
    void appendingToTheDefaultsKeepsBoth() throws Exception {
        Map<String, String> change = Map.of(ALLOW_IPS_KEY, "127.0.0.1/32\n::1/128\n203.0.113.5");
        assertEquals(Map.of(), applier.confirmationsFor(change));
        applier.applyAndTrack(change);

        assertEquals(200, send("127.0.0.1"));
        assertEquals(200, send("203.0.113.5"));
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"10.0.0.300", "office-pc", "", "  \n  "})
    @DisplayName("有一条写错或整份留空：照旧拒绝保存，不走这一问，原名单照用")
    void invalidOrEmptyListIsRejectedNotAsked(String value) throws Exception {
        // 抓的故障：写错的名单先弹「确定吗」，点了确定才说写错了
        Map<String, String> change = Map.of(ALLOW_IPS_KEY, value);
        assertFalse(applier.validateValues(change).isEmpty());
        assertEquals(Map.of(), applier.confirmationsFor(change));
        assertEquals(LOOPBACK, properties.getSecurity().getAllowIps());
        assertTrue(send("127.0.0.1") == 200, "原名单照用");
    }

    private int send(String remote) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setRequestURI(PATH);
        request.setRemoteAddr(remote);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
