package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.config.ui.auth.*;
import org.frostnova.nova.core.service.WeeklyReportDeliveryService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.*;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WeeklyReportMemberAccessTest {
    @TempDir Path dir;
    private MockMvc mvc;
    private WeeklyReportDeliveryService reports;
    private final ConfigUiAccount member = new ConfigUiAccount("member", ConfigUiAccount.Role.MEMBER,
            ConfigUiAccount.Status.ENABLED, "Member", null, null, null, List.of("bilibili:1"), Instant.EPOCH, null, true);

    @BeforeEach void setup() throws Exception {
        var properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        Files.writeString(dir.resolve("datasource.json"), """
                [{"platform":"bilibili","uid":1,"enabled":false,"targets":[
                  {"platform":"qq-onebot","type":1,"num":1001,"enabled":false}]},
                 {"platform":"bilibili","uid":2,"targets":[{"platform":"qq-onebot","type":1,"num":2002}]}]
                """);
        reports = mock(WeeklyReportDeliveryService.class);
        when(reports.trial(anyString(), anyLong(), any())).thenReturn(JSONObject.of("code", 0));
        var platforms = mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class);
        when(platforms.platforms()).thenReturn(List.of("bilibili"));
        var gate = new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class), properties, platforms);
        mvc = MockMvcBuilders.standaloneSetup(new WeeklyReportPushController(reports)).addInterceptors(gate).build();
    }

    // 成员只能把自家周报发往这位主播配置过的通道，包括暂时停用的通道。
    @Test void trialOnlyUsesOwnedStreamersOwnTargets() throws Exception {
        send(member, "1", "qq-onebot", "1", "1001").andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        clearInvocations(reports);
        for (String[] target : List.of(new String[]{"qq-onebot", "1", "2002"},
                new String[]{"other", "1", "1001"}, new String[]{"qq-onebot", "0", "1001"})) {
            send(member, "1", target[0], target[1], target[2]).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("只能试发到这位主播自己的通道"));
        }
        verifyNoInteractions(reports);
    }

    // 管理员保留向任意会话试发的原行为，不能被成员的通道限制误伤。
    @Test void administratorCanStillTrialAnyTarget() throws Exception {
        send(ConfigUiAccount.local(Instant.EPOCH), "1", "qq-onebot", "1", "9999")
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(reports).trial(eq("bilibili"), eq(1L), argThat(target -> target.getNum() == 9999L));
    }

    // 试发也必须隐藏别人的主播和不存在的主播，不能先透露通道归属。
    @Test void trialConcealsOtherAndMissingStreamers() throws Exception {
        var b = send(member, "2", "qq-onebot", "1", "2002").andExpect(status().isNotFound()).andReturn();
        var c = send(member, "3", "qq-onebot", "1", "2002").andExpect(status().isNotFound()).andReturn();
        assertEquals(b.getResponse().getContentAsString(), c.getResponse().getContentAsString());
        assertEquals("没有这位主播", JSONObject.parseObject(b.getResponse().getContentAsString()).getString("message"));
        verifyNoInteractions(reports);
    }

    // 试发查询参数也按 Spring 绑定后的值核对，重复平台参数不能绕过通道检查。
    @Test void trialTargetUsesControllerNumberAndPlatformBinding() throws Exception {
        send(member, "+1", "qq-onebot", "+1", "001001").andExpect(status().isOk());
        mvc.perform(post("/config/api/weekly-report/bilibili/1/trial")
                .requestAttr(ConfigUiAccess.ACCOUNT, member).param("pushPlatform", "qq-onebot", "other")
                .param("type", "1").param("num", "1001")).andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions send(ConfigUiAccount account, String uid,
            String platform, String type, String num) throws Exception {
        return mvc.perform(post("/config/api/weekly-report/bilibili/" + uid + "/trial")
                .requestAttr(ConfigUiAccess.ACCOUNT, account).param("pushPlatform", platform).param("type", type).param("num", num));
    }
}
