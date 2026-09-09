package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.NovaCoreApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一平台的两份指标目录撞了同一个键时，起动就停住
 *
 * <h2>这一格补的是什么洞</h2>
 * 一个平台可以有多份 {@link LiveMetricCatalog} 实现——平台插件说自己采的那几项，
 * 报告插件说自己算的那几项。主播页那一侧把同平台各份的快照说明<b>串起来不去重</b>，
 * 理由写在取数那一处：去重就得挑一份留下，而挑哪一份是界面替插件做的决定。
 * <p>
 * 于是「不许撞键」这条约束落在了目录这一侧，而目录这一侧原先只有各实现自己的用例
 * 守着<b>自己内部</b>那两张表。两份实现各自都对、合起来撞了同一个键时，
 * <b>编译过、单测全绿，屏幕上出现两行同名的指标</b>，而且两处说的名字未必一样。
 * 现在只有一份实现，这个洞还不会发作——正因如此才要现在把守卫立好：
 * 等到第二份实现装上去那天，撞键是在使用者的屏幕上被发现的。
 *
 * <h2>为什么四问都真起一次容器</h2>
 * 直接 {@code new} 出守卫再手动调那个回调也能量出判定对不对，但那样量的是一个方法，
 * 答不了「起动时当真有谁会调它」。守卫的全部价值就在这一点上，所以四问一律
 * 把守卫按 bean 定义登记进容器、把假目录摆成单例、跑真的 {@code refresh()}，
 * 红的形态与线上起动失败是同一个。
 * <p>
 * 第⑤问补的是这四问自己盖不住的那半：上面是本格自己把守卫 register 进去的，
 * 真起动时靠的是包扫描——注解掉了、或者类挪出扫描射程，上面四问照样全绿。
 */
@DisplayName("直播指标目录的撞键守卫")
class LiveMetricCatalogGuardTest {
    /** 假平台名两个，不用真平台名：本守卫与是哪个平台无关，写真名反倒像是只管那一个 */
    private static final String ONE_PLATFORM = "alpha";

    private static final String OTHER_PLATFORM = "beta";

    /** 两份实现撞上的那个键 */
    private static final String SHARED_KEY = "gift_value";

    @Test
    @DisplayName("① 同平台两份目录的可累加指标撞同一个键, 起动当场停住")
    void collidingPeriodMetricKeyStopsStartup() {
        FirstCatalog first = new FirstCatalog(ONE_PLATFORM, List.of(SHARED_KEY), List.of());
        SecondCatalog second = new SecondCatalog(ONE_PLATFORM, List.of(SHARED_KEY), List.of());

        String reason = reasonOf(assertThrows(RuntimeException.class, () -> start(first, second)));

        assertTrue(reason.contains(IllegalStateException.class.getName()),
                "撞键该以 IllegalStateException 停住起动, 实际停在: " + reason);
        assertTrue(reason.contains(ONE_PLATFORM),
                "说明里得写明是哪个平台撞的, 否则装了几个平台时不知道从哪儿查起: " + reason);
        assertTrue(reason.contains(SHARED_KEY),
                "说明里得写明是哪个键撞的: " + reason);
        assertTrue(reason.contains(FirstCatalog.class.getName()) && reason.contains(SecondCatalog.class.getName()),
                "说明里得写明是哪两份实现撞的——只说「撞了」的话, 拿到这条报错的人得把每个插件都翻一遍: " + reason);
    }

    @Test
    @DisplayName("② 同平台两份目录的快照指标撞同一个键, 起动当场停住")
    void collidingSnapshotMetricKeyStopsStartup() {
        FirstCatalog first = new FirstCatalog(ONE_PLATFORM, List.of(), List.of(SHARED_KEY));
        SecondCatalog second = new SecondCatalog(ONE_PLATFORM, List.of(), List.of(SHARED_KEY));

        String reason = reasonOf(assertThrows(RuntimeException.class, () -> start(first, second)));

        assertTrue(reason.contains(IllegalStateException.class.getName()),
                "撞键该以 IllegalStateException 停住起动, 实际停在: " + reason);
        assertTrue(reason.contains(SHARED_KEY),
                "快照那张表也得受同一条守卫管——主播页那张基础数据卡串的正是这张表: " + reason);
        assertTrue(reason.contains(FirstCatalog.class.getName()) && reason.contains(SecondCatalog.class.getName()),
                "说明里得写明是哪两份实现撞的: " + reason);
    }

