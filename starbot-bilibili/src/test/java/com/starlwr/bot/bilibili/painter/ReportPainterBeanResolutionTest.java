package com.starlwr.bot.bilibili.painter;

import com.starlwr.bot.bilibili.command.BilibiliLiveReportCommand;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.controller.BilibiliReportLayoutController;
import com.starlwr.bot.bilibili.handler.BilibiliLiveReportPushHandler;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveReportArchive;
import com.starlwr.bot.core.service.LiveRoomInfoHistory;
import com.starlwr.bot.core.service.RevenueVisibilityService;
import com.starlwr.bot.core.util.FontUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.core.io.DefaultResourceLoader;

import java.lang.reflect.Field;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 两个报告画手同在容器里时，注入点各拿到哪一个
 *
 * <h2>这一格补的是什么洞</h2>
 * 🔴 单元测试里每个类都是自己 {@code new} 出来的，谁也不经过容器。于是
 * <b>「全部判据绿」与「这个程序起得来」之间没有任何一处把对方钉住</b>——
 * 2026-09-04 实测到的形状：预览画手继承正式画手且两个都标了组件注解，
 * 容器里同一个类型就有了两个候选；按类型注入的地方要一个，容器不知道该给哪一个，
 * 上下文当场装不起来，进程退出。而那一刻整测是全绿的。
 *
 * <h2>为什么按插件加载器那条路注册</h2>
 * 这两个类在真实运行时不是被 Spring 扫进来的，而是由插件加载器读 jar 之后
 * 逐个建 {@link AnnotatedGenericBeanDefinition} 注册的。注解要不要生效、
 * 生效到哪一层，取决于走的是哪条注册路——所以这里照抄那条路，
 * 而不是图省事用 {@code context.register(...)}。
 *
 * <p>另一侧同样要钉住：预览那一屏必须仍然拿到<b>预览</b>画手。
 * 只钉「别再撞了」的话，把预览画手从容器里摘掉也能过——而那会让版式预览整屏消失。
 */
@DisplayName("报告画手的容器解析")
class ReportPainterBeanResolutionTest {
    private AnnotationConfigApplicationContext context;

    private DefaultListableBeanFactory beans;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        beans = context.getDefaultListableBeanFactory();

