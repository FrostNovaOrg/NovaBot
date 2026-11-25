package org.frostnova.nova.core.assistant;

import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** 校验设置页的改动，并将同批助手设置一起交给后续的提问和试连。 */
public final class AssistantRuntimeConfiguration implements RuntimeConfigurationApplierContributor {
    private static final String PREFIX = "novabot.assistant.";
    private static final String EXCLUDED_UIDS = PREFIX + "excluded-uids";
    private static final AssistantProperties DEFAULTS = new AssistantProperties();

    private static final Map<String, Function<String, Consumer<AssistantProperties>>> SETTINGS = Map.ofEntries(
            setting("enabled", DEFAULTS.isEnabled(), value -> bool(value, "启用智能助手"), AssistantProperties::setEnabled),
            setting("base-url", DEFAULTS.getBaseUrl(), Function.identity(), AssistantProperties::setBaseUrl),
            setting("model", DEFAULTS.getModel(), Function.identity(), AssistantProperties::setModel),
            setting("api-format", DEFAULTS.getApiFormat(), Function.identity(), AssistantProperties::setApiFormat),
            setting("thinking", DEFAULTS.getThinking(), Function.identity(), AssistantProperties::setThinking),
            setting("adaptive-thinking", DEFAULTS.isAdaptiveThinking(),
                    value -> bool(value, "按难度调思考强度"), AssistantProperties::setAdaptiveThinking),
            setting("hard-thinking", DEFAULTS.getHardThinking(), Function.identity(), AssistantProperties::setHardThinking),
            setting("difficulty-model", DEFAULTS.getDifficultyModel(), Function.identity(), AssistantProperties::setDifficultyModel),
            setting("hard-timeout-seconds", DEFAULTS.getHardTimeoutSeconds(), AssistantRuntimeConfiguration::integer,
                    AssistantProperties::setHardTimeoutSeconds),
            setting("weekly-budget-yuan", DEFAULTS.getWeeklyBudgetYuan(),
                    value -> budget(value, DEFAULTS.getWeeklyBudgetYuan()), AssistantProperties::setWeeklyBudgetYuan),
            setting("streamer-daily-budget-yuan", DEFAULTS.getStreamerDailyBudgetYuan(),
                    value -> budget(value, DEFAULTS.getStreamerDailyBudgetYuan()), AssistantProperties::setStreamerDailyBudgetYuan),
            setting("streamer-weekly-budget-yuan", DEFAULTS.getStreamerWeeklyBudgetYuan(),
                    value -> budget(value, DEFAULTS.getStreamerWeeklyBudgetYuan()), AssistantProperties::setStreamerWeeklyBudgetYuan),
            setting("answer-limit", DEFAULTS.getAnswerLimit(), AssistantRuntimeConfiguration::integer, AssistantProperties::setAnswerLimit),
            setting("price-input-yuan-per-million", DEFAULTS.getPriceInputYuanPerMillion(), AssistantRuntimeConfiguration::price,
                    AssistantProperties::setPriceInputYuanPerMillion),
            setting("price-cached-input-yuan-per-million", DEFAULTS.getPriceCachedInputYuanPerMillion(),
                    AssistantRuntimeConfiguration::price, AssistantProperties::setPriceCachedInputYuanPerMillion),
            setting("price-output-yuan-per-million", DEFAULTS.getPriceOutputYuanPerMillion(), AssistantRuntimeConfiguration::price,
                    AssistantProperties::setPriceOutputYuanPerMillion),
            setting("max-tool-calls", DEFAULTS.getMaxToolCalls(), AssistantRuntimeConfiguration::integer, AssistantProperties::setMaxToolCalls),
            setting("question-input-limit", DEFAULTS.getQuestionInputLimit(), AssistantRuntimeConfiguration::integer,
                    AssistantProperties::setQuestionInputLimit),
            setting("excluded-uids", DEFAULTS.getExcludedUids(), ExcludedViewersStore::parse, AssistantProperties::setExcludedUids));

    private final AssistantProperties properties;
    private final ExcludedViewersStore excludedViewers;

    public AssistantRuntimeConfiguration(AssistantProperties properties, ExcludedViewersStore excludedViewers) {
        this.properties = properties;
        this.excludedViewers = excludedViewers;
    }

    @Override public Map<String, Consumer<String>> appliers() { return Map.of(); }

    @Override public BatchApplier batchApplier() { return new BatchApplier(SETTINGS.keySet(), this::apply); }

    @Override
    public Map<String, Function<String, String>> valueValidators() {
        Map<String, Function<String, String>> validators = new LinkedHashMap<>();
        SETTINGS.forEach((key, prepare) -> validators.put(key, value -> {
            try {
                prepare.apply(value);
                return null;
            } catch (IllegalArgumentException invalid) {
                return invalid.getMessage();
            }
        }));
        return validators;
    }

    private void apply(Map<String, String> changes) {
        var updates = new ArrayList<Consumer<AssistantProperties>>();
        changes.forEach((key, value) -> updates.add(SETTINGS.get(key).apply(value)));
        synchronized (properties) {
            updates.forEach(update -> update.accept(properties));
        }
        if (changes.containsKey(EXCLUDED_UIDS)) excludedViewers.refresh();
    }

    private static <T> Map.Entry<String, Function<String, Consumer<AssistantProperties>>> setting(
            String suffix, T defaultValue, Function<String, T> parse, BiConsumer<AssistantProperties, T> set) {
        return Map.entry(PREFIX + suffix, value -> {
            T parsed = value == null ? defaultValue : parse.apply(value);
            set.accept(new AssistantProperties(), parsed);
            return target -> set.accept(target, parsed);
        });
    }

    private static boolean bool(String value, String label) {
        return switch (value.strip()) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(label + "的开关请选择开启或关闭");
        };
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("智能助手次数、限时与 token 上限请填写整数");
        }
    }

    private static BigDecimal budget(String value, BigDecimal defaultValue) {
        return value.isBlank() ? defaultValue : decimal(value, "助手金额上限请填写数字");
    }

    private static BigDecimal price(String value) {
        return decimal(value, "智能助手单价请填写数字");
    }

    private static BigDecimal decimal(String value, String message) {
        try {
            return new BigDecimal(value.strip());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(message);
        }
    }
}
