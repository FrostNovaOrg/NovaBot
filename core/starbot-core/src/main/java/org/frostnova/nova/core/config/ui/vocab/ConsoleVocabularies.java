package org.frostnova.nova.core.config.ui.vocab;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 控制台词表的整理
 * <p>
 * 词来自插件，也就是<b>本模块管不着的代码</b>。闭集外的键丢掉并留一行日志，
 * 空白值视同未供；同一个键有多份时按清单顺序用「／」连起来。
 */
@Slf4j
public final class ConsoleVocabularies {
    /**
     * 词表键的闭集
     */
    public static final Set<String> KEYS = Set.of(
            "bot.platform",
            "bot.impl",
            "bot.family",
            "bot.impl.hint",
            "bot.target.group",
            "bot.target.user",
            "bot.targets");

    private ConsoleVocabularies() {
    }

    /**
     * 把各供方的词合成一张表
     * <p>
     * 不合规的键直接丢掉并留一行日志：登记不上的词在界面上根本不出现，
     * 不写日志的话，插件作者只会看到「我的词没了」而无从知道是哪一项填错。
     * @param providers 原始供方
     * @return 整理后的词表，不可修改
     */
    public static Map<String, String> merge(List<ConsoleVocabulary> providers) {
        if (providers == null || providers.isEmpty()) {
            return Map.of();
        }

        Map<String, List<String>> collected = new LinkedHashMap<>();
        for (ConsoleVocabulary provider : providers) {
            if (provider == null) {
                continue;
            }
            Map<String, String> terms = readTerms(provider);
            if (terms == null || terms.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, String> entry : terms.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || !KEYS.contains(key)) {
                    log.warn("控制台词表 {} 申报了闭集外的键 {}, 已忽略",
                            provider.getClass().getName(), key);
                    continue;
                }
                if (value == null || value.isBlank()) {
                    continue;
                }
                collected.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
            }
        }

        if (collected.isEmpty()) {
            return Map.of();
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : collected.entrySet()) {
            out.put(entry.getKey(), String.join("／", entry.getValue()));
        }
        return Map.copyOf(out);
    }

    /**
     * 向供方要词表，它抛了就当这一项没填
     * @param provider 供方
     * @return 取到的词表，取值过程抛异常时为空
     */
    private static Map<String, String> readTerms(ConsoleVocabulary provider) {
        try {
            return provider.terms();
        } catch (RuntimeException | LinkageError e) {
            log.warn("控制台词表读取词条失败, 已忽略该供方: {}", e.toString());
            return null;
        }
    }
}
