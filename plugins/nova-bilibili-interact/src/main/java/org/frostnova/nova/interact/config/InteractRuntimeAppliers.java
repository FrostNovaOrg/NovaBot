package org.frostnova.nova.interact.config;

import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.interact.danmaku.DanmakuAccountChoice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 弹幕那三项保存后写回运行中的配置
 * <p>
 * 三项的读取方都在发弹幕那一刻现读（{@code LiveDanmakuService#send}），写回即生效，不用重启：
 * 改了账号，下一条就换号发；改了间隔或暂停时长，下一条就按新值算。
 * <p>
 * 一批里的项<b>先全部读懂、再一起写回</b>，有一项读不懂就一项也不写；读不懂的取值在写配置文件之前
 * 就由 {@link #valueValidators()} 拒收。删键回到默认值。
 * 把账号改成监听账号要先问一句（{@link #saveConfirmations()}）：那是唯一一个会连累监听的选择。
 */
@Slf4j
@NovaComponent
public class InteractRuntimeAppliers implements RuntimeConfigurationApplierContributor {
    private static final String PREFIX = InteractConfigurationGroups.PREFIX + ".danmaku.";

    /**
     * 发弹幕用哪个账号
     */
    public static final String ACCOUNT = PREFIX + "account";

    /**
     * 两条之间的最短间隔，秒
     */
    public static final String MIN_INTERVAL = PREFIX + "min-interval";

    /**
     * 被限制后的暂停时长，分钟
     */
    public static final String PAUSE_MINUTES = PREFIX + "pause-minutes";

    /**
     * 选监听账号之前问的那一句
     */
    public static final String LISTENER_WARNING = "这个号如果因为发弹幕被平台限制，所有主播的监听、开播通知和直播报告会一起受影响。"
            + "确定用监听直播间的账号发弹幕吗？";

    private static final InteractProperties.Danmaku DEFAULTS = new InteractProperties.Danmaku();

    private static final Map<String, Setting<?>> SETTINGS = new LinkedHashMap<>();

    static {
        SETTINGS.put(ACCOUNT, new Setting<>("发弹幕用哪个账号", DEFAULTS.getAccount(),
                InteractRuntimeAppliers::account, InteractProperties.Danmaku::setAccount));
        SETTINGS.put(MIN_INTERVAL, new Setting<>("弹幕 · 最短间隔", DEFAULTS.getMinInterval(),
                value -> atLeast(value, 1, "秒"), InteractProperties.Danmaku::setMinInterval));
        SETTINGS.put(PAUSE_MINUTES, new Setting<>("弹幕 · 被限制后暂停", DEFAULTS.getPauseMinutes(),
                value -> atLeast(value, 1, "分钟"), InteractProperties.Danmaku::setPauseMinutes));
    }

    private final InteractProperties properties;

    /**
     * 只给按名单清点即时生效项的检查用：写回的是一份自己的配置
     */
    public InteractRuntimeAppliers() {
        this(new InteractProperties());
    }

    @Autowired
    public InteractRuntimeAppliers(InteractProperties properties) {
        this.properties = properties;
    }

    /**
     * @return 本类写回的配置项全名，登记顺序
     */
    public static List<String> keys() {
        return List.copyOf(SETTINGS.keySet());
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
        return Map.of();
    }

    @Override
    public BatchApplier batchApplier() {
        return new BatchApplier(SETTINGS.keySet(), this::apply);
    }

    @Override
    public Map<String, Function<String, String>> valueValidators() {
        Map<String, Function<String, String>> validators = new LinkedHashMap<>();
        SETTINGS.forEach((key, setting) -> validators.put(key, value -> {
            if (value == null) {
                return null;
            }
            try {
                setting.parse().apply(value);
                return null;
            } catch (IllegalArgumentException invalid) {
                return key + "（" + setting.label() + "）：" + invalid.getMessage();
            }
        }));
        return validators;
    }

    @Override
    public Map<String, Function<String, String>> saveConfirmations() {
        return Map.of(ACCOUNT, value -> DanmakuAccountChoice.parse(value) == DanmakuAccountChoice.LISTENER
                ? LISTENER_WARNING : null);
    }

    /**
     * 先把这一批全部读懂，再在同一把锁下一起写回；有一项读不懂就抛出，一项也不写
     */
    void apply(Map<String, String> changes) {
        List<Consumer<InteractProperties.Danmaku>> updates = new ArrayList<>();
        changes.forEach((key, value) -> updates.add(SETTINGS.get(key).prepare(value)));
        InteractProperties.Danmaku danmaku = properties.getDanmaku();
        synchronized (danmaku) {
            updates.forEach(update -> update.accept(danmaku));
        }
        if (changes.containsKey(ACCOUNT)) {
            log.info("设置页改了发弹幕用的账号，下一条起用{}",
                    DanmakuAccountChoice.of(danmaku.getAccount()).label());
        }
    }

    private record Setting<T>(String label, T fallback, Function<String, T> parse,
                              BiConsumer<InteractProperties.Danmaku, T> write) {
        Consumer<InteractProperties.Danmaku> prepare(String value) {
            T parsed = value == null ? fallback : parse.apply(value);
            return target -> write.accept(target, parsed);
        }
    }

    private static String account(String value) {
        DanmakuAccountChoice choice = DanmakuAccountChoice.parse(value);
        if (choice == null) {
            throw new IllegalArgumentException("只能是 speaker（单独的发言账号）或 listener（和监听直播间用的同一个账号）");
        }
        return choice.value();
    }

    private static Integer atLeast(String value, int minimum, String unit) {
        Integer number;
        try {
            number = ApplicationConversionService.getSharedInstance().convert(value.strip(), Integer.class);
        } catch (RuntimeException e) {
            number = null;
        }
        if (number == null) {
            throw new IllegalArgumentException("请填写整数");
        }
        if (number < minimum) {
            throw new IllegalArgumentException("最少 " + minimum + " " + unit);
        }
        return number;
    }
}
