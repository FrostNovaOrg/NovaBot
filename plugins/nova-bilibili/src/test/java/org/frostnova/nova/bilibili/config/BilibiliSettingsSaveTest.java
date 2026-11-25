package org.frostnova.nova.bilibili.config;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import org.frostnova.nova.bilibili.model.Dynamic;
import org.frostnova.nova.bilibili.service.BilibiliAccountService;
import org.frostnova.nova.bilibili.service.BilibiliDynamicArchive;
import org.frostnova.nova.bilibili.service.BilibiliDynamicService;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigurationEffectResolver;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplier;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.properties.ConfigEffect;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B 站设置经控制台保存后，运行中的程序读到的就是新值
 * <p>
 * 抓的是「页面说已生效，运行中的值却没变」：设置页按字段上的生效标记说话，
 * 而真正把值写回运行中配置对象的是插件申报的套用器。两边任一边漏了，下面的表就有一行红。
 * 保存走的是保存口那两步：先问取值合不合规矩，再把改动交给套用器。
 */
@DisplayName("B 站设置保存即生效")
class BilibiliSettingsSaveTest {
    private static final String PREFIX = "novabot.bilibili.";

    private static final long UID = 10001L;

    /**
     * 每个登记为当场生效的 B 站键：存什么、运行中该读到什么（与默认值不同，改没改看得出来）
     */
    private static final Map<String, Change> TABLE = table(
            change("account.auto-refresh-cookie", "false", false),
            change("account.qr-code-login-mode", "web", "web"),
            change("live.reconnect-recovery", "false", false),
            change("live.live-room-connect-interval", "2500", 2500),
            change("live.live-room-reconnect-interval", "4000", 4000),
            change("live.max-decompressed-bytes", "1048576", 1048576),
            change("live.max-decode-nesting-depth", "2", 2),
            change("live.complete-event", "true", true),
            change("live.word-cloud-exclude-uids", "1\n2", List.of("1", "2")),
            change("live.word-cloud-block-words", "甲\n乙", List.of("甲", "乙")),
            change("dynamic.push-minutes", "30", 30),
            change("dynamic.push-live-dynamic", "true", true),
            change("dynamic.auto-save-image", "true", true),
            change("dynamic.block-words", "丙", List.of("丙")),
            change("debug.live-room-raw-message-log", "true", true),
            change("debug.dynamic-raw-message-log", "true", true),
            change("debug.dynamic-dedup-probe", "true", true),
            change("network.user-agent", "Mozilla/5.0 Example", "Mozilla/5.0 Example"),
            change("network.api-retry-max-times", "5", 5),
            change("network.api-retry-interval", "500", 500));

    private GenericApplicationContext context;
    private NovaBilibiliProperties properties;
    private RuntimeConfigurationApplier applier;

    @BeforeEach void start() {
        context = new GenericApplicationContext();
        context.registerBean(NovaBilibiliProperties.class, NovaBilibiliProperties::new);
        context.registerBean(BilibiliRuntimeAppliers.class,
                () -> new BilibiliRuntimeAppliers(context.getBean(NovaBilibiliProperties.class)));
        context.registerBean(BilibiliDynamicBlockWordsApplier.class,
                () -> new BilibiliDynamicBlockWordsApplier(context.getBean(NovaBilibiliProperties.class)));
        context.registerBean(BilibiliWordCloudBlockWordsApplier.class,
                () -> new BilibiliWordCloudBlockWordsApplier(context.getBean(NovaBilibiliProperties.class)));
        context.registerBean(BilibiliWordCloudExcludeApplier.class,
                () -> new BilibiliWordCloudExcludeApplier(context.getBean(NovaBilibiliProperties.class)));
        context.refresh();
        properties = context.getBean(NovaBilibiliProperties.class);
        applier = new RuntimeConfigurationApplier(new NovaCoreProperties(),
                context.getBeanProvider(RuntimeConfigurationApplierContributor.class), TimelineWriter.NONE);
    }

