package org.frostnova.nova.console.controller;

import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.ConfigUiApiInterceptor;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.frostnova.nova.core.service.StreamerProfile;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.util.DurableFiles;
import org.frostnova.nova.core.service.StreamerProfileStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StreamerProfileApiTest {
    @TempDir Path dir;
    private MockMvc mvc;
    private StreamerProfileStore profiles;
    private final List<TimelineEvent> events = new java.util.ArrayList<>();
    private static final long UID = 990000000101L, TIME = 1792454400000L;
    private static final String URL = "/config/api/streamers/bilibili/" + UID + "/profile";
    @BeforeEach void setUp() throws Exception {
        profiles = new StreamerProfileStore(dir);
        profiles.update("bilibili", UID, p -> new StreamerProfile(2, TIME,
                List.of(new StreamerProfile.Concern("回访", TIME)),
                List.of(new StreamerProfile.Exclusion(StreamerProfile.Pattern.slot_compare, TIME)), true, TIME, p.preLiveLeadMinutes(), p.preLiveDailyTime(), p.preLiveDisabled(), p.status()));
        var platforms = mock(DataSourceServiceRegistry.class); when(platforms.platforms()).thenReturn(List.of("bilibili"));
        mvc = MockMvcBuilders.standaloneSetup(new StreamerProfileController(profiles, events::add))
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class), null, platforms)).build();
    }
    @Test @DisplayName("管理员和分到这位主播的成员能只读看到全部条目与时间")
    void administratorAndAssignedMemberCanRead() throws Exception {
        for (var account : List.of(ConfigUiAccount.local(Instant.EPOCH), member(List.of("bilibili:" + UID)))) {
            mvc.perform(get(URL).requestAttr(ConfigUiAccess.ACCOUNT, account)).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.profile.tier").value(2)).andExpect(jsonPath("$.profile.tierSetAt").value(TIME))
                    .andExpect(jsonPath("$.profile.concerns[0].text").value("回访")).andExpect(jsonPath("$.profile.concerns[0].setAt").value(TIME))
                    .andExpect(jsonPath("$.profile.exclusions[0].type").value("slot_compare")).andExpect(jsonPath("$.profile.exclusions[0].setAt").value(TIME))
                    .andExpect(jsonPath("$.profile.paused").value(true)).andExpect(jsonPath("$.profile.pauseSetAt").value(TIME));
        }
    }
    @Test @DisplayName("无关成员看不到私人设置，改平台或不存在编号也不能探查")
    void otherMembersCannotSeePrivateSettings() throws Exception {
        var account = member(List.of("bilibili:" + (UID + 1)));
        String hidden = mvc.perform(get(URL).requestAttr(ConfigUiAccess.ACCOUNT, account)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        var nonexistent = mvc.perform(get(URL.replace(String.valueOf(UID), String.valueOf(UID + 2)))
                .requestAttr(ConfigUiAccess.ACCOUNT, account)).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertEquals(hidden, nonexistent); assertFalse(hidden.contains("回访"));
        mvc.perform(get(URL.replace("bilibili", "another")).requestAttr(ConfigUiAccess.ACCOUNT, member(List.of("bilibili:" + UID))))
                .andExpect(status().isNotFound());
    }
    @Test @DisplayName("档案坏件状态在控制台可见，不能被显示成正常默认档案")
    void unreadableStatusIsExposed() throws Exception {
        Files.writeString(profiles.path("bilibili", UID), "broken");
        mvc.perform(get(URL).requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profile.status").value("UNREADABLE"));
    }
    @Test @DisplayName("控制台只读，没有修改主播档案的写入口")
    void consoleCannotWriteProfile() throws Exception {
        var before = profiles.read("bilibili", UID);
        mvc.perform(post(URL).requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))
                .contentType("application/json").content("{\"paused\":false}"))
                .andExpect(status().isMethodNotAllowed());
        assertEquals(before, profiles.read("bilibili", UID));
    }
    @Test @DisplayName("核对后管理员和所属成员都能恢复空档案，避免一次坏件永久停发")
    void authorizedRecoveryResetsProfile() throws Exception {
        for (var account : List.of(ConfigUiAccount.local(Instant.EPOCH), member(List.of("bilibili:" + UID)))) {
            Files.writeString(profiles.path("bilibili", UID), "broken");
            assertEquals(StreamerProfile.Status.UNREADABLE, profiles.read("bilibili", UID).status());
            mvc.perform(post(URL + "/recover").requestAttr(ConfigUiAccess.ACCOUNT, account))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.profile.status").value("READY"))
                    .andExpect(jsonPath("$.profile.tier").value(2)).andExpect(jsonPath("$.profile.paused").value(false));
            assertEquals(StreamerProfile.empty(StreamerProfile.Status.READY), new StreamerProfileStore(dir).read("bilibili", UID));
            var event = events.get(events.size() - 1);
            assertEquals(account.actor(), event.detail().get("actor"));
            assertEquals("bilibili", event.detail().get("streamerPlatform"));
            assertEquals(String.valueOf(UID), event.detail().get("streamerUid"));
            assertEquals("bilibili:" + UID, event.streamer());
            assertTrue(event.text().contains("恢复")); assertFalse(event.toString().contains("回访"));
        }
    }
    @Test @DisplayName("无关成员不能恢复别人的档案，未登录不能操作")
    void recoveryRequiresStreamerAccess() throws Exception {
        mvc.perform(post(URL + "/recover").requestAttr(ConfigUiAccess.ACCOUNT, member(List.of("bilibili:" + (UID + 1)))))
                .andExpect(status().isNotFound());
        mvc.perform(post(URL + "/recover")).andExpect(status().isUnauthorized());
        assertEquals(2, profiles.read("bilibili", UID).tier()); assertTrue(profiles.read("bilibili", UID).paused());
        assertTrue(events.isEmpty());
    }
    @Test @DisplayName("非 UTF-8 坏件可在控制台恢复，避免永久停发且恢复接口一直报错")
    void authorizedRecoveryResetsInvalidUtf8() throws Exception {
        Files.write(profiles.path("bilibili", UID), new byte[]{(byte) 0xFF, (byte) 0xFE, 0x78, (byte) 0xC3});
        mvc.perform(post(URL + "/recover").requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profile.status").value("READY"))
                .andExpect(jsonPath("$.profile.tier").value(2)).andExpect(jsonPath("$.profile.paused").value(false));
        assertEquals(StreamerProfile.empty(StreamerProfile.Status.READY), new StreamerProfileStore(dir).read("bilibili", UID));
    }
    @Test @DisplayName("过期恢复按钮不能清空一份已经恢复的正常档案")
    void staleRecoveryKeepsReadySettings() throws Exception {
        var before = profiles.read("bilibili", UID);
        mvc.perform(post(URL + "/recover").requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH)))
                .andExpect(status().isOk());
        assertEquals(before, profiles.read("bilibili", UID));
    }
    @Test @DisplayName("恢复写入失败不回成功、不记成功时间线，仍保留停发状态")
    void failedRecoveryDoesNotPretendSuccess() throws Exception {
        Files.writeString(profiles.path("bilibili", UID), "broken");
        profiles.read("bilibili", UID);
        try (var files = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
            files.when(() -> DurableFiles.replace(eq(profiles.path("bilibili", UID)), anyString(), eq(DurableFiles.OWNER_ONLY)))
                    .thenThrow(new java.io.IOException("write unavailable"));
            mvc.perform(post(URL + "/recover").requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH)))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.success").value(false))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        assertTrue(events.isEmpty());
        assertEquals(StreamerProfile.Status.UNREADABLE, new StreamerProfileStore(dir).read("bilibili", UID).status());
    }
    private static ConfigUiAccount member(List<String> streamers) {
        return new ConfigUiAccount("sample-member", ConfigUiAccount.Role.MEMBER, ConfigUiAccount.Status.ENABLED,
                "示例成员", null, null, null, streamers, Instant.EPOCH, null, true);
    }
}
