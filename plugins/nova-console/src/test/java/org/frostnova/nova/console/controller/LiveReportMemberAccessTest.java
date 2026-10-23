package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.config.ui.auth.*;
import org.frostnova.nova.core.service.LiveReportDeliveryService;
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

class LiveReportMemberAccessTest {
    @TempDir Path dir;
    private MockMvc mvc;
    private LiveReportDeliveryService reports;
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
        reports = mock(LiveReportDeliveryService.class);
        when(reports.sendLatest(anyString(), anyLong(), any(), anyLong())).thenReturn(JSONObject.of("code", 0));
        var platforms = mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class);
        when(platforms.platforms()).thenReturn(List.of("bilibili"));
        var gate = new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class), properties, platforms);
        mvc = MockMvcBuilders.standaloneSetup(new LiveReportPushController(reports)).addInterceptors(gate).build();
    }

    // 成员只能把自家下播报告发往这位主播配置过的通道，包括暂时停用的通道。
    @Test void trialOnlyUsesOwnedStreamersOwnTargets() throws Exception {
        send(member, "1", "qq-onebot", "1", "1001").andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        clearInvocations(reports);
        for (String[] target : List.of(new String[]{"qq-onebot", "1", "2002"},
                new String[]{"other", "1", "1001"}, new String[]{"qq-onebot", "0", "1001"})) {
            send(member, "1", target[0], target[1], target[2]).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("只能发送到这位主播自己的通道"));
        }
        verifyNoInteractions(reports);
    }

    // 管理员的访问不受成员归属检查影响。
    @Test void administratorCanStillTrialAnyTarget() throws Exception {
        send(ConfigUiAccount.local(Instant.EPOCH), "1", "qq-onebot", "1", "9999")
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(reports).sendLatest(eq("bilibili"), eq(1L), argThat(target -> target.getNum() == 9999L), eq(100L));
    }

    // 主动发送也必须隐藏别人的主播和不存在的主播，不能先透露通道归属。
    @Test void trialConcealsOtherAndMissingStreamers() throws Exception {
        var b = send(member, "2", "qq-onebot", "1", "2002").andExpect(status().isNotFound()).andReturn();
        var c = send(member, "3", "qq-onebot", "1", "2002").andExpect(status().isNotFound()).andReturn();
        assertEquals(b.getResponse().getContentAsString(), c.getResponse().getContentAsString());
        assertEquals("没有这位主播", JSONObject.parseObject(b.getResponse().getContentAsString()).getString("message"));
        verifyNoInteractions(reports);
    }

    // 发送查询参数也按 Spring 绑定后的值核对，重复平台参数不能绕过通道检查。
    @Test void trialTargetUsesControllerNumberAndPlatformBinding() throws Exception {
        send(member, "+1", "qq-onebot", "+1", "001001").andExpect(status().isOk());
        mvc.perform(post("/config/api/live-report/bilibili/1/send-latest")
                .requestAttr(ConfigUiAccess.ACCOUNT, member).param("pushPlatform", "qq-onebot", "other")
                .param("type", "1").param("num", "1001").param("start", "100")).andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions send(ConfigUiAccount account, String uid,
            String platform, String type, String num) throws Exception {
        return mvc.perform(post("/config/api/live-report/bilibili/" + uid + "/send-latest")
                .requestAttr(ConfigUiAccess.ACCOUNT, account).param("pushPlatform", platform).param("type", type).param("num", num).param("start", "100"));
    }
}