    @AfterEach void close() {
        context.close();
    }

    @Test
    @DisplayName("表上的键正是设置页标着当场生效的 B 站键，防止新标的一项没人核对")
    void tableCoversEveryImmediateBilibiliKey() {
        Set<String> immediate = new ConfigurationEffectResolver(context).getEffects().entrySet().stream()
                .filter(entry -> entry.getValue() == ConfigEffect.Effect.IMMEDIATE)
                .map(Map.Entry::getKey)
                .filter(name -> name.startsWith(PREFIX))
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(new TreeSet<>(TABLE.keySet()), immediate);
    }

    @Test
    @DisplayName("动态标识图片仍标重启：出图那侧读过一次就缓存，改路径不换图")
    void logoPathStillNeedsRestart() {
        String key = PREFIX + "dynamic.logo-path";
        assertEquals(ConfigEffect.Effect.RESTART, new ConfigurationEffectResolver(context).getEffects().get(key));
        assertEquals(List.of(key), save(Map.of(key, "logo.png")).restartRequired());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("逐项保存后运行中读到新值，且与重启后按文件读到的相同")
    void savedValueReachesTheRunningProgram(String key) {
        Change change = TABLE.get(key);
        assertNotEquals(change.expected(), runtimeValue(new NovaBilibiliProperties(), key), "表上的新值应与默认值不同");

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

    @Test
    @DisplayName("删掉一项回到默认值")
    void clearedKeyFallsBackToTheDefault() {
        save(Map.of(PREFIX + "network.api-retry-max-times", "7"));
        Map<String, String> cleared = new LinkedHashMap<>();
        cleared.put(PREFIX + "network.api-retry-max-times", null);

        assertEquals(List.of(), save(cleared).restartRequired());
        assertEquals(3, properties.getNetwork().getApiRetryMaxTimes());
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
                Arguments.of("account.auto-refresh-cookie", "maybe"),
                Arguments.of("account.qr-code-login-mode", "app"),
                Arguments.of("live.live-room-connect-interval", "1.5"),
                Arguments.of("live.max-decompressed-bytes", "ten"),
                Arguments.of("dynamic.push-minutes", ""),
                Arguments.of("debug.dynamic-dedup-probe", "sometimes"),
                Arguments.of("network.user-agent", " "),
                Arguments.of("network.api-retry-interval", "3s"));
    }

    @Test
    @DisplayName("一批里有一项取值不对时整批拒收，防止只改一半")
    void invalidValueRejectsTheWholeBatch() {
        Map<String, String> batch = new LinkedHashMap<>();
        batch.put(PREFIX + "dynamic.push-minutes", "30");
        batch.put(PREFIX + "live.complete-event", "true");
        batch.put(PREFIX + "network.api-retry-max-times", "many");

        Saved rejected = save(batch);

        assertAll(
                () -> assertEquals(1, rejected.problems().size(), rejected.problems()::toString),
                () -> assertTrue(rejected.problems().get(0).contains("整数"), rejected.problems()::toString),
                () -> assertEquals(1440, properties.getDynamic().getPushMinutes()),
                () -> assertFalse(properties.getLive().isCompleteEvent()));
    }

    @Test
    @DisplayName("绕过校验直接交给套用器的坏值也是整批不写，按待重启处理")
    void unparsableBatchWritesNothing() {
        Map<String, String> batch = new LinkedHashMap<>();
        batch.put(PREFIX + "dynamic.push-minutes", "30");
        batch.put(PREFIX + "network.api-retry-max-times", "many");

        List<String> restart = applier.applyAndTrack(batch);

        assertEquals(new TreeSet<>(batch.keySet()), new TreeSet<>(restart));
        assertEquals(1440, properties.getDynamic().getPushMinutes(), "同批的好值也不应写进去");
    }

    // ============ 走真实读处 ============

    @Test
    @DisplayName("保存「推送开播动态」后不重启，下一轮动态就按新开关处理")
    void pushLiveDynamicTakesEffectOnTheNextRound() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        List<BilibiliDynamicUpdateEvent> published = new ArrayList<>();
        Runnable poll = dynamicPoll(api, published);

        when(api.getDynamicUpdateList()).thenReturn(List.of());
        poll.run();

        when(api.getDynamicUpdateList()).thenReturn(List.of(liveDynamic("1")));
        poll.run();
        assertEquals(List.of(), published, "默认不推开播动态");

        save(Map.of(PREFIX + "dynamic.push-live-dynamic", "true"));
        when(api.getDynamicUpdateList()).thenReturn(List.of(liveDynamic("2")));
        poll.run();
        assertEquals(1, published.size(), "打开之后的下一轮应推出这条开播动态");
        assertEquals("https://t.bilibili.com/2", published.get(0).getUrl());

        save(Map.of(PREFIX + "dynamic.push-live-dynamic", "false"));
        when(api.getDynamicUpdateList()).thenReturn(List.of(liveDynamic("3")));
        poll.run();
        assertEquals(1, published.size(), "关掉之后的下一轮不应再推");
    }

