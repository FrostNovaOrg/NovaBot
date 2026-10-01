package org.frostnova.nova.report.demo;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.painter.CommonPainter;
import org.frostnova.nova.report.painter.ReportSharedStyle;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.info.BuildProperties;

import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 下播报告画上去的字：金额符号的整图约束、排行得分不越右边距、大航海副行不截断
 * <p>
 * 照 {@code BilibiliDynamicPainterTest} 的写法覆写画字方法，把整张报告画上去的字
 * 全收下来再查——成品图上的像素认不出「这里是哪个字」，金额符号漏到哪一格、
 * 得分越出去几个像素，都无从找起。三个重载最终都汇进四参那一个，覆写它即全覆盖。
 * <p>
 * 数据用全量演示夹具：一张图把卡片、曲线、榜单、名单全画满，单造一场喂不全这些区块。
 * 词云例外——它走独立渲染口、不经过画字方法，其语料在输入侧另钉一道。
 */
@DisplayName("下播报告画上去的字")
class BilibiliLiveReportDrawnTextTest {
    private static FontUtil fonts;

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(
            DemoAssets.STREAMER_UID, DemoAssets.STREAMER_A, DemoAssets.ROOM_ID, "demo-face-0");

    /**
     * 画上去的每一句字
     */
    private final List<String> drawnText = new ArrayList<>();

    /**
     * 按 {@code Point} 定位画上去的字与其右端 x：越不越右边距只对定位字有意义
     */
    private final List<int[]> positionedRight = new ArrayList<>();

    private final List<String> positionedText = new ArrayList<>();

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
        fonts = DemoAssets.bundledFonts();
    }

    @Test
    @DisplayName("隐藏金额的整张报告：画上去的字没有一个金额符号")
    void hiddenReportDrawsNoYenSignAnywhere(@TempDir Path dir) throws Exception {
        Optional<String> hidden = paintReport(dir, false);

        List<String> red = new ArrayList<>();
        try {
            assertTrue(hidden.isPresent(), "报告没画出来，这把尺无从量起");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            List<String> offenders = drawnText.stream()
                    .filter(text -> text != null && text.contains("¥")).toList();
            assertTrue(offenders.isEmpty(), "隐藏金额的图上出现了金额符号：" + offenders);
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            // 常驻的阳性对照：同一份夹具画显示金额那一版，金额符号必须在——
            // 否则上面的绿只是「这场本来就没有可露的金额」，什么也没钉住
            paintReport(dir, true);
            assertTrue(drawnText.stream().anyMatch(text -> text != null && text.contains("¥")),
                    "显示金额的图上没有金额符号，夹具分不出两种会话");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }
        try {
            // 词云不走画字方法（独立渲染成图后整块贴上来），它的字在输入侧钉：
            // 演示语料里若有金额符号，上面的断言量不到它
            assertTrue(Arrays.stream(DemoAssets.DANMU).noneMatch(word -> word.contains("¥")),
                    "演示弹幕语料进了金额符号，词云那块会绕过画字口");
        } catch (Throwable t) {
            red.add("④ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("排行得分：最长的样例得分（盲盒榜第 1 行）右端不越右边距")
    void longestScoreLabelStaysInsideRightMargin(@TempDir Path dir) throws Exception {
        paintReport(dir, true);

        // 合并后的盲盒榜第 1 行：个数与盈亏一行两个数，是各榜里最长的得分文案
        String expected = "11 个 · +¥18.6";
        int rightMargin = ReportSharedStyle.WIDTH - ReportSharedStyle.MARGIN;

        List<Integer> offenders = new ArrayList<>();
        boolean drawn = false;
        for (int i = 0; i < positionedText.size(); i++) {
            if (!expected.equals(positionedText.get(i))) {
                continue;
            }
            drawn = true;
            if (positionedRight.get(i)[0] > rightMargin) {
                offenders.add(positionedRight.get(i)[0]);
            }
        }

        List<String> red = new ArrayList<>();
        try {
            assertTrue(drawn, "盲盒榜第 1 行的得分文字没画上去，这格没量到东西");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertTrue(offenders.isEmpty(), "得分文字右端越过右边距 " + rightMargin + "：" + offenders);
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("大航海卡副行：新开与续费完整画上，不被截断，也不写「开通」")
    void guardCardBreakdownIsDrawnWhole(@TempDir Path dir) throws Exception {
        // 演示场次新开 3、续费 1：显示金额那版要先试着带金额（放不下才舍），两版都要完整画出拆分
        paintReport(dir, true);
        List<String> shown = List.copyOf(drawnText);
        paintReport(dir, false);

        List<String> red = new ArrayList<>();
        try {
            // 不写省略号那条由「完整画出」钉：被截断的副行不含这段拆分
            assertTrue(shown.stream().anyMatch(text -> text != null && text.contains("新开 3 · 续费 1")),
                    "显示金额的图上大航海副行没有完整的新开／续费拆分：" + shown.stream()
                            .filter(text -> text != null && text.contains("大航海")).toList());
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertTrue(drawnText.stream().anyMatch(text -> text != null && text.contains("新开 3 · 续费 1")),
                    "隐藏金额的图上大航海副行没有完整的新开／续费拆分");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            assertTrue(shown.stream().noneMatch(text -> text != null && text.startsWith("开通大航海")),
                    "副行仍以「开通大航海」起头：这里数的是人次，续费也被读成新开");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 用挂了钩的画坊出一张报告，先清掉上一张收的字。返回报告 Base64
     */
    private Optional<String> paintReport(Path dir, boolean showRevenue) throws Exception {
        drawnText.clear();
        positionedRight.clear();
        positionedText.clear();

        Path demo = dir.resolve("demo");
        DemoAssets.generate(demo, fonts);

        NovaCoreProperties coreProperties = new NovaCoreProperties();
        coreProperties.getPaint().getFonts().add("内置");
        coreProperties.getPaint().getFonts().add("内置表情");
        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("group", "org.frostnova.nova");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");
        BuildProperties build = new BuildProperties(buildInfo);
        NovaCommonPainterFactory factory = new NovaCommonPainterFactory(build, coreProperties, fonts) {
            @Override
            public CommonPainter create(int width, int height, boolean autoExpand) {
                return new CommonPainter(build, coreProperties, fonts, width, height, autoExpand) {
                    @Override
                    public CommonPainter drawTextWithStyle(List<TextWithStyle> texts, Point drawLocation,
                                                           boolean autoWrap, int marginRight) {
                        for (TextWithStyle text : texts) {
                            drawnText.add(text.getText());
                            if (drawLocation != null) {
                                positionedText.add(text.getText());
                                positionedRight.add(new int[] {
                                        drawLocation.x + getStringWidthAndHeight(text).getFirst()});
                            }
                        }
                        return super.drawTextWithStyle(texts, drawLocation, autoWrap, marginRight);
                    }
                };
            }
        };

        DemoAssets.DemoReportPainter painter = DemoAssets.demoPainter(demo, fonts, factory);
        JSONObject params = new JSONObject();
        params.put("box_ranking", 5);
        return painter.paint(BilibiliPlatform.BILIBILI.id(), STREAMER,
                BilibiliLiveReportOptions.of(params, showRevenue));
    }
}
