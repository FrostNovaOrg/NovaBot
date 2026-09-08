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
import com.starlwr.bot.core.datasource.DataSourceService;
import com.starlwr.bot.core.protocol.EventStreamTokenService;
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
 * 直播链接由平台自己认
 * <p>
 * 核心不认识任何一家的域名：链接长什么样是平台的事，核心只收「uid 还是直播间号」这个结果。
 * 域名写死在核心里的那一版有一个安静的失败形态——装上第二个平台时，它的链接核心一条都不认得，
 * 而第一家的链接核心照单全收，于是那串数字会被拿去问一个根本不管这条链接的平台，
 * 而它很可能真的答得上来：房间号与 uid 是两套编号，同一个值在两边各有主。
 * <p>
 * 阳（本平台自报的域名认得出）与阴（没人认得的域名不查、认的时候炸了也不查）成对立。
 */
@DisplayName("直播链接由平台自己认")
class StreamerLinkPlatformTest {
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
    @DisplayName("阳：平台自己认得的直播间链接，只按房间号查一趟")
    void ownLiveLinkResolvesToRoomId() {
        FakeLive fake = register("fakelive", "fake.test");

        JSONObject result = controller().lookupStreamer(request("fakelive", "https://live.fake.test/222"));

        assertTrue(result.getBooleanValue("success"), result.toString());
        assertEquals(1001L, result.getLongValue("uid"));
        assertEquals(222L, result.getLongValue("roomId"));
        assertEquals(1, fake.roomCalls.get());
        assertEquals(0, fake.uidCalls.get(), "链接已经标明是房间号，不该再按 uid 打一趟");
    }

    @Test
    @DisplayName("阳：平台自己认得的空间链接，只按 uid 查一趟")
    void ownSpaceLinkResolvesToUid() {
        FakeLive fake = register("fakelive", "fake.test");

        JSONObject result = controller().lookupStreamer(request("fakelive", "https://space.fake.test/777?spm_id_from=333.1"));

        assertTrue(result.getBooleanValue("success"), result.toString());
        assertEquals(777L, result.getLongValue("uid"));
        assertEquals(1, fake.uidCalls.get());
        assertEquals(0, fake.roomCalls.get(), "链接已经标明是 uid，不打房间号");
    }

    @Test
    @DisplayName("阴：没有平台认得的链接不查，回一句「不认识」")
    void unknownLinkIsNotLookedUp() {
        FakeLive fake = register("fakelive", "fake.test");
        when(registry.platforms()).thenReturn(List.of("fakelive"));

        JSONObject result = controller().lookupStreamer(request("fakelive", "https://live.bilibili.com/222"));

        assertFalse(result.getBooleanValue("success"), result.toString());
        String message = result.getString("message");
        assertTrue(message.contains("不认识"), message);
        assertEquals(0, fake.roomCalls.get(), "没人认得的链接不该拿那串数字去查房间号");
        assertEquals(0, fake.uidCalls.get(), "没人认得的链接不该拿那串数字去查 uid");
    }

    @Test
    @DisplayName("阴：认链接时抛了异常，当作认不出，不是 500")
    void throwingParserIsTreatedAsUnknown() {
        FakeLive fake = new FakeLive("fake.test", true);
        when(registry.getDataSourceService("fakelive")).thenReturn(Optional.of(fake));
        when(registry.platforms()).thenReturn(List.of("fakelive"));

        JSONObject result = controller().lookupStreamer(request("fakelive", "https://live.fake.test/222"));

        assertFalse(result.getBooleanValue("success"), result.toString());
        assertTrue(result.getString("message").contains("不认识"), result.toString());
        assertEquals(0, fake.roomCalls.get());
        assertEquals(0, fake.uidCalls.get());
    }

