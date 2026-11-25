package org.frostnova.nova.adapter.onebot.config;

import org.frostnova.nova.adapter.onebot.security.PushApiSecurityFilter;
import org.frostnova.nova.adapter.onebot.security.PushApiTokenStore;
import org.frostnova.nova.adapter.onebot.security.RateLimiter;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigurationEffectResolver;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplier;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.properties.ConfigEffect;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OneBot 适配器设置经控制台保存后，运行中的程序读到的就是新值
 * <p>
 * 抓的是「页面说已生效，运行中的值却没变」：设置页按字段上的生效标记说话，
 * 而真正把值写回运行中配置对象的是适配器申报的套用器。两边任一边漏了，下面的表就有一行红。
 */
@DisplayName("OneBot 设置保存即生效")
class OneBotSettingsSaveTest {
    private static final String PREFIX = "novabot.adapter.onebot.";
    private static final String PATH = "/onebot/send";
    private static final String TOKEN = "Xq7-Rt2_Kd9vLm4Zp0Ns";

    /**
     * 每个登记为当场生效、走通用保存的 OneBot 键：存什么、运行中该读到什么（与默认值不同）
     * <p>
     * 机器人连接列表另走连接页的专门入口，不在这张表上。
     */
    private static final Map<String, Change> TABLE = table(
            change("alert.platform", "qq-backup", "qq-backup"),
            change("alert.type", "1", 1),
            change("alert.num", "20001", 20001L),
            change("napcat.enabled", "false", false),
            change("security.allow-ips", "127.0.0.1/32\n 10.0.0.0/8 ", List.of("127.0.0.1/32", "10.0.0.0/8")),
            change("security.trust-proxy", "true", true),
            change("security.audit-log", "false", false),
            change("security.rate-limit.enabled", "false", false),
            change("detect.slow-threshold-millis", "500", 500));

    private GenericApplicationContext context;
    private OneBotAdapterPluginProperties properties;
    private RuntimeConfigurationApplier applier;

    @BeforeEach void start() {
        context = new GenericApplicationContext();
        context.registerBean(OneBotAdapterPluginProperties.class, OneBotAdapterPluginProperties::new);
        context.registerBean(OneBotRuntimeAppliers.class,
                () -> new OneBotRuntimeAppliers(context.getBean(OneBotAdapterPluginProperties.class)));
        context.refresh();
        properties = context.getBean(OneBotAdapterPluginProperties.class);
        applier = new RuntimeConfigurationApplier(new NovaCoreProperties(),
                context.getBeanProvider(RuntimeConfigurationApplierContributor.class), TimelineWriter.NONE);
    }

    @AfterEach void close() {
        context.close();
    }

    @Test
    @DisplayName("表上的键正是设置页标着当场生效的 OneBot 键，防止新标的一项没人核对")
    void tableCoversEveryImmediateOneBotKey() {
        Set<String> immediate = new ConfigurationEffectResolver(context).getEffects().entrySet().stream()
                .filter(entry -> entry.getValue() == ConfigEffect.Effect.IMMEDIATE)
                .map(Map.Entry::getKey)
                .filter(name -> name.startsWith(PREFIX))
                .filter(name -> !name.equals(PREFIX + "senders"))
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(new TreeSet<>(TABLE.keySet()), immediate);
    }

