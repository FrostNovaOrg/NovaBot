package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.service.StarBotStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 初始设置的标记
 * <p>
 * 「重新跑一遍初始设置」按下之后要留下一个标记，初始设置页据它从第一步重新走。
 * 页面本体还没建，但<b>标记这一侧先立起来</b>：按钮若只是跳一下地址栏，
 * 那台机器上「使用者要求重来一遍」这件事就没有任何地方记得住，
 * 而他刷新一次页面就回到了原样。
 *
 * <h2>为什么落在运行状态里，不落进配置文件</h2>
 *
 * 这是<b>程序自己产生的状态</b>，不是使用者手写的配置。写进 application.yml 等于
 * 给配置面多一个键，而那个键使用者既不该手填也不该看见；
 * {@link StarBotStateStore} 正是为这类东西准备的，且它同样落盘、重启后还在。
 */
@Slf4j
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/setup")
@ConditionalOnProperty(name = "starbot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiSetupController {
    /**
     * 运行状态里的命名空间
     */
    static final String NAMESPACE = "config-ui-setup";

    /**
     * 请求重来一遍的时刻，ISO 格式。<b>记时刻而不是一个布尔</b>：
     * 日后要回答「这台机器上是什么时候重来的」时，答案得在盘上，而不是靠人回忆
     */
    static final String RERUN_KEY = "rerunRequestedAt";

    private final StarBotStateStore stateStore;

    public ConfigUiSetupController(StarBotStateStore stateStore) {
        this.stateStore = stateStore;
    }

    /**
     * 初始设置此刻的状态
     * <p>
     * 现在只回答一件事：有没有人要求重来一遍。五步各自走到哪归初始设置页那一笔。
     * @return 标记
     */
    @GetMapping("/state")
    public JSONObject state() {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("rerunRequestedAt", rerunRequestedAt());
        result.put("rerunRequested", rerunRequestedAt() != null);
        return result;
    }

    /**
     * 重新跑一遍初始设置
     * <p>
     * 只落标记，<b>不动任何已经配好的东西</b>：使用者按这个按钮是想再走一遍流程，
     * 不是想把机器人清空。界面上那句确认写的也是这个意思。
     * @return 标记与去处
     */
    @PostMapping("/rerun")
    public JSONObject rerun() {
        String at = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        stateStore.write(NAMESPACE, data -> data.put(RERUN_KEY, at));
        // 当场落盘而不是等下一次定时保存：这是使用者按下按钮之后立刻要跳走的一件事，
        // 中间进程若停了，他回来看到的是「按了没反应」
        stateStore.save();
        log.info("配置界面: 已标记重新跑一遍初始设置, 时间 {}", at);

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("rerunRequestedAt", at);
        result.put("message", "已标记。这就从第一步开始走一遍，已经配好的东西不会被清掉");
        return result;
    }

    private String rerunRequestedAt() {
        return stateStore.read(NAMESPACE, RERUN_KEY, data -> data.getString(RERUN_KEY)).orElse(null);
    }
}
