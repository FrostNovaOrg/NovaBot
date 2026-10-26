package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.SessionRecap;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.config.ui.auth.*;
import org.frostnova.nova.core.datasource.*;
import org.frostnova.nova.core.enums.*;
import org.frostnova.nova.core.model.*;
import org.frostnova.nova.core.sender.*;
import org.frostnova.nova.core.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SessionRecapPushApiTest {
    @TempDir Path dir;
    static final long UID = 910000001L, NUM = 930000001L;
    static final String ROOT = "/config/api/session-recap/test/" + UID;
    final ConfigUiAccount member = new ConfigUiAccount("member", ConfigUiAccount.Role.MEMBER,
            ConfigUiAccount.Status.ENABLED, "Member", null, null, null, List.of("test:" + UID), Instant.EPOCH, null, true);
    MockMvc mvc;
    StreamerProfileStore profiles = mock(StreamerProfileStore.class);
    NovaMessageSender sender = mock(NovaMessageSender.class);
    PushGate quiet = mock(PushGate.class);
    PushTarget channel;

    @BeforeEach void setup() throws Exception {
        var props = new NovaCoreProperties();
        Path data = dir.resolve("datasource.json"); props.getDatasource().setJsonPath(data.toString());
        Files.writeString(data, """
                [{"platform":"test","uid":910000001,"targets":[
                  {"platform":"qq-onebot","type":0,"num":930000001,"streamerSelf":true}]},
                 {"platform":"test","uid":910000002,"targets":[]}]
                """);
        var source = mock(AbstractDataSource.class);
        var user = new PushUser(); user.setPlatform("test"); user.setUid(UID);
        channel = new PushTarget(); channel.setPlatform("qq-onebot"); channel.setType(PushTargetType.FRIEND);
        channel.setNum(NUM); channel.setStreamerSelf(true); user.setTargets(List.of(channel));
        when(source.getAllUsers()).thenReturn(List.of(user));
        var archive = mock(LiveSessionArchive.class);
        long start = Instant.parse("2026-10-25T12:00:00Z").toEpochMilli();
        when(archive.sealedSessions()).thenReturn(List.of(new LiveSessionArchive.ArchivedSession(
                "test", UID, "测试主播", start, start + 3600000, 3600, LiveEndReason.NORMAL)));
        when(profiles.read("test", UID)).thenReturn(StreamerProfile.empty(StreamerProfile.Status.READY));
        var recap = mock(SessionRecapService.class);
        when(recap.calculate("test", UID, start)).thenReturn(new SessionRecap.Result(List.of(new SessionRecap.Item(
                "duration", new SessionRecap.Streamer("test", UID), start,
                Map.of("start", start, "end", start + 3600000, "durationMinutes", 60),
                new SessionRecap.Sample(1, start), "strong", List.of(), List.of())), List.of(), null));
        when(sender.sendNow(any())).thenReturn(JSONObject.of("code", 0));
        var service = new SessionRecapDeliveryService(source, archive, mock(LiveDataService.class),
                mock(AudienceLedgerService.class), recap, profiles, mock(RevenueVisibilityService.class),
                new AssistantProperties(), sender, quiet, new SessionRecapDeliveryStore(dir.resolve("deliveries.json")),
                Clock.fixed(Instant.parse("2026-10-25T14:00:00Z"), ZoneOffset.UTC));
        var platforms = mock(DataSourceServiceRegistry.class); when(platforms.platforms()).thenReturn(List.of("test"));
        mvc = MockMvcBuilders.standaloneSetup(new SessionRecapPushController(service))
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class), props, platforms)).build();
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder trial() {
        return post(ROOT + "/trial").requestAttr(ConfigUiAccess.ACCOUNT, member)
                .param("pushPlatform", "qq-onebot").param("type", "0").param("num", String.valueOf(NUM));
    }
    @Test @DisplayName("接口没有分别确认暂停和静音便不发，避免绕过页面打扰主播")
    void endpointRequiresBothConfirmations() throws Exception {
        when(profiles.read("test", UID)).thenReturn(new StreamerProfile(1, null, List.of(), List.of(),
                true, 1L, StreamerProfile.Status.READY));
        when(quiet.inQuietHoursFor(channel)).thenReturn(true);
        mvc.perform(trial()).andExpect(status().isOk()).andExpect(jsonPath("$.message").value("要先确认"))
                .andExpect(jsonPath("$.confirmation").value("paused"));
        mvc.perform(trial().param("confirmPaused", "true")).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.confirmation").value("quiet"));
        verify(sender, never()).sendNow(any());
        mvc.perform(trial().param("confirmPaused", "true").param("confirmQuiet", "true"))
                .andExpect(jsonPath("$.success").value(true));
        verify(sender, times(1)).sendNow(any());
    }
    @Test @DisplayName("管理员与负责成员可看状态，无关成员状态与试发均隐藏，避免泄露或越权发消息")
    void endpointScopesStatusAndTrial() throws Exception {
        for (var account : List.of(member, ConfigUiAccount.local(Instant.EPOCH))) {
            mvc.perform(get(ROOT + "/status").requestAttr(ConfigUiAccess.ACCOUNT, account)
                    .param("pushPlatform", "qq-onebot").param("type", "0").param("num", String.valueOf(NUM)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("还没有场次：下一场下播 30 分钟后发"));
            mvc.perform(trial().requestAttr(ConfigUiAccess.ACCOUNT, account)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
        verify(sender, times(2)).sendNow(any()); clearInvocations(sender);
        for (String uid : List.of("910000002", "910000003")) {
            String root = "/config/api/session-recap/test/" + uid;
            mvc.perform(get(root + "/status").requestAttr(ConfigUiAccess.ACCOUNT, member)
                    .param("pushPlatform", "qq-onebot").param("type", "0").param("num", String.valueOf(NUM)))
                    .andExpect(status().isNotFound());
            mvc.perform(post(root + "/trial").requestAttr(ConfigUiAccess.ACCOUNT, member)
                    .param("pushPlatform", "qq-onebot").param("type", "0").param("num", String.valueOf(NUM)))
                    .andExpect(status().isNotFound());
        }
        verify(sender, never()).sendNow(any());
    }
    @Test @DisplayName("坏档案状态给出恢复入口标记，试发拒绝，避免遗漏别提")
    void unreadableProfileIsVisibleAndBlocked() throws Exception {
        when(profiles.read("test", UID)).thenReturn(StreamerProfile.empty(StreamerProfile.Status.TEMPORARILY_UNREADABLE));
        mvc.perform(get(ROOT + "/status").requestAttr(ConfigUiAccess.ACCOUNT, member)
                .param("pushPlatform", "qq-onebot").param("type", "0").param("num", String.valueOf(NUM)))
                .andExpect(jsonPath("$.status").value("档案读不出，已停发"))
                .andExpect(jsonPath("$.profileUnreadable").value(true));
        mvc.perform(trial().param("confirmPaused", "true").param("confirmQuiet", "true"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("档案读不出，已停发，请先去主播页核对恢复"));
        verify(sender, never()).sendNow(any());
    }
    @Test @DisplayName("管理员也不能向群或未标本人好友试发，避免私密小结外泄")
    void administratorCannotBypassDestination() throws Exception {
        channel.setStreamerSelf(false);
        mvc.perform(trial().requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH)))
                .andExpect(jsonPath("$.success").value(false));
        channel.setStreamerSelf(true); channel.setType(PushTargetType.GROUP);
        mvc.perform(trial().param("type", "1").requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH)))
                .andExpect(jsonPath("$.success").value(false));
        verify(sender, never()).sendNow(any());
    }
}