    @Test
    @DisplayName("长连接存活检测两项仍标重启：连上那一刻读一次，已连着的连接看不见新值")
    void websocketDetectStillNeedsRestart() {
        var effects = new ConfigurationEffectResolver(context).getEffects();
        for (String key : List.of(PREFIX + "detect.enable-websocket-detect", PREFIX + "detect.websocket-silence-timeout")) {
            assertEquals(ConfigEffect.Effect.RESTART, effects.get(key), key);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("逐项保存后运行中读到新值，且与重启后按文件读到的相同")
    void savedValueReachesTheRunningProgram(String key) {
        Change change = TABLE.get(key);
        assertNotEquals(change.expected(), runtimeValue(new OneBotAdapterPluginProperties(), key), "表上的新值应与默认值不同");

        Saved saved = save(Map.of(key, change.saved()));

        assertAll(
                () -> assertEquals(List.of(), saved.problems()),
                () -> assertEquals(ConfigEffect.Effect.IMMEDIATE,
                        new ConfigurationEffectResolver(context).getEffects().get(key), "设置页标的生效时机"),
                () -> assertEquals(List.of(), saved.restartRequired(), "保存后要重启的项"),
                () -> assertEquals(List.of(), applier.getPendingRestart(), "仍在等重启的项"),
                () -> assertEquals(change.expected(), runtimeValue(properties, key), "运行中的值"),
                () -> assertEquals(runtimeValue(boundFrom(Map.of(key, change.saved())), key),
                        runtimeValue(properties, key), "运行中的值应与重启后读到的相同"));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("invalidValues")
    @DisplayName("读不懂的取值当场拒收，旧值不变")
    void invalidValueIsRejected(String suffix, String value) {
        String key = PREFIX + suffix;
        Object before = runtimeValue(properties, key);

        Saved rejected = save(Map.of(key, value));

        assertAll(
                () -> assertEquals(1, rejected.problems().size(), rejected.problems()::toString),
                () -> assertTrue(rejected.problems().get(0).contains(key), rejected.problems()::toString),
                () -> assertEquals(before, runtimeValue(properties, key)));
    }

    static Stream<Arguments> invalidValues() {
        return Stream.of(
                Arguments.of("security.allow-ips", "127.0.0.1/32\n10.0.0.300"),
                Arguments.of("security.allow-ips", " \n "),
                Arguments.of("security.trust-proxy", "maybe"),
                Arguments.of("security.audit-log", ""),
                Arguments.of("security.rate-limit.enabled", "enable"),
                Arguments.of("detect.slow-threshold-millis", "2s"));
    }

    @Test
    @DisplayName("一批里有一项取值不对时整批拒收，安全三项不会只改一半")
    void invalidValueRejectsTheWholeBatch() {
        Map<String, String> batch = new LinkedHashMap<>();
        batch.put(PREFIX + "security.trust-proxy", "true");
        batch.put(PREFIX + "security.rate-limit.enabled", "nope");

        Saved rejected = save(batch);

        assertAll(
                () -> assertEquals(1, rejected.problems().size(), rejected.problems()::toString),
                () -> assertFalse(properties.getSecurity().isTrustProxy()),
                () -> assertTrue(properties.getSecurity().getRateLimit().isEnabled()));

        List<String> restart = applier.applyAndTrack(batch);
        assertEquals(new TreeSet<>(batch.keySet()), new TreeSet<>(restart), "绕过校验的坏值整批按待重启处理");
        assertFalse(properties.getSecurity().isTrustProxy(), "同批的好值也不应写进去");
    }

    @Test
    @DisplayName("删掉一项回到默认值")
    void clearedKeyFallsBackToTheDefault() {
        save(Map.of(PREFIX + "security.audit-log", "false"));
        Map<String, String> cleared = new LinkedHashMap<>();
        cleared.put(PREFIX + "security.audit-log", null);

        assertEquals(List.of(), save(cleared).restartRequired());
        assertTrue(properties.getSecurity().isAuditLog());
    }

    @Test
    @DisplayName("安全三项并发改读，一个请求拿到的不会是半新半旧的一组")
    void requestPolicyIsNeverHalfUpdated() throws Exception {
        Map<String, String> on = Map.of(PREFIX + "security.trust-proxy", "true",
                PREFIX + "security.audit-log", "true", PREFIX + "security.rate-limit.enabled", "true");
        Map<String, String> off = Map.of(PREFIX + "security.trust-proxy", "false",
                PREFIX + "security.audit-log", "false", PREFIX + "security.rate-limit.enabled", "false");
        applier.applyAndTrack(on);

        List<String> torn = Collections.synchronizedList(new ArrayList<>());
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 5_000; i++) applier.applyAndTrack(i % 2 == 0 ? off : on);
        });
        writer.start();
        while (writer.isAlive()) {
            var policy = properties.getSecurity().requestPolicy();
            if (policy.trustProxy() != policy.auditLog() || policy.auditLog() != policy.rateLimited()) {
                torn.add(policy.toString());
            }
        }
        writer.join(30_000);
        assertEquals(List.of(), torn.stream().limit(3).toList());
    }