    @Test
    @DisplayName("③ 阴性: 同平台两份目录的键各不相同, 起得来")
    void samePlatformWithDistinctKeysStartsFine() {
        FirstCatalog first = new FirstCatalog(ONE_PLATFORM, List.of("danmu_count"), List.of("fans"));
        SecondCatalog second = new SecondCatalog(ONE_PLATFORM, List.of("box_count"), List.of("guard"));

        assertDoesNotThrow(() -> start(first, second),
                "同平台两份目录各说各的键是常态: 平台插件说自己采的, 报告插件说自己算的, 守卫不该拦");

        // 阳性锚: 守卫当真把这两份目录读过一遍。少了这一句, 守卫压根没被容器回调时本问照样绿
        assertTrue(first.consulted() > 0 && second.consulted() > 0,
                "起动过程里没有谁问过这两份目录, 本问的绿是空跑出来的");
    }

    @Test
    @DisplayName("④ 阴性: 不同平台用同一个键, 起得来")
    void differentPlatformsSharingAKeyStartFine() {
        FirstCatalog first = new FirstCatalog(ONE_PLATFORM, List.of(SHARED_KEY), List.of());
        SecondCatalog second = new SecondCatalog(OTHER_PLATFORM, List.of(SHARED_KEY), List.of());

        assertDoesNotThrow(() -> start(first, second),
                "键只在平台内部要求唯一: 两个平台各有一项叫礼物价值是理所当然的, 拦下来等于逼平台名进键里");

        assertTrue(first.consulted() > 0 && second.consulted() > 0,
                "起动过程里没有谁问过这两份目录, 本问的绿是空跑出来的");
    }

    @Test
    @DisplayName("⑤ 接线: 守卫带组件注解、落在核心的扫描射程内、且是全部单例造完后才回调的那一种")
    void guardIsReachedByRealStartup() {
        assertTrue(AnnotatedElementUtils.hasAnnotation(LiveMetricCatalogGuard.class, Component.class),
                "守卫没带组件注解: 上面四问是本格自己把它登记进容器的, 真起动时没有谁会装它");

        assertTrue(LiveMetricCatalogGuard.class.getPackageName()
                        .startsWith(NovaCoreApplication.class.getPackageName() + "."),
                "守卫落在核心起动类的扫描射程外, 带了注解也扫不到");

        assertTrue(SmartInitializingSingleton.class.isAssignableFrom(LiveMetricCatalogGuard.class),
                "守卫不是 SmartInitializingSingleton: 目录们是别的插件的单例, "
                        + "在自己被造出来那一刻去问, 问到的是还没装齐的一半");
    }

    /**
     * 按真起动那条路把守卫装进容器
     * <p>
     * 假目录摆成手工单例、守卫按 bean 定义登记，{@code refresh()} 走到
     * 「全部单例造完」那一步时容器会回调守卫。撞键时抛出的异常就这么原样穿出来。
     */
    private static void start(LiveMetricCatalog... catalogs) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            for (int i = 0; i < catalogs.length; i++) {
                context.getBeanFactory().registerSingleton("catalog" + i, catalogs[i]);
            }
            context.register(LiveMetricCatalogGuard.class);
            context.refresh();
        }
    }

    /**
     * 整条 cause 链的类名与文案
     * <p>
     * 只看最外层那个的话，容器哪天多包一层，四问会一起变成「抛的不是我要的那个」。
     */
    private static String reasonOf(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            text.append(current.getClass().getName()).append(": ").append(current.getMessage()).append('\n');
        }
        return text.toString();
    }

    /**
     * 一份假目录：平台与两张表都由构造参数给，并记下自己被问过几次
     * <p>
     * 记次数是给阴性两问当阳性锚用的：不抛异常有两种可能，一种是判对了，
     * 另一种是守卫压根没跑。
     */
    private abstract static class RecordingCatalog implements LiveMetricCatalog {
        private final String platform;

        private final List<String> metricKeys;

        private final List<String> snapshotKeys;

        private int consulted;

        RecordingCatalog(String platform, List<String> metricKeys, List<String> snapshotKeys) {
            this.platform = platform;
            this.metricKeys = metricKeys;
            this.snapshotKeys = snapshotKeys;
        }

        @Override
        public String platform() {
            return platform;
        }

        @Override
        public List<Metric> metrics() {
            consulted++;
            return describe(metricKeys);
        }

        @Override
        public List<Metric> snapshotMetrics() {
            consulted++;
            return describe(snapshotKeys);
        }

        int consulted() {
            return consulted;
        }

        private static List<Metric> describe(List<String> keys) {
            return keys.stream().map(key -> Metric.count(key, key, "次")).toList();
        }
    }

    /** 两份实现要有两个不同的全类名，撞键说明才说得出「是这两个撞的」 */
    private static final class FirstCatalog extends RecordingCatalog {
        FirstCatalog(String platform, List<String> metricKeys, List<String> snapshotKeys) {
            super(platform, metricKeys, snapshotKeys);
        }
    }

    private static final class SecondCatalog extends RecordingCatalog {
        SecondCatalog(String platform, List<String> metricKeys, List<String> snapshotKeys) {
            super(platform, metricKeys, snapshotKeys);
        }
    }
}
