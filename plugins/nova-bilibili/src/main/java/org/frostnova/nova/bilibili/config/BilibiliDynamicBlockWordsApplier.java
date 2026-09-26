package org.frostnova.nova.bilibili.config;

import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 动态屏蔽词写回运行中的配置，保存后下一条动态就按新名单判。
 * <p>
 * 这一份名单是逐条动态判一次的（不像出图那样等到下一张报告才读），
 * 写回即生效；不写回的话，界面上存了新词而下一条含新词的动态照旧推出去，
 * 而界面上显示的是「已生效」。
 */
@NovaComponent
public class BilibiliDynamicBlockWordsApplier implements RuntimeConfigurationApplierContributor {
    /**
     * 配置项全名
     */
    public static final String KEY = "novabot.bilibili.dynamic.block-words";

    private final NovaBilibiliProperties properties;

    public BilibiliDynamicBlockWordsApplier() {
        this(new NovaBilibiliProperties());
    }

    @Autowired
    public BilibiliDynamicBlockWordsApplier(NovaBilibiliProperties properties) {
        this.properties = properties;
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
        Map<String, Consumer<String>> appliers = new LinkedHashMap<>();
        appliers.put(KEY, value -> properties.getDynamic().setBlockWords(LineLists.parse(value)));
        return appliers;
    }
}
