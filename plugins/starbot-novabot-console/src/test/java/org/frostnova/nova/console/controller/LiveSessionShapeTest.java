package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.RoomInfoSnapshot;
import org.frostnova.nova.core.model.SeriesPeak;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.frostnova.nova.core.service.LiveReportRedrawer;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.StreamerDirectory;
import org.frostnova.nova.core.service.StreamerSnapshotArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 场次 JSON 在两个调用方之间同形
 * <p>
 * 同一场直播有两处出口：运营分析的逐场流水（{@link AnalyticsController#sessions}）
 * 与主播详情的场次表（{@link StreamerController#streamer}）。两处共用一份
 * {@code LiveSessionJson}，可「共用」这件事本身之前没有任何一格钉住——
 * 哪一处顺手改成自己拼 JSON，另一处照旧，同一场直播就会在两张表上显示出不同的数，
 * 而<b>「空不等于零」那几条规矩只会被改对其中一处</b>：另一处继续把历史场次
 * 显示成「零观众」「人气峰 0」。
 * <p>
 * 所以本组用例不去量那份共用代码，而是<b>两个出口各要一次、拿实际吐出来的 JSON 相比</b>——
 * 只量其中一处的话，正是那种「一处被改坏、判据全绿」的形态。
 */
@DisplayName("场次 JSON 两个调用方同形")
class LiveSessionShapeTest {
    private static final long DAY = 86_400_000L;

    private static final String PLATFORM = "bilibili";

    private static final long UID = 1001L;

    /**
     * 主播详情比运营分析多出来的那一项：这一行点不点得开。
     * <p>
     * 它<b>只属于详情页</b>——运营分析那张表没有「点开看报告」这个动作。
     * 列在这里而不是在断言里现写，是为了让「多出来的到底该有哪些」有一处明账：
     * 日后再多出一项，得先动这个集合，动的时候就会被问一句「凭什么」。
     */
    private static final Set<String> DETAIL_ONLY_KEYS = Set.of("hasReport");

    @TempDir
    Path dir;

    private NovaCoreProperties properties;

    private LiveSessionArchive archive;

    private AnalyticsController analytics;

    private StreamerController streamers;

    private long todayStart;

    @BeforeEach
    void setUp() throws IOException {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());

        todayStart = System.currentTimeMillis();

        archive = new LiveSessionArchive(properties);

        Files.writeString(Path.of(properties.getDatasource().getJsonPath()), """
                [
                  {"uid": 1001, "platform": "bilibili", "enabled": true,
                   "targets": [{"platform":"qq-onebot","type":1,"num":30003,
                                "messages":[{"handler":"LiveOn","enabled":true}]}]}
                ]
                """, StandardCharsets.UTF_8);

        LiveDataService liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStatus(any(), any())).thenReturn(Optional.of(false));
        when(liveDataService.getLiveStartTime(any(), any())).thenReturn(Optional.empty());

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of());

        analytics = new AnalyticsController(archive, catalogs());
        streamers = new StreamerController(dataSource, new StreamerDirectory(properties), liveDataService,
                archive, new StreamerSnapshotArchive(properties), new LiveDetailArchive(properties),
                catalogs(), redrawers());
    }

    @Test
    @DisplayName("同一场在两处吐出的键集相同，只差详情页那一项")
    void bothCallersEmitTheSameKeySet() {
        archive.append(richSession());

        JSONObject fromAnalytics = analyticsItem();
        JSONObject fromDetail = detailItem();

        // 先报分母：两侧各自的键数。少了这一句，两边都退化成空对象时下面那一条照样全绿
        assertFalse(fromAnalytics.isEmpty(), "运营分析吐出来的是空对象, 下面的比对无从谈起");
        assertFalse(fromDetail.isEmpty(), "主播详情吐出来的是空对象, 下面的比对无从谈起");

        Set<String> analyticsKeys = new LinkedHashSet<>(fromAnalytics.keySet());
        Set<String> detailKeys = new LinkedHashSet<>(fromDetail.keySet());
        Set<String> detailOnly = new LinkedHashSet<>(detailKeys);
        detailOnly.removeAll(analyticsKeys);
        detailKeys.removeAll(DETAIL_ONLY_KEYS);

        assertEquals(analyticsKeys, detailKeys,
                "两处场次 JSON 的键集应当一致 (运营分析 " + analyticsKeys.size()
                        + " 项, 主播详情 " + fromDetail.size() + " 项)");
        assertEquals(DETAIL_ONLY_KEYS, detailOnly,
                "详情页只该多出「这一行点不点得开」这一项");
    }

    @Test
    @DisplayName("同一场在两处吐出的值逐键相同")
    void bothCallersEmitTheSameValues() {
        archive.append(richSession());

        JSONObject fromAnalytics = analyticsItem();
        JSONObject fromDetail = detailItem();

        // 🔴 圈的是两侧的**并集**，不是任一侧的键集：按一侧循环的话，
        // 那一侧被改成只吐两个键时，这一格的分母跟着自己缩到 2，于是照样全绿——
        // 少掉的十几项一句话都不说
        Set<String> shared = new LinkedHashSet<>(fromAnalytics.keySet());
        shared.addAll(fromDetail.keySet());
        shared.removeAll(DETAIL_ONLY_KEYS);

        assertTrue(shared.size() > DETAIL_ONLY_KEYS.size(),
                "两侧并集只有 " + shared.size() + " 项, 这一格没量到东西");

        for (String key : shared) {
            assertEquals(fromAnalytics.get(key), fromDetail.get(key),
                    "同一场的 " + key + " 在两张表上应当是同一个值");
        }
    }

    @Test
    @DisplayName("「空不等于零」的两项在两处都答得出，且答的是同一句")
    void bothCallersDistinguishEmptyFromZero() {
        // 一场带名单带峰值的、一场当年格式的：后者的「空」意思是「不知道」，不是「零」。
        // 两处若各写一份, 被改对的往往只有一处——所以这一格要的是两处对同一场答同一句
        // 归档按开播时间升序落盘，两处都要倒过来给界面：先落早的那一场，
        // 于是两处的第 0 场都是新的那一场
        archive.append(legacySession());
        archive.append(richSession());

        for (int index = 0; index < 2; index++) {
            JSONObject fromAnalytics = analyticsItem(index);
            JSONObject fromDetail = detailItem(index);

            assertEquals(fromAnalytics.getBooleanValue("hasUserSets"), fromDetail.getBooleanValue("hasUserSets"),
                    "第 " + index + " 场的 hasUserSets 两处不一致");
            assertEquals(fromAnalytics.getBooleanValue("hasPeaks"), fromDetail.getBooleanValue("hasPeaks"),
                    "第 " + index + " 场的 hasPeaks 两处不一致");
        }

        // 阴性对照：两场在这两项上本来就该给出相反的答案。少了这一句，
        // 上面那个循环在「两处都恒答 false」时同样全绿
        assertTrue(analyticsItem(0).getBooleanValue("hasPeaks"), "新场次有峰值");
        assertFalse(analyticsItem(1).getBooleanValue("hasPeaks"), "当年格式的场次没有峰值, 该是「不知道」");
    }

    /**
     * 一场带齐了名单、峰值、标题轨迹与两项缺口的直播
     */
    private LiveSession richSession() {
        return new LiveSession(PLATFORM, UID, "主播甲", 20002L,
                todayStart - DAY, todayStart - DAY + 3600_000L, 3600,
                Map.of("danmu_count", 106.0), Map.of("danmu_count", 12),
                LiveEndReason.ROOM_LOCK,
                List.of(new RoomInfoSnapshot(todayStart - DAY, "今天也在直播", "虚拟主播")), 120,
                Map.of("danmu_count", List.of(7L, 8L)), 45,
                Map.of("watched_count", new SeriesPeak(todayStart - DAY + 42 * 60_000L, 8642)));
    }

    /**
     * 一场当年格式的直播：没有名单也没有峰值，那不是「零」，是「不知道」
     */
    private LiveSession legacySession() {
        return new LiveSession(PLATFORM, UID, "主播甲", 20002L,
                todayStart - 2 * DAY, todayStart - 2 * DAY + 3600_000L, 3600,
                Map.of("danmu_count", 5.0), Map.of());
    }

    /**
     * 运营分析逐场流水里的第一场
     */
    private JSONObject analyticsItem() {
        return analyticsItem(0);
    }

    private JSONObject analyticsItem(int index) {
        return analytics.sessions(UID, 0).getJSONArray("sessions").getJSONObject(index);
    }

    /**
     * 主播详情场次表里的第一场
     */
    private JSONObject detailItem() {
        return detailItem(0);
    }

    private JSONObject detailItem(int index) {
        return streamers.streamer(PLATFORM, UID, "week", 1, 0)
                .getBody().getJSONObject("sessions").getJSONArray("items").getJSONObject(index);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<LiveMetricCatalog> catalogs() {
        ObjectProvider<LiveMetricCatalog> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> java.util.stream.Stream.of(catalog()));
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<LiveReportRedrawer> redrawers() {
        ObjectProvider<LiveReportRedrawer> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> java.util.stream.Stream.empty());
        return provider;
    }

    private LiveMetricCatalog catalog() {
        return new LiveMetricCatalog() {
            @Override
            public String platform() {
                return PLATFORM;
            }

            @Override
            public List<Metric> metrics() {
                return List.of(Metric.count("danmu_count", "弹幕", "条"));
            }
        };
    }
}
