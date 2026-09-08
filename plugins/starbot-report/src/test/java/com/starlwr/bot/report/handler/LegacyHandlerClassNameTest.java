package com.starlwr.bot.report.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.command.BilibiliAtNoticeKind;
import com.starlwr.bot.bilibili.command.BilibiliDynamicAtMeCommand;
import com.starlwr.bot.bilibili.command.BilibiliStreamerChoice;
import com.starlwr.bot.bilibili.handler.BilibiliLiveOnPushHandler;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.handler.NovaEventHandler;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.sender.NovaMessageSender;
import com.starlwr.bot.core.service.AtSubscriptionService;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.PushTemplateDefaults;
import com.starlwr.bot.core.service.RevenueVisibilityService;
import com.starlwr.bot.core.service.StarBotEventHandlerService;
import com.starlwr.bot.report.painter.BilibiliDynamicPainter;
import com.starlwr.bot.report.painter.BilibiliLiveReportPainter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 处理器搬了包，老配置里的旧全类名仍认得出
 * <p>
 * 两个推送处理器从 {@code com.starlwr.bot.bilibili.handler} 搬到了本模块。而这两串
 * <b>写在使用者的 datasource.json 里</b>，也写在 {@code template-defaults.json} 的键上——
 * 搬包搬不掉它们。核心按全类名精确认处理器，于是升级之后旧名一个也查不到：
 * 推送不发、自定义模板悄悄回到出厂默认，而<b>没有任何一处报错</b>。
 * <p>
 * 因此本类量的是「旧名仍认得出」这件事本身，而不是某个处理器的行为：
 * 阳的一侧是旧名查得到实例、旧名下的模板覆盖读得到；阴的一侧是主表里不许混进旧名、
 * 没人认领的名字仍然查不到——旧名回落若做成「查不到就随便给一个」，阳的那几条照样绿。
 */
@DisplayName("处理器旧全类名兼容")
class LegacyHandlerClassNameTest {
    /**
     * 搬包之前动态推送处理器的全类名，逐字取自 337 笔之前的源码路径
     */
    private static final String OLD_DYNAMIC = "com.starlwr.bot.bilibili.handler.BilibiliDynamicPushHandler";

    /**
     * 搬包之前下播报告推送处理器的全类名
     */
    private static final String OLD_REPORT = "com.starlwr.bot.bilibili.handler.BilibiliLiveReportPushHandler";

    @TempDir
    Path dir;

    private BilibiliDynamicPushHandler dynamic;

    private BilibiliLiveReportPushHandler report;

    @BeforeEach
    void setUp() {
        dynamic = new BilibiliDynamicPushHandler(mock(BilibiliApiUtil.class), mock(BilibiliDynamicPainter.class),
                mock(NovaMessageSender.class), mock(AtSubscriptionService.class), mock(LiveDataService.class));
        report = new BilibiliLiveReportPushHandler(mock(BilibiliApiUtil.class), mock(NovaMessageSender.class),
                mock(BilibiliLiveReportPainter.class), mock(RevenueVisibilityService.class));
    }

    /**
     * 按容器里真有这两个处理器的样子建一份处理器表
     */
    private StarBotEventHandlerService service() {
        Map<String, NovaEventHandler> beans = new LinkedHashMap<>();
        beans.put("bilibiliDynamicPushHandler", dynamic);
        beans.put("bilibiliLiveReportPushHandler", report);

        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeansOfType(NovaEventHandler.class)).thenReturn(beans);

