package org.frostnova.nova.bilibili.config;

import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 排行榜出图的两项写回运行中的配置，保存后下一张图就按新值画。
 * <p>
 * 这两项每出一张图才读一次，写回即生效；不写回的话，界面上存了新值而下一张图照旧，
 * 而界面上显示的是「已生效」。
 * <p>
 * 顺带把两项的取值范围也申报出去：填 0、十万、一百这种出界的值，保存时就回一段说清该填多少的
 * 人话，而不是写进配置文件再由出图那侧悄悄兜底。
 */
@NovaComponent
public class BilibiliRankingApplier implements RuntimeConfigurationApplierContributor {
    /**
     * 最多列出名次
     */
    public static final String TOP_N_KEY = "novabot.bilibili.ranking.top-n";

    /**
     * 整图高度上限
     */
    public static final String HEIGHT_LIMIT_KEY = "novabot.bilibili.ranking.height-limit";

    /**
     * 最多列出名次能填的范围
     * <p>
     * 下限 1：填 0 就是一张只有头部没有人的图。上限 500：超过这个数的长图，
     * 群里没人翻得到底，真要全列出来得先把整图高度上限调大配合
     */
    public static final int TOP_N_MIN = 1;

    public static final int TOP_N_MAX = 500;

    /**
     * 整图高度上限能填的范围，单位：像素
     * <p>
     * 下限 300：一张最小的图（头部加一行再加脚注）就有三百多像素，再矮连一行都摆不下。
     * 300 到那个最小高度之间的值仍然收，出图时按最小高度画并记一条 WARN（见出图那侧）。
     * 上限 30000：再高就超出常见屏幕与图片通道的承受，也远超任何一场榜该有的长度
     */
    public static final int HEIGHT_LIMIT_MIN = 300;

    public static final int HEIGHT_LIMIT_MAX = 30000;

    private final NovaBilibiliProperties properties;

    public BilibiliRankingApplier() {
        this(new NovaBilibiliProperties());
    }

    @Autowired
    public BilibiliRankingApplier(NovaBilibiliProperties properties) {
        this.properties = properties;
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
        Map<String, Consumer<String>> appliers = new LinkedHashMap<>();
        appliers.put(TOP_N_KEY, value -> properties.getRanking().setTopN(number(value)));
        appliers.put(HEIGHT_LIMIT_KEY, value -> properties.getRanking().setHeightLimit(number(value)));
        return appliers;
    }

    @Override
    public Map<String, Function<String, String>> valueValidators() {
        Map<String, Function<String, String>> validators = new LinkedHashMap<>();
        validators.put(TOP_N_KEY,
                value -> inRange(value, NovaBilibiliProperties.Ranking.TOP_N_LABEL, TOP_N_MIN, TOP_N_MAX));
        validators.put(HEIGHT_LIMIT_KEY,
                value -> inRange(value, NovaBilibiliProperties.Ranking.HEIGHT_LIMIT_LABEL,
                        HEIGHT_LIMIT_MIN, HEIGHT_LIMIT_MAX));
        return validators;
    }

    /**
     * 设置页交来的是文本，存成整数
     */
    private static int number(String value) {
        return Integer.parseInt(value.trim());
    }

    /**
     * 值要在 [min, max] 里。回一段说清该填多少的人话表示不能收，回 {@code null} 表示能收
     */
    private static String inRange(String value, String label, int min, int max) {
        Integer parsed = integer(value);
        if (parsed == null) {
            return label + " 要填整数，当前填的是「" + value + "」；本批未保存";
        }
        if (parsed < min || parsed > max) {
            return label + " 的取值要在 " + min + " 到 " + max + " 之间，当前填的是 " + parsed + "；本批未保存";
        }
        return null;
    }

    /**
     * 填的不是整数时回 {@code null}，好让调用方说出「要填整数」而不是抛出一场异常
     */
    private static Integer integer(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
