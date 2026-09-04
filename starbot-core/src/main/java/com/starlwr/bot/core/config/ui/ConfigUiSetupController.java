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
 * 初始设置页上有两件事<b>推不出来</b>，只能记：使用者有没有要求重来一遍，
 * 以及第 5 步「发一条试试」发过没有。前四步各有服务端事实可查（上锁没有、探针红不红、
 * 平台登录没有、有没有主播，见界面侧的 setupSteps），唯独这两件事没有任何现象——
 * 不记的话，按钮点完刷新一次就回到了原样，而第 5 步会永远显示成没做过。
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

    /**
     * 发过「发一条试试」的时刻，ISO 格式
     * <p>
     * 同样记时刻而不是一个布尔：这一位日后会被问「这台机器上第一条消息是什么时候通的」，
     * 而那个答案得在盘上。
     */
    static final String TEST_SENT_KEY = "testSentAt";

    /**
     * 已经被收下的那一道「重来一遍」，值是<b>它请求时的那一串</b>，不是收下的时刻
     * <p>
     * 🔴 记「收的是哪一道」而不是「什么时候收的」：后者要拿两个时刻比先后，
     * 而这两个时刻可能带着不同的时区偏移，也可能落在同一秒里——同一秒那一档比不出先后，
     * 表现是使用者刚按下的「重新跑一遍」当场被当成已经收过的那一道。
     * 比的是同一个串是否相等，就没有精度这回事。
     */
    static final String RERUN_CONSUMED_KEY = "rerunConsumed";

    private final StarBotStateStore stateStore;

    public ConfigUiSetupController(StarBotStateStore stateStore) {
        this.stateStore = stateStore;
    }

    /**
     * 初始设置此刻的状态
     * <p>
     * 只回答那两件推不出来的事：有没有人要求重来一遍，以及第 5 步发过没有。
     * <b>前四步不在这里回答</b>——它们各有服务端事实可查，再在这里记一份的话，
     * 记下的那一份与事实分叉时，屏幕上会显示一个早已不成立的「完成」。
     * @return 标记
     */
    @GetMapping("/state")
    public JSONObject state() {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("rerunRequestedAt", rerunRequestedAt());
        result.put("rerunConsumed", consumedRerun());
        // 界面读的是这一位而不是 rerunRequestedAt：请求过、且这一道还没被收下，才算「还等着」
        result.put("rerunRequested", rerunPending());
        result.put("testSentAt", testSentAt());
        result.put("testSent", testSentAt() != null);
        return result;
    }

    /**
     * 记下「第 5 步发过了」
     * <p>
     * 由初始设置页在使用者按下「群里收到了，完成」时调。<b>不由发消息那一侧自己记</b>：
     * 那一侧知道的是「接口调通了」，而这一步问的是「群里的人看见了吗」——
     * 两者差着 QQ 掉登录、机器人被踢出群这几种情形，且它们的表现都是什么都不发生。
     * 这一位因此只能由看见了的那个人按下来。
     * @return 标记与时刻
     */
    @PostMapping("/test-sent")
    public JSONObject testSent() {
        String at = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        stateStore.write(NAMESPACE, data -> data.put(TEST_SENT_KEY, at));
        // 当场落盘，理由同 rerun：这是使用者按下按钮之后立刻要跳走的一件事
        stateStore.save();
        log.info("配置界面: 初始设置第五步已确认收到, 时间 {}", at);

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("testSentAt", at);
        result.put("message", "记下了。这五步定的东西都已经生效");
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
        // 🔴 这一串不截到秒：它同时是这一道标记的身份（见 RERUN_CONSUMED_KEY）。
        // 截到秒之后，同一秒里按第二次写出来的是同一串，而那一串刚刚才被收下——
        // 表现是「重新跑一遍」按了没反应
        String at = OffsetDateTime.now().toString();
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

    /**
     * 收下「重来一遍」这道标记
     * <p>
     * 初始设置页在开页、按标记落到第一步之后调它。<b>标记必须有人收</b>：
     * 只落不收的话，这台机器此后每一次打开初始设置页都被拉回第一步，
     * 而使用者只按过那一次按钮——那不是「重新跑一遍」，那是「从此只能从头走」。
     * <p>
     * 收下的方式是把<b>那一道的那一串</b>另记一份，而不是把请求那一条抹掉：抹掉之后，
     * 「这台机器上什么时候有人要求重来过」这件事就没有了，而它正是日后要查的那一条。
     * 没有待收的标记时什么也不做——那不是错，只是这一页照常打开了一次。
     * @return 收下的是哪一道
     */
    @PostMapping("/rerun/consumed")
    public JSONObject rerunConsumed() {
        String requested = rerunRequestedAt();
        if (requested != null) {
            stateStore.write(NAMESPACE, data -> data.put(RERUN_CONSUMED_KEY, requested));
            stateStore.save();
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("rerunConsumed", consumedRerun());
        return result;
    }

    private String rerunRequestedAt() {
        return stateStore.read(NAMESPACE, RERUN_KEY, data -> data.getString(RERUN_KEY)).orElse(null);
    }

    private String consumedRerun() {
        return stateStore.read(NAMESPACE, RERUN_CONSUMED_KEY, data -> data.getString(RERUN_CONSUMED_KEY))
                .orElse(null);
    }

    private String testSentAt() {
        return stateStore.read(NAMESPACE, TEST_SENT_KEY, data -> data.getString(TEST_SENT_KEY)).orElse(null);
    }

    /**
     * 这道「重来一遍」还等着人接吗
     * @return 请求过、且这一道还没被收下时为 true
     */
    private boolean rerunPending() {
        String requested = rerunRequestedAt();
        return requested != null && !requested.equals(consumedRerun());
    }
}