    // ============ 走真实读处 ============

    @Test
    @DisplayName("保存「推送接口 · 频率限制」后不重启，下一个请求就按新开关处理")
    void rateLimitSwitchTakesEffectOnTheNextRequest() throws Exception {
        PushApiTokenStore tokens = new PushApiTokenStore();
        tokens.register(PATH, TOKEN);
        // 桶里只有一枚：第二个请求起就超限
        PushApiSecurityFilter filter = new PushApiSecurityFilter(properties.getSecurity(), tokens, new RateLimiter(1, 1));

        assertEquals(200, send(filter), "桶里还有一枚时放行");
        assertEquals(429, send(filter), "开着频率限制时超限被拒");

        save(Map.of(PREFIX + "security.rate-limit.enabled", "false"));
        assertEquals(200, send(filter), "关掉之后的下一个请求不再限频");
        assertEquals(200, send(filter));

        save(Map.of(PREFIX + "security.rate-limit.enabled", "true"));
        assertEquals(429, send(filter), "重新打开后的下一个请求照旧限频");
    }

    @Test
    @DisplayName("保存「推送接口 · 允许的来源 IP」后不重启，下一个请求就按新名单放行")
    void allowListTakesEffectOnTheNextRequest() throws Exception {
        // 抓的故障：名单在启动时建成匹配器交给过滤器，设置页改了以后不重启，新加的网段仍被挡在外面
        PushApiTokenStore tokens = new PushApiTokenStore();
        tokens.register(PATH, TOKEN);
        PushApiSecurityFilter filter = new PushApiSecurityFilter(properties.getSecurity(), tokens, new RateLimiter(600, 100));
        assertEquals(403, send(filter, "10.1.2.3", null), "改之前这个地址进不来");

        Saved saved = save(Map.of(PREFIX + "security.allow-ips", "127.0.0.1/32\n10.0.0.0/8"));

        assertEquals(List.of(), saved.restartRequired(), "保存后要重启的项");
        assertEquals(200, send(filter, "10.1.2.3", null), "改之后的下一个请求就该放行");
        assertEquals(403, send(filter, "192.168.1.20", null), "不在新名单里的照旧挡住");
    }

    @Test
    @DisplayName("名单里有一条写错：拒绝保存并指出是哪一条，旧名单照用")
    void mistypedAllowListEntryIsRejected() throws Exception {
        PushApiTokenStore tokens = new PushApiTokenStore();
        tokens.register(PATH, TOKEN);
        PushApiSecurityFilter filter = new PushApiSecurityFilter(properties.getSecurity(), tokens, new RateLimiter(600, 100));
        save(Map.of(PREFIX + "security.allow-ips", "127.0.0.1/32\n10.0.0.0/8"));

        for (String wrong : List.of("10.0.0.300", "192.168.1.0/40", "office-pc", "10.0.0.0/abc")) {
            Saved rejected = save(Map.of(PREFIX + "security.allow-ips", "127.0.0.1/32\n" + wrong));
            assertEquals(1, rejected.problems().size(), rejected.problems()::toString);
            assertTrue(rejected.problems().get(0).contains("「" + wrong + "」"), rejected.problems()::toString);
        }
        assertEquals(List.of("127.0.0.1/32", "10.0.0.0/8"), properties.getSecurity().getAllowIps());
        assertEquals(200, send(filter, "10.1.2.3", null), "旧名单照用");
    }

