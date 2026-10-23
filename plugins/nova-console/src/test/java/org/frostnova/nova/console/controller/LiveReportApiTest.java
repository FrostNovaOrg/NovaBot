package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.service.LiveReportDeliveryService;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LiveReportApiTest {
    private LiveReportDeliveryService reports;
    private MockMvc mvc;
    @BeforeEach void setup() {
        reports = mock(LiveReportDeliveryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new LiveReportPushController(reports)).build();
    }
    @Test void noSessionReturnsClearMessageWithoutSending() throws Exception {
        when(reports.latest("bilibili", 1L)).thenReturn(Optional.empty());
        mvc.perform(get("/config/api/live-report/bilibili/1/latest"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("还没有可发的下播报告"));
        verify(reports).latest("bilibili", 1L);
        verifyNoMoreInteractions(reports);
    }
    @Test void confirmationReceivesExactStartTime() throws Exception {
        var detail = mock(LiveDetail.class); when(detail.startTime()).thenReturn(123456L);
        when(reports.latest("bilibili", 1L)).thenReturn(Optional.of(detail));
        mvc.perform(get("/config/api/live-report/bilibili/1/latest"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.startTime").value(123456));
    }
    @Test void sendsOnlyTheConfirmedChannelAndReportsFailures() throws Exception {
        when(reports.sendLatest(eq("bilibili"), eq(1L), any(), eq(123456L)))
                .thenReturn(JSONObject.of("code", 0)).thenThrow(new IllegalStateException("消息未发出"));
        for (boolean success : new boolean[]{true, false}) {
            mvc.perform(post("/config/api/live-report/bilibili/1/send-latest")
                    .param("pushPlatform", "qq-onebot").param("type", "1").param("num", "1001").param("start", "123456"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(success));
        }
        verify(reports, times(2)).sendLatest(eq("bilibili"), eq(1L),
                argThat(target -> target.getPlatform().equals("qq-onebot") && target.getNum() == 1001L
                        && target.getType() == org.frostnova.nova.core.enums.PushTargetType.GROUP), eq(123456L));
    }
}
