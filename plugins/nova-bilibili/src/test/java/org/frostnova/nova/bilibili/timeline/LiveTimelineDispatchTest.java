package org.frostnova.nova.bilibili.timeline;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOnEvent;
import org.frostnova.nova.core.config.EventConfig;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.TimelineController;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.timeline.TimelineCategory;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开播记事走容器里的事件派发
 * <p>
 * 直接调用记事方法时，主播名写错栏、以及旁边一步抛了异常把后面的监听一起停掉，
 * 这两件事都看不出来：方法被叫到了，名字落在哪一栏、有没有被一起停掉，调用点都分不清。
 * 这里让容器自己派发，再按日志页那个接口去筛。
 */
@DisplayName("开播记事走事件派发")
class LiveTimelineDispatchTest {
    @TempDir
    Path dir;

    private AnnotationConfigApplicationContext context;

    private TimelineController controller;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("按主播筛日志，找得到这次开播，主播名不在通道栏")
    void streamerFilterFindsLiveOn() {
        open(false);
        context.publishEvent(new BilibiliLiveOnEvent(new LiveStreamerInfo(10001L, "主播甲", 20002L)));

        JSONObject result = controller.timeline(null, false, TimelineCategory.LIVE.name(),
                TimelineEventType.LIVE_ON.name(), "主播甲", null, null, 0, null);
        assertTrue(result.getBooleanValue("success"), result.getString("message"));
        assertEquals(1, result.getIntValue("matched"), "按主播筛日志找不到这次开播: " + result);

        JSONObject row = result.getJSONArray("events").getJSONObject(0);
        assertEquals("主播甲", row.getString("streamer"), "主播名该在主播栏");
        assertNull(row.getString("channel"), "主播名不该出现在群和私聊那一栏");
    }

    @Test
    @DisplayName("同一开播事件里别的监听出错，直播记录里仍有这次开播")
    void siblingFailureStillLeavesLiveOn() {
        open(true);

        boolean siblingThrew = false;
        try {
            context.publishEvent(new BilibiliLiveOnEvent(new LiveStreamerInfo(10001L, "主播甲", 20002L)));
        } catch (RuntimeException ex) {
            siblingThrew = true;
        }
        assertTrue(siblingThrew, "旁边那一步没有出错，测不到被一起停掉");

        JSONObject result = controller.timeline(null, false, TimelineCategory.LIVE.name(),
                TimelineEventType.LIVE_ON.name(), null, null, null, 0, null);
        assertTrue(result.getBooleanValue("success"), result.getString("message"));
        assertEquals(1, result.getIntValue("matched"), "别的环节出错后，直播记录里少了这次开播: " + result);
    }

    /**
     * 装上与运行时相同的派发器、时间线，以及开播记事。
     * @param withSibling 是否再装一个会抛异常、且排在推送那个位置的监听
     */
    private void open(boolean withSibling) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        TimelineStore store = new TimelineStore(properties);
        store.load();
        controller = new TimelineController(store);

        context = new AnnotationConfigApplicationContext();
        DefaultListableBeanFactory beans = context.getDefaultListableBeanFactory();
        beans.registerSingleton("timelineStore", store);
        context.register(EventConfig.class);
        registerAsComponentScanDoes(beans, BilibiliLiveTimelineRecorder.class);
        if (withSibling) {
            registerAsComponentScanDoes(beans, EarlierLiveOnFailure.class);
        }
        context.refresh();
    }

    /**
     * 照组件扫描那条路注册一个组件类
     */
    private static void registerAsComponentScanDoes(DefaultListableBeanFactory beans, Class<?> clazz) {
        AnnotatedGenericBeanDefinition definition = new AnnotatedGenericBeanDefinition(clazz);
        AnnotationConfigUtils.processCommonDefinitionAnnotations(definition);
        String name = AnnotationBeanNameGenerator.INSTANCE.generateBeanName(definition, beans);
        beans.registerBeanDefinition(name, definition);
    }

    /**
     * 排在与推送相同的位置，一出错就把还没跑到的监听停掉
     */
    static class EarlierLiveOnFailure {
        @Order(0)
        @EventListener
        public void onLiveOn(BilibiliLiveOnEvent event) {
            throw new IllegalStateException("旁边一步出错");
        }
    }
}