    @Test
    @DisplayName("保存来源 IP 名单与信任反向代理的那一刻，同一个请求不会一半按旧设置、一半按新设置")
    void oneRequestNeverMixesOldAndNewSecuritySettings() throws Exception {
        // 抓的故障：请求按旧名单过了来源检查、又按新的代理设置认来源（或反过来），
        // 两份各自都放行的设置拼在一起，把本该放行的请求挡掉、或把本该挡住的放进来
        PushApiTokenStore tokens = new PushApiTokenStore();
        tokens.register(PATH, TOKEN);
        PushApiSecurityFilter filter = new PushApiSecurityFilter(properties.getSecurity(), tokens, new RateLimiter(600, 100));
        // 两份设置都放行这个请求：对端是本机反代，代理头里的真实来源是 203.0.113.9
        Map<String, String> direct = Map.of(PREFIX + "security.allow-ips", "127.0.0.1/32",
                PREFIX + "security.trust-proxy", "false", PREFIX + "security.rate-limit.enabled", "false");
        Map<String, String> proxied = Map.of(PREFIX + "security.allow-ips", "203.0.113.0/24",
                PREFIX + "security.trust-proxy", "true", PREFIX + "security.rate-limit.enabled", "false");
        assertEquals(List.of(), save(direct).restartRequired());
        assertEquals(200, send(filter, "127.0.0.1", "203.0.113.9"));
        assertEquals(List.of(), save(proxied).restartRequired());
        assertEquals(200, send(filter, "127.0.0.1", "203.0.113.9"));

        List<Integer> mixed = Collections.synchronizedList(new ArrayList<>());
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 20_000; i++) applier.applyAndTrack(i % 2 == 0 ? direct : proxied);
        });
        writer.start();
        while (writer.isAlive()) {
            int status = send(filter, "127.0.0.1", "203.0.113.9");
            if (status != 200) mixed.add(status);
        }
        writer.join(30_000);
        assertEquals(List.of(), mixed.stream().limit(3).toList(), "两份设置各自都放行，被挡下就是拼了半新半旧的一组");
    }

    // ============ 工具 ============

    private static int send(PushApiSecurityFilter filter) throws Exception {
        return send(filter, "127.0.0.1", null);
    }

    private static int send(PushApiSecurityFilter filter, String remote, String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setRequestURI(PATH);
        request.setRemoteAddr(remote);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    /**
     * 照保存口的两步：先问取值合不合规矩，有问题就整批不动；没问题再交给套用器
     */
    private Saved save(Map<String, String> changes) {
        Map<String, String> toValidate = new LinkedHashMap<>(changes);
        toValidate.values().removeIf(Objects::isNull);
        List<String> problems = applier.validateValues(toValidate);
        if (!problems.isEmpty()) {
            return new Saved(problems, List.of());
        }
        return new Saved(List.of(), applier.applyAndTrack(new LinkedHashMap<>(changes)));
    }

    private record Saved(List<String> problems, List<String> restartRequired) {
    }

    /**
     * 重启那一路：同样的取值按 Spring 绑定读进一份新的配置对象
     * <p>
     * 多行的取值是名单：配置文件里逐行去首尾空白、去空行写成列表项，这里照样摊成带下标的键。
     */
    private static OneBotAdapterPluginProperties boundFrom(Map<String, String> values) {
        Map<String, String> file = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value.contains("\n")) {
                List<String> items = value.lines().map(String::strip).filter(item -> !item.isEmpty()).toList();
                for (int i = 0; i < items.size(); i++) file.put(key + "[" + i + "]", items.get(i));
            } else {
                file.put(key, value);
            }
        });
        OneBotAdapterPluginProperties bound = new OneBotAdapterPluginProperties();
        new Binder(new MapConfigurationPropertySource(file))
                .bind("novabot.adapter.onebot", Bindable.ofInstance(bound));
        return bound;
    }

    private static Object runtimeValue(OneBotAdapterPluginProperties target, String key) {
        String path = Stream.of(key.substring(PREFIX.length()).split("\\."))
                .map(OneBotSettingsSaveTest::camel).collect(Collectors.joining("."));
        return new BeanWrapperImpl(target).getPropertyValue(path);
    }

    private static String camel(String segment) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char c : segment.toCharArray()) {
            if (c == '-') {
                upper = true;
            } else {
                result.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return result.toString();
    }

    static Stream<Arguments> rows() {
        return TABLE.keySet().stream().map(Arguments::of);
    }

    private record Change(String key, String saved, Object expected) {
    }

    private static Change change(String suffix, String saved, Object expected) {
        return new Change(PREFIX + suffix, saved, expected);
    }

    private static Map<String, Change> table(Change... changes) {
        Map<String, Change> table = new LinkedHashMap<>();
        for (Change change : changes) table.put(change.key(), change);
        return table;
    }
}
