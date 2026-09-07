package com.starlwr.bot.core.service;

import com.starlwr.bot.core.handler.StarBotEventHandler;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StarBot 事件处理器服务
 */
@Slf4j
@Service
public class StarBotEventHandlerService {
    private final ApplicationContext applicationContext;

    private final Map<String, StarBotEventHandler> cache = new HashMap<>();

    /**
     * 旧全类名 → 处理器，见 {@link StarBotEventHandler#legacyClassNames()}
     * <p>
     * <b>与主表分开放</b>：主表是「现在有哪些处理器」，配置界面的勾选项、随包示例与
     * 保存前校验都按它来；旧名只是「以前这么写过」，混进主表就等于把一个已经不存在的
     * 类名重新发给使用者。
     */
    private final Map<String, StarBotEventHandler> aliases = new HashMap<>();

    /**
     * 已经提醒过的旧名。提醒每个旧名只出一条：查处理器这件事每推一条消息就发生一次，
     * 每次都刷一条的话，真正要看的日志会被这一条淹掉
     */
    private final Set<String> warnedLegacyNames = ConcurrentHashMap.newKeySet();

    @Autowired
    public StarBotEventHandlerService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 加载事件处理器
     */
    @Order(0)
    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshedEvent() {
        for (StarBotEventHandler handler : applicationContext.getBeansOfType(StarBotEventHandler.class).values()) {
            cache.put(handler.getClass().getName(), handler);
        }

        // 主表建完才建别名表：某个旧名如今正好是另一个处理器的真类名时，那个真类名说了算
        for (StarBotEventHandler handler : cache.values()) {
            for (String legacy : handler.legacyClassNames()) {
                if (legacy == null || legacy.isBlank() || cache.containsKey(legacy)) {
                    continue;
                }

                StarBotEventHandler previous = aliases.put(legacy, handler);
                if (previous != null && previous != handler) {
                    log.error("旧处理器类名 {} 被 {} 与 {} 同时认领, 本次按后者办, 请检查插件",
                            legacy, previous.getClass().getName(), handler.getClass().getName());
                }
            }
        }
    }

    /**
     * 获取事件处理器
     * <p>
     * 先按真类名查，查不到再按旧名回落查一次：处理器换过包名之后，使用者
     * {@code datasource.json} 里写的仍是旧名，认不出来的表现是那一类推送不发也不报错。
     * @param handlerClass 处理器全类名
     * @return 事件处理器
     */
    public Optional<StarBotEventHandler> getHandler(@NonNull String handlerClass) {
        StarBotEventHandler handler = cache.get(handlerClass);
        if (handler != null) {
            return Optional.of(handler);
        }

        StarBotEventHandler byLegacyName = aliases.get(handlerClass);
        if (byLegacyName == null) {
            return Optional.empty();
        }

        if (warnedLegacyNames.add(handlerClass)) {
            log.warn("配置里写的 {} 是旧名字, 这个处理器现在叫 {}, 建议改过来; 这一版仍按旧名认",
                    handlerClass, byLegacyName.getClass().getName());
        }
        return Optional.of(byLegacyName);
    }

    /**
     * 获取全部已注册的事件处理器全类名
     * <p>
     * 供推送配置的保存前校验使用：处理器类名写错时应在保存时就指出来，而不是等到运行期
     * 才以「找不到处理器」的形式静默失败。
     * @return 已注册的处理器全类名
     */
    public Set<String> getRegisteredHandlerClasses() {
        return Set.copyOf(cache.keySet());
    }

    /**
     * 保存推送配置时认得的全部处理器全类名：真类名加上旧名
     * <p>
     * 与 {@link #getRegisteredHandlerClasses()} 分成两个方法，是因为两处问的不是同一件事：
     * 那一处问「现在有哪些处理器」，答案要发给界面与示例，旧名不许混进去；这一处问
     * 「这一串在运行期认不认得出」，答案要用来判断该不该拦下保存。两处合成一处的话，
     * 必有一头是错的——运行期认得的名字在保存时被拒，或者已经不存在的类名被界面重新发出去。
     * @return 认得的处理器全类名
     */
    public Set<String> getAcceptedHandlerClasses() {
        // 主表从上面那个方法取，不另读一遍 cache：「现在有哪些处理器」只该有一处说了算，
        // 两处各读各的话，改了一处而另一处没跟上时两边会安静地分叉
        Set<String> names = new HashSet<>(getRegisteredHandlerClasses());
        names.addAll(aliases.keySet());
        return Set.copyOf(names);
    }

    /**
     * 获取全部已注册的事件处理器
     * <p>
     * 供配置界面渲染「推送哪些事件」的勾选项：处理器由此列表驱动，插件新增处理器时界面自动出现，
     * 不需要前端硬编码任何类名。
     * @return 处理器全类名到实例的映射
     */
    public Map<String, StarBotEventHandler> getRegisteredHandlers() {
        return Map.copyOf(cache);
    }
}
