package org.frostnova.nova.adapter.outbox;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.frostnova.nova.adapter.outbox.OutboxRecord.Segment;
import org.frostnova.nova.adapter.outbox.OutboxRecord.SegmentType;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.Message.PushKind;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.sender.AtAllPermissionResolver;
import org.frostnova.nova.core.sender.FirstPushTipService;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.AtAllQuotaService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.HttpUtil;
import org.frostnova.nova.core.util.MessageImageAddressPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 外发箱交给取件程序的图片地址：取件程序照地址取图时，是从取件那台设备所在的网络去取，
 * 所以和机器人插件过同一道检查
 */
@DisplayName("外发箱里的图片地址")
class OutboxImageAddressTest {
    private static final OutboxRecord.Streamer OWNER = new OutboxRecord.Streamer("live", 101L, "主播甲");

    @TempDir Path data;
    final NovaCoreProperties properties = new NovaCoreProperties();
    final NovaSenderService registry = new NovaSenderService(properties);
    final List<TimelineEvent> events = new CopyOnWriteArrayList<>();

    /** 不带核心那一件的插件装配 */
    ApplicationContextRunner withoutPolicy() {
        properties.getLive().setLiveDataPath(data.resolve("live.json").toString());
        return new ApplicationContextRunner().withUserConfiguration(OutboxPluginAutoConfiguration.class)
                .withBean(NovaCoreProperties.class, () -> properties)
                .withBean(NovaSenderService.class, () -> registry)
                .withBean(TimelineWriter.class, () -> events::add);
    }

    /** 带核心那一件（不联网解析），放行给定的网段 */
    ApplicationContextRunner withPolicy(String... allowedNetworks) {
        return withoutPolicy().withBean(MessageImageAddressPolicy.class, () -> OutboxImageHosts.policy(allowedNetworks));
    }

    /** 入箱一条，读回交给取件程序的那几段 */
    static List<Segment> stored(ApplicationContext context, String content) {
        var conversations = context.getBean(OutboxConversations.class);
        long id = conversations.list().isEmpty() ? conversations.create("早班", "", 0, 0).id()
                : conversations.list().get(0).id();
        var service = context.getBean(OutboxService.class);
        long record = service.enqueue(id, OWNER, PushKind.LIVE_ON, content).get(0).id();
        var rows = service.list(null, record - 1, 1, streamer -> true);
        assertEquals(record, rows.get(0).id());
        return rows.get(0).segments();
    }

    static List<Segment> text(String value) {
        return List.of(new Segment(SegmentType.TEXT, value));
    }

    @Test
    @DisplayName("指向本机、内网或解析不出的图片地址不交给取件程序，其余内容照常入箱，各记一条不带地址的警告")
    void doesNotHandInternalOrUnresolvedAddressesToThePickupProgram() {
        var blocked = List.of("http://127.0.0.1/a.png", "http://192.168.1.30/a.png?token=s3cr3t",
                "http://169.254.169.254/latest/meta-data/", "http://[::1]/a.png", "http://nas.lan/a.png",
                "http://2130706433/a.png", "http://10.0.0.8:8080/a.png");
        var unresolved = List.of("https://img.missing.example/a.png?token=s3cr3t");
        withPolicy().run(context -> {
            for (String url : blocked) {
                var warnings = warnings(() -> assertEquals(text("开播了快来看"),
                        stored(context, "开播了{image_url=" + url + "}快来看"), url));
                assertEquals(1, warnings.size(), url + " " + warnings);
                String warning = warnings.get(0);
                assertTrue(warning.contains("本机或内网"), warning);
                assertTrue(warning.contains("消息图片 · 允许的内网网段"), warning);
                assertNoAddress(warning, url);
            }
            for (String url : unresolved) {
                var warnings = warnings(() -> assertEquals(text("开播了快来看"),
                        stored(context, "开播了{image_url=" + url + "}快来看"), url));
                assertEquals(1, warnings.size(), url + " " + warnings);
                assertTrue(warnings.get(0).contains("解析不出"), warnings.get(0));
                assertNoAddress(warnings.get(0), url);
            }
        });
    }

    @Test
    @DisplayName("写法不对的图片地址（file:、ftp:、带 .. 段、读不出主机名）整个占位符原样留成文字段，不成图片段")
    void invalidAddressesStayText() {
        var invalid = List.of("file:///etc/passwd", "ftp://cdn.example/a.png", "FILE:/etc/hosts",
                "http://203.0.113.10/../../../../etc/x.png", "http://cdn.example/a/%2e%2e/b.png",
                "http:///a.png", "C:\\images\\a.png", "/opt/a.png");
        withPolicy().run(context -> {
            for (String url : invalid) {
                String placeholder = "{image_url=" + url + "}";
                assertEquals(text("开播了" + placeholder + "快来看"), stored(context, "开播了" + placeholder + "快来看"), url);
            }
        });
    }

