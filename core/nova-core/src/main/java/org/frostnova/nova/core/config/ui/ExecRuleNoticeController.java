package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.exec.EventCommandRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部程序触发规则的待调整清单
 * <p>
 * 控制台顶上那条提醒照这一份显示。清单按实际生效的配置与本次执行记录更新，改好配置并重启后为空。
 * 只给管理员，见 {@link ConfigUiPermissions}。
 */
@RestController
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ExecRuleNoticeController {
    static final String PATH = ConfigUiController.BASE_PATH + "/api/exec/notice";

    private final EventCommandRunner runner;

    @Autowired
    public ExecRuleNoticeController(EventCommandRunner runner) {
        this.runner = runner;
    }

    /**
     * 待调整的规则：序号、配置项位置与事件名；没有时 {@code rules} 为空数组
     */
    @GetMapping(PATH)
    public JSONObject notice() {
        JSONArray rules = new JSONArray();
        for (EventCommandRunner.RuleToAdjust rule : runner.rulesToAdjust()) {
            JSONObject item = new JSONObject();
            item.put("index", rule.index());
            item.put("key", "novabot.core.exec.rules[" + rule.index() + "]");
            item.put("event", rule.event() == null ? "" : rule.event());
            rules.add(item);
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("rules", rules);
        return result;
    }
}
