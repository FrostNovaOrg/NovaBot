package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.frostnova.nova.core.enums.PushTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConfigUiPushTargetLookupTest {
    private static final String PATH = "/config/api/bot/targets/lookup";
    private static final String INVALID = "请填写推送平台、group 或 friend 类型与完整号码，不支持关键字";
    private static final ConfigUiAccount MEMBER = new ConfigUiAccount("member", ConfigUiAccount.Role.MEMBER,
            ConfigUiAccount.Status.ENABLED, "Member", null, null, null, List.of("bilibili:1"), Instant.EPOCH, null, true);

    private PushTargetLookup otherLookup;
    private PushTargetLookup lookup;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        otherLookup = mock(PushTargetLookup.class);
        lookup = mock(PushTargetLookup.class);
        when(otherLookup.supports("other")).thenReturn(true);
        when(lookup.supports("qq")).thenReturn(true);
        var platforms = mock(DataSourceServiceRegistry.class);
        when(platforms.platforms()).thenReturn(List.of("bilibili"));
        var controller = new ConfigUiPushTargetsController(List.of(), List.of(otherLookup, lookup), mock(AbstractDataSource.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class), null, platforms)).build();
    }

    @Test
    void exactGroupResponseContainsOnlyIdentity() throws Exception {
        when(lookup.lookup("qq", PushTargetType.GROUP, 81001, MEMBER))
                .thenReturn(new PushTargetLookup.Result(true, "Shared group", null));

        query("group", "81001").andExpect(status().isOk()).andExpect(jsonPath("$.found").value(true))
                .andExpect(content().json("""
                        {"success":true,"found":true,"platform":"qq","type":"group","num":81001,"name":"Shared group"}
                        """, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.configured").doesNotExist());
        verify(lookup).lookup("qq", PushTargetType.GROUP, 81001, MEMBER);
        verify(otherLookup, never()).lookup(anyString(), any(), anyLong(), any());
    }

    @Test
    void friendsAndMissingNumbersHaveSpecificResults() throws Exception {
        when(lookup.lookup("qq", PushTargetType.FRIEND, 91001, MEMBER))
                .thenReturn(new PushTargetLookup.Result(true, "Friend", null));
        when(lookup.lookup("qq", PushTargetType.GROUP, 89999, MEMBER))
                .thenReturn(new PushTargetLookup.Result(false, null, "机器人不在这个群"));
        when(lookup.lookup("qq", PushTargetType.FRIEND, 99999, MEMBER))
                .thenReturn(new PushTargetLookup.Result(false, null, "机器人没有这个好友，刚查过，请稍后再试"));

        query("friend", "91001").andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("Friend"))
                .andExpect(jsonPath("$.remark").doesNotExist()).andExpect(jsonPath("$.configured").doesNotExist());
        query("group", "89999").andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.message").value("机器人不在这个群"));
        query("friend", "99999").andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.message").value(containsString("机器人没有这个好友")));
    }

    @Test
    void unclaimedPlatformIsUnavailable() throws Exception {
        mvc.perform(request().param("platform", "unknown").param("type", "group").param("num", "81001"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"found":false,"platform":"unknown","type":"group","num":81001,"message":"推送平台不可用"}
                        """, JsonCompareMode.STRICT));
        verify(lookup, never()).lookup(anyString(), any(), anyLong(), any());
        verify(otherLookup, never()).lookup(anyString(), any(), anyLong(), any());
    }

    @Test
    void rejectsKeywordsAndMalformedNumbers() throws Exception {
        for (String num : List.of("Shared", "81*", "81.1", "0", "-1", "81001,91001", " 81001")) {
            query("group", num).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(INVALID));
        }
        query("groups", "81001").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(INVALID));
        mvc.perform(request().param("platform", " ").param("type", "group").param("num", "81001"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(INVALID));
        mvc.perform(request().param("platform", "qq").param("type", "group").param("num", "81001").param("q", "Shared"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(INVALID));
        mvc.perform(request().param("platform", "qq").param("type", "group")).andExpect(status().isBadRequest());
        verifyNoInteractions(lookup, otherLookup);
    }

    private ResultActions query(String type, String num) throws Exception {
        return mvc.perform(request().param("platform", "qq").param("type", type).param("num", num));
    }

    private MockHttpServletRequestBuilder request() {
        return get(PATH).requestAttr(ConfigUiAccess.ACCOUNT, MEMBER);
    }
}
