package org.frostnova.nova.core.sender;

import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.SessionKey;
import org.frostnova.nova.core.service.SessionQuietHoursService;
import org.frostnova.nova.core.service.SessionQuietHoursService.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 推送闸门按会话判静音
 * <p>
 * 会话那一档只决定拿哪一对起止去判，「在不在时段里」仍是闸门里那一份实现，
 * 所以这里不重测跨零点等规则（{@link PushGateTest} 管），只测三档各自拿的是哪一对。
 */
@DisplayName("推送闸门按会话判静音")
class SessionQuietGateTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long WORK = 10000001L;

    private static final Long FANS = 10000002L;

    private static final PushTargetType GROUP = PushTargetType.GROUP;

    @Test
    @DisplayName("会话设了自己的时段、全局没设：时段内拦、时段外放")
    void customWindowWithoutGlobal() {
        SessionQuietHoursService sessions = sessions();
        sessions.set(PLATFORM, GROUP, WORK, Mode.CUSTOM, "22:00", "07:00");
        PushGate gate = new PushGate(new NovaCoreProperties(), sessions);

        assertFalse(gate.allowedAt(LocalTime.of(23, 0), PLATFORM, GROUP, WORK), "自己的时段内应拦");
        assertFalse(gate.allowedAt(LocalTime.of(6, 59), PLATFORM, GROUP, WORK), "跨零点那一半同样拦");
        assertTrue(gate.allowedAt(LocalTime.of(12, 0), PLATFORM, GROUP, WORK), "时段外应放");
        assertTrue(gate.allowedAt(LocalTime.of(23, 0), PLATFORM, GROUP, FANS), "没设的会话跟全局，全局没设就放");
    }

    @Test
    @DisplayName("同号的群开了自己的静音时段：同号好友的推送照发，反过来也一样")
    void sameNumberGroupAndFriendAreSeparate() {
        // 用户故障：给群设了免打扰，同号好友的推送和命令回复也被静掉
        SessionQuietHoursService sessions = sessions();
        sessions.set(PLATFORM, GROUP, WORK, Mode.CUSTOM, "22:00", "07:00");
        sessions.set(PLATFORM, PushTargetType.FRIEND, FANS, Mode.CUSTOM, "22:00", "07:00");
        PushGate gate = new PushGate(new NovaCoreProperties(), sessions);

        assertFalse(gate.allowedAt(LocalTime.of(23, 0), PLATFORM, GROUP, WORK), "群自己的时段内应拦");
        assertTrue(gate.allowedAt(LocalTime.of(23, 0), PLATFORM, PushTargetType.FRIEND, WORK), "同号好友没设，跟全局放");
        assertFalse(gate.allowedAt(LocalTime.of(23, 0), PLATFORM, PushTargetType.FRIEND, FANS), "好友自己的时段内应拦");
        assertTrue(gate.allowedAt(LocalTime.of(23, 0), PLATFORM, GROUP, FANS), "同号群没设，跟全局放");
    }

    @Test
    @DisplayName("会话设「不静音」、全局正在静音：放")
    void offPassesDuringGlobalQuiet() {
        SessionQuietHoursService sessions = sessions();
        sessions.set(PLATFORM, GROUP, FANS, Mode.OFF, null, null);
        PushGate gate = new PushGate(quiet("23:00", "08:00"), sessions);

        assertTrue(gate.allowedAt(LocalTime.of(2, 0), PLATFORM, GROUP, FANS), "不静音的会话照发");
        assertFalse(gate.allowedAt(LocalTime.of(2, 0), PLATFORM, GROUP, WORK), "跟全局的会话照旧拦");
    }

    @Test
    @DisplayName("会话自己的时段替掉全局那一对，而不是两份叠加")
    void customReplacesGlobal() {
        SessionQuietHoursService sessions = sessions();
        sessions.set(PLATFORM, GROUP, WORK, Mode.CUSTOM, "12:00", "14:00");
        PushGate gate = new PushGate(quiet("23:00", "08:00"), sessions);

        assertTrue(gate.allowedAt(LocalTime.of(2, 0), PLATFORM, GROUP, WORK), "全局的时段不再管这个会话");
        assertFalse(gate.allowedAt(LocalTime.of(13, 0), PLATFORM, GROUP, WORK));
    }

    @Test
    @DisplayName("跟全局：与只有全局那一项时一模一样")
    void followMatchesGlobal() {
        NovaCoreProperties properties = quiet("23:00", "08:00");
        PushGate global = new PushGate(properties);
        PushGate perSession = new PushGate(properties, sessions());

        for (int hour = 0; hour < 24; hour++) {
            LocalTime at = LocalTime.of(hour, 30);
            assertEquals(global.allowedAt(at), perSession.allowedAt(at, PLATFORM, GROUP, WORK), "时刻 " + at);
        }
    }

    @Test
    @DisplayName("会话的时段起止相同或格式不对：视为没设，与全局那项同一规则")
    void customFollowsSameRules() {
        SessionQuietHoursService sessions = sessions();
        sessions.set(PLATFORM, GROUP, WORK, Mode.CUSTOM, "09:00", "09:00");
        sessions.set(PLATFORM, GROUP, FANS, Mode.CUSTOM, "晚上", "早上");
        PushGate gate = new PushGate(quiet("00:00", "23:59"), sessions);

        assertTrue(gate.allowedAt(LocalTime.of(9, 0), PLATFORM, GROUP, WORK));
        assertTrue(gate.allowedAt(LocalTime.of(3, 0), PLATFORM, GROUP, FANS));
    }

    @Test
    @DisplayName("全局开关关着：三档一律拦")
    void masterSwitchBlocksEveryMode() {
        SessionQuietHoursService sessions = sessions();
        sessions.set(PLATFORM, GROUP, WORK, Mode.OFF, null, null);
        sessions.set(PLATFORM, GROUP, FANS, Mode.CUSTOM, "12:00", "14:00");
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPush().setEnabled(false);
        PushGate gate = new PushGate(properties, sessions);

        assertFalse(gate.allowedAt(LocalTime.of(3, 0), PLATFORM, GROUP, WORK));
        assertFalse(gate.allowedAt(LocalTime.of(3, 0), PLATFORM, GROUP, FANS));
        assertFalse(gate.allowedAt(LocalTime.of(3, 0), PLATFORM, GROUP, 404L));
        assertEquals(PushGate.Block.DISABLED, gate.blockedBy());
    }

    @Test
    @DisplayName("告警不受会话那一档影响")
    void alertsIgnoreSessionMode() {
        SessionQuietHoursService sessions = sessions();
        sessions.set(PLATFORM, GROUP, WORK, Mode.CUSTOM, "00:00", "23:59");
        assertTrue(new PushGate(new NovaCoreProperties(), sessions).alertsAllowed());

        NovaCoreProperties off = new NovaCoreProperties();
        off.getPush().setEnabled(false);
        sessions.set(PLATFORM, GROUP, WORK, Mode.OFF, null, null);
        assertFalse(new PushGate(off, sessions).alertsAllowed(), "全局开关关着时告警照拦，「不静音」不是例外");
    }

    @Test
    @DisplayName("设完立即生效，不用重建闸门")
    void takesEffectImmediately() {
        SessionQuietHoursService sessions = sessions();
        PushGate gate = new PushGate(quiet("23:00", "08:00"), sessions);
        assertFalse(gate.allowedAt(LocalTime.of(2, 0), PLATFORM, GROUP, FANS));

        sessions.set(PLATFORM, GROUP, FANS, Mode.OFF, null, null);
        assertTrue(gate.allowedAt(LocalTime.of(2, 0), PLATFORM, GROUP, FANS));
    }

    @Test
    @DisplayName("状态件里这一条读不出：照全局那一项判，不抛")
    void unreadableRecordFollowsGlobal() {
        NovaStateStore store = new NovaStateStore(new NovaCoreProperties());
        store.write("SessionQuietHours", data -> {
            data.put(SessionKey.of(PLATFORM, GROUP, WORK), "off");
            data.put(SessionKey.of(PLATFORM, GROUP, FANS), JSON.parseObject("{\"mode\":\"custom\",\"start\":2200,\"end\":[7]}"));
        });
        PushGate gate = new PushGate(quiet("23:00", "08:00"), new SessionQuietHoursService(store));

        for (Long num : List.of(WORK, FANS)) {
            assertFalse(assertDoesNotThrow(() -> gate.allowedAt(LocalTime.of(2, 0), PLATFORM, GROUP, num)), "全局静音时段内拦 " + num);
            assertTrue(assertDoesNotThrow(() -> gate.allowedAt(LocalTime.of(12, 0), PLATFORM, GROUP, num)), "全局时段外放 " + num);
        }
    }

    @Test
    @DisplayName("读会话那一档时出任何异常：闸门兜住，按跟全局判")
    void sessionLookupFailureFollowsGlobal() {
        SessionQuietHoursService broken = mock(SessionQuietHoursService.class);
        when(broken.get(PLATFORM, GROUP, WORK)).thenThrow(new IllegalStateException("读不出"));
        PushGate gate = new PushGate(quiet("23:00", "08:00"), broken);

        assertFalse(assertDoesNotThrow(() -> gate.allowedAt(LocalTime.of(2, 0), PLATFORM, GROUP, WORK)));
        assertTrue(assertDoesNotThrow(() -> gate.allowedAt(LocalTime.of(12, 0), PLATFORM, GROUP, WORK)));
    }

    private static SessionQuietHoursService sessions() {
        return new SessionQuietHoursService(new NovaStateStore(new NovaCoreProperties()));
    }

    private static NovaCoreProperties quiet(String start, String end) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPush().setQuietStart(start);
        properties.getPush().setQuietEnd(end);
        return properties;
    }
}
