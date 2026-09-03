package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.ConfigEffect;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置项生效时机解析器
 * <p>
 * 从 {@code @ConfigurationProperties} 类上反射读取 {@link ConfigEffect}，
 * 得到「配置项名 → 生效时机」。「哪个键对应哪个字段」这条规则不在本类里，
 * 见 {@link ConfigurationPropertyFields}。
 * <p>
 * <b>没标的项在这份映射里不出现，本类不替它补一个默认值。</b>补一个「重启生效」看起来更稳妥，
 * 实际是把「这一项还没人回答过」伪装成「已经答过了，答案是重启」——而后者<b>没有任何东西会再去纠正它</b>。
 * 界面拿不到时按需重启显示（往安全的方向失败），同时这里在启动日志里点名，
 * 保证漏标这件事在跑起来的程序上也看得见，不只在构建时红一下。
 */
@Slf4j
@Service
public class ConfigurationEffectResolver {
    private final ApplicationContext context;

    private volatile Map<String, ConfigEffect.Effect> effects;

    @Autowired
    public ConfigurationEffectResolver(ApplicationContext context) {
        this.context = context;
    }

    /**
     * 获取配置项名到生效时机的映射
     * @return 映射，未标注的配置项不在其中
     */
    public Map<String, ConfigEffect.Effect> getEffects() {
        if (effects == null) {
            synchronized (this) {
                if (effects == null) {
                    effects = resolve();
                }
            }
        }

        return effects;
    }

    /**
     * 一批配置项里有哪些需要重启才生效
     * <p>
     * 未标注的一并算进来：界面据此提示使用者重启，最坏结果是白重启一次；
     * 反过来把没标的算成即时生效，则是承诺一件谁也没保证过的事。
     * @param names 配置项名
     * @return 其中需要重启的那些，保持传入顺序
     */
    public List<String> restartRequired(Iterable<String> names) {
        Map<String, ConfigEffect.Effect> resolved = getEffects();

        List<String> result = new ArrayList<>();
        for (String name : names) {
            if (resolved.get(name) != ConfigEffect.Effect.IMMEDIATE) {
                result.add(name);
            }
        }

        return result;
    }

    private Map<String, ConfigEffect.Effect> resolve() {
        Map<String, ConfigEffect.Effect> result = new HashMap<>(ExternalConfigurationFields.effects());

        List<String> unmarked = new ArrayList<>();
        for (Map.Entry<String, Field> entry : ConfigurationPropertyFields.scan(context).entrySet()) {
            ConfigEffect effect = entry.getValue().getAnnotation(ConfigEffect.class);
            if (effect != null) {
                result.put(entry.getKey(), effect.value());
            } else if (entry.getKey().startsWith("starbot.")) {
                unmarked.add(entry.getKey());
            }
        }

        if (unmarked.isEmpty()) {
            log.info("已解析 {} 个配置项的生效时机", result.size());
        } else {
            // 插件是运行期装进来的，构建期那道判据管不到它们。这一行是它们唯一会被点名的地方
            log.warn("已解析 {} 个配置项的生效时机, 另有 {} 项未标注, 界面对它们一律按需重启显示: {}",
                    result.size(), unmarked.size(), String.join(", ", unmarked));
        }

        return Map.copyOf(result);
    }
}
