package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 把保存下来的配置改动落到运行中的程序上
 * <p>
 * 绝大多数配置项是启动时读一次就不再回头看的，改了只能等重启。但有几项<b>「等重启」等于没有这项功能</b>：
 * 「临时静音」要的就是现在生效，为此重启一次会把正在采集的场次打断；告警接收人配错了的时候，
 * 正需要告警的往往就是此刻。
 * <p>
 * <b>能即时生效的键是这里这份显式名单，不是推断出来的。</b>「把新值写回配置对象」这个动作本身
 * 到处都能做，但它只在<b>读取方每次都重新读</b>的前提下才真的管用——启动时把值抄进自己字段的组件，
 * 改了配置对象它也看不见。哪几项满足这个前提是一件需要逐项确认的事，写成名单才有地方确认；
 * 靠「凡是标了即时生效的都试着写回去」，就会出现界面说已生效而实际没有的情形，
 * 且<b>没有任何提示说它没生效</b>。
 * <p>
 * 名单与字段上的 {@code @ConfigEffect} 标注必须一一对应，两边对不上时构建会红
 * （判据在 {@code ConfigurationConsistencyTest}）。
 *
 * <h2>不在名单里的那两类</h2>
 * 「命令开关」与「金额可见」同样是即时生效的，但它们<b>不是 application.yml 里的配置项</b>——
 * 两者都按会话记在运行状态里，走 {@link RuntimeStateController} 那条路，本类够不着也不该够得着。
 */
@Slf4j
@Service
public class RuntimeConfigurationApplier {
    /**
     * 配置项名 → 把新值写回运行中的配置对象
     */
    private static final Map<String, BiConsumer<StarBotCoreProperties, String>> APPLIERS = new LinkedHashMap<>();

    static {
        // ---- 推送总开关 ----
        // PushGate 每次判断都重新读这一项，因此写回即生效
        APPLIERS.put("starbot.core.push.enabled", (properties, value) ->
                properties.getPush().setEnabled(Boolean.parseBoolean(value)));

        // ---- 静音时段 ----
        // 同上，PushGate 每条推送都现读一次起止时刻
        APPLIERS.put("starbot.core.push.quiet-start", (properties, value) ->
                properties.getPush().setQuietStart(value));
        APPLIERS.put("starbot.core.push.quiet-end", (properties, value) ->
                properties.getPush().setQuietEnd(value));

        // ---- 告警接收人 ----
        // QQ 这一路的收件人是「平台 + 类型 + 号码」三项合起来的一个地址，
        // 只让号码即时生效而类型要等重启，改一次群/私聊就会发到上一个地址去
        APPLIERS.put("starbot.core.alert.qq-platform", (properties, value) ->
                properties.getAlert().setQqPlatform(value));
        APPLIERS.put("starbot.core.alert.qq-type", (properties, value) ->
                properties.getAlert().setQqType(Integer.parseInt(value.trim())));
        APPLIERS.put("starbot.core.alert.qq-num", (properties, value) ->
                properties.getAlert().setQqNum(value == null || value.isBlank() ? null : Long.parseLong(value.trim())));
        APPLIERS.put("starbot.core.alert.webhook-url", (properties, value) ->
                properties.getAlert().setWebhookUrl(value));
        APPLIERS.put("starbot.core.mail.default-to", (properties, value) ->
                properties.getMail().setDefaultTo(value));
    }

    /**
     * 保存过、但要等重启才生效的配置项
     * <p>
     * 记在进程里而不是浏览器里，「等到重启」这件事才是准的：重启之后进程换了一个，这份记录随之消失，
     * 界面上那条提示自然收起。记在浏览器里的话，换台机器打开就看不见，
     * 而重启之后它还挂着——那条提示会一直在，直到有人手动点掉。
     */
    private final Set<String> pendingRestart = Collections.synchronizedSet(new LinkedHashSet<>());

    private final StarBotCoreProperties properties;

    @Autowired
    public RuntimeConfigurationApplier(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 名单里有哪些键
     * @return 能即时生效的配置项名
     */
    public static Set<String> supportedKeys() {
        return Collections.unmodifiableSet(APPLIERS.keySet());
    }

    /**
     * 把一批已写入配置文件的改动尽量落到运行中的程序上
     * <p>
     * 落不下的（不在名单里，或值的形式不对）一律计入待重启，并记在本实例上直到进程结束。
     * @param changes 已写入的配置项名到取值
     * @return 其中要等重启才生效的那些，按传入顺序
     */
    public List<String> applyAndTrack(Map<String, String> changes) {
        List<String> restartRequired = new ArrayList<>();

        for (Map.Entry<String, String> change : changes.entrySet()) {
            BiConsumer<StarBotCoreProperties, String> applier = APPLIERS.get(change.getKey());
            if (applier == null) {
                restartRequired.add(change.getKey());
                continue;
            }

            try {
                applier.accept(properties, change.getValue());
                log.info("配置项 {} 已即时生效", change.getKey());
            } catch (RuntimeException e) {
                // 值的形式不对时不当作已生效：界面写「已生效」而实际没变，比多重启一次糟得多
                log.warn("配置项 {} 的取值 {} 无法即时生效, 已按需重启处理: {}",
                        change.getKey(), change.getValue(), e.toString());
                restartRequired.add(change.getKey());
            }
        }

        pendingRestart.addAll(restartRequired);
        return restartRequired;
    }

    /**
     * 至今保存过、仍在等重启的配置项
     * @return 配置项名
     */
    public List<String> getPendingRestart() {
        synchronized (pendingRestart) {
            return List.copyOf(pendingRestart);
        }
    }
}
