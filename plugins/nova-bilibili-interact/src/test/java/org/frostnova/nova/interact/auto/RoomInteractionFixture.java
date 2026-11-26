package org.frostnova.nova.interact.auto;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.event.live.BilibiliFollowEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliPaidGiftEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliShareEvent;
import org.frostnova.nova.bilibili.service.BilibiliAccountService;
import org.frostnova.nova.bilibili.service.BilibiliPkOpponentService;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.GiftInfo;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.model.UserInfo;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.interact.config.InteractProperties;
import org.frostnova.nova.interact.danmaku.LiveDanmakuService;
import org.frostnova.nova.interact.speaker.BilibiliSpeakerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

abstract class RoomInteractionFixture {
    static final LiveStreamerInfo ROOM = new LiveStreamerInfo(101L, "示例主播", 501L);
    static final UserInfo MING = new UserInfo(1L, "小明");
    static final UserInfo HONG = new UserInfo(2L, "小红");
    static final UserInfo SELF = new UserInfo(7L, "发言账号");
    static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    Instant now = START;
    boolean living = true;
    int platformCode;
    final List<String> sent = new ArrayList<>();
    private final BilibiliSpeakerService speaker = mock(BilibiliSpeakerService.class);
    private final NovaCoreProperties core = new NovaCoreProperties();
    final InteractProperties properties = new InteractProperties();
    AnnotationConfigApplicationContext events;
    AutoDanmakuQueue queue;
    final BilibiliPkOpponentService opponents = mock(BilibiliPkOpponentService.class);
    InteractStore store;
    NovaStateStore state;
    long broadcastStart = START.toEpochMilli();
    @TempDir Path dir;

    @BeforeEach
    void start() {
        core.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        var live = mock(LiveDataService.class);
        when(live.getLiveStartTime("bilibili", 101L)).thenAnswer(i -> Optional.of(broadcastStart));
        when(live.getLiveStatus("bilibili", 101L)).thenAnswer(i -> Optional.of(living));
        when(speaker.uid()).thenReturn(Optional.of(7L));
        when(speaker.get(anyString(), isNull())).thenReturn(JSONObject.of("property", JSONObject.of("danmu", JSONObject.of("length", 50))));
        doAnswer(i -> {
            Map<?, ?> form = i.getArgument(1);
            sent.add((String) form.get("msg"));
            return JSONObject.of("code", platformCode);
        }).when(speaker).postOnce(anyString(), anyMap());
        var source = mock(AbstractDataSource.class);
        var user = new PushUser();
        user.setUid(101L);
        user.setRoomId(501L);
        user.setUname("示例主播");
        when(source.getUsers("bilibili")).thenReturn(List.of(user));
        var sender = new LiveDanmakuService(properties, speaker, mock(BilibiliAccountService.class),
                mock(BilibiliApiUtil.class), source, event -> {});
        ReflectionTestUtils.invokeMethod(sender, "setClock", (Supplier<Instant>) () -> now);
        state = new NovaStateStore(core);
        store = new InteractStore(state);
        queue = new AutoDanmakuQueue(sender, live, store, () -> now);
        sender.rooms().forEach(queue::register);
        events = new AnnotationConfigApplicationContext();
        events.registerBean(LiveInteractRuntime.class, () -> new LiveInteractRuntime(queue, store, sender, opponents));
        events.refresh();
    }

    @AfterEach
    void stop() {
        events.close();
    }

    void settings(boolean master, JSONObject thanks, String... enabled) {
        var features = new JSONObject();
        for (String key : enabled) features.put(key, true);
        queue.save(101, RoomSettings.parse(JSONObject.of("enabled", master, "features", features, "thanks", thanks)));
    }

    void at(int seconds) {
        now = START.plusSeconds(seconds);
    }

    void tick(int seconds) {
        at(seconds);
        queue.tick();
    }

    GiftInfo gift(String name, int count) {
        return new GiftInfo(null, name, 0.25, count, null);
    }

    void paid(UserInfo viewer, String name, int count) {
        events.publishEvent(new BilibiliPaidGiftEvent(ROOM, viewer, gift(name, count), 0.25 * count));
    }

    void follow(UserInfo viewer) {
        events.publishEvent(new BilibiliFollowEvent(ROOM, viewer));
    }

    void share(UserInfo viewer) {
        events.publishEvent(new BilibiliShareEvent(ROOM, viewer));
    }
}
