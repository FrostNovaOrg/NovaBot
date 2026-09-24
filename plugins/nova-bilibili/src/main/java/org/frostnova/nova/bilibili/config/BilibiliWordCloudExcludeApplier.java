package org.frostnova.nova.bilibili.config;

import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        appliers.put(KEY, value -> properties.getLive().setWordCloudExcludeUids(parseLines(value)));
        return appliers;
    }

    /**
     * 设置页按换行交来一份名单
     * @param value 换行分隔的用户编号，空表示清空
     * @return 去掉空行后的名单
     */
    static List<String> parseLines(String value) {
        List<String> uids = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return uids;
        }
        for (String line : value.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                uids.add(trimmed);
            }
        }
        return uids;
    }
}
