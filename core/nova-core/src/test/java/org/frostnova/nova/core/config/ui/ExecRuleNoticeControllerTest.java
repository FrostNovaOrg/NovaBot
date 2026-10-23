package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.exec.EventCommandRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("控制台：外部程序触发规则待调整清单")
class ExecRuleNoticeControllerTest {
    @TempDir
    Path dir;

    private NovaCoreProperties properties;
    private EventCommandRunner runner;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        properties.getExec().setEnabled(true);
        runner = new EventCommandRunner(properties);
    }

    @AfterEach
    void close() {
        runner.shutdown();
    }

    @Test
    @DisplayName("启动检查标出的规则按序号与事件名下发")
    void listsRulesMarkedAtStartup() {
        properties.getExec().getRules().add(rule("LiveOnEvent", List.of("tool", "--", "{uname}")));
        properties.getExec().getRules().add(rule("LiveOffEvent", Arrays.asList("{uname}", "value")));
        properties.getExec().getRules().add(rule("LiveOnEvent", List.of("tool", "{uname}")));
        runner.checkConfiguration();

        JSONObject result = new ExecRuleNoticeController(runner).notice();
        assertTrue(result.getBooleanValue("success"));
        JSONArray rules = result.getJSONArray("rules");
        assertEquals(2, rules.size());
        assertEquals(1, rules.getJSONObject(0).getIntValue("index"));
        assertEquals("novabot.core.exec.rules[1]", rules.getJSONObject(0).getString("key"));
        assertEquals("LiveOffEvent", rules.getJSONObject(0).getString("event"));
        assertEquals(2, rules.getJSONObject(1).getIntValue("index"));
        assertEquals("novabot.core.exec.rules[2]", rules.getJSONObject(1).getString("key"));
    }

    @Test
    @DisplayName("没有要调整的规则时下发空清单")
    void listsNothingForCurrentConfiguration() {
        properties.getExec().getRules().add(rule("LiveOnEvent", List.of("tool", "--name={uname}")));
        runner.checkConfiguration();

        JSONObject result = new ExecRuleNoticeController(runner).notice();
        assertTrue(result.getBooleanValue("success"));
        assertEquals(0, result.getJSONArray("rules").size());
    }

    @Test
    @DisplayName("保存未生效时仍提醒，重新启动后按新配置显示")
    void noticeTracksRunningConfigurationUntilRestart() throws Exception {
        Path config = dir.resolve("application.yml");
        String initial = "novabot:\n  core:\n    exec:\n      enabled: true\n      rules:\n"
                + "        - event: LiveOnEvent\n          command: [tool, '{uname}']\n";
        Files.writeString(config, initial);
        var active = load(config);
        try (var original = new RunnerScope(active)) {
            original.runner.checkConfiguration();
            assertEquals(1, new ExecRuleNoticeController(original.runner).notice().getJSONArray("rules").size());
            Files.writeString(config, initial.replace("[tool, '{uname}']", "[tool, '--', '{uname}']"));
            assertEquals(1, new ExecRuleNoticeController(original.runner).notice().getJSONArray("rules").size());
            try (var restarted = new RunnerScope(load(config))) {
                restarted.runner.checkConfiguration();
                assertTrue(new ExecRuleNoticeController(restarted.runner).notice().getJSONArray("rules").isEmpty());
            }
        }
    }

    @Test
    @DisplayName("成员与未登录访问不取得规则清单和工程日志，管理员可以查看")
    void noticeAndLogFollowCurrentAccount() throws Exception {
        properties.getExec().getRules().add(rule("ExampleEvent", List.of("{uname}")));
        runner.checkConfiguration();
        var mvc = MockMvcBuilders.standaloneSetup(new ExecRuleNoticeController(runner),
                        new EngineeringLogController(mock(EngineeringLogService.class)))
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class))).build();
        var member = new ConfigUiAccount("member-example", ConfigUiAccount.Role.MEMBER,
                ConfigUiAccount.Status.ENABLED, "Example Member", null, null, null, List.of(), Instant.EPOCH, null, true);
        for (String path : List.of(ExecRuleNoticeController.PATH, "/config/api/engineering-log")) {
            for (var account : List.of(member, ConfigUiAccount.local(Instant.EPOCH), member)) {
                var response = mvc.perform(get(path).requestAttr(ConfigUiAccess.ACCOUNT, account))
                        .andExpect(account.role() == ConfigUiAccount.Role.ADMIN ? status().isOk() : status().isForbidden())
                        .andReturn().getResponse().getContentAsString();
                if (account.role() == ConfigUiAccount.Role.MEMBER) {
                    assertTrue(!response.contains("ExampleEvent") && !response.contains("novabot.core.exec.rules"));
                }
            }
            var response = mvc.perform(get(path)).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
            assertTrue(!response.contains("ExampleEvent") && !response.contains("novabot.core.exec.rules"));
        }
    }

    private static NovaCoreProperties load(Path config) throws Exception {
        var sources = new YamlPropertySourceLoader().load("settings", new FileSystemResource(config));
        return new Binder(ConfigurationPropertySources.from(sources)).bind("novabot.core", Bindable.of(NovaCoreProperties.class)).get();
    }

    private static class RunnerScope implements AutoCloseable {
        final EventCommandRunner runner;

        RunnerScope(NovaCoreProperties properties) {
            runner = new EventCommandRunner(properties);
        }

        public void close() {
            runner.shutdown();
        }
    }

    private static NovaCoreProperties.ExecRule rule(String event, List<String> command) {
        var rule = new NovaCoreProperties.ExecRule();
        rule.setEvent(event);
        rule.setCommand(command);
        return rule;
    }
}
