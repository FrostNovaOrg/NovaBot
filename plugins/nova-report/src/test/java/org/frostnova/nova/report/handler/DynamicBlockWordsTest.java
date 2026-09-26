package org.frostnova.nova.report.handler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import org.frostnova.nova.bilibili.model.Dynamic;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplier;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.report.painter.BilibiliDynamicPainter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 动态屏蔽词：正文、标题或转发原文里含任一词的动态不推
 *
 * <h2>每一格抓的是哪种故障</h2>
 * 屏蔽词出错的样子都很像：<b>该挡的没挡</b>，群里多了一条没人想看的动态。
 * 因此按「文字散在哪几处」分格——正文、标题、转发原文是三个取值口，
 * 少开一处的那一版，只有对着那一处写的格子红，其余几格全绿。
 * <p>
 * 「没配上就照推」与「改了名单要重启」也分开：前者的名单一配上就该挡，
 * 后者问的是换成新名单之后下一条就该按新的判。混成一格的话，红了说不清是没生效还是没读新的。
 *
 * <h2>名单怎么送进去</h2>
 * 判过滤的那几格用配置键绑出运行中的配置（手写 application.yml 填的就是这个键），
 * 判「保存后立刻生效」的那一格走设置页保存的同一条通道，两下不互相担保。
 */
@DisplayName("动态屏蔽词")
class DynamicBlockWordsTest {
    /**
     * 设置页上那一个名单的配置项名
     */
    private static final String KEY = "novabot.bilibili.dynamic.block-words";

    private static final long UID = 19604318752096L;

    private static final long ROOM_ID = 47615208934771L;

    private static final long GROUP = 38025617409263L;

    private static final long OTHER_GROUP = 38025617409264L;

    private static final String WORD = "直播回放";

    private static final String TEMPLATE = "{uname} {action} {url}";

    private BilibiliApiUtil api;

    private BilibiliDynamicPainter painter;

    private NovaMessageSender sender;

    private AtSubscriptionService subscriptions;

    private LiveDataService liveDataService;

    private final List<TimelineEvent> timeline = new ArrayList<>();

    private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();

    private final ListAppender<ILoggingEvent> logLines = new ListAppender<>();

