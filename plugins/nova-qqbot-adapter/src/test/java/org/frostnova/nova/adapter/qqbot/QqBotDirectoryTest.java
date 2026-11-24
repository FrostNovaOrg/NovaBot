package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiPushTargetsController;
import org.frostnova.nova.core.config.ui.PushTargetDirectory;
import org.frostnova.nova.core.config.ui.PushTargetLookup;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.NovaSenderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.awaitility.Awaitility.await;
import static org.frostnova.nova.adapter.qqbot.QqBotDirectory.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QqBotDirectoryTest {
    private static final String PLATFORM = "qq-official";
    private static final String APP_ID = "12345678";
    private static final String GROUP_A = "A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4";
    private static final String GROUP_B = "E1F2A3B4C5D6E7F8A9B0C1D2E3F4A5B6";
    private static final Instant JOINED = Instant.parse("2026-11-16T17:05:00Z");
    private static final String TARGETS_PATH = "/config/api/bot/targets";

    @TempDir
    Path dataDirectory;

    @Test
    void pluginProvidesThePushTargetDirectoryAndLookup() {
        CountDownLatch infoRequested = new CountDownLatch(1);
        CountDownLatch reply = new CountDownLatch(1);
        QqBotHttpTransport transport = (method, uri, headers, body) -> {
            if (isToken(uri)) {
                return token();
            }
            if (isInfo(uri)) {
                infoRequested.countDown();
                assertTrue(reply.await(10, TimeUnit.SECONDS));
                return json("code", 11253);
            }
            return json("member_role", "admin", "allow_proactive_msg", false);
        };
        new ApplicationContextRunner().withUserConfiguration(QqBotPluginAutoConfiguration.class)
                .withBean("qqBotSettings", QqBotSettings.class, () -> new QqBotSettings(dataDirectory))
                .withBean(NovaSenderService.class, () -> new NovaSenderService(new NovaCoreProperties()))
                .withBean(QqBotHttpTransport.class, () -> transport, definition -> definition.setPrimary(true))
                .withBean(QqBotGatewayScheduler.class, QqBotGatewayTest.ManualScheduler::new,
                        definition -> definition.setPrimary(true))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var directories = List.copyOf(context.getBeansOfType(PushTargetDirectory.class).values());
                    var lookups = List.copyOf(context.getBeansOfType(PushTargetLookup.class).values());
                    assertTrue(directories.stream().anyMatch(directory -> directory.id().equals(PLATFORM)),
                            "推送页应当能取得 QQ 官方机器人的群名单");
                    assertTrue(lookups.stream().anyMatch(lookup -> lookup.supports(PLATFORM)),
                            "推送页应当能查 QQ 官方机器人的群号");
                    context.getBean(QqBotCredentials.class).save(APP_ID, "fixture-secret");
                    var directory = context.getBean(QqBotDirectory.class);
                    var mvc = MockMvcBuilders.standaloneSetup(new ConfigUiPushTargetsController(
                            directories, lookups, mock(AbstractDataSource.class))).build();
                    long number = directory.groupJoined(GROUP_A, JOINED);
                    try {
                        assertTrue(infoRequested.await(10, TimeUnit.SECONDS));
                        mvc.perform(get(TARGETS_PATH).param("type", "group"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items[0].sender").value(PLATFORM))
                                .andExpect(jsonPath("$.items[0].num").value(number))
                                .andExpect(jsonPath("$.items[0].name").value("未命名群（11-17 01:05 加入）"));
                    } finally {
                        reply.countDown();
                    }
                    await().atMost(Duration.ofSeconds(10)).until(() -> group(directory, number).role() == BotRole.ADMIN);
                    var lookup = get(TARGETS_PATH + "/lookup").param("platform", PLATFORM)
                            .param("type", "group").param("num", Long.toString(number));
                    mvc.perform(lookup).andExpect(status().isOk())
                            .andExpect(jsonPath("$.found").value(true))
                            .andExpect(jsonPath("$.name").value("未命名群（11-17 01:05 加入）"));
                    directory.groupRemoved(GROUP_A, JOINED.plusSeconds(60));
                    mvc.perform(get(TARGETS_PATH).param("type", "group")).andExpect(jsonPath("$.items").isEmpty());
                    mvc.perform(lookup).andExpect(jsonPath("$.found").value(false))
                            .andExpect(jsonPath("$.message").value("机器人已被移出这个群"));
                });
    }

    @ParameterizedTest
    @CsvSource({
            "group," + GROUP_A + ",4793062243600896",
            "group," + GROUP_B + ",1719442409494629",
            "member," + GROUP_A + ",8415836002984767",
            "member," + GROUP_B + ",1903426848530243"
    })
    void configuredTargetNumbersRemainCompatible(String kind, String openid, long expected) {
        var dir = new Rig(uri -> json()).directory();
        if (kind.equals("group")) {
            assertEquals(expected, dir.groupSeen(openid, JOINED));
            assertEquals(expected, dir.groupNumber(openid).orElseThrow());
            assertEquals(openid, dir.groupOpenId(expected).orElseThrow());
        } else {
            assertEquals(expected, dir.memberSeen(openid, JOINED));
            assertEquals(expected, dir.memberNumber(openid).orElseThrow());
            assertEquals(openid, dir.memberOpenId(expected).orElseThrow());
        }
        assertEquals(16, Long.toString(expected).length());
        assertTrue(expected < (1L << 53));
    }

    @Test
    void rebuildingDeletedDirectoryKeepsExistingPushTargetsAddressedToTheSameGroups() throws IOException {
        var rig = new Rig(uri -> json("group_name", "提醒群"));
        var original = rig.directory();
        long first = original.groupJoined(GROUP_A, JOINED);
        long second = original.groupJoined(GROUP_B, JOINED);
        rig.drain();
        Files.delete(directoryFile());

        var rebuilt = rig.directory();
        assertEquals(second, rebuilt.groupJoined(GROUP_B, JOINED.plusSeconds(50)));
        assertEquals(first, rebuilt.groupJoined(GROUP_A, JOINED.plusSeconds(100)));
        assertEquals(GROUP_A, rebuilt.groupOpenId(first).orElseThrow());
        assertEquals(GROUP_B, rebuilt.groupOpenId(second).orElseThrow());
        assertTrue(Files.isRegularFile(directoryFile()));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void collidingRegistrationCannotReplaceAnExistingGroupOrMember(CapturedOutput output) throws IOException {
        var rig = new Rig(uri -> json(), bytes -> new byte[32]);
        var directory = rig.directory();
        long number = directory.groupJoined(GROUP_A, JOINED);
        assertEquals(number, directory.memberSeen(GROUP_B, JOINED));
        byte[] original = Files.readAllBytes(directoryFile());

        assertThrows(IllegalStateException.class, () -> directory.groupSeen(GROUP_B, JOINED.plusSeconds(2)));
        assertThrows(IllegalStateException.class, () -> directory.memberSeen(GROUP_A, JOINED.plusSeconds(2)));
        assertEquals(GROUP_A, directory.groupOpenId(number).orElseThrow());
        assertEquals(GROUP_B, directory.memberOpenId(number).orElseThrow());
        assertTrue(directory.groupNumber(GROUP_B).isEmpty());
        assertTrue(directory.memberNumber(GROUP_A).isEmpty());
        assertArrayEquals(original, Files.readAllBytes(directoryFile()));

        rig.credentials.save("87654321", "fixture-secret-2");
        assertThrows(IllegalStateException.class, () -> directory.groupJoined(GROUP_B, JOINED));
        assertArrayEquals(original, Files.readAllBytes(directoryFile()));
        assertEquals(3, output.getAll().lines().filter(line -> line.contains("ERROR")
                && line.contains("编号冲突") && line.contains(Long.toString(number))).count());
        assertFalse(output.getAll().contains(GROUP_A));
        assertFalse(output.getAll().contains(GROUP_B));
    }

    @ParameterizedTest
    @CsvSource({"code,11253", "err_code,40012010"})
    @ExtendWith(OutputCaptureExtension.class)
    void unavailableInfoLeavesASelectableNameAndStopsRequestsForADay(
            String errorField, int code, CapturedOutput output) {
        AtomicBoolean infoAvailable = new AtomicBoolean();
        var rig = new Rig(uri -> isInfo(uri)
                ? (infoAvailable.get() ? json("group_name", "开播提醒", "group_member_num", 37)
                    : json(errorField, code, "message", "group_openid=" + GROUP_A, "trace_id", GROUP_B))
                : json("member_role", "member"));
        var directory = rig.directory();
        long number = directory.groupSeen(GROUP_A, JOINED);
        rig.drain();
        var snapshot = directory.snapshots(false).get(0);
        assertEquals(new PushTargetDirectory.Group(number, "未命名群（11-17 01:05 加入）", null, false),
                snapshot.groups().get(0));
        assertEquals("接口没开放", group(directory, number).lastFailure().reason());

        rig.clock.advance(Duration.ofHours(24).minusSeconds(1));
        var restarted = rig.directory();
        infoAvailable.set(true);
        restarted.groupSeen(GROUP_A, rig.clock.instant());
        restarted.groupSeen(GROUP_B, rig.clock.instant());
        rig.drain();
        assertEquals(1, rig.infoCalls());
        assertEquals(2, rig.calls.stream().filter(uri -> !isInfo(uri)).count());

        rig.clock.advance(Duration.ofSeconds(1));
        restarted.groupSeen(GROUP_A, rig.clock.instant());
        rig.drain();
        assertEquals(2, rig.infoCalls());
        assertEquals("开播提醒", group(restarted, number).name());
        assertEquals(NameSource.GROUP_INFO_API, group(restarted, number).nameSource());
        assertEquals(37, group(restarted, number).memberCount());
        assertFalse(output.getAll().contains(GROUP_A));
        assertFalse(output.getAll().contains(GROUP_B));
    }

    @Test
    void otherFailuresRetryOnlyOnNextSightingAfterAnHourAndKeepManualName() {
        AtomicBoolean available = new AtomicBoolean();
        var rig = new Rig(uri -> {
            if (!isInfo(uri)) {
                return json("member_role", "owner");
            }
            return available.get() ? json("group_name", "网上的群名", "group_member_num", 32) : json("code", 50001);
        });
        var dir = rig.directory();
        long number = dir.groupJoined(GROUP_A, JOINED);
        dir.groupSeen(GROUP_A, JOINED.plusSeconds(1));
        assertEquals(2, rig.tasks.size());
        rig.drain();
        assertTrue(group(dir, number).lastFailure().reason().contains("50001"));
        dir.renameGroup(number, "站内通知");
        available.set(true);

        rig.clock.advance(Duration.ofMinutes(59));
        dir.groupSeen(GROUP_A, rig.clock.instant());
        rig.drain();
        assertEquals(1, rig.infoCalls());
        rig.clock.advance(Duration.ofMinutes(1));
        rig.drain();
        assertEquals(1, rig.infoCalls());
        dir.groupSeen(GROUP_A, rig.clock.instant());
        rig.drain();
        assertEquals(2, rig.infoCalls());
        assertEquals(NameSource.MANUAL, group(dir, number).nameSource());
        assertEquals(new PushTargetDirectory.Group(number, "站内通知", 32, true),
                rig.directory().snapshots(true).get(0).groups().get(0));
    }

    @Test
    void removedGroupStaysOutUntilRejoinedWithItsOriginalNumberAndName() {
        var rig = new Rig(uri -> json());
        var dir = rig.directory();
        long number = dir.groupJoined(GROUP_A, JOINED);
        dir.renameGroup(number, "节目提醒");
        dir.groupRemoved(GROUP_A, JOINED.plusSeconds(3));
        dir.groupSeen(GROUP_A, JOINED.plusSeconds(4));
        rig.drain();
        assertTrue(rig.calls.isEmpty(), "排队中的查询不应继续访问已移出的群");
        var restarted = rig.directory();
        assertTrue(restarted.snapshots(false).get(0).groups().isEmpty());
        assertEquals(new PushTargetLookup.Result(false, null, "机器人已被移出这个群"),
                restarted.lookup(PLATFORM, PushTargetType.GROUP, number, null));

        Instant rejoined = JOINED.plusSeconds(5);
        assertEquals(number, restarted.groupJoined(GROUP_A, rejoined));
        var snapshot = restarted.snapshots(false).get(0);
        assertEquals(PLATFORM, snapshot.sender());
        assertEquals(List.of(new PushTargetDirectory.Group(number, "节目提醒", null, null)), snapshot.groups());
        assertTrue(snapshot.friends().isEmpty());
        assertEquals(rig.clock.instant(), snapshot.fetchedAt());
        assertFalse(snapshot.stale());
        assertEquals(rejoined, group(restarted, number).joinedAt());
        assertEquals(new PushTargetLookup.Result(true, "节目提醒", null),
                restarted.lookup(PLATFORM, PushTargetType.GROUP, number, null));
        assertEquals(new PushTargetLookup.Result(false, null, "这个群还没对上号，在群里 @机器人 说一句即可"),
                restarted.lookup(PLATFORM, PushTargetType.GROUP, 1, null));
        assertEquals(new PushTargetLookup.Result(false, null, "QQ 官方机器人暂不支持好友推送"),
                restarted.lookup(PLATFORM, PushTargetType.FRIEND, number, null));
        assertFalse(restarted.supports("qq"));
    }

    @Test
    void atAllRejectionSurvivesRestartUntilMidnightInChina() {
        var rig = new Rig(uri -> json());
        var directory = rig.directory();
        long first = directory.groupJoined(GROUP_A, JOINED);
        long second = directory.groupJoined(GROUP_B, JOINED);
        Instant midnight = Instant.parse("2026-11-17T16:00:00Z");
        rig.clock.advance(Duration.between(JOINED, midnight.minusSeconds(1)));
        directory.recordAtAllRejection(APP_ID, first);

        var restarted = rig.directory();
        assertFalse(restarted.canAtAll(PLATFORM, first));
        assertTrue(restarted.canAtAll(PLATFORM, second));
        assertFalse(restarted.supports("qq-onebot"));
        rig.clock.advance(Duration.ofSeconds(1));
        assertTrue(restarted.canAtAll(PLATFORM, first));
        assertNull(restarted.denyReason(PLATFORM, first));
    }

    @Test
    void botStateRecordsAdminAndAnExplicitlyDisabledProactiveSwitch() {
        var rig = new Rig(uri -> isInfo(uri)
                ? json("group_name", "通知群", "group_member_num", 28)
                : json("member_role", "admin", "allow_proactive_msg", false, "member_openid", GROUP_B));
        var directory = rig.directory();
        long number = directory.groupJoined(GROUP_A, JOINED);
        assertEquals(ProactiveStatus.UNKNOWN, group(directory, number).proactive().status());
        rig.drain();
        var restarted = rig.directory();
        assertEquals(new Proactive(ProactiveStatus.DISABLED, SwitchSource.GROUP_STATE_API, rig.clock.instant()),
                group(restarted, number).proactive());
        assertEquals(new PushTargetDirectory.Group(number, "通知群", 28, true),
                restarted.snapshots(false).get(0).groups().get(0));
        assertTrue(restarted.memberNumber(GROUP_B).isEmpty());
        restarted.recordProactive(number, ProactiveStatus.ENABLED, SwitchSource.EVENT, JOINED.plusSeconds(5));
        restarted.recordProactive(number, ProactiveStatus.DISABLED, SwitchSource.EVENT, JOINED.plusSeconds(4));
        assertEquals(new Proactive(ProactiveStatus.ENABLED, SwitchSource.EVENT, JOINED.plusSeconds(5)),
                group(rig.directory(), number).proactive());
    }

    @Test
    void manualConsoleRefreshFindsAnOpenedSwitchBeforeTheRoleCacheExpires() throws Exception {
        var allowed = new AtomicBoolean();
        var role = new AtomicReference<>("member");
        var rig = new Rig(uri -> isInfo(uri) ? json("group_name", "开播群")
                : json("member_role", role.get(), "allow_proactive_msg", allowed.get()));
        var directory = rig.directory();
        long number = directory.groupJoined(GROUP_A, JOINED);
        rig.drain();
        var probe = rig.probe(directory, number);
        var members = mock(QqBotMembers.class);
        when(members.claimState()).thenReturn(new QqBotMembers.ClaimState(null, Map.of()));
        var console = MockMvcBuilders.standaloneSetup(new QqBotConsoleController(
                rig.credentials, rig.settings, probe, directory, members)).build();
        var state = get("/config/api/qqbot/state");
        console.perform(state).andExpect(jsonPath("$.groups[0].proactive").value("DISABLED"));

        allowed.set(true);
        role.set("admin");
        rig.clock.advance(Duration.ofMinutes(1).minusSeconds(1));
        console.perform(get("/config/api/qqbot/state").param("refresh", "true"));
        rig.drain();
        assertEquals(1, rig.botStateCalls());

        rig.clock.advance(Duration.ofSeconds(1));
        console.perform(state);
        assertTrue(rig.tasks.isEmpty());
        var refresh = get("/config/api/qqbot/state").param("refresh", "true");
        console.perform(refresh).andExpect(status().isOk()).andExpect(jsonPath("$.refreshing").value(true));
        console.perform(refresh).andExpect(jsonPath("$.refreshing").value(true));
        assertEquals(1, rig.tasks.size());
        rig.drain();
        console.perform(get("/config/api/qqbot/state").param("refresh", "false"))
                .andExpect(jsonPath("$.refreshing").value(false))
                .andExpect(jsonPath("$.groups[0].proactive").value("ENABLED"))
                .andExpect(jsonPath("$.groups[0].role").value("MEMBER"));
        assertEquals(HealthStatus.Level.OK, probe.check().level());

        rig.clock.advance(Duration.ofMinutes(9).minusSeconds(1));
        directory.recordProactive(number, ProactiveStatus.DISABLED, SwitchSource.EVENT, rig.clock.instant());
        console.perform(refresh).andExpect(jsonPath("$.refreshing").value(true));
        rig.drain();
        assertEquals(BotRole.MEMBER, group(directory, number).role());

        rig.clock.advance(Duration.ofSeconds(1));
        console.perform(refresh).andExpect(status().isOk()).andExpect(jsonPath("$.refreshing").value(false));
        assertTrue(rig.tasks.isEmpty(), "已开启的群在十分钟内不应手动重查");
        var callers = Executors.newSingleThreadExecutor();
        try {
            var mention = callers.submit(() -> directory.canAtAll(PLATFORM, number));
            var fetch = rig.tasks.poll(8, TimeUnit.SECONDS);
            assertNotNull(fetch, "管理员角色应在原来的十分钟到期时查询");
            fetch.run();
            assertTrue(mention.get(8, TimeUnit.SECONDS));
        } finally {
            callers.shutdownNow();
        }
        assertEquals(4, rig.botStateCalls());
    }

    @Test
    void joinWarningWaitsForASecondDisabledReadingAfterTenMinutes() throws Exception {
        var allowed = new AtomicBoolean();
        var rig = new Rig(uri -> isInfo(uri) ? json("group_name", "开播群")
                : json("allow_proactive_msg", allowed.get()));
        var directory = rig.directory();
        long number = directory.groupJoined(GROUP_A, JOINED);
        rig.drain();
        assertEquals(ProactiveStatus.DISABLED, group(directory, number).proactive().status());
        var probe = rig.probe(directory, number);
        assertEquals(HealthStatus.Level.OK, probe.check().level());

        rig.clock.advance(Duration.ofMinutes(10).minusSeconds(1));
        assertEquals(HealthStatus.Level.OK, probe.check().level());
        rig.drain();
        assertEquals(1, rig.botStateCalls());

        rig.clock.advance(Duration.ofSeconds(1));
        assertEquals(HealthStatus.Level.OK, probe.check().level(), "等到重查有读数再告警");
        rig.drain();
        var warning = probe.check();
        assertEquals(HealthStatus.Level.DEGRADED, warning.level());
        assertTrue(warning.summary().contains("群主还没打开主动发言"));
        assertTrue(warning.summary().contains("开播群"));
        rig.drain();
        assertEquals(2, rig.botStateCalls());

        allowed.set(true);
        rig.clock.advance(Duration.ofMinutes(1));
        directory.consoleGroups(true);
        rig.drain();
        assertEquals(HealthStatus.Level.OK, probe.check().level());
        assertEquals(3, rig.botStateCalls());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 50001, 11253, 40012010})
    void manualRefreshChecksUnknownEarlyAndKeepsTheLastReadingDuringCooldown(int errorCode) {
        var allowed = new AtomicReference<Boolean>();
        var unavailable = new AtomicBoolean();
        var rig = new Rig(uri -> {
            if (isInfo(uri)) return json();
            if (!unavailable.get()) {
                return allowed.get() == null ? json() : json("allow_proactive_msg", allowed.get());
            }
            if (errorCode == 0) throw new HttpTimeoutException("群状态查询超时");
            return json("code", errorCode);
        });
        var directory = rig.directory();
        long number = directory.groupJoined(GROUP_A, JOINED);
        rig.drain();
        assertEquals(ProactiveStatus.UNKNOWN, group(directory, number).proactive().status());

        rig.clock.advance(Duration.ofMinutes(1));
        allowed.set(false);
        directory.consoleGroups(true);
        rig.drain();
        assertEquals(ProactiveStatus.DISABLED, group(directory, number).proactive().status());

        rig.clock.advance(Duration.ofMinutes(9));
        unavailable.set(true);
        var probe = rig.probe(directory, number);
        probe.check();
        rig.drain();
        assertEquals(3, rig.botStateCalls());
        assertEquals(ProactiveStatus.DISABLED, group(directory, number).proactive().status());
        assertEquals(HealthStatus.Level.OK, probe.check().level());

        unavailable.set(false);
        allowed.set(true);
        Duration cooldown = Duration.ofHours(errorCode == 11253 || errorCode == 40012010 ? 24 : 1);
        rig.clock.advance(cooldown.minusSeconds(1));
        assertFalse(directory.consoleGroups(true).refreshing());
        assertEquals(HealthStatus.Level.OK, probe.check().level());
        rig.drain();
        assertEquals(3, rig.botStateCalls());

        rig.clock.advance(Duration.ofSeconds(1));
        assertEquals(HealthStatus.Level.OK, probe.check().level());
        rig.drain();
        assertEquals(4, rig.botStateCalls());
        assertEquals(ProactiveStatus.ENABLED, group(directory, number).proactive().status());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void consoleRefreshSchedulesOneRoleUpdateAfterRestart(boolean connectionsPage) {
        var role = new AtomicReference<>("member");
        var rig = new Rig(uri -> isInfo(uri) ? json("group_name", "开播提醒") : json("member_role", role.get()));
        long number = rig.directory().groupJoined(GROUP_A, JOINED);
        rig.drain();
        role.set("admin");
        rig.clock.advance(Duration.ofMinutes(10));
        var restarted = rig.directory();
        for (int i = 0; i < 2; i++) {
            if (connectionsPage) {
                assertEquals(BotRole.MEMBER, restarted.consoleGroups(null).groups().get(0).role());
            } else {
                assertFalse(restarted.snapshots(true).get(0).groups().get(0).admin());
            }
        }
        assertEquals(1, rig.tasks.size());
        rig.drain();
        assertEquals(BotRole.ADMIN, restarted.consoleGroups(null).groups().get(0).role());
        assertTrue(restarted.snapshots(true).get(0).groups().get(0).admin());
        assertTrue(restarted.canAtAll(PLATFORM, number));
        assertTrue(rig.tasks.isEmpty());
        assertEquals(1, rig.infoCalls());
    }

    @Test
    void concurrentMentionsShareAPendingRefreshAndCanUseTheCachedRoleOnTimeout() throws Exception {
        var role = new AtomicReference<>("member");
        var rig = new Rig(uri -> isInfo(uri) ? json() : json("member_role", role.get()));
        var directory = rig.directory();
        long number = directory.groupJoined(GROUP_A, JOINED);
        rig.drain();
        rig.clock.advance(Duration.ofMinutes(10));
        role.set("owner");

        var callers = Executors.newFixedThreadPool(2);
        try {
            var first = callers.submit(() -> directory.canAtAll(PLATFORM, number));
            var refresh = rig.tasks.poll(8, TimeUnit.SECONDS);
            assertNotNull(refresh);
            assertFalse(first.get(8, TimeUnit.SECONDS));
            var second = callers.submit(() -> directory.canAtAll(PLATFORM, number));
            var third = callers.submit(() -> directory.canAtAll(PLATFORM, number));
            directory.snapshots(true);
            refresh.run();
            assertTrue(second.get(8, TimeUnit.SECONDS));
            assertTrue(third.get(8, TimeUnit.SECONDS));
            assertTrue(rig.tasks.isEmpty());
            assertEquals(2, rig.calls.stream().filter(uri -> !isInfo(uri)).count());
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void unavailableBotStateKeepsTheSwitchUnknownWhileGroupInfoRemainsAvailable() {
        var rig = new Rig(uri -> isInfo(uri) ? json("group_name", "通知群") : json("err_code", 40012010));
        var directory = rig.directory();
        long number = directory.groupJoined(GROUP_A, JOINED);
        rig.drain();
        assertEquals("通知群", group(directory, number).name());
        assertEquals(ProactiveStatus.UNKNOWN, group(directory, number).proactive().status());
        assertEquals("接口没开放", group(directory, number).lastFailure().reason());

        rig.clock.advance(Duration.ofHours(23));
        directory.groupSeen(GROUP_B, rig.clock.instant());
        rig.drain();
        assertEquals(2, rig.infoCalls());
        assertEquals(3, rig.calls.size());
    }

    @Test
    void switchUpdateRecordedDuringBotStateRequestIsKept() {
        AtomicReference<Runnable> duringRequest = new AtomicReference<>();
        var rig = new Rig(uri -> {
            if (isInfo(uri)) {
                return json();
            }
            duringRequest.get().run();
            return json("allow_proactive_msg", false, "member_role", "admin");
        });
        var directory = rig.directory();
        long number = directory.groupJoined(GROUP_A, JOINED);
        duringRequest.set(() -> directory.recordProactive(number, ProactiveStatus.ENABLED, SwitchSource.EVENT, JOINED));
        rig.drain();
        assertEquals(new Proactive(ProactiveStatus.ENABLED, SwitchSource.EVENT, JOINED), group(directory, number).proactive());
        assertEquals(BotRole.ADMIN, group(directory, number).role());
    }

    @Test
    void restoredDirectoryFileIsOwnerOnlyAndKeepsGroupAndMemberTimestamps() throws IOException {
        assumeTrue(Files.getFileStore(dataDirectory).supportsFileAttributeView(PosixFileAttributeView.class),
                "当前文件系统不支持 POSIX 文件权限");
        var rig = new Rig(uri -> json());
        var dir = rig.directory();
        long number = dir.groupSeen(GROUP_A, JOINED);
        long member = dir.memberSeen(GROUP_B, JOINED);
        Instant seen = JOINED.plusSeconds(60);
        dir.groupSeen(GROUP_A, seen);
        assertEquals(member, dir.memberSeen(GROUP_B, seen));
        dir.recordFailure(number, "主动发言关闭", seen);

        var ownerOnly = PosixFilePermissions.fromString("rw-------");
        assertEquals(ownerOnly, Files.getPosixFilePermissions(directoryFile()));
        Files.setPosixFilePermissions(directoryFile(), PosixFilePermissions.fromString("rw-r--r--"));
        var restored = rig.directory();
        assertEquals(ownerOnly, Files.getPosixFilePermissions(directoryFile()));
        assertEquals(new Failure("主动发言关闭", seen), group(restored, number).lastFailure());
        assertEquals(JOINED, group(restored, number).joinedAt());
        assertEquals(seen, group(restored, number).lastSeenAt());
        var memberRow = JSON.parseObject(Files.readString(directoryFile())).getJSONObject("bots")
                .getJSONObject(APP_ID).getJSONObject("members").getJSONObject(Long.toString(member));
        assertEquals(JOINED, memberRow.getInstant("firstSeenAt"));
        assertEquals(seen, memberRow.getInstant("lastSeenAt"));
    }

    @Test
    void namesReturnedToConsoleMaskKnownOpenIds() {
        var rig = new Rig(uri -> isInfo(uri) ? json("group_name", "提醒 " + GROUP_A + " " + GROUP_B) : json());
        var directory = rig.directory();
        long number = directory.groupSeen(GROUP_A, JOINED);
        directory.memberSeen(GROUP_B, JOINED);
        rig.drain();
        var snapshot = directory.snapshots(false).get(0);
        assertEquals("提醒 A1B2…C3D4 E1F2…A5B6", snapshot.groups().get(0).name());
        assertEquals(snapshot.groups().get(0).name(), directory.lookup(PLATFORM, PushTargetType.GROUP, number, null).name());
        assertFalse(group(directory, number).toString().contains(GROUP_A));
    }

    @Test
    void failedWriteDoesNotChangeTheLiveDirectory() throws IOException {
        var rig = new Rig(uri -> json("group_name", "新名称"));
        var dir = rig.directory();
        long number = dir.groupJoined(GROUP_A, JOINED);
        Path backup = directoryFile().resolveSibling("directory-backup.json");
        Files.move(directoryFile(), backup);
        Files.createDirectory(directoryFile());
        assertThrows(IllegalStateException.class, () -> dir.renameGroup(number, "未写入的名称"));
        assertEquals(NameSource.DEFAULT, group(dir, number).nameSource());
        Files.delete(directoryFile());
        Files.move(backup, directoryFile());
        rig.drain();
        assertEquals("新名称", group(rig.directory(), number).name());
    }

    @Test
    void changingAppIdHidesTheOtherBotsGroupsAndCancelsItsQueuedQueries() {
        var rig = new Rig(uri -> json());
        var dir = rig.directory();
        long previous = dir.groupJoined(GROUP_A, JOINED);
        dir.renameGroup(previous, "原机器人的群");
        rig.credentials.save("87654321", "fixture-secret-2");
        assertTrue(dir.snapshots(false).get(0).groups().isEmpty());
        assertTrue(dir.groupOpenId(previous).isEmpty());
        long current = dir.groupJoined(GROUP_B, JOINED);
        rig.drain();
        assertEquals(2, rig.calls.size());
        assertTrue(rig.calls.stream().allMatch(uri -> uri.getPath().contains(GROUP_B)));
        rig.credentials.save(APP_ID, "fixture-secret");
        var restored = rig.directory();
        assertEquals("原机器人的群", group(restored, previous).name());
        assertTrue(restored.groupOpenId(current).isEmpty());
    }

    @Test
    void requestRejectedAfterAppIdSwitchCannotRetryWithTheNewBotsToken() {
        AtomicReference<QqBotCredentials> credentials = new AtomicReference<>();
        var rig = new Rig(uri -> {
            credentials.get().save("87654321", "fixture-secret-2");
            return new QqBotHttpTransport.Response(401, null);
        });
        credentials.set(rig.credentials);
        var directory = rig.directory();
        directory.groupSeen(GROUP_A, JOINED);
        rig.drain();
        assertEquals(1, rig.calls.size());
        assertTrue(directory.groupNumber(GROUP_A).isEmpty());
    }

    private Path directoryFile() {
        return dataDirectory.resolve("qqbot/directory.json");
    }

    private static GroupEntry group(QqBotDirectory dir, long number) {
        return dir.group(number).orElseThrow();
    }

    private static boolean isToken(URI uri) {
        return uri.getPath().equals("/app/getAppAccessToken");
    }

    private static boolean isInfo(URI uri) {
        return uri.getPath().endsWith("/info");
    }

    private static QqBotHttpTransport.Response token() {
        return json("access_token", "fixture-token", "expires_in", 7200);
    }

    private static QqBotHttpTransport.Response json(Object... fields) {
        JSONObject response = new JSONObject();
        for (int i = 0; i < fields.length; i += 2) {
            response.put((String) fields[i], fields[i + 1]);
        }
        return new QqBotHttpTransport.Response(200, response.toJSONString());
    }

    private final class Rig {
        final TestClock clock = new TestClock();
        final QqBotSettings settings = new QqBotSettings(dataDirectory);
        final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        final List<URI> calls = new ArrayList<>();
        final Function<byte[], byte[]> hash;
        final QqBotCredentials credentials;
        final QqBotApiClient api;

        Rig(GroupResponse response) {
            this(response, QqBotDirectory::sha256);
        }

        Rig(GroupResponse response, Function<byte[], byte[]> hash) {
            this.hash = hash;
            settings.save(APP_ID, "fixture-secret");
            QqBotHttpTransport transport = (method, uri, headers, body) -> {
                assertEquals(QqBotHttpTransport.ORIGIN.getHost(), uri.getHost());
                if (isToken(uri)) {
                    return token();
                }
                assertEquals("GET", method);
                assertTrue(uri.getPath().startsWith("/v2/groups/"));
                calls.add(uri);
                return response.get(uri);
            };
            var tokens = new QqBotTokenService(settings, transport, clock);
            credentials = new QqBotCredentials(tokens, mock(QqBotGateway.class), mock(QqBotSender.class));
            api = new QqBotApiClient(transport, tokens);
        }

        QqBotDirectory directory() {
            return new QqBotDirectory(settings, api, clock, tasks::add, hash);
        }

        QqBotHealthProbe probe(QqBotDirectory directory, long groupNumber) {
            var gateway = mock(QqBotGateway.class);
            var health = HealthStatus.ok("已连接");
            when(gateway.health()).thenReturn(health);
            when(gateway.connectionState()).thenReturn(new QqBotGateway.ConnectionState(health, null));
            var tokens = mock(QqBotTokenService.class);
            when(tokens.settings()).thenReturn(settings.snapshot());
            var target = new PushTarget();
            target.setPlatform(PLATFORM);
            target.setType(PushTargetType.GROUP);
            target.setNum(groupNumber);
            var streamer = new PushUser();
            streamer.setTargets(List.of(target));
            var source = mock(AbstractDataSource.class);
            when(source.getAllUsers()).thenReturn(List.of(streamer));
            return new QqBotHealthProbe(tokens, gateway, directory, Optional.of(source));
        }

        void drain() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }

        long infoCalls() {
            return calls.stream().filter(QqBotDirectoryTest::isInfo).count();
        }

        long botStateCalls() {
            return calls.size() - infoCalls();
        }
    }

    @FunctionalInterface
    private interface GroupResponse {
        QqBotHttpTransport.Response get(URI uri) throws IOException;
    }

    private static final class TestClock extends Clock {
        private Instant now = JOINED;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }
    }
}
