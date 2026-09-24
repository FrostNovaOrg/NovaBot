package org.frostnova.nova.report.painter;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 没采到的那几分钟怎么画
 * <p>
 * 在线人数是瞬时量：B 站推一条记一条、按分钟取最大，没推的那一分钟没有键。
 * 把没键的列画成 0，折线会在「平台没推送」的那几分钟跌到地板上——
 * 读图的人会以为那会儿直播间没人。累加量（弹幕、礼物等）相反：
 * 没消息就是真 0，补值反而会把冷场画热闹。
 * <p>
 * 另一格钉住：升级后旧配置里留着的已下线开关，只是没人读的多余键，
 * 解析与出报告都照常，不许当成错误。
 */
@DisplayName("缺样本的分钟怎么补")
class BilibiliLiveReportMissingSampleFillTest {

    private static final long MINUTE = 60_000L;

    /**
     * 整分锚点：某天 12:00:00，开播也落在整分上，列与绝对分钟格一一对应
     */
    private static final long START = 46_800_000L;

    private static final int COLUMNS = 12;

    private static final String PLATFORM = "bilibili";

    private BilibiliApiUtil api;

    private NovaCommonPainterFactory factory;

    private FontUtil fontUtil;

    private DefaultLiveDataService liveDataService;

    private LiveRoomInfoHistory roomInfoHistory;

    private BilibiliLiveReportPainter painter;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        NovaCoreProperties coreProperties = new NovaCoreProperties();
        coreProperties.getPaint().getFonts().add("内置");

        fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("group", "com.example");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");
        factory = new NovaCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

