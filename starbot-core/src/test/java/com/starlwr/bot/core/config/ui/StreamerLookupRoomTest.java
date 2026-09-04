package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.datasource.DataSourceServiceRegistry;
import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.sender.PushGate;
import com.starlwr.bot.core.service.DataSourceService;
import com.starlwr.bot.core.service.EventStreamTokenService;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.PushTemplateDefaults;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.timeline.TimelineStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 查主播口收直播间号
 * <p>
 * 除 uid 与空间链接外，短号与直播间链接也要能认出人。uid 与直播间号都像数字时
 * <b>先按 uid 查，查不到再按房间号查一次</b>——反过来会把一个真实存在的 uid
 * 误认成别人的短号。失败走原来那句「未查到」，不加重试。
 */
@DisplayName("查主播口收直播间号")
class StreamerLookupRoomTest {
    private static final String NOT_FOUND_HINT = "未查到";

    @TempDir
    Path dir;

    private StarBotCoreProperties properties;

    private DataSourceServiceRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        registry = mock(DataSourceServiceRegistry.class);
    }

    @Test
    @DisplayName("短号命中：先按 uid 查不到，再按房间号查到")
    void shortRoomNumberHitsAfterUidMiss() {
        FakeRooms rooms = FakeRooms.roomOnly(222L, 1001L, "主播甲");
        when(registry.getDataSourceService("bilibili")).thenReturn(Optional.of(rooms));

        JSONObject result = controller().lookupStreamer(request("222"));

        assertTrue(result.getBooleanValue("success"), result.toString());
        assertEquals(1001L, result.getLongValue("uid"));
        assertEquals("主播甲", result.getString("uname"));
        assertEquals(222L, result.getLongValue("roomId"));
        assertEquals(1, rooms.uidCalls.get(), "短号应先按 uid 走一趟");
        assertEquals(1, rooms.roomCalls.get(), "uid 落空后再按房间号走一趟");
    }

    @Test
    @DisplayName("直播间链接命中：只按房间号查一次，不走 uid")
    void liveRoomLinkHitsWithoutUidLookup() {
        FakeRooms rooms = FakeRooms.roomOnly(222L, 1001L, "主播甲");
        when(registry.getDataSourceService("bilibili")).thenReturn(Optional.of(rooms));

        JSONObject result = controller().lookupStreamer(request("https://live.bilibili.com/222"));

        assertTrue(result.getBooleanValue("success"), result.toString());
        assertEquals(1001L, result.getLongValue("uid"));
        assertEquals("主播甲", result.getString("uname"));
        assertEquals(0, rooms.uidCalls.get(), "链接已经标明是房间号，不该再按 uid 打一趟");
        assertEquals(1, rooms.roomCalls.get(), "房间号只查一次，不加重试");
    }

    @Test
    @DisplayName("找不到：短号两趟都落空，文案仍是原来那句，不加重试")
    void missingShortNumberKeepsExistingCopy() {
        FakeRooms rooms = FakeRooms.empty();
        when(registry.getDataSourceService("bilibili")).thenReturn(Optional.of(rooms));

        JSONObject result = controller().lookupStreamer(request("999"));

        assertFalse(result.getBooleanValue("success"));
        assertTrue(result.getString("message").contains(NOT_FOUND_HINT), result.getString("message"));
        assertEquals(1, rooms.uidCalls.get());
        assertEquals(1, rooms.roomCalls.get(), "落空后只再查房间号一次");
    }

    @Test
    @DisplayName("uid 优先：数字既能当 uid 又能当房间号时，只按 uid 返回，不打房间号")
    void numericUidWinsOverRoomNumber() {
        FakeRooms rooms = FakeRooms.uidAndRoom(1001L, "主播甲", 222L, 9L, "别人");
        when(registry.getDataSourceService("bilibili")).thenReturn(Optional.of(rooms));

        JSONObject result = controller().lookupStreamer(request("1001"));

        assertTrue(result.getBooleanValue("success"), result.toString());
        assertEquals(1001L, result.getLongValue("uid"));
        assertEquals("主播甲", result.getString("uname"));
        assertEquals(1, rooms.uidCalls.get());
        assertEquals(0, rooms.roomCalls.get(), "uid 已经命中，不该再按房间号查");
    }

    @Test
    @DisplayName("默认实现按房间号查不到，不是每个平台都有独立于 uid 的房间号")
    void defaultRoomLookupIsEmpty() {
        DataSourceService bare = user -> user.setUname("主播甲");
        assertTrue(bare.lookupByRoomId(222L).isEmpty(),
                "没实现的平台按房间号查应是空，而不是抛");
    }

    /**
     * 假接口：短号命中、链接命中、找不到、uid 优先，四格都走这里
     */
    private static final class FakeRooms implements DataSourceService {
        private final Long uidHit;
        private final String uidName;
        private final Long roomHit;
        private final Long roomUid;
        private final String roomName;
        private final AtomicInteger uidCalls = new AtomicInteger();
        private final AtomicInteger roomCalls = new AtomicInteger();

        private FakeRooms(Long uidHit, String uidName, Long roomHit, Long roomUid, String roomName) {
            this.uidHit = uidHit;
            this.uidName = uidName;
            this.roomHit = roomHit;
            this.roomUid = roomUid;
            this.roomName = roomName;
        }

        static FakeRooms empty() {
            return new FakeRooms(null, null, null, null, null);
        }

        static FakeRooms roomOnly(long roomId, long uid, String name) {
            return new FakeRooms(null, null, roomId, uid, name);
        }

        static FakeRooms uidAndRoom(long uid, String uidName, long roomId, long roomUid, String roomName) {
            return new FakeRooms(uid, uidName, roomId, roomUid, roomName);
        }

        @Override
        public void completePushUser(PushUser user) {
            uidCalls.incrementAndGet();
            if (user.getUid() != null && user.getUid().equals(uidHit)) {
                user.setUname(uidName);
                user.setRoomId(8888L);
            }
        }

        @Override
        public Optional<PushUser> lookupByRoomId(Long roomId) {
            roomCalls.incrementAndGet();
            if (roomId == null || !roomId.equals(roomHit)) {
                return Optional.empty();
            }
            PushUser user = new PushUser();
            user.setUid(roomUid);
            user.setUname(roomName);
            user.setRoomId(roomId);
            return Optional.of(user);
        }
    }

    private JSONObject request(String uid) {
        JSONObject body = new JSONObject();
        body.put("platform", "bilibili");
        body.put("uid", uid);
        return body;
    }

    @SuppressWarnings("unchecked")
    private ConfigUiController controller() {
        ObjectProvider<HealthProbe> healthProbes = mock(ObjectProvider.class);
        when(healthProbes.orderedStream()).thenAnswer(invocation -> java.util.stream.Stream.<HealthProbe>of());

        StarBotSenderService senders = mock(StarBotSenderService.class);
        when(senders.getSenderNames()).thenReturn(Set.of("默认"));

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of());

        return new ConfigUiController(
                mock(ConfigurationMetadataService.class),
                mock(ConfigurationFileService.class),
                properties,
                dataSource,
                healthProbes,
                mock(ConfigurationValidator.class),
                senders,
                mock(com.starlwr.bot.core.sender.StarBotMessageSender.class),
                mock(ObjectProvider.class),
                mock(com.starlwr.bot.core.health.PushActivityRecorder.class),
                mock(com.starlwr.bot.core.service.StarBotEventHandlerService.class),
                registry,
                mock(ConfigurationLevelResolver.class),
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
                mock(RuntimeConfigurationApplier.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                new EventStreamTokenService(properties.getLive()),
                mock(ObjectProvider.class),
                new PushGate(properties),
                mock(LiveDataService.class),
                mock(TimelineStore.class),
                mock(ConfigUiAuthService.class),
                new PushTemplateDefaults(new StarBotCoreProperties()));
    }
}
