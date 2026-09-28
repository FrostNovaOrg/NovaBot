package org.frostnova.nova.report.handler;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.enums.GuardOperateType;
import org.frostnova.nova.bilibili.event.live.BilibiliOfflineRewardDigestEvent;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.handler.NovaEventHandlerPushMessageInitializer;
import org.frostnova.nova.core.listener.NovaHandlerListener;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.NovaEventHandlerService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.painter.BilibiliOfflineRewardDigestPainter;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下播打赏播报处理器测试
 * <p>
 * 金额写不写跟随会话的「金额可见」，是这一类通知与其它几类最不一样的地方；
 * 静音与总开关不走处理器，与其它推送同一条路，这里只验它照样被拦下。
 */
@DisplayName("下播打赏播报处理器")
class BilibiliOfflineRewardDigestPushHandlerTest {
    private static final long STREAMER_UID = 10001L;

    private static final long ROOM_ID = 20002L;

    private BilibiliApiUtil api;

    private NovaMessageSender sender;

    private RevenueVisibilityService revenueVisibility;

    private BilibiliOfflineRewardDigestPainter painter;

    private BilibiliOfflineRewardDigestPushHandler handler;

    private NovaCommonPainterFactory factory;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        api = mock(BilibiliApiUtil.class);
        sender = mock(NovaMessageSender.class);
        revenueVisibility = mock(RevenueVisibilityService.class);

