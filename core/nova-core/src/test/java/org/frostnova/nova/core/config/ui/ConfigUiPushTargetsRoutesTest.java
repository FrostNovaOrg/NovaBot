package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConfigUiPushTargetsRoutesTest {
    private static final String PATH = "/config/api/bot/targets";
    private static final String SWITCH = "novabot.core.config-ui.enabled";
    private static final Instant FETCHED_AT = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void mergesTargetsFromTwoPlugins() throws Exception {
        var first = directory("bot-a", 81001, "晨间通知", FETCHED_AT);
        var second = directory("bot-b", 81002, "晚间通知", FETCHED_AT.minusSeconds(60));
        try (var context = context()) {
            context.registerBean("firstDirectory", PushTargetDirectory.class, () -> first);
            context.registerBean("secondDirectory", PushTargetDirectory.class, () -> second);
            context.refresh();
            var mvc = mvc(context);
            mvc.perform(get(PATH).param("type", "group"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.items[*].sender", contains("bot-a", "bot-b")))
                    .andExpect(jsonPath("$.items[*].num", contains(81001, 81002)))
                    .andExpect(jsonPath("$.senders[*].sender", contains("bot-a", "bot-b")))
                    .andExpect(jsonPath("$.fetchedAt").value(FETCHED_AT.minusSeconds(60).toString()))
                    .andExpect(jsonPath("$.stale").value(false));
            mvc.perform(get(PATH).param("type", "friend"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[*].sender", contains("bot-a", "bot-b")))
                    .andExpect(jsonPath("$.items[*].num", contains(91001, 91002)));
            mvc.perform(post(PATH + "/refresh"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.senders[*].sender", contains("bot-a", "bot-b")));
            verify(first).snapshots(true);
            verify(second).snapshots(true);
        }
    }

    @Test
    void oneOfflinePluginLeavesOtherTargetsAvailable() throws Exception {
        var offline = mock(PushTargetDirectory.class);
        when(offline.id()).thenReturn("bot-a");
        when(offline.snapshots(anyBoolean())).thenThrow(new IllegalStateException("连接超时"));
        var healthy = directory("bot-b", 81002, "晚间通知", FETCHED_AT);
        try (var context = context()) {
            context.registerBean("offlineDirectory", PushTargetDirectory.class, () -> offline);
            context.registerBean("healthyDirectory", PushTargetDirectory.class, () -> healthy);
            context.refresh();
            var mvc = mvc(context);
            mvc.perform(get(PATH).param("type", "group"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.stale").value(true))
                    .andExpect(jsonPath("$.items[*].sender", contains("bot-b")))
                    .andExpect(jsonPath("$.fetchedAt").value(FETCHED_AT.toString()))
                    .andExpect(jsonPath("$.senders[*].sender", contains("bot-a", "bot-b")))
                    .andExpect(jsonPath("$.senders[0].stale").value(true))
                    .andExpect(jsonPath("$.senders[0].message").value("连接超时"))
                    .andExpect(jsonPath("$.senders[1].stale").value(false));
            mvc.perform(post(PATH + "/refresh"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.stale").value(true))
                    .andExpect(jsonPath("$.senders[*].sender", contains("bot-a", "bot-b")))
                    .andExpect(jsonPath("$.senders[0].message").value("连接超时"));
            verify(healthy).snapshots(true);
        }
    }

    @Test
    void listWithoutBotPluginsIsEmpty() throws Exception {
        try (var context = context()) {
            context.refresh();
            mvc(context).perform(get(PATH).param("type", "group"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.items").isEmpty())
                    .andExpect(jsonPath("$.senders").isEmpty())
                    .andExpect(jsonPath("$.stale").value(false));
        }
    }

    @Test
    void controllerIsNotRegisteredWhenConsoleIsDisabled() {
        var gate = ConfigUiPushTargetsController.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(gate);
        assertArrayEquals(new String[]{SWITCH}, gate.name());
        assertEquals("true", gate.havingValue());
        assertTrue(gate.matchIfMissing());

        try (var context = context(SWITCH + "=false")) {
            context.refresh();
            assertTrue(context.getBeansOfType(ConfigUiPushTargetsController.class).isEmpty());
        }
    }

    private static PushTargetDirectory directory(String sender, long num, String name, Instant fetchedAt) {
        var directory = mock(PushTargetDirectory.class);
        when(directory.snapshots(anyBoolean())).thenReturn(List.of(new PushTargetDirectory.Snapshot(sender,
                List.of(new PushTargetDirectory.Group(num, name, 12, true)),
                List.of(new PushTargetDirectory.Friend(num + 10000, "值班账号", "")),
                fetchedAt, fetchedAt, false, null)));
        return directory;
    }

    private static AnnotationConfigApplicationContext context(String... properties) {
        var context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of(properties).applyTo(context);
        context.registerBean(AbstractDataSource.class, () -> mock(AbstractDataSource.class));
        context.register(ConfigUiPushTargetsController.class);
        return context;
    }

    private static MockMvc mvc(AnnotationConfigApplicationContext context) {
        return MockMvcBuilders.standaloneSetup(context.getBean(ConfigUiPushTargetsController.class)).build();
    }
}