        StarBotEventHandlerService service = new StarBotEventHandlerService(context);
        service.onContextRefreshedEvent();
        return service;
    }

    // ---------- 阳 ----------

    @Test
    @DisplayName("旧全类名仍取得到处理器")
    void legacyClassNameStillResolves() {
        StarBotEventHandlerService service = service();

        Optional<NovaEventHandler> byOldDynamic = service.getHandler(OLD_DYNAMIC);
        assertTrue(byOldDynamic.isPresent(), "老 datasource.json 里写的 " + OLD_DYNAMIC + " 查不到处理器, "
                + "升级后这一类推送不发也不报错");
        assertSame(dynamic, byOldDynamic.get());

        Optional<NovaEventHandler> byOldReport = service.getHandler(OLD_REPORT);
        assertTrue(byOldReport.isPresent(), "老 datasource.json 里写的 " + OLD_REPORT + " 查不到处理器");
        assertSame(report, byOldReport.get());
    }

    @Test
    @DisplayName("新全类名照常取得到")
    void currentClassNameStillResolves() {
        StarBotEventHandlerService service = service();

        assertSame(dynamic, service.getHandler(BilibiliDynamicPushHandler.class.getName()).orElse(null));
        assertSame(report, service.getHandler(BilibiliLiveReportPushHandler.class.getName()).orElse(null));
    }

    @Test
    @DisplayName("同一个旧名连问两次, 只提醒一条")
    void warnsOncePerLegacyName() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(StarBotEventHandlerService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            StarBotEventHandlerService service = service();
            service.getHandler(OLD_DYNAMIC);
            service.getHandler(OLD_DYNAMIC);

            List<String> warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains(OLD_DYNAMIC))
                    .toList();

            assertEquals(1, warnings.size(), "旧名每读一次就刷一条, 推送一开日志就被这条刷满: " + warnings);
            assertTrue(warnings.get(0).contains(BilibiliDynamicPushHandler.class.getName()),
                    "提醒里要写清现在叫什么, 否则使用者无从改起: " + warnings.get(0));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("按旧名存的模板覆盖仍读得到")
    void legacyKeyedTemplateOverrideStillReads() throws Exception {
        writeTemplateDefaults(new JSONObject()
                .fluentPut(OLD_DYNAMIC, new JSONObject().fluentPut("message", "老用户改过的动态模板")));

        assertEquals("老用户改过的动态模板", templateDefaults().paramsOf(dynamic).getString("message"),
                "老 template-defaults.json 的键是旧全类名, 读不到等于使用者的自定义模板悄悄回到出厂默认");
    }

    @Test
    @DisplayName("改过默认再清空, 旧名那一份不许把它带回来")
    void savingClearsTheLegacyKeyedOverride() throws Exception {
        writeTemplateDefaults(new JSONObject()
                .fluentPut(OLD_DYNAMIC, new JSONObject().fluentPut("message", "老用户改过的动态模板")));

        PushTemplateDefaults defaults = templateDefaults();
        assertEquals(List.of(), defaults.save(dynamic, new JSONObject()), "空对象＝这一类整个回到出厂默认");

        assertEquals(dynamic.getDefaultParams().getString("message"),
                defaults.paramsOf(dynamic).getString("message"),
                "「恢复默认」之后读的一侧又回落到旧名那一份, 屏幕上显示已回到默认, 发出去的还是旧覆盖");
    }

    @Test
    @DisplayName("随包示例里的处理器名都在类路径上")
    void shippedExampleNamesAreLoadable() throws Exception {
        List<String> names = handlerNamesIn(shippedExample());
        assertFalse(names.isEmpty(), "示例里一个 handler 都没读出来, 这条判据等于没跑");

        List<String> missing = new ArrayList<>();
        for (String name : names) {
            try {
                Class.forName(name);
            } catch (ClassNotFoundException e) {
                missing.add(name);
            }
        }

        assertEquals(List.of(), missing, "随包示例里的处理器名在类路径上不存在, "
                + "照着示例装出来的新机器一样不工作");
    }

    /**
     * 「@我」订阅那张表在 bilibili 插件里，而它认领的处理器有一个住在本插件——
     * 那边<b>不许指名本插件的类</b>（边界尺格8），于是表里写的是那个处理器搬走之前的名字。
     * 两边对不对得上，只有在本模块量得到：这里 bilibili 与 report 都在类路径上。
     * <p>
     * 因此比的不是字面相等，而是「这一串认到的是不是那个处理器」——真类名或它声明的旧名，
     * 命中任意一个即可。表里写错一个字不会有任何报错，只是那一类通知的
     * 「本群是不是 @全体成员」永远判成没配过。
     */
    @Test
    @DisplayName("「@我」订阅认领的处理器名认得到真处理器")
    void atNoticeKindNamesResolveToTheRealHandlers() {
        assertTrue(namesOf(dynamic).contains(BilibiliAtNoticeKind.DYNAMIC.handlerName()),
                "动态通知认领的 " + BilibiliAtNoticeKind.DYNAMIC.handlerName()
                        + " 既不是动态推送处理器的真类名, 也不在它声明的旧名里: " + namesOf(dynamic));

        BilibiliLiveOnPushHandler liveOn = new BilibiliLiveOnPushHandler(mock(BilibiliApiUtil.class),
                mock(NovaMessageSender.class), mock(AtSubscriptionService.class), mock(LiveDataService.class));
        assertTrue(namesOf(liveOn).contains(BilibiliAtNoticeKind.LIVE.handlerName()),
                "开播通知认领的 " + BilibiliAtNoticeKind.LIVE.handlerName() + " 认不到开播推送处理器");
    }

    /**
     * 一个处理器认得的全部名字：真类名加它声明过的旧名
     */
    private static List<String> namesOf(NovaEventHandler handler) {
        List<String> names = new ArrayList<>();
        names.add(handler.getClass().getName());
        names.addAll(handler.legacyClassNames());
        return names;
    }

    /**
     * 「本群这类通知是不是配成了 @全体成员」这一问，三种写法都得答对
     * <p>
     * 这一问在 bilibili 插件里，而它认领的处理器住在本插件——两边只有在这里同时看得见，
     * 因此这条行为判据只能落在本模块。答错不会有任何报错：订阅命令照列、照办，
     * 而每次通知本来就 @ 到所有人，订阅了也没有任何效果。
     */
    @Test
    @DisplayName("动态通知配成 @全体成员: 新名、旧名、没解析出实例三种写法都认得出")
    void dynamicAtModeIsReadUnderBothNames() {
        String everyone = "本群动态通知会 @全体成员，不用单独订阅";

        assertEquals(everyone, atMeReply(BilibiliDynamicPushHandler.class.getName(), dynamic),
                "升级之后照新示例写的配置认不出来");
        assertEquals(everyone, atMeReply(OLD_DYNAMIC, dynamic),
                "老配置写的是旧名, 核心按别名解出的实例是新类, 两头对不上就一律读成「本群没配过」");
        assertEquals(everyone, atMeReply(OLD_DYNAMIC, null),
                "还没解析出实例时只剩配置里那一串, 也得认");
    }

    // ---------- 阴 ----------

    @Test
    @DisplayName("别的那一类通知不算数")
    void anotherNoticeKindDoesNotCount() {
        PushUser streamer = streamerWithDynamicPush(BilibiliAtNoticeKind.LIVE.handlerName(), null, "all_or_subscribers");
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers("bilibili")).thenReturn(List.of(streamer));

        BilibiliDynamicAtMeCommand command = new BilibiliDynamicAtMeCommand(dataSource,
                mock(BilibiliStreamerChoice.class), mock(AtSubscriptionService.class));

        assertEquals("", command.menuNote(atMeContext()),
                "认成本类的话, 开播那条推送的 @ 模式会被当成动态通知的, 说出来的话是错的");
    }

    @Test
    @DisplayName("没人认领的名字仍然查不到")
    void unknownNameStaysUnknown() {
        StarBotEventHandlerService service = service();

        assertTrue(service.getHandler("com.starlwr.bot.bilibili.handler.NeverExistedPushHandler").isEmpty(),
                "旧名回落若做成「查不到就随便给一个」, 写错的类名就再也拦不下来了");
        assertTrue(service.getHandler("com.example.NotExist").isEmpty());
    }

    @Test
    @DisplayName("主表里只放真类名")
    void registryHoldsRealClassNamesOnly() {
        StarBotEventHandlerService service = service();

        assertTrue(service.getRegisteredHandlerClasses().contains(BilibiliDynamicPushHandler.class.getName()));
        assertFalse(service.getRegisteredHandlerClasses().contains(OLD_DYNAMIC),
                "旧名混进主表, 配置界面的勾选项与随包示例就会把它当成一个还在的处理器接着发出去");
        assertFalse(service.getRegisteredHandlerClasses().contains(OLD_REPORT));
    }

    @Test
    @DisplayName("旧名在类路径上确已不存在")
    void legacyNamesAreReallyGone() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(OLD_DYNAMIC),
                "旧名还在类路径上的话, 本类量的就不是「搬走之后仍认得出」这件事");
        assertThrows(ClassNotFoundException.class, () -> Class.forName(OLD_REPORT));
    }

    // ---------- 零件 ----------

    private PushTemplateDefaults templateDefaults() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        return new PushTemplateDefaults(properties);
    }

    private void writeTemplateDefaults(JSONObject content) throws Exception {
        Files.writeString(dir.resolve("template-defaults.json"), content.toJSONString(), StandardCharsets.UTF_8);
    }

    /**
     * 随发行包交付的那份示例推送配置。测试的工作目录是模块目录，仓库根可能在上一级或再上一层；
     * 向上走到找到那份文件为止，找不到就当场红——找不到时悄悄跳过的判据等于没有这条判据
     */
    private static Path shippedExample() {
        String relative = "dist/templates/datasource.example.json";
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到发行包示例 " + relative + ", 工作目录: " + Path.of("").toAbsolutePath());
    }

    /**
     * 「动态@我」在这个群里说的那句话。推送里配的是 @全体成员，因此命令该直接答
     * 「不用单独订阅」——答不上来就说明这条推送没被认成动态通知
     */
    private String atMeReply(String handlerName, NovaEventHandler instance) {
        PushUser streamer = streamerWithDynamicPush(handlerName, instance, "all");
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers("bilibili")).thenReturn(List.of(streamer));

        // 认不出这条推送时命令不会早退，而是一路走到真去订阅那一步。订阅那一侧留着桩,
        // 是为了让这一格红在「答的话不对」上, 而不是红在半路一个看不出所以然的空指针上
        AtSubscriptionService subscriptions = mock(AtSubscriptionService.class);
        when(subscriptions.subscribe(anyString(), any(), any(), anyString(), any()))
                .thenReturn(AtSubscriptionService.Result.OK);

        BilibiliDynamicAtMeCommand command = new BilibiliDynamicAtMeCommand(dataSource,
                mock(BilibiliStreamerChoice.class), subscriptions);
        return command.execute(atMeContext()).content();
    }

    private static CommandContext atMeContext() {
        return new CommandContext("qq-onebot", PushTargetType.GROUP, 30003L, 2000000002L,
                "动态@我", List.of(), "动态@我");
    }

    /**
     * 一位主播，推到本群，一条动态推送
     * @param handlerName 推送配置里 {@code handler} 写的那一串
     * @param instance 核心解析出来的处理器实例，{@code null} 表示还没解析出来
     * @param atMode @ 谁那一档
     */
    private static PushUser streamerWithDynamicPush(String handlerName, NovaEventHandler instance, String atMode) {
        PushUser user = new PushUser();
        user.setUid(10001L);
        user.setUname("测试主播");
        user.setPlatform("bilibili");

        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(30003L);

        PushMessage message = new PushMessage();
        message.setHandler(handlerName);
        message.setHandlerInstance(instance);
        message.setParamsJsonObject(new JSONObject().fluentPut("at_mode", atMode));

        target.setMessages(new ArrayList<>(List.of(message)));
        user.setTargets(List.of(target));
        return user;
    }

    private static List<String> handlerNamesIn(Path file) throws Exception {
        JSONArray users = JSON.parseArray(Files.readString(file, StandardCharsets.UTF_8));
        List<String> names = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            JSONArray targets = users.getJSONObject(i).getJSONArray("targets");
            for (int j = 0; targets != null && j < targets.size(); j++) {
                JSONArray messages = targets.getJSONObject(j).getJSONArray("messages");
                for (int k = 0; messages != null && k < messages.size(); k++) {
                    String handler = messages.getJSONObject(k).getString("handler");
                    if (handler != null && !handler.isBlank()) {
                        names.add(handler);
                    }
                }
            }
        }
        return names;
    }
}
