package org.frostnova.nova.core.analytics;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 起动时检查同一平台的各份指标说明有没有撞键
 * <p>
 * 一个平台可以有多份 {@link LiveMetricCatalog} 实现：平台插件说自己采的那几项，
 * 报告插件说自己算的那几项。界面把同平台各份<b>串起来不去重</b>，理由写在取数那一处——
 * 去重就得挑一份留下，而挑哪一份是界面替插件做的决定。
 * <p>
 * 「不许撞键」这条约束因此落在目录这一侧。<b>各实现自己的用例只守得住自己内部那两张表</b>：
 * 两份实现各自都对、合起来撞了同一个键时，编译过、单测全绿，而屏幕上会出现两行同名的指标，
 * 且两处说的名字未必一样。这是个装上第二份实现那天才发作、且要在使用者屏幕上才看得见的错，
 * 所以放在起动时算——两份实现的键都摆在那里，只是从来没有人把它们放在一起看。
 * <p>
 * <b>撞了就不让起动，而不是打条日志绕过去。</b>指标说明是给人读数用的，
 * 一份读错的报表比没有报表更糟；而这个错只要改一个字符串就能解决，没有必要带病运行。
 * 与 {@link LiveMetricCatalog#snapshotMetrics()} 那一段是同一条道理：
 * 同一个键有两种说法时，看的人无从分辨。
 * <p>
 * 两张表放在同一个池子里比：接口本身就写明了「两边的键一项都不许重合」，
 * 分开比的话，可累加集里的键与快照集里的键撞上时没有谁会说话。
 */
@Component
public class LiveMetricCatalogGuard implements SmartInitializingSingleton {
    /**
     * 用 {@link ObjectProvider} 而不是直接注入列表：一份实现都没有时也要能起动，
     * 那种情况下统计只展示场次与时长，这是接口注释里写明的常态。
     */
    private final ObjectProvider<LiveMetricCatalog> catalogs;

    public LiveMetricCatalogGuard(ObjectProvider<LiveMetricCatalog> catalogs) {
        this.catalogs = catalogs;
    }

    /**
     * 全部单例造完之后才问
     * <p>
     * 目录多半来自插件模块，在本类被造出来那一刻去问，问到的是还没装齐的一半——
     * 而「装齐的那一半里没有撞键」正是本守卫最不该给出的那种绿。
     */
    @Override
    public void afterSingletonsInstantiated() {
        check(catalogs.orderedStream().toList());
    }

    /**
     * 按平台分组，同组内的键出现两次就抛
     * @param all 容器里全部的指标说明实现
     * @throws IllegalStateException 同一平台的键撞了
     */
    static void check(List<LiveMetricCatalog> all) {
        // 平台 -> 键 -> 谁在哪张表里报的
        Map<String, Map<String, String>> declared = new LinkedHashMap<>();

        for (LiveMetricCatalog catalog : all) {
            Map<String, String> byKey = declared.computeIfAbsent(catalog.platform(), platform -> new LinkedHashMap<>());
            declare(byKey, catalog, "可累加指标", catalog.metrics());
            declare(byKey, catalog, "快照指标", catalog.snapshotMetrics());
        }
    }

    /**
     * 把一张表里的键记进本平台的账，已经有人报过就抛
     */
    private static void declare(Map<String, String> byKey, LiveMetricCatalog catalog,
                                String table, List<LiveMetricCatalog.Metric> metrics) {
        String owner = catalog.getClass().getName() + "（" + table + "）";

        for (LiveMetricCatalog.Metric metric : metrics) {
            String previous = byKey.putIfAbsent(metric.key(), owner);
            if (previous != null) {
                throw new IllegalStateException(String.format(
                        "平台 %s 的指标说明撞键: %s 同时由 %s 与 %s 申报。"
                                + "界面把同平台各份说明串起来展示且不去重, 撞键会让同一项出现两行, "
                                + "而两处给的名字与单位未必一样, 看的人无从分辨哪一行才算数。"
                                + "留哪一份不该由界面替插件决定, 因此在这里停住: 请把其中一处的键改掉",
                        catalog.platform(), metric.key(), previous, owner));
            }
        }
    }
}
