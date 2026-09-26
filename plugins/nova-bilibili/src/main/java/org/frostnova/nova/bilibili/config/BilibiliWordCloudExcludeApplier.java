package org.frostnova.nova.bilibili.config;

import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 词云屏蔽名单写回运行中的配置，保存后下一张报告就按新名单画。
 */
@NovaComponent
public class BilibiliWordCloudExcludeApplier implements RuntimeConfigurationApplierContributor {
    /**
     * 配置项全名
     */
    public static final String KEY = "novabot.bilibili.live.word-cloud-exclude-uids";

    private final NovaBilibiliProperties properties;

    public BilibiliWordCloudExcludeApplier() {
        this(new NovaBilibiliProperties());
    }

    @Autowired
    public BilibiliWordCloudExcludeApplier(NovaBilibiliProperties properties) {
        this.properties = properties;
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
        Map<String, Consumer<String>> appliers = new LinkedHashMap<>();
        appliers.put(KEY, value -> properties.getLive().setWordCloudExcludeUids(LineLists.parse(value)));
        return appliers;
    }
}