        // 昵称接口不可用时回退到事件携带的昵称，测试不关心接口路径
        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));

        factory = painterFactory();
        painter = new BilibiliOfflineRewardDigestPainter(factory, api, new NovaBilibiliProperties());
        handler = new BilibiliOfflineRewardDigestPushHandler(api, sender, revenueVisibility, painter);
    }

    @Test
    @DisplayName("默认模板只发一张图，群里不再收到文字感谢")
    void sendsImageByDefault() {
        // 抓的用户故障：群里收到的是一条文字感谢，没有图——默认模板换成只放图
        when(revenueVisibility.isVisible(anyString(), any(), anyLong())).thenReturn(true);

        handler.handle(digest(guardPerson("李四", 3, GuardOperateType.ACTIVATION, 198.0),
                giftPerson("张三", "心动盲盒", 2, 100.0)), pushMessage());

        List<String> sent = sentContents();
        assertEquals(1, sent.size(), "一阵一条");
        String content = sent.get(0);
        assertTrue(content.matches("\\{image_base64=[A-Za-z0-9+/=]+}"), "默认该是纯一张图: " + head(content));
        assertFalse(content.contains("感谢"), "不再发文字感谢: " + head(content));
    }

    @Test
    @DisplayName("画图失败时退回文字版，这一阵的播报不丢（金额可见带金额）")
    void fallsBackToTextWithAmountsWhenVisible() {
        // 抓的用户故障：图画不出来时这一阵的感谢整条消失——退路必须照发
        when(revenueVisibility.isVisible(anyString(), any(), anyLong())).thenReturn(true);
        BilibiliOfflineRewardDigestPushHandler failing = failingPainterHandler();

        failing.handle(digest(guardPerson("李四", 3, GuardOperateType.ACTIVATION, 198.0),
                giftPerson("张三", "心动盲盒", 2, 100.0)), pushMessage());

        assertEquals(List.of("感谢 李四 开通了舰长（¥198）、张三 送了 心动盲盒×2（合计 ¥100），主播甲 都收到啦"
                + "\n\n（播报图片绘制失败，本条为文字版）"), sentContents());
    }

    @Test
    @DisplayName("画图失败退回的文字版不带金额（金额不可见）")
    void fallsBackToTextWithoutAmountsWhenHidden() {
        // 抓的用户故障：金额不可见的群，退回文字版时把金额带了出来
        when(revenueVisibility.isVisible(anyString(), any(), anyLong())).thenReturn(false);
        BilibiliOfflineRewardDigestPushHandler failing = failingPainterHandler();

        failing.handle(digest(guardPerson("李四", 3, GuardOperateType.ACTIVATION, 198.0),
                giftPerson("张三", "心动盲盒", 2, 100.0)), pushMessage());

        assertEquals(List.of("感谢 李四 开通了舰长、张三 送了 心动盲盒×2，主播甲 都收到啦"
                + "\n\n（播报图片绘制失败，本条为文字版）"), sentContents());
    }

    @Test
    @DisplayName("文字退路上舰按等级与开通续费用词")
    void namesGuardLevelAndOperationOnFallback() {
        when(revenueVisibility.isVisible(anyString(), any(), anyLong())).thenReturn(false);
        BilibiliOfflineRewardDigestPushHandler failing = failingPainterHandler();

        failing.handle(digest(guardPerson("王五", 2, GuardOperateType.RENEWAL, 398.0)), pushMessage());
        failing.handle(digest(guardPerson("赵六", 1, GuardOperateType.ACTIVATION, 1998.0)), pushMessage());

        assertEquals(List.of(
                "感谢 王五 续费了提督，主播甲 都收到啦\n\n（播报图片绘制失败，本条为文字版）",
                "感谢 赵六 开通了总督，主播甲 都收到啦\n\n（播报图片绘制失败，本条为文字版）"), sentContents());
    }

    @Test
    @DisplayName("默认模板只剩 {picture}，不再预填文字")
    void defaultMessageIsPictureOnly() {
        JSONObject params = handler.getDefaultParams();
        assertEquals("{picture}", params.getString("message"), "默认只发图");
        assertFalse(params.getBooleanValue("at_all"), "默认不 @ 全体");
    }

    @Test
    @DisplayName("自己改过模板的群：{list} {uname} {url} 照旧能用，{picture} 放图")
    void customTemplateKeepsPlaceholdersAndAddsPicture() {
        // 抓的用户故障：处理器换成出图后，自己改过模板的群里那几句占位符失效了
        when(revenueVisibility.isVisible(anyString(), any(), anyLong())).thenReturn(true);

        PushMessage message = pushMessage();
        message.getParamsJsonObject().put("message", "{uname} 收到啦：{list}\n{url}{picture}");
        handler.handle(digest(guardPerson("李四", 3, GuardOperateType.ACTIVATION, 198.0)), message);

        String content = sentContents().get(0);
        assertTrue(content.startsWith("主播甲 收到啦：李四 开通了舰长（¥198）\nhttps://live.bilibili.com/20002{image_base64="),
                "占位符该照旧展开、图接在后面: " + head(content));
        assertTrue(content.endsWith("}"), "图占位符之后不该再有多余文字");
    }

    @Test
    @DisplayName("用旧默认模板的群自动换成新默认，自己改过的一个字不动")
    void oldDefaultMigratesToNewOne(@TempDir Path dir) {
        // 抓的用户故障：升级之后这一类通知还在发旧文字感谢——存着的旧默认值要跟着新默认走
        NovaEventHandlerPushMessageInitializer initializer = initializer(dir);

        PushMessage onOldDefault = rawPushMessage("{\"message\":\"感谢 {list}，{uname} 都收到啦\"}");
        assertTrue(initializer.initialize(onOldDefault));
        assertEquals("{picture}", onOldDefault.getParamsJsonObject().getString("message"), "旧默认该换成只发图");

        PushMessage custom = rawPushMessage("{\"message\":\"自己写的 {list}\"}");
        assertTrue(initializer.initialize(custom));
        assertEquals("自己写的 {list}", custom.getParamsJsonObject().getString("message"), "改过的模板不许被迁");
    }

    @Test
    @DisplayName("配置里写着旧类名照样认得这个处理器")
    void legacyClassNameStillResolves(@TempDir Path dir) {
        // 抓的用户故障：处理器搬进报告插件后，配置里还是旧类名的那一条推送悄悄停了
        NovaEventHandlerPushMessageInitializer initializer = initializer(dir);

        PushMessage message = rawPushMessage("{\"message\":\"感谢 {list}，{uname} 都收到啦\"}");
        assertTrue(initializer.initialize(message), "旧类名该认得出来");
        assertSame(handler, message.getHandlerInstance());
        assertEquals(BilibiliOfflineRewardDigestEvent.class, message.getEventClass());
    }

    @Test
    @DisplayName("静音时段这一类通知照样被丢弃，不发也不出声")
    void droppedDuringQuietHours() {
        LocalTime now = LocalTime.now();
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPush().setQuietStart(now.minusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")));
        properties.getPush().setQuietEnd(now.plusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")));

        List<TimelineEvent> timeline = new ArrayList<>();
        NovaHandlerListener listener = listener(properties, timeline::add);

        listener.onNovaExternalBaseEvent(digest(guardPerson("李四", 3, GuardOperateType.ACTIVATION, 198.0)));

        verify(sender, never()).send(any());
        assertEquals(1, timeline.size(), "丢弃在分发那一层记一条");
        assertEquals(TimelineEventType.PUSH_MUTED, timeline.get(0).type());
    }

    // —— 以下为夹具 ——

    private static String head(String content) {
        return content.substring(0, Math.min(120, content.length()));
    }

    /**
     * 画图必败的处理器：专验退回文字版那一路，文字版本身走真实实现
     */
    private BilibiliOfflineRewardDigestPushHandler failingPainterHandler() {
        BilibiliOfflineRewardDigestPainter failing = new BilibiliOfflineRewardDigestPainter(
                factory, api, new NovaBilibiliProperties()) {
            @Override
            public Optional<String> paint(BilibiliOfflineRewardDigestEvent event, boolean showRevenue) {
                return Optional.empty();
            }
        };
        return new BilibiliOfflineRewardDigestPushHandler(api, sender, revenueVisibility, failing);
    }

    /**
     * 画图那一路与下播报告绘制测试同一套桩：内置字体、占位头像、固定版本号
     */
    private NovaCommonPainterFactory painterFactory() {
        NovaCoreProperties coreProperties = new NovaCoreProperties();
        coreProperties.getPaint().getFonts().add("内置");
        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();
        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("group", "org.frostnova.nova");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");
        return new NovaCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);
    }

    /**
     * 一位上过舰的人。入参金额按元写，事件里按分记
     */
    private BilibiliOfflineRewardDigestEvent.Contribution guardPerson(String uname, int level,
                                                                      GuardOperateType operateType, double amountYuan) {
        BilibiliOfflineRewardDigestEvent.Contribution person = new BilibiliOfflineRewardDigestEvent.Contribution();
        person.setUid(50001L);
        person.setUname(uname);
        person.setGuardLevel(level);
        person.setOperateType(operateType);
        person.setGuardAmountFen(Math.round(amountYuan * 100));
        return person;
    }

    /**
     * 一位送过礼物的人。入参金额按元写，事件里按分记
     */
    private BilibiliOfflineRewardDigestEvent.Contribution giftPerson(String uname, String giftName,
                                                                     int count, double amountYuan) {
        long amountFen = Math.round(amountYuan * 100);
        BilibiliOfflineRewardDigestEvent.Contribution person = new BilibiliOfflineRewardDigestEvent.Contribution();
        person.setUid(50002L);
        person.setUname(uname);
        person.setGiftAmountFen(amountFen);
        person.setGifts(List.of(new BilibiliOfflineRewardDigestEvent.GiftLine(giftName, count, amountFen)));
        return person;
    }

    private BilibiliOfflineRewardDigestEvent digest(BilibiliOfflineRewardDigestEvent.Contribution... people) {
        return new BilibiliOfflineRewardDigestEvent(new LiveStreamerInfo(STREAMER_UID, "主播甲", ROOM_ID),
                List.of(people));
    }

    private PushMessage pushMessage() {
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(30003L);

        PushMessage message = new PushMessage();
        message.setTarget(target);
        message.setParamsJsonObject(handler.getDefaultParams());
        return message;
    }

    private List<String> sentContents() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender, atLeastOnce()).send(captor.capture());
        return captor.getAllValues().stream().map(Message::getContent).toList();
    }

    /**
     * 数据源里那一格的形状：处理器写的是旧类名，参数是整份存下来的
     */
    private PushMessage rawPushMessage(String params) {
        PushMessage message = new PushMessage();
        message.setHandler("org.frostnova.nova.bilibili.handler.BilibiliOfflineRewardDigestPushHandler");
        message.setParams(params);
        return message;
    }

    /**
     * 真实的认处理器与默认模板迁移那一层，处理器表里只挂这一个
     */
    private NovaEventHandlerPushMessageInitializer initializer(Path dir) {
        ApplicationContext context = mock(ApplicationContext.class);
        Map<String, NovaEventHandler> beans = Map.of(handler.getClass().getName(), handler);
        when(context.getBeansOfType(NovaEventHandler.class)).thenReturn(beans);
        NovaEventHandlerService service = new NovaEventHandlerService(context);
        service.onContextRefreshedEvent();

        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        return new NovaEventHandlerPushMessageInitializer(service, new PushTemplateDefaults(properties));
    }

    /**
     * 把处理器挂进分发那一层，走的是真闸门
     */
    private NovaHandlerListener listener(NovaCoreProperties properties, TimelineWriter timeline) {
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(30003L);

        PushMessage message = pushMessage();
        message.setHandlerInstance(handler);
        message.setEventClass(BilibiliOfflineRewardDigestEvent.class);
        target.setMessages(new ArrayList<>(List.of(message)));

        PushUser user = new PushUser();
        user.setPlatform("bilibili");
        user.setUid(STREAMER_UID);
        user.setUname("主播甲");
        user.setTargets(List.of(target));

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUser("bilibili", STREAMER_UID)).thenReturn(Optional.of(user));

        return new NovaHandlerListener(dataSource, new PushGate(properties), timeline);
    }
}