    @Test
    @DisplayName("平台选错了：链接是另一家的，说的是「选错平台」而不是「不认识」")
    void linkOfAnotherPlatformSaysSo() {
        FakeLive chosen = register("fakelive", "fake.test");
        FakeLive other = register("otherlive", "other.test");
        when(registry.platforms()).thenReturn(List.of("fakelive", "otherlive"));

        JSONObject result = controller().lookupStreamer(request("fakelive", "https://live.other.test/222"));

        assertFalse(result.getBooleanValue("success"), result.toString());
        String message = result.getString("message");
        assertTrue(message.contains("不属于当前选择的平台"), message);
        assertFalse(message.contains("不认识"), "认得这条链接的平台就在这台实例上，不该说不认识: " + message);
        assertEquals(0, chosen.roomCalls.get() + chosen.uidCalls.get(), "不拿别家链接里的数字去查所选平台");
        assertEquals(0, other.roomCalls.get() + other.uidCalls.get(), "也不替使用者改查另一家");
    }

    @Test
    @DisplayName("纯数字不问平台：不认识链接的平台照样收短号")
    void plainDigitsNeedNoParser() {
        DataSourceService bare = new DataSourceService() {
            @Override
            public void completePushUser(PushUser user) {
                user.setUname("主播甲");
                user.setRoomId(8888L);
            }
        };
        when(registry.getDataSourceService("bareplatform")).thenReturn(Optional.of(bare));

        JSONObject result = controller().lookupStreamer(request("bareplatform", "1001"));

        assertTrue(result.getBooleanValue("success"), result.toString());
        assertEquals(1001L, result.getLongValue("uid"));
    }

    @Test
    @DisplayName("默认实现认不出任何链接，不是每个平台都有可粘贴的主播链接")
    void defaultLinkParserIsEmpty() {
        DataSourceService bare = user -> user.setUname("主播甲");
        assertTrue(bare.parseStreamerLink("https://live.fake.test/222").isEmpty(),
                "没实现的平台认链接应是空，而不是抛");
    }

    private FakeLive register(String platform, String domain) {
        FakeLive fake = new FakeLive(domain, false);
        when(registry.getDataSourceService(platform)).thenReturn(Optional.of(fake));
        return fake;
    }

    /**
     * 假平台：只认自己域名下的两种链接，不认别人的
     */
    private static final class FakeLive implements DataSourceService {
        private static final long ROOM_HIT = 222L;
        private static final long ROOM_UID = 1001L;

        private final Pattern liveUrl;
        private final Pattern spaceUrl;
        private final boolean throwOnParse;
        private final AtomicInteger uidCalls = new AtomicInteger();
        private final AtomicInteger roomCalls = new AtomicInteger();

        private FakeLive(String domain, boolean throwOnParse) {
            String quoted = Pattern.quote(domain);
            this.liveUrl = Pattern.compile("live\\." + quoted + "/(\\d{1,19})");
            this.spaceUrl = Pattern.compile("space\\." + quoted + "/(\\d{1,19})");
            this.throwOnParse = throwOnParse;
        }

        @Override
        public Optional<StreamerReference> parseStreamerLink(String text) {
            if (throwOnParse) {
                throw new IllegalStateException("认链接时炸了");
            }

            Matcher live = liveUrl.matcher(text);
            if (live.find()) {
                return Optional.of(StreamerReference.roomId(Long.parseLong(live.group(1))));
            }
            Matcher space = spaceUrl.matcher(text);
            if (space.find()) {
                return Optional.of(StreamerReference.uid(Long.parseLong(space.group(1))));
            }
            return Optional.empty();
        }

        @Override
        public void completePushUser(PushUser user) {
            uidCalls.incrementAndGet();
            user.setUname("主播甲");
            user.setRoomId(8888L);
        }

        @Override
        public Optional<PushUser> lookupByRoomId(Long roomId) {
            roomCalls.incrementAndGet();
            if (roomId == null || roomId != ROOM_HIT) {
                return Optional.empty();
            }
            PushUser user = new PushUser();
            user.setUid(ROOM_UID);
            user.setUname("主播乙");
            user.setRoomId(roomId);
            return Optional.of(user);
        }
    }

    private JSONObject request(String platform, String uid) {
        JSONObject body = new JSONObject();
        body.put("platform", platform);
        body.put("uid", uid);
        return body;
    }

    private StreamerLookupController controller() {
        return new StreamerLookupController(registry);
    }

}
