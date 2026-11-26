package org.frostnova.nova.adapter.outbox;

import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.ConfigUiApiInterceptor;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.enums.PushTargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OutboxConversationTest {
    @TempDir Path data;

    @Test @DisplayName("管理员改名后找不到旧会话、成员能改限量，或者重启后停用的会话又能被选中")
    void consoleChangesPersistAndRespectRole() throws Exception {
        var directory = new OutboxConversations(new OutboxDatabase(data));
        var controller = new OutboxConversationController(directory);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class), null,
                        mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class))).build();
        String url = "/config/api/outbox/conversations";
        var admin = ConfigUiAccount.local(Instant.EPOCH);
        var member = new ConfigUiAccount("member", ConfigUiAccount.Role.MEMBER, ConfigUiAccount.Status.ENABLED,
                "成员", null, null, null, List.of(), Instant.EPOCH, null, true);
        mvc.perform(post(url).requestAttr(ConfigUiAccess.ACCOUNT, admin).contentType("application/json")
                .content("{\"name\":\"早班\",\"remark\":\"通知\",\"dailyLimit\":3}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.id").value(1));
        mvc.perform(put(url + "/1").requestAttr(ConfigUiAccess.ACCOUNT, member).contentType("application/json")
                .content("{\"dailyLimit\":20}")).andExpect(status().isForbidden());
        mvc.perform(put(url + "/1").requestAttr(ConfigUiAccess.ACCOUNT, admin).contentType("application/json")
                .content("{\"name\":\"午班\",\"minIntervalSeconds\":4}")).andExpect(status().isOk());
        mvc.perform(get(url).requestAttr(ConfigUiAccess.ACCOUNT, member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.conversations[0].name").value("午班"))
                .andExpect(jsonPath("$.conversations[0].dailyLimit").value(3))
                .andExpect(jsonPath("$.conversations[0].minIntervalSeconds").value(4));
        var reopened = new OutboxConversations(new OutboxDatabase(data));
        assertEquals("午班", reopened.lookup("outbox", PushTargetType.GROUP, 1, member).name());
        assertEquals("通知", reopened.snapshots(false).get(0).groups().get(0).remark());
        mvc.perform(post(url + "/1/disable").requestAttr(ConfigUiAccess.ACCOUNT, admin)).andExpect(status().isOk());
        assertTrue(reopened.snapshots(false).get(0).groups().isEmpty());
        assertFalse(reopened.lookup("outbox", PushTargetType.GROUP, 1, member).found());
        assertEquals(2, reopened.create("晚班", null, 0, 0).id());
    }

    @Test @DisplayName("空白名称、负数或小数限量被保存后无法正确选择和限流")
    void rejectsInvalidSettings() throws Exception {
        var conversations = new OutboxConversations(new OutboxDatabase(data));
        var mvc = MockMvcBuilders.standaloneSetup(new OutboxConversationController(conversations)).build();
        for (var body : List.of("{\"name\":\"  \"}", "{\"name\":\"测试\",\"dailyLimit\":-1}",
                "{\"name\":\"测试\",\"minIntervalSeconds\":0.1}")) {
            mvc.perform(post("/config/api/outbox/conversations").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        assertTrue(conversations.list().isEmpty());
    }
}
