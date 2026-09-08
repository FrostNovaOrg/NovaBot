package com.starlwr.bot.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.ConfigUiController;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.AtAllQuotaService;
import com.starlwr.bot.core.service.StarBotSenderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * @全体成员 的每日额度用量
 * <p>
 * 首页那一格「@全体成员 已用 3/10」问的就是这一份。在此之前这两个数<b>只出现在日志里</b>，
 * 而且只在用尽的那一刻出现一次——等看见那行 WARN 时，那条开播通知已经没 @ 到人了。
 * 界面提前看得见剩几次，才谈得上「今天少 @ 一次」。
 * <p>
 * <b>两个维度都给</b>（配额规则见 {@link AtAllQuotaService}）：账号那份额度<b>全部群共享</b>、
 * 每群另有自己的一份。只给一个的话，「这个群还剩 18 次」在账号额度已经见底时是句假话。
 * <p>
 * <b>各群那一列圈的是「已配置推送的群」</b>，账号那一行是全量真值。撤了推送配置、
 * 而今天已经花过额度的群不在这一列里，于是账号已用会大于各群之和——
 * 那个差额本身就是要说的话，不该在这里把它抹平。
 * <p>
 * 计数只活在内存里、按东八区的自然日重置，因此这里连同 {@code date} 一起给出：
 * 少了它，跨零点那一刻的一次刷新看起来就像「数字自己回去了」。
 */
@Slf4j
@StarBotComponent
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/at-all/quota")
@ConditionalOnProperty(name = "starbot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class AtAllQuotaController {
    private final AtAllQuotaService quota;

    private final AbstractDataSource dataSource;

    private final StarBotCoreProperties properties;

    private final StarBotSenderService senders;

    @Autowired
    public AtAllQuotaController(AtAllQuotaService quota, AbstractDataSource dataSource,
                                StarBotCoreProperties properties, StarBotSenderService senders) {
        this.quota = quota;
        this.dataSource = dataSource;
        this.properties = properties;
        this.senders = senders;
    }

    /**
     * 今日用量
     * @return 账号维度与会话维度各自的已用次数与上限
     */
    @GetMapping
    public JSONObject quota() {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("date", quota.today().toString());

        int botLimit = properties.getPush().getAtAllDailyLimit();
        int sessionLimit = properties.getPush().getAtAllSessionDailyLimit();

        JSONArray bots = new JSONArray();
        JSONArray sessions = new JSONArray();
        for (String platform : platforms()) {
            bots.add(usage(platform, null, quota.usedByBot(platform), botLimit));
        }
        for (PushTarget target : groups()) {
            sessions.add(usage(target.getPlatform(), target.getNum(),
                    quota.used(target.getPlatform(), target.getNum()), sessionLimit));
        }

        result.put("bots", bots);
        result.put("sessions", sessions);
        return result;
    }

    /**
     * 一行用量
     * <p>
     * {@code limited} 单列一栏，而不是让界面自己按「上限 ≤ 0」去认：那条约定写在配额服务里，
     * 抄到界面上的第二份迟早与它对不上，而对不上的表现是「不限」的那台机器上画出一个 0 的分母。
     * {@code platformName} 是适配器登记时自报的人话名；没报过就等于 {@code platform}。
     * 界面拿它做前缀，拿 {@code platform} 做键，两份不能在这里合成一份映射。
     * @param num 会话号，账号维度那一行为 null
     */
    private JSONObject usage(String platform, Long num, int used, int limit) {
        JSONObject item = new JSONObject();
        item.put("platform", platform);
        item.put("platformName", senders.displayName(platform));
        if (num != null) {
            item.put("num", num);
        }
        item.put("used", used);
        item.put("limit", limit);
        item.put("limited", limit > 0);
        return item;
    }

    /**
     * 配了推送的全部群会话，按平台与群号去重
     * <p>
     * 只取群聊：私聊没有 @全体成员 这回事，给它报一行 0/20 只会让人以为那里也有一份额度。
     */
    private Set<PushTarget> groups() {
        Set<PushTarget> targets = new LinkedHashSet<>();
        Set<Map.Entry<String, Long>> seen = new LinkedHashSet<>();
        for (PushUser user : dataSource.getAllUsers()) {
            for (PushTarget target : user.getTargets()) {
                if (PushTargetType.GROUP != target.getType() || target.getNum() == null) {
                    continue;
                }
                // 按平台与群号这一对去重，不拼字符串：配额的计数键已因平台名带冒号
                // 改成成对的键，同一个身份不该在这里又养出另一种拼法
                if (seen.add(Map.entry(target.getPlatform(), target.getNum()))) {
                    targets.add(target);
                }
            }
        }
        return targets;
    }

    /**
     * 有群会话的推送平台
     * <p>
     * 账号维度的额度按推送平台记：一台机器可以同时接好几个 QQ 号，各自有各自的十次。
     */
    private Set<String> platforms() {
        Set<String> platforms = new LinkedHashSet<>();
        for (PushTarget target : groups()) {
            platforms.add(target.getPlatform());
        }
        return platforms;
    }
}