        StarBotCoreProperties coreProperties = new StarBotCoreProperties();
        // 用核心内置字体，免得结论取决于跑测试这台机器装了什么字体
        coreProperties.getPaint().getFonts().add("内置");
        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "5.1.0");
        buildInfo.setProperty("group", "com.starlwr");
        buildInfo.setProperty("artifact", "starbot-core");
        buildInfo.setProperty("name", "StarBotCore");

        // 画手与消费者的构造参数：这一格量的是「容器挑哪一个」，不是它们各自干得对不对，
        // 所以除画图必需的那几件外一律给替身
        beans.registerSingleton("starBotCommonPainterFactory",
                new StarBotCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil));
        beans.registerSingleton("fontUtil", fontUtil);
        beans.registerSingleton("starBotBilibiliProperties", new StarBotBilibiliProperties());
        beans.registerSingleton("bilibiliApiUtil", mock(BilibiliApiUtil.class));
        beans.registerSingleton("liveRoomInfoHistory", mock(LiveRoomInfoHistory.class));
        beans.registerSingleton("liveDataService", mock(LiveDataService.class));
        beans.registerSingleton("starBotMessageSender", mock(StarBotMessageSender.class));
        beans.registerSingleton("revenueVisibilityService", mock(RevenueVisibilityService.class));
        beans.registerSingleton("liveReportArchive", mock(LiveReportArchive.class));
        beans.registerSingleton("abstractDataSource", mock(AbstractDataSource.class));

        registerAsPluginLoaderDoes(BilibiliLiveReportPainter.class);
        registerAsPluginLoaderDoes(BilibiliLiveReportPreviewPainter.class);
        registerAsPluginLoaderDoes(BilibiliLiveReportPushHandler.class);
        registerAsPluginLoaderDoes(BilibiliLiveReportCommand.class);
        registerAsPluginLoaderDoes(BilibiliReportLayoutController.class);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("🔴 上下文装得起来：按类型注入的两处各凑得齐参数")
    void contextStartsWithBothPaintersPresent() {
        // 这一句本身就是判据：撞号那一版在这里抛
        // NoUniqueBeanDefinitionException（found 2: …PreviewPainter, …Painter），
        // 与产物起不来时控制台上那一段是同一句话
        context.refresh();

        assertEquals(2, beans.getBeanNamesForType(BilibiliLiveReportPainter.class).length,
                "容器里不再是两个候选了，这一格量的东西得跟着重判");
    }

    @Test
    @DisplayName("🔴 下播报告推送拿到的是正式画手，不是预览画手")
    void pushHandlerGetsTheRealPainter() {
        context.refresh();

        Object injected = field(BilibiliLiveReportPushHandler.class,
                context.getBean(BilibiliLiveReportPushHandler.class), "painter");

        assertSame(context.getBean("bilibiliLiveReportPainter"), injected,
                "推送处理器拿到的不是正式画手");
        assertFalse(injected instanceof BilibiliLiveReportPreviewPainter,
                "推送出去的报告成了夹具数据画的那一张：图上有主播的名字，数字却是编的");
    }

    @Test
    @DisplayName("🔴 「直播报告」命令拿到的也是正式画手")
    void reportCommandGetsTheRealPainter() {
        context.refresh();

        Object injected = field(BilibiliLiveReportCommand.class,
                context.getBean(BilibiliLiveReportCommand.class), "painter");

        assertSame(context.getBean("bilibiliLiveReportPainter"), injected,
                "命令拿到的不是正式画手");
        assertFalse(injected instanceof BilibiliLiveReportPreviewPainter,
                "命令回的报告成了夹具数据画的那一张");
    }

    @Test
    @DisplayName("🔴 版式预览那一屏仍拿到预览画手")
    void layoutPreviewStillGetsThePreviewPainter() {
        context.refresh();

        Object injected = field(BilibiliReportLayoutController.class,
                context.getBean(BilibiliReportLayoutController.class), "preview");

        assertSame(context.getBean("bilibiliLiveReportPreviewPainter"), injected,
                "预览口拿到的不是预览画手：那一屏会去打真接口，还会画出真主播的数据");
    }

    @Test
    @DisplayName("🔴 本修法的前提：@Primary 不随继承传给子类")
    void primaryIsNotInheritedBySubclass() {
        // 上面几格靠的是「两个候选里只有一个是首选」。若哪天 @Primary 变成可继承的，
        // 两个候选就都成了首选，上面几格会红——而红出来的话会指向注入点，
        // 与真正变了的那件事隔着好几层。这一格把前提单独量一次
        assertTrue(beans.getBeanDefinition("bilibiliLiveReportPainter").isPrimary(),
                "正式画手不是首选了");
        assertFalse(beans.getBeanDefinition("bilibiliLiveReportPreviewPainter").isPrimary(),
                "预览画手也成了首选：@Primary 已随继承传到子类，本修法不再成立");
    }

    /**
     * 照插件加载器那条路注册一个组件类
     */
    private void registerAsPluginLoaderDoes(Class<?> clazz) {
        AnnotatedGenericBeanDefinition definition = new AnnotatedGenericBeanDefinition(clazz);
        AnnotationConfigUtils.processCommonDefinitionAnnotations(definition);
        String name = AnnotationBeanNameGenerator.INSTANCE.generateBeanName(definition, beans);
        beans.registerBeanDefinition(name, definition);
    }

    private static Object field(Class<?> owner, Object target, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("取不到字段 " + owner.getSimpleName() + "." + name, e);
        }
    }
}
