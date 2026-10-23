package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.exec.EventCommandRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("控制台：外部程序触发规则待调整清单")
class ExecRuleNoticeControllerTest {
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

    private static NovaCoreProperties.ExecRule rule(String event, List<String> command) {
        var rule = new NovaCoreProperties.ExecRule();
        rule.setEvent(event);
        rule.setCommand(command);
        return rule;
    }
}
