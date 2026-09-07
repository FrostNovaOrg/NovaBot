package com.starlwr.bot.console.controller;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.datasource.DataSourceServiceRegistry;
import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.model.StreamerReference;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>
 * 这里量的是查主播口收到「uid 还是直播间号」之后怎么走，因此假平台连域名一起自带：
 * 链接由平台自己认，本口不认识任何一家真平台的域名。
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
    @DisplayName("空输入同时提到直播间号与直播间链接")
    void blankInputMentionsRoomNumberAndLiveLink() {
        assertBlankInputCopy(controller().lookupStreamer(request("")));
    }

    @Test
    @DisplayName("只填空白时同样提到直播间号与直播间链接")
    void whitespaceInputMentionsRoomNumberAndLiveLink() {
        assertBlankInputCopy(controller().lookupStreamer(request("   ")));
    }

    @Test
    @DisplayName("请求里没有 uid 时同样提到直播间号与直播间链接")
    void missingUidMentionsRoomNumberAndLiveLink() {
        JSONObject body = new JSONObject();
        body.put("platform", "fakelive");
        assertBlankInputCopy(controller().lookupStreamer(body));
    }

    private static void assertBlankInputCopy(JSONObject result) {
        assertFalse(result.getBooleanValue("success"), result.toString());
        String message = result.getString("message");
        assertTrue(message.contains("直播间号"), "空输入句须提到直播间号: " + message);
        assertTrue(message.contains("直播间链接"), "空输入句须提到直播间链接: " + message);
    }

    @Test
    @DisplayName("短号命中：先按 uid 查不到，再按房间号查到")
    void shortRoomNumberHitsAfterUidMiss() {
        FakeRooms rooms = FakeRooms.roomOnly(222L, 1001L, "主播甲");
        when(registry.getDataSourceService("fakelive")).thenReturn(Optional.of(rooms));

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
        when(registry.getDataSourceService("fakelive")).thenReturn(Optional.of(rooms));

        JSONObject result = controller().lookupStreamer(request("https://live.fake.test/222"));

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
        when(registry.getDataSourceService("fakelive")).thenReturn(Optional.of(rooms));

        JSONObject result = controller().lookupStreamer(request("999"));

        assertFalse(result.getBooleanValue("success"));
        String message = result.getString("message");
        assertTrue(message.contains(NOT_FOUND_HINT), message);
        assertTrue(message.contains("未查到 uid 999"), message);
        assertTrue(message.contains("请确认 uid 是否正确"), message);
        assertEquals(1, rooms.uidCalls.get());
        assertEquals(1, rooms.roomCalls.get(), "落空后只再查房间号一次");
    }

    @Test
    @DisplayName("空间链接找不到：原句仍称 uid")
    void missingSpaceLinkKeepsUidCopy() {
        FakeRooms rooms = FakeRooms.empty();
        when(registry.getDataSourceService("fakelive")).thenReturn(Optional.of(rooms));

        JSONObject result = controller().lookupStreamer(request("https://space.fake.test/999"));

        assertFalse(result.getBooleanValue("success"));
        String message = result.getString("message");
        assertTrue(message.contains("未查到 uid 999"), message);
        assertTrue(message.contains("请确认 uid 是否正确"), message);
        assertFalse(message.contains("直播间号"), "uid 输入不该改口成直播间号: " + message);
        assertEquals(1, rooms.uidCalls.get());
        assertEquals(0, rooms.roomCalls.get(), "空间链接已经标明是 uid，不打房间号");
    }

    @Test
    @DisplayName("直播间链接找不到：改称直播间号")
    void missingLiveLinkUsesRoomCopy() {
        FakeRooms rooms = FakeRooms.empty();
        when(registry.getDataSourceService("fakelive")).thenReturn(Optional.of(rooms));

        JSONObject result = controller().lookupStreamer(request("https://live.fake.test/999"));

        assertFalse(result.getBooleanValue("success"));
        String message = result.getString("message");
        assertTrue(message.contains("未查到直播间号 999"), message);
        assertTrue(message.contains("请确认直播间号是否正确"), message);
        assertFalse(message.contains("uid"), "直播间链接不该仍称 uid: " + message);
        assertEquals(0, rooms.uidCalls.get(), "链接已经标明是房间号，不按 uid 打");
        assertEquals(1, rooms.roomCalls.get());
    }

    @Test
    @DisplayName("uid 优先：数字既能当 uid 又能当房间号时，只按 uid 返回，不打房间号")
    void numericUidWinsOverRoomNumber() {
        FakeRooms rooms = FakeRooms.uidAndRoom(1001L, "主播甲", 222L, 9L, "别人");
        when(registry.getDataSourceService("fakelive")).thenReturn(Optional.of(rooms));

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

        /**
         * 本平台的两种链接：直播间链接给房间号，空间链接给 uid
         */
        @Override
        public Optional<StreamerReference> parseStreamerLink(String text) {
            Matcher live = Pattern.compile("live\\.fake\\.test/(\\d{1,19})").matcher(text);
            if (live.find()) {
                return Optional.of(StreamerReference.roomId(Long.parseLong(live.group(1))));
            }
            Matcher space = Pattern.compile("space\\.fake\\.test/(\\d{1,19})").matcher(text);
            if (space.find()) {
                return Optional.of(StreamerReference.uid(Long.parseLong(space.group(1))));
            }
            return Optional.empty();
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
        body.put("platform", "fakelive");
        body.put("uid", uid);
        return body;
    }

    private StreamerLookupController controller() {
        return new StreamerLookupController(registry);
    }

}