    @BeforeEach
    void setUp() {
        api = mock(BilibiliApiUtil.class);
        painter = mock(BilibiliDynamicPainter.class);
        sender = mock(NovaMessageSender.class);
        subscriptions = mock(AtSubscriptionService.class);
        liveDataService = mock(LiveDataService.class);

        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));
        when(subscriptions.list(anyString(), anyLong(), anyLong(), anyString())).thenReturn(List.of());
        when(painter.paint(any())).thenReturn(Optional.empty());

        Logger logger = (Logger) LoggerFactory.getLogger(BilibiliDynamicPushHandler.class);
        logger.setLevel(Level.INFO);
        logLines.start();
        logger.addAppender(logLines);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(BilibiliDynamicPushHandler.class)).detachAppender(logLines);
        for (AnnotationConfigApplicationContext context : contexts) {
            context.close();
        }
        contexts.clear();
        timeline.clear();
    }

    @Test
    @DisplayName("🔴 正文里写着屏蔽词的动态不该推出去")
    void bodyWordStopsThePush() {
        BilibiliDynamicPushHandler handler = handlerUsing(propertiesBoundWith(KEY, WORD));

        handler.handle(event(dynamicWithText(WORD + "，点这里看回放")), messageTo(GROUP));

        assertTrue(sentMessages().isEmpty(), "配了屏蔽词「" + WORD + "」，正文含这个词的动态照样推出去了");
    }

    @Test
    @DisplayName("🔴 视频标题里写着屏蔽词的动态不该推出去")
    void titleWordStopsThePush() {
        BilibiliDynamicPushHandler handler = handlerUsing(propertiesBoundWith(KEY, WORD));

        handler.handle(event(dynamicWithTitle(WORD + " 第 12 期")), messageTo(GROUP));

        assertTrue(sentMessages().isEmpty(), "视频标题不在正文那一格里，只比正文时标题含「" + WORD + "」的动态照样推出去了");
    }

    @Test
    @DisplayName("🔴 转发动态的原文里写着屏蔽词的不该推出去")
    void originWordStopsThePush() {
        BilibiliDynamicPushHandler handler = handlerUsing(propertiesBoundWith(KEY, WORD));

        handler.handle(event(forwardOf(dynamicWithText("本期" + WORD + "已上传"))), messageTo(GROUP));

        assertTrue(sentMessages().isEmpty(), "转发动态自己的文字是转发评语，含词的是原文，这一条照样推出去了");
    }

    @Test
    @DisplayName("🔴 英文按词配、动态里是别的大小写，也该挡住")
    void englishIgnoresCase() {
        BilibiliDynamicPushHandler handler = handlerUsing(propertiesBoundWith(KEY, "LiveReplay"));

        handler.handle(event(dynamicWithText("今晚 LIVEREPLAY 开播")), messageTo(GROUP));

        assertTrue(sentMessages().isEmpty(), "屏蔽词配的是 LiveReplay，动态里写成别的大小写就没挡住");
    }

    @Test
    @DisplayName("🔴 挡下时日志里写清哪位主播、命中哪个词")
    void logsWhoAndWhichWord() {
        BilibiliDynamicPushHandler handler = handlerUsing(propertiesBoundWith(KEY, WORD));

        handler.handle(event(dynamicWithText("今晚发" + WORD)), messageTo(GROUP));

        assertTrue(sentMessages().isEmpty(), "这一格量的是挡下之后日志写了什么，没挡住就什么都没量到");
        // 别的行（比如取昵称失败）也会进这份采集，认的是这一行在不在，不是这一份有几行
        assertTrue(logMessages().contains("主播甲 的动态命中屏蔽词 " + WORD + ", 跳过推送"),
                "挡下时日志该写清哪位主播、命中哪个词——查「刚才那条为什么没推」就指着这一行。实际: " + logMessages());
    }

    @Test
    @DisplayName("🔴 改了名单要重启才生效")
    void newListTakesEffectWithoutRestart() {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        BilibiliDynamicPushHandler handler = handlerUsing(properties);

        // 设置页保存走的就是这条通道：先落盘，再把新值写回运行中的配置
        RuntimeConfigurationApplier applier = new RuntimeConfigurationApplier(
                new NovaCoreProperties(), null, provider(applierContributors(properties)), timeline::add);
        List<String> waitingForRestart = applier.applyAndTrack(Map.of(KEY, WORD));
        assertTrue(waitingForRestart.isEmpty(),
                "改了名单要重启才生效：保存后这项还欠重启，说明新名单压根没写回运行中的配置");

        handler.handle(event(dynamicWithText("今晚发" + WORD)), messageTo(GROUP));
        assertTrue(sentMessages().isEmpty(),
                "改了名单要重启才生效：名单已经写回运行中的配置，下一条含新词的动态还是推出去了");
    }

    @Test
    @DisplayName("🔴 同一条动态推给几个群，时间线上只记一条")
    void recordsOneTimelineEntryForManyTargets() {
        BilibiliDynamicPushHandler handler = handlerUsing(propertiesBoundWith(KEY, WORD));
        Dynamic dynamic = dynamicWithText("今晚发" + WORD);

        handler.handle(event(dynamic), messageTo(GROUP));
        handler.handle(event(dynamic), messageTo(OTHER_GROUP));

        assertTrue(sentMessages().isEmpty(), "这一格量的是没推出去之后记了几条，推出去了就什么都没量到");
        assertEquals(1, timeline.size(),
                "同一条动态推给几个群，时间线该只记一条——记几条就刷几行，而使用者只想知道「刚才那条没推」");
        TimelineEvent recorded = timeline.get(0);
        assertEquals("屏蔽词挡下", recorded.type().getDescription(), "记在时间线上的该是「屏蔽词挡下」这一类");
        assertEquals("主播甲", recorded.streamer(), "记下是哪位主播的动态");
        assertEquals(WORD, recorded.detail().get("word"), "记下命中的词");
        assertEquals(dynamic.getUrl(), recorded.detail().get("url"), "记下动态链接");
    }

    @Test
    @DisplayName("🔴 屏蔽词写在图文正文里，动态照样推出去")
    void summaryWordStillPushed() {
        BilibiliDynamicPushHandler handler = handlerUsing(propertiesBoundWith(KEY, WORD));

        handler.handle(event(opusDynamic(null, "今晚发" + WORD, null)), messageTo(GROUP));

        assertTrue(sentMessages().isEmpty(),
                "图文动态 desc 为 null，屏蔽词「" + WORD + "」写在 summary 正文里，这条照样推出去了");
    }

    @Test
    @DisplayName("🔴 外层 desc 没有这个词，词在 summary 正文里，照样推出去")
    void summaryWordIgnoredBesideDesc() {
        BilibiliDynamicPushHandler handler = handlerUsing(propertiesBoundWith(KEY, WORD));

        handler.handle(event(opusDynamic("今晚八点见", "正文里有" + WORD, null)), messageTo(GROUP));

        assertTrue(sentMessages().isEmpty(),
                "desc 里没有屏蔽词，summary 正文里有「" + WORD + "」，两处都该比，这条照样推出去了");
    }

    @Test
    @DisplayName("🔴 屏蔽词写在直播推荐标题里，动态照样推出去")
    void liveTitleWordStillPushed() {
        BilibiliDynamicPushHandler handler = handlerUsing(propertiesBoundWith(KEY, WORD));

        handler.handle(event(liveDynamic(WORD + " 今晚场")), messageTo(GROUP));

        assertTrue(sentMessages().isEmpty(),
                "直播推荐的标题在 content 里，屏蔽词「" + WORD + "」挡不住，这条照样推出去了");
    }

    @Test
    @DisplayName("🔴 类型不在白名单里的动态，仍记了屏蔽词挡下")
    void whitelistSkipStillRecordsBlockedWord() {
        BilibiliDynamicPushHandler handler = handlerUsing(propertiesBoundWith(KEY, WORD));
        PushMessage message = messageTo(GROUP);
        JSONArray whiteList = new JSONArray();
        whiteList.add("DYNAMIC_TYPE_AV");
        message.getParamsJsonObject().put("white_list", whiteList);

        handler.handle(event(dynamicWithText("今晚发" + WORD)), message);

        assertTrue(sentMessages().isEmpty(), "类型不在白名单里，这条本来就不会推");
        assertEquals(0, timeline.size(),
                "这条动态的类型不在白名单里，本来就不会推，时间线上却记了「屏蔽词挡下」。实际: " + timelineTexts());
        assertTrue(logMessages().stream().noneMatch(line -> line.contains("命中屏蔽词")),
                "类型名单已经跳过的动态，不该再记屏蔽词挡下。实际: " + logMessages());
    }

    @Test
    @DisplayName("🔴 类型在黑名单里的动态，仍记了屏蔽词挡下")
    void blacklistSkipStillRecordsBlockedWord() {
        BilibiliDynamicPushHandler handler = handlerUsing(propertiesBoundWith(KEY, WORD));
        PushMessage message = messageTo(GROUP);
        JSONArray blackList = new JSONArray();
        blackList.add("DYNAMIC_TYPE_DRAW");
        message.getParamsJsonObject().put("black_list", blackList);

        handler.handle(event(dynamicWithText("今晚发" + WORD)), message);

        assertTrue(sentMessages().isEmpty(), "类型在黑名单里，这条本来就不会推");
        assertEquals(0, timeline.size(),
                "这条动态的类型在黑名单里，本来就不会推，时间线上却记了「屏蔽词挡下」。实际: " + timelineTexts());
    }

    @Test
    @DisplayName("🔴 只推转发自己的，转发别人时仍记了屏蔽词挡下")
    void notSelfOriginStillRecordsBlockedWord() {
        BilibiliDynamicPushHandler handler = handlerUsing(propertiesBoundWith(KEY, WORD));
        PushMessage message = messageTo(GROUP);
        message.getParamsJsonObject().put("only_self_origin", true);

        handler.handle(event(forwardOf(dynamicWithText("别人的" + WORD))), message);

        assertTrue(sentMessages().isEmpty(), "转发的不是自己的动态，这条本来就不会推");
        assertEquals(0, timeline.size(),
                "这条是转发别人的动态，会话只要自己的转发，本来就不会推，时间线上却记了「屏蔽词挡下」。实际: " + timelineTexts());
    }

    @Test
    @DisplayName("不含词的动态照推（护栏）")
    void plainDynamicStillGoes() {
        BilibiliDynamicPushHandler handler = handlerUsing(propertiesBoundWith(KEY, WORD));

        handler.handle(event(dynamicWithText("今晚八点开播")), messageTo(GROUP));

        assertEquals(1, sentMessages().size(), "不含屏蔽词的动态一条都不该少推");
    }

    @Test
    @DisplayName("名单只写了空行时照推（护栏）")
    void emptyListChangesNothing() {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        BilibiliDynamicPushHandler handler = handlerUsing(properties);
        new RuntimeConfigurationApplier(new NovaCoreProperties(), null,
                provider(applierContributors(properties)), timeline::add).applyAndTrack(Map.of(KEY, "\n  \n"));

        handler.handle(event(dynamicWithText("今晚八点开播")), messageTo(GROUP));

        assertEquals(1, sentMessages().size(),
                "名单为空时推送该与没配这一项时完全一样——空行不许当成一个到处都命中的词");
    }

    /**
     * 用一个配置键绑出运行中的配置
     * <p>
     * 手写 application.yml 的人填的就是这个键；键名或字段名对不上时名单落不到配置对象上，
     * 含词的动态照样推——正是前几格要抓的。
     */
    private static NovaBilibiliProperties propertiesBoundWith(String key, String value) {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        new Binder(new MapConfigurationPropertySource(Map.of(key, value)))
                .bind("novabot.bilibili", Bindable.ofInstance(properties));
        return properties;
    }

    /**
     * 装出一个处理器：与生产同一条装配路（运行中的配置与时间线都是容器里那一份）
     * <p>
     * 不直接 new：new 出来的那个手里没有运行中的配置与时间线，
     * 「保存后立刻生效」与「只记一条时间线」两格就没法在这里量。
     */
    private BilibiliDynamicPushHandler handlerUsing(NovaBilibiliProperties properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        contexts.add(context);
        context.registerBean(BilibiliApiUtil.class, () -> api);
        context.registerBean(BilibiliDynamicPainter.class, () -> painter);
        context.registerBean(NovaMessageSender.class, () -> sender);
        context.registerBean(AtSubscriptionService.class, () -> subscriptions);
        context.registerBean(LiveDataService.class, () -> liveDataService);
        context.registerBean(NovaBilibiliProperties.class, () -> properties);
        context.registerBean(TimelineWriter.class, () -> timeline::add);
        context.register(BilibiliDynamicPushHandler.class);
        context.refresh();
        return context.getBean(BilibiliDynamicPushHandler.class);
    }

    /**
     * 即时生效的应用器：按类型从插件的配置包里扫出来
     * <p>
     * 与生产装配同一个口径（应用器是容器里的组件，按类型收），不按类名点名——
     * 点名的话改个类名这一格就红在「类不在了」上，而它要抓的是保存之后名单没有写回。
     */
    private static List<RuntimeConfigurationApplierContributor> applierContributors(
            NovaBilibiliProperties properties) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(RuntimeConfigurationApplierContributor.class));
        List<String> classNames = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("org.frostnova.nova.bilibili.config")) {
            classNames.add(candidate.getBeanClassName());
        }
        // 扫出来的是空表时下面每一格都会绿在「没人申报」上，那与真正要抓的毛病分不开
        assertTrue(classNames.size() >= 2, "扫到的即时生效应用器太少，这一格量的是空集: " + classNames);

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        try {
            context.getBeanFactory().registerSingleton("properties", properties);
            for (String className : classNames) {
                context.register(load(className));
            }
            context.refresh();
            return List.copyOf(context.getBeansOfType(RuntimeConfigurationApplierContributor.class).values());
        } finally {
            context.close();
        }
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("扫出来的应用器类不在类路径上: " + className, e);
        }
    }

    private static ObjectProvider<RuntimeConfigurationApplierContributor> provider(
            List<RuntimeConfigurationApplierContributor> contributors) {
        return new ObjectProvider<>() {
            @Override
            public RuntimeConfigurationApplierContributor getObject() {
                return contributors.get(0);
            }

            @Override
            public Stream<RuntimeConfigurationApplierContributor> stream() {
                return contributors.stream();
            }

            @Override
            public Stream<RuntimeConfigurationApplierContributor> orderedStream() {
                return contributors.stream();
            }
        };
    }

    /**
     * 只有正文的动态
     */
    private static Dynamic dynamicWithText(String text) {
        JSONObject desc = new JSONObject();
        desc.put("text", text);
        return dynamicOf("DYNAMIC_TYPE_DRAW", desc, null);
    }

    /**
     * 正文那一格是空的、标题在视频那一格的动态
     */
    private static Dynamic dynamicWithTitle(String title) {
        JSONObject archive = new JSONObject();
        archive.put("title", title);
        JSONObject major = new JSONObject();
        major.put("type", "MAJOR_TYPE_ARCHIVE");
        major.put("archive", archive);
        return dynamicOf("DYNAMIC_TYPE_AV", null, major);
    }

    /**
     * 转发动态：自己的文字是转发评语，内容在原文里
     */
    private static Dynamic forwardOf(Dynamic origin) {
        Dynamic dynamic = dynamicWithText("转发一下");
        dynamic.setType("DYNAMIC_TYPE_FORWARD");
        dynamic.setOrigin(origin);
        return dynamic;
    }

    /**
     * 图文动态：desc 可为 null，正文在 major.opus.summary.text，标题在 opus.title（可为 null）。
     */
    private static Dynamic opusDynamic(String descText, String summaryText, String title) {
        JSONObject opus = new JSONObject();
        JSONObject summary = new JSONObject();
        summary.put("text", summaryText);
        opus.put("summary", summary);
        opus.put("title", title);
        opus.put("pics", new JSONArray());
        JSONObject major = new JSONObject();
        major.put("type", "MAJOR_TYPE_OPUS");
        major.put("opus", opus);

        JSONObject desc = null;
        if (descText != null) {
            desc = new JSONObject();
            desc.put("text", descText);
        }
        return dynamicOf("DYNAMIC_TYPE_DRAW", desc, major);
    }

    /**
     * 直播推荐：标题在 live_rcmd.content 解析后的 live_play_info.title。
     */
    private static Dynamic liveDynamic(String title) {
        JSONObject info = new JSONObject();
        info.put("title", title);
        info.put("cover", "https://cover.example/live.jpg");
        JSONObject wrapped = new JSONObject();
        wrapped.put("live_play_info", info);
        JSONObject live = new JSONObject();
        live.put("content", wrapped.toJSONString());
        live.put("reserve_type", 0);
        JSONObject major = new JSONObject();
        major.put("type", "MAJOR_TYPE_LIVE_RCMD");
        major.put("live_rcmd", live);
        return dynamicOf("DYNAMIC_TYPE_LIVE_RCMD", null, major);
    }

    private static Dynamic dynamicOf(String type, JSONObject desc, JSONObject major) {
        JSONObject moduleDynamic = new JSONObject();
        if (desc != null) {
            moduleDynamic.put("desc", desc);
        }
        if (major != null) {
            moduleDynamic.put("major", major);
        }
        JSONObject modules = new JSONObject();
        modules.put("module_dynamic", moduleDynamic);

        Dynamic dynamic = new Dynamic();
        dynamic.setId("1");
        dynamic.setType(type);
        dynamic.setModules(modules);
        return dynamic;
    }

    private static BilibiliDynamicUpdateEvent event(Dynamic dynamic) {
        return new BilibiliDynamicUpdateEvent(
                new LiveStreamerInfo(UID, "主播甲", ROOM_ID), dynamic, "发布了动态", dynamic.getUrl());
    }

    private static PushMessage messageTo(long group) {
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(group);

        JSONObject params = new JSONObject();
        params.put("message", TEMPLATE);

        PushMessage message = new PushMessage();
        message.setTarget(target);
        message.setParamsJsonObject(params);
        return message;
    }

    private List<Message> sentMessages() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        // 一条都没发也要问得出「没发」：用 atLeastOnce 的话，零条时红在「没被调用」上，
        // 而那正是挡住之后该有的样子
        verify(sender, atMost(Integer.MAX_VALUE)).send(captor.capture());
        return captor.getAllValues();
    }

    private List<String> timelineTexts() {
        List<String> texts = new ArrayList<>();
        for (TimelineEvent event : timeline) {
            texts.add(event.type().getDescription() + " " + event.text());
        }
        return texts;
    }

    private List<String> logMessages() {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent event : logLines.list) {
            messages.add(event.getFormattedMessage());
        }
        return messages;
    }
}