        BufferedImage placeholder = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = placeholder.createGraphics();
        graphics.setColor(Color.GRAY);
        graphics.fillRect(0, 0, 64, 64);
        graphics.dispose();
        api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));
        when(api.getGuardList(anyLong(), anyLong())).thenReturn(Optional.of(List.of()));

        liveDataService = new DefaultLiveDataService(new NovaCoreProperties());
        roomInfoHistory = new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties()));
        painter = new BilibiliLiveReportPainter(factory, api, liveDataService, fontUtil,
                new NovaBilibiliProperties(), roomInfoHistory);
    }

    /**
     * 瞬时量缺的列落在左右样本之间，累加量缺的列仍是 0
     * <p>
     * 样本打在第 2、5、8 列，其余列没有键，也不在采集缺口里：
     * <ul>
     *   <li>第 0、1 列在第一个样本之前 → 取最近那个样本（第 2 列）的值</li>
     *   <li>第 3、4 列夹在样本之间 → 线性插值</li>
     *   <li>第 6、7 列夹在样本之间 → 线性插值</li>
     *   <li>第 9～11 列在最后一个样本之后 → 取最近那个样本（第 8 列）的值</li>
     * </ul>
     * 同一格再断言弹幕数那条累加量序列：缺的列仍是 0（阴性对照——补值若顺手把
     * 累加量也补了，这一条当场红）。
     */
    @Test
    @DisplayName("在线人数缺的分钟落在左右样本之间、不为 0；弹幕缺的分钟仍为 0")
    void instantaneousFillsWhileAccumulativeStaysZero() {
        Map<Long, Double> online = new LinkedHashMap<>();
        online.put(START + 2 * MINUTE, 100.0);
        online.put(START + 5 * MINUTE, 130.0);
        online.put(START + 8 * MINUTE, 160.0);

        Map<Long, Double> danmu = new LinkedHashMap<>();
        danmu.put(START + 2 * MINUTE, 5.0);
        danmu.put(START + 5 * MINUTE, 8.0);
        danmu.put(START + 8 * MINUTE, 11.0);

        long end = START + 11 * MINUTE;
        boolean[] missing = BilibiliLiveReportPainter.gapColumns(List.of(), START,
                BilibiliLiveReportPainter.bucketCount(START, end), COLUMNS);

        double[] onlineValues = BilibiliLiveReportPainter.resample(online, START, end, COLUMNS);
        boolean[] onlineSampled = BilibiliLiveReportPainter.sampledColumns(online, START, end, COLUMNS);
        BilibiliLiveReportPainter.fillMissingSamples(onlineValues, onlineSampled, missing, true);

        double[] danmuValues = BilibiliLiveReportPainter.resample(danmu, START, end, COLUMNS);
        boolean[] danmuSampled = BilibiliLiveReportPainter.sampledColumns(danmu, START, end, COLUMNS);
        // 累加量走 false：没消息就是真 0，不补
        BilibiliLiveReportPainter.fillMissingSamples(danmuValues, danmuSampled, missing, false);

        // 夹在样本之间：线性插值，落在左右样本之间、不为 0
        assertBetween(onlineValues[3], 100.0, 130.0, 3);
        assertBetween(onlineValues[4], 100.0, 130.0, 4);
        assertBetween(onlineValues[6], 130.0, 160.0, 6);
        assertBetween(onlineValues[7], 130.0, 160.0, 7);

        // 样本列本身不许被动过
        assertEquals(100.0, onlineValues[2], 1e-9, "样本列取值应原样保留");
        assertEquals(130.0, onlineValues[5], 1e-9, "样本列取值应原样保留");
        assertEquals(160.0, onlineValues[8], 1e-9, "样本列取值应原样保留");

        // 第一个样本之前：取最近样本的值
        assertEquals(100.0, onlineValues[0], 1e-9, "首样本之前的列应取最近样本的值");
        assertEquals(100.0, onlineValues[1], 1e-9, "首样本之前的列应取最近样本的值");

        // 最后一个样本之后：取最近样本的值
        assertEquals(160.0, onlineValues[9], 1e-9, "末样本之后的列应取最近样本的值");
        assertEquals(160.0, onlineValues[10], 1e-9, "末样本之后的列应取最近样本的值");
        assertEquals(160.0, onlineValues[11], 1e-9, "末样本之后的列应取最近样本的值");

        // 阴性对照：累加量缺的列仍是 0
        assertEquals(0.0, danmuValues[0], 1e-9, "弹幕数缺的分钟仍为 0");
        assertEquals(0.0, danmuValues[1], 1e-9, "弹幕数缺的分钟仍为 0");
        assertEquals(0.0, danmuValues[3], 1e-9, "弹幕数缺的分钟仍为 0");
        assertEquals(0.0, danmuValues[4], 1e-9, "弹幕数缺的分钟仍为 0");
        assertEquals(0.0, danmuValues[6], 1e-9, "弹幕数缺的分钟仍为 0");
        assertEquals(0.0, danmuValues[7], 1e-9, "弹幕数缺的分钟仍为 0");
        assertEquals(0.0, danmuValues[9], 1e-9, "弹幕数缺的分钟仍为 0");
        assertEquals(5.0, danmuValues[2], 1e-9, "样本列取值应原样保留");
    }

    /**
     * 升级后旧配置里还写着已下线的那个开关，读配置与出报告都不许报错
     * <p>
     * 这个键从版式表里去掉了，但用户家里的配置文件不会跟着改：
     * 解析时当它不存在即可，别整份配置拒收，也别让出报告半路炸掉。
     */
    @Test
    @DisplayName("旧配置里留着已下线的开关时照常解析、报告照常出")
    void legacyTitleChangesKeyIsIgnored() {
        JSONObject params = new JSONObject();
        params.put("title_changes", true);
        params.put("cover", false);

        BilibiliLiveReportOptions options = BilibiliLiveReportOptions.of(params, true);

        assertNotNull(options, "带着多余键的配置也应解析出一份可用的版式选项");
        assertEquals(false, options.isCover(), "认识的键仍照常读取");
        assertEquals(true, options.isCards(), "没写的键仍是默认值");

        long start = 1_700_000_000_000L;
        liveDataService.setLiveStartTime(PLATFORM, 10001L, start);
        liveDataService.setLiveEndTime(PLATFORM, 10001L, start + 30 * MINUTE);

        Optional<String> report = painter.paint(PLATFORM,
                new org.frostnova.nova.core.model.LiveStreamerInfo(10001L, "测试主播", 20002L, "https://pic.example/face.jpg"),
                options);
        assertTrue(report.isPresent(), "带着多余键的配置也应照常出报告");
    }

    private static void assertBetween(double actual, double low, double high, int column) {
        assertTrue(actual > low && actual < high,
                "第 " + column + " 列的补值 " + actual + " 应落在左右样本 " + low + " 与 " + high + " 之间、不为 0");
        assertTrue(actual != 0.0, "第 " + column + " 列的补值不为 0");
    }
}