    // ============ 工具 ============

    private Runnable dynamicPoll(BilibiliApiUtil api, List<BilibiliDynamicUpdateEvent> published) {
        properties.getDynamic().setAutoFollow(false);
        BilibiliAccountService account = mock(BilibiliAccountService.class);
        when(account.isLoggedIn()).thenReturn(true);
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof BilibiliDynamicUpdateEvent update) published.add(update);
        };
        TaskScheduler scheduler = mock(TaskScheduler.class);
        PushUser user = new PushUser();
        user.setUid(UID);
        user.setUname("测试UP主");
        user.setPlatform(BilibiliPlatform.BILIBILI.id());
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers(anyString())).thenReturn(List.of(user));

        BilibiliDynamicService service = new BilibiliDynamicService(api, account, properties, publisher, scheduler,
                mock(BilibiliDynamicArchive.class));
        service.start(dataSource);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(task.capture(), any(Duration.class));
        return task.getValue();
    }

    private static Dynamic liveDynamic(String id) {
        Dynamic dynamic = new Dynamic();
        dynamic.setId(id);
        dynamic.setType("DYNAMIC_TYPE_LIVE_RCMD");
        JSONObject author = new JSONObject();
        author.put("mid", UID);
        author.put("name", "测试UP主");
        author.put("pub_ts", Instant.now().getEpochSecond());
        JSONObject modules = new JSONObject();
        modules.put("module_author", author);
        dynamic.setModules(modules);
        return dynamic;
    }

    /**
     * 照保存口的两步：先问取值合不合规矩，有问题就整批不动；没问题再交给套用器
     */
    private Saved save(Map<String, String> changes) {
        Map<String, String> toValidate = new LinkedHashMap<>(changes);
        toValidate.values().removeIf(java.util.Objects::isNull);
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
     */
    private static NovaBilibiliProperties boundFrom(Map<String, String> values) {
        NovaBilibiliProperties bound = new NovaBilibiliProperties();
        Map<String, Object> source = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value.contains("\n")) {
                String[] items = value.split("\n");
                for (int i = 0; i < items.length; i++) source.put(key + "[" + i + "]", items[i]);
            } else {
                source.put(key, value);
            }
        });
        new Binder(new MapConfigurationPropertySource(source))
                .bind("novabot.bilibili", org.springframework.boot.context.properties.bind.Bindable.ofInstance(bound));
        return bound;
    }

    /**
     * 照配置键在配置对象上取值：去掉前缀，各段由连字符写法换成驼峰
     */
    private static Object runtimeValue(NovaBilibiliProperties target, String key) {
        String path = Stream.of(key.substring(PREFIX.length()).split("\\."))
                .map(BilibiliSettingsSaveTest::camel).collect(Collectors.joining("."));
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
