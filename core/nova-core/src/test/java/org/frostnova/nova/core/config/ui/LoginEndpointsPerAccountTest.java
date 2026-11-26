package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.account.AccountLoginProvider;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.protocol.EventStreamTokenService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 同一个平台上有两个账号时，登录接口各管各的
 * <p>
 * 抓的用户故障：装了发言账号之后，在原来那张平台主账号的卡上点「退出登录」，退掉的却是另一个账号——
 * 页面脚本只发一个平台名，接口按平台名取第一个，而两个账号的平台名一样；
 * 反过来在另一张卡上点退出，接口认不出它，回一句「未找到」。
 */
@DisplayName("登录接口按账号区分")
class LoginEndpointsPerAccountTest {
    private FakeAccount listener;

    private FakeAccount extra;

    private ConfigUiController controller;

    /**
     * 两个账号排在接口清单里的先后。缺省另一个账号在前：按平台名取第一个的写法正好取到它
     */
    private List<FakeAccount> order;

    @BeforeEach
    void setUp() {
        listener = new FakeAccount("demo", "demo", false);
        extra = new FakeAccount("demo", "demo-extra", true);
        order = List.of(extra, listener);

        @SuppressWarnings("unchecked")
        ObjectProvider<AccountLoginProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenAnswer(ignored -> order.stream().map(AccountLoginProvider.class::cast));

        NovaCoreProperties properties = new NovaCoreProperties();
        controller = new ConfigUiController(
                mock(ConfigurationMetadataService.class),
                mock(ConfigurationFileService.class),
                properties,
                mock(org.frostnova.nova.core.datasource.AbstractDataSource.class),
                mock(ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(org.frostnova.nova.core.service.NovaSenderService.class),
                mock(org.frostnova.nova.core.sender.NovaMessageSender.class),
                providers,
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
                mock(EventStreamTokenService.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.sender.PushGate.class),
                mock(org.frostnova.nova.core.service.LiveDataService.class),
                mock(org.frostnova.nova.core.timeline.TimelineStore.class),
                mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService.class),
                new PushTemplateDefaults(new NovaCoreProperties()),
                mock(UpdateCheckService.class));
    }

    @Test
    @DisplayName("原来那张卡只发平台名：退出的是平台主账号，同平台的另一个账号不动")
    void platformOnlyLogoutHitsThePlatformAccount() {
        JSONObject result = controller.logout(new JSONObject().fluentPut("platform", "demo"));

        assertTrue(result.getBooleanValue("success"), result.toJSONString());
        assertEquals(1, listener.logouts.get(), "平台主账号没有退出");
        assertEquals(0, extra.logouts.get(), "同平台的另一个账号被一并退出了");
    }

    @Test
    @DisplayName("另一张卡按账号标识退出：只退它自己")
    void idLogoutHitsOnlyThatAccount() {
        // 这一格反过来排：平台主账号在前时，按平台名取第一个的写法会把它退掉
        order = List.of(listener, extra);

        JSONObject result = controller.logout(new JSONObject()
                .fluentPut("platform", "demo").fluentPut("id", "demo-extra"));

        assertTrue(result.getBooleanValue("success"), result.toJSONString());
        assertEquals(1, extra.logouts.get(), "点了哪张卡就该退哪个账号");
        assertEquals(0, listener.logouts.get(), "平台主账号被误退了");
    }

    @Test
    @DisplayName("登录状态里每个账号都带标识与是否可选，页面才配得上卡")
    void loginStateCarriesIdAndOptional() {
        JSONArray accounts = controller.login().getJSONArray("accounts");

        assertEquals(2, accounts.size());
        JSONObject first = accounts.getJSONObject(0);
        JSONObject second = accounts.getJSONObject(1);
        assertEquals("demo-extra", first.getString("id"));
        assertTrue(first.getBooleanValue("optional"), "可选账号没有标出来，初始设置会把它当成必做的一步");
        assertEquals("demo", second.getString("id"));
        assertFalse(second.getBooleanValue("optional"));
        assertEquals(List.of("demo", "demo"), List.of(first.getString("platform"), second.getString("platform")));
    }

    /**
     * 抓的用户故障：发言账号卡上点「扫码登录」，二维码却出在另一张卡上，或者接口把请求转给了不该平时备码的账号
     */
    @Test
    @DisplayName("点「扫码登录」只叫醒按需扫码的那个账号；平时一直备码的账号不接这一下")
    void startLoginOnlyWakesOnDemandAccount() {
        JSONObject started = controller.startLogin(new JSONObject().fluentPut("id", "demo-extra"));
        assertTrue(started.getBooleanValue("success"), started.toJSONString());
        assertEquals(1, extra.starts.get(), "点了扫码登录, 那个账号没有开始扫码");

        JSONObject refused = controller.startLogin(new JSONObject().fluentPut("id", "demo"));
        assertFalse(refused.getBooleanValue("success"), "平时一直备码的账号不该从这里再起一轮扫码");
        assertEquals(0, listener.starts.get());
    }

    /**
     * 只记退出了几次的账号。标识与可选两个方法不写 {@code @Override}：
     * 接口上还没有这两个方法时，这个文件照样编得过，读数才能拿来与改动前对照
     */
    private static final class FakeAccount implements AccountLoginProvider {
        private final String platform;

        private final String id;

        private final boolean optional;

        private final AtomicInteger logouts = new AtomicInteger();

        private final AtomicInteger starts = new AtomicInteger();

        private FakeAccount(String platform, String id, boolean optional) {
            this.platform = platform;
            this.id = id;
            this.optional = optional;
        }

        @Override
        public String platform() {
            return platform;
        }

        public String id() {
            return id;
        }

        public boolean optional() {
            return optional;
        }

        @Override
        public String displayName() {
            return "演示平台";
        }

        @Override
        public boolean isLoggedIn() {
            return true;
        }

        @Override
        public Optional<String> accountId() {
            return Optional.of(id.equals("demo") ? "1001" : "1002");
        }

        @Override
        public Optional<String> pendingQrCodeContent() {
            return Optional.empty();
        }

        @Override
        public boolean loginOnDemand() {
            return optional;
        }

        @Override
        public String startLogin() {
            starts.incrementAndGet();
            return "正在获取二维码";
        }

        @Override
        public void logout() {
            logouts.incrementAndGet();
        }
    }
}
