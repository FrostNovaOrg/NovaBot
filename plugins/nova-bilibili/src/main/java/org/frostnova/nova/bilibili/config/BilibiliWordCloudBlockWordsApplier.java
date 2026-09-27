package org.frostnova.nova.bilibili.config;

import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 词云屏蔽词写回运行中的配置，保存后下一张报告就按新表画。
 */
@NovaComponent
public class BilibiliWordCloudBlockWordsApplier implements RuntimeConfigurationApplierContributor {
    /**
     * 配置项全名
     */
    public static final String KEY = "novabot.bilibili.live.word-cloud-block-words";

    private final NovaBilibiliProperties properties;

    public BilibiliWordCloudBlockWordsApplier() {
        this(new NovaBilibiliProperties());
    }

    @Autowired
    public BilibiliWordCloudBlockWordsApplier(NovaBilibiliProperties properties) {
        this.properties = properties;
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
        Map<String, Consumer<String>> appliers = new LinkedHashMap<>();
        appliers.put(KEY, value -> properties.getLive().setWordCloudBlockWords(LineLists.parse(value)));
        return appliers;
    }
}