    @Test
    @DisplayName("能取的图片地址照旧作为网址段交出，值保持原文")
    void publicAddressesArePassedThroughVerbatim() {
        var allowed = List.of("https://cdn.example/a.png?size=原图&w=1", "http://203.0.113.10/a.png",
                "https://[2001:db8::7]:8443/a.png", "https://cdn.example/a..b.png");
        withPolicy().run(context -> {
            for (String url : allowed) {
                assertEquals(List.of(new Segment(SegmentType.TEXT, "开播了"), new Segment(SegmentType.IMAGE_URL, url),
                        new Segment(SegmentType.TEXT, "快来看")), stored(context, "开播了{image_url=" + url + "}快来看"), url);
            }
        });
    }

    @Test
    @DisplayName("设置页里放行的网段同样生效：放行的那一台交出，同网段别的机器照样不交")
    void honoursTheConfiguredAllowedNetworks() {
        withPolicy("192.168.1.30").run(context -> {
            for (String url : List.of("http://192.168.1.30/a.png", "http://nas.lan:8080/a.png")) {
                assertEquals(List.of(new Segment(SegmentType.IMAGE_URL, url)), stored(context, "{image_url=" + url + "}"), url);
            }
            assertEquals(text("封面"), stored(context, "封面{image_url=http://192.168.1.31/a.png}"));
            assertEquals(text("封面"), stored(context, "封面{image_url=http://127.0.0.1/a.png}"));
        });
    }

    @Test
    @DisplayName("没装核心那一件时退到不放行任何内网网段")
    void fallsBackToNoAllowedNetworksWithoutTheCorePolicy() {
        withoutPolicy().run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(text("封面"), stored(context, "封面{image_url=http://192.168.1.30/a.png}"));
            assertEquals(text("封面"), stored(context, "封面{image_url=http://[fd00::1]/a.png}"));
        });
    }

    @Test
    @DisplayName("一条只有一张被拒图片的消息：沿用外发内容为空的处理，不入箱、不剥图重发，推送记录写明原因")
    void messageWithOnlyARefusedImageIsNotQueued() {
        HttpUtil http = mock(HttpUtil.class);
        withPolicy().withBean(NovaMessageSender.class, () -> new NovaMessageSender(http, registry,
                        new PushActivityRecorder(events::add), new PushGate(properties), events::add,
                        mock(AtAllQuotaService.class), new DefaultListableBeanFactory()
                        .getBeanProvider(AtAllPermissionResolver.class), mock(FirstPushTipService.class)))
                .run(context -> {
                    long num = context.getBean(OutboxConversations.class).create("早班", "", 0, 0).id();
                    var sender = context.getBean(NovaMessageSender.class);
                    try {
                        var channel = new PushTarget();
                        channel.setPlatform("outbox");
                        channel.setType(PushTargetType.GROUP);
                        channel.setNum(num);
                        var message = Message.create("outbox", PushTargetType.GROUP, num,
                                "{image_url=http://192.168.1.30/cover.png}").get(0);
                        message.setChannel(channel);
                        message.setPushOrigin(new Message.PushOrigin(PushKind.LIVE_ON, "live", 101L, "主播甲"));

                        var result = sender.sendNow(message);
                        assertNotEquals(0, result.getIntValue("code"), result.toJSONString());
                        assertEquals("外发内容为空", result.getString("message"));
                        assertTrue(result.getBooleanValue(Sender.LocalDelivery.SKIP_IMAGE_FALLBACK));
                        assertTrue(message.getFailureReason().contains("外发内容为空"), message.getFailureReason());
                        assertTrue(context.getBean(OutboxService.class).list(null, 0, 10, s -> true).isEmpty());
                        assertTrue(events.toString().contains("外发内容为空"), events.toString());
                        assertFalse(events.toString().contains("192.168.1.30"), events.toString());
                    } finally {
                        sender.shutdown();
                    }
                });
    }

    private static void assertNoAddress(String warning, String url) {
        assertFalse(warning.contains(url), "警告里不带地址: " + warning);
        String host = java.net.URI.create(url).getHost();
        assertFalse(warning.contains(host), "警告里不带主机: " + warning);
        assertFalse(warning.contains("s3cr3t"), "警告里不带令牌: " + warning);
    }

    /** 跑一段入箱，收下外发箱这期间记的警告原文 */
    private static List<String> warnings(Runnable work) {
        Logger logger = (Logger) LoggerFactory.getLogger("org.frostnova.nova.adapter.outbox");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            work.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
