package org.frostnova.nova.core.api;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiRegistrar;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcClient;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.StreamerDirectory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 统一接口面的装配
 *
 * <h2>不挂控制台开关</h2>
 * 与代签发只读口令、推送接口一样，控制台关掉之后这个前缀照常服务：
 * 关控制台是不想让人在浏览器里改东西，不等于不让已经发出去的程序接着读。
 * 令牌照旧只能在控制台里签发与吊销，关着控制台时签不出新的，也吊销不了旧的。
 * 账号表在控制台开着时与控制台共用同一个对象；关着时另起一个读同一份运行状态的。
 */
@Configuration(proxyBeanMethods = false)
public class NovaApiConfiguration {
    /**
     * 与控制台安全过滤器同一档：安全响应头那道仍排在前面。
     * 来访者由这道过滤器自己判（{@link NovaApiVisitor}），不靠 {@code server.forward-headers-strategy}；
     * 若有人开了那一项，框架改写来源地址的那一步照样排在前面，这里看到的对端就是改写之后的那个
     */
    public static final int FILTER_ORDER = ConfigUiRegistrar.FILTER_ORDER;

    @Bean
    public NovaApiTokenStore novaApiTokenStore(NovaStateStore state) {
        return new NovaApiTokenStore(state);
    }

    /**
     * 插件登记的接口与范围，外加底座自己的范围「读取事件流」
     * <p>
     * 那一项不属于哪一组接口：事件输出不在 {@code /nova/api/v1/} 下，「从别的机器访问」里也就没有它的开关
     */
    @Bean
    public NovaApiRegistry novaApiRegistry(ObjectProvider<NovaApiProvider> providers) {
        return new NovaApiRegistry(List.of(NovaApiEventStreamTokens.SCOPE), providers.orderedStream().toList());
    }

    /**
     * 让事件输出的首帧也认访问令牌
     * <p>
     * 账号表与统一接口、控制台用同一份：控制台开着时是同一个对象，改角色、停用之类的改动当场通知得到；
     * 关着时另起一个读同一份运行状态的，那时没有地方改账号。
     */
    @Bean
    public NovaApiEventStreamTokens novaApiEventStreamTokens(NovaStateStore state, NovaApiTokenStore tokens,
                                                             ObjectProvider<ConfigUiAccountStore> accounts) {
        return new NovaApiEventStreamTokens(tokens, accounts.getIfAvailable(() -> new ConfigUiAccountStore(state)));
    }

    @Bean
    public NovaApiThrottle novaApiThrottle() {
        return new NovaApiThrottle();
    }

    /**
     * 成员此刻能不能登录控制台
     * <p>
     * 控制台开着时每次现问登录校验那一处（口令可以在运行期设下或撤掉，单点登录随之开关）。
     * 控制台关着时谁也登不进，这时按配置判：设了口令、单点登录开着，就算成员登得进——
     * 关控制台是不想让人在浏览器里改东西，不该连带掐断成员已经发出去的程序，
     * 与「控制台关掉后这个前缀照常服务」同一个口径。
     */
    @Bean
    public NovaApiMemberSignIn novaApiMemberSignIn(NovaCoreProperties properties,
                                                   ObjectProvider<ConfigUiAuthService> auth,
                                                   ObjectProvider<OidcClient> oidc) {
        return () -> {
            ConfigUiAuthService service = auth.getIfAvailable();
            if (service != null) {
                return service.memberSignInOpen();
            }
            OidcClient client = oidc.getIfAvailable();
            String password = properties.getConfigUi().getAuth().getPassword();
            return client != null && client.isEnabled() && password != null && !password.isBlank();
        };
    }

    @Bean
    public FilterRegistrationBean<NovaApiFilter> novaApiFilterRegistration(
            NovaCoreProperties properties, NovaStateStore state, NovaApiTokenStore tokens, NovaApiRegistry registry,
            NovaApiThrottle throttle, NovaApiMemberSignIn memberSignIn, ObjectProvider<ConfigUiAccountStore> accounts,
            ObjectProvider<DataSourceServiceRegistry> platforms, ObjectProvider<StreamerDirectory> directory,
            ObjectProvider<AbstractDataSource> dataSource) {
        ConfigUiAccountStore accountStore = accounts.getIfAvailable(() -> new ConfigUiAccountStore(state));
        NovaApiOpenGroups openGroups = new NovaApiOpenGroups(properties.getConfigUi()::getApiAnySourceGroups, registry);
        NovaApiFilter filter = new NovaApiFilter(openGroups, tokens,
                accountStore, memberSignIn, registry, throttle,
                () -> {
                    DataSourceServiceRegistry registered = platforms.getIfAvailable();
                    return registered == null ? List.of() : registered.platforms();
                },
                () -> {
                    StreamerDirectory streamers = directory.getIfAvailable();
                    AbstractDataSource source = dataSource.getIfAvailable();
                    List<PushUser> loaded = source == null ? List.of() : source.getAllUsers();
                    return NovaApiMe.roster(streamers == null ? List.of() : streamers.entries(), loaded);
                });
        FilterRegistrationBean<NovaApiFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(NovaApiFilter.PREFIX, NovaApiFilter.PREFIX + "/*");
        registration.setOrder(FILTER_ORDER);
        registration.setName("novaApiFilter");
        return registration;
    }
}
