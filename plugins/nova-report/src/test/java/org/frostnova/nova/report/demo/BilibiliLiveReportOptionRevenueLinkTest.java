package org.frostnova.nova.report.demo;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.painter.CommonPainter;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.info.BuildProperties;

import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 版式项的「随金额」栏与画图取舍一致
 * <p>
 * 用户那头的故障：群里设了不显示金额，再去版式里把「流水排行」调成前 10 名，
 * 图上永远没有这一榜，还以为是坏了；反过来开着金额勾「本场开通大航海名单」，
 * 怎么勾也不出。版式表把每一项<b>随金额怎么走</b>标出来、控制台据此灰掉不出图的项，
 * 前提是「标出来的」与「画图真的会做的」是同一件事——标错一项，界面就灰错一项，
 * 而两头都不会报错。
 * <p>
 * 因此本尺两个方向对着量：表上标「显示金额时才出」的每一项，按隐藏金额画整张报告时
 * 它那一块确实不出、按显示金额画时确实出；「隐藏金额时才出」的反之。表经 JSON 读回
 * 再查（与 {@code /api/handlers} 交给前端同一形态）：栏位没到前端，控制台的灰就无从谈起。
 * 画图与收字照 {@code BilibiliLiveReportDrawnTextTest} 的挂法：区块的标题字画不画，
 * 就是那一块在与不在的读数。数据用全量演示夹具——礼物、流水、醒目留言、大航海都齐，
 * 缺哪一样，那一项的「显示时确实出」就量不到。
 */
@DisplayName("版式项的随金额栏与画图一致")
class BilibiliLiveReportOptionRevenueLinkTest {
    private static FontUtil fonts;

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(
            DemoAssets.STREAMER_UID, DemoAssets.STREAMER_A, DemoAssets.ROOM_ID, "demo-face-0");

    /**
     * 各版式项在图上认得出的那一行标题字
     */
    private static final Map<String, String> BLOCK_TITLE = Map.of(
            "gift_list", "收到的礼物",
            "gift_ranking", "流水排行",
            "super_chat_ranking", "醒目留言名单",
            "guard_list", "本场开通大航海");

    /**
     * 表上该标「显示金额时才出」的键
     */
    private static final Set<String> ONLY_WHEN_SHOWN = Set.of("gift_list", "gift_ranking", "super_chat_ranking");

    /**
     * 表上该标「隐藏金额时才出」的键
     */
    private static final Set<String> ONLY_WHEN_HIDDEN = Set.of("guard_list");

    /**
     * 表上该标「隐藏金额时变样」的键：不整块消失，控制台不灰、只加说明，这里只核它们确实带原因句
     */
    private static final Set<String> RESTYLED_WHEN_HIDDEN = Set.of("cards", "interaction_curve", "box_ranking");

    /**
     * 画上去的每一句字
     */
    private final List<String> drawnText = new ArrayList<>();

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
        fonts = DemoAssets.bundledFonts();
    }

    @Test
    @DisplayName("标了随金额的每一项，隐藏／显示两版图上都照标的来")
    void markedOptionsBehaveAsMarked(@TempDir Path dir) throws Exception {
        List<JSONObject> table = readTableThroughJson();

        // —— 栏位与归类 ——
        Map<String, String> visibility = new LinkedHashMap<>();
        Set<String> missing = new TreeSet<>();
        for (JSONObject item : table) {
            String value = item.getString("revenueVisibility");
            if (value == null || value.isBlank()) {
                missing.add(item.getString("key"));
            } else {
                visibility.put(item.getString("key"), value);
            }
        }

        List<String> red = new ArrayList<>();
        try {
            assertTrue(missing.isEmpty(), "表上还没有「随金额」这一栏：" + missing
                    + "——栏位没标出来，控制台灰不掉任何项，用户会以为配了没用");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        if (missing.isEmpty()) {
            try {
                assertTrue(ONLY_WHEN_SHOWN.stream().allMatch(key -> "ONLY_WHEN_SHOWN".equals(visibility.get(key))),
                        "该标「显示金额时才出」而没标的：" + diff(ONLY_WHEN_SHOWN, "ONLY_WHEN_SHOWN", visibility));
            } catch (Throwable t) {
                red.add("② " + t.getMessage());
            }
            try {
                assertTrue(ONLY_WHEN_HIDDEN.stream().allMatch(key -> "ONLY_WHEN_HIDDEN".equals(visibility.get(key))),
                        "该标「隐藏金额时才出」而没标的：" + diff(ONLY_WHEN_HIDDEN, "ONLY_WHEN_HIDDEN", visibility));
            } catch (Throwable t) {
                red.add("③ " + t.getMessage());
            }
            try {
                assertTrue(RESTYLED_WHEN_HIDDEN.stream().allMatch(key -> "RESTYLED_WHEN_HIDDEN".equals(visibility.get(key))),
                        "该标「隐藏金额时变样」而没标的：" + diff(RESTYLED_WHEN_HIDDEN, "RESTYLED_WHEN_HIDDEN", visibility));
            } catch (Throwable t) {
                red.add("④ " + t.getMessage());
            }
            Set<String> marked = new TreeSet<>(ONLY_WHEN_SHOWN);
            marked.addAll(ONLY_WHEN_HIDDEN);
            marked.addAll(RESTYLED_WHEN_HIDDEN);
            try {
                List<String> noNote = marked.stream()
                        .filter(key -> {
                            JSONObject item = table.stream()
                                    .filter(row -> key.equals(row.getString("key"))).findFirst().orElse(null);
                            String note = item == null ? null : item.getString("revenueNote");
                            return note == null || note.isBlank();
                        })
                        .toList();
                assertTrue(noNote.isEmpty(), "标了随金额却没带原因句的：" + noNote);
            } catch (Throwable t) {
                red.add("⑤ " + t.getMessage());
            }
        }

        // —— 画图两个方向 ——
        List<String> hidden = paintReport(dir, false);
        List<String> shown = paintReport(dir, true);

        for (String key : ONLY_WHEN_SHOWN) {
            try {
                assertTrue(hidden.stream().noneMatch(text -> text != null && text.contains(BLOCK_TITLE.get(key))),
                        "按隐藏金额画时「" + key + "」那一块还在画（认它的标题字「"
                                + BLOCK_TITLE.get(key) + "」出现在图上）——它标着「显示金额时才出」");
            } catch (Throwable t) {
                red.add("⑥ " + t.getMessage());
            }
            try {
                assertTrue(shown.stream().anyMatch(text -> text != null && text.contains(BLOCK_TITLE.get(key))),
                        "按显示金额画时「" + key + "」那一块没画（图上找不到它的标题字「"
                                + BLOCK_TITLE.get(key) + "」）——要么演示夹具缺这一块的数据，要么它其实不随金额");
            } catch (Throwable t) {
                red.add("⑦ " + t.getMessage());
            }
        }
        for (String key : ONLY_WHEN_HIDDEN) {
            try {
                assertTrue(shown.stream().noneMatch(text -> text != null && text.contains(BLOCK_TITLE.get(key))),
                        "按显示金额画时「" + key + "」那一块还在画（认它的标题字「"
                                + BLOCK_TITLE.get(key) + "」出现在图上）——它标着「隐藏金额时才出」");
            } catch (Throwable t) {
                red.add("⑧ " + t.getMessage());
            }
            try {
                assertTrue(hidden.stream().anyMatch(text -> text != null && text.contains(BLOCK_TITLE.get(key))),
                        "按隐藏金额画时「" + key + "」那一块没画（图上找不到它的标题字「"
                                + BLOCK_TITLE.get(key) + "」）——要么演示夹具缺这一块的数据，要么它其实不随金额");
            } catch (Throwable t) {
                red.add("⑨ " + t.getMessage());
            }
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 表经 JSON 读回：控制台拿到的是序列化之后的那一份，栏位没序列化出去的话这一栏等于没有
     */
    private static List<JSONObject> readTableThroughJson() {
        return JSON.parseArray(JSON.toJSONString(BilibiliLiveReportOptions.layoutOptions()), JSONObject.class);
    }

    private static String diff(Set<String> keys, String wanted, Map<String, String> visibility) {
        List<String> wrong = keys.stream()
                .filter(key -> !wanted.equals(visibility.get(key)))
                .map(key -> key + " 现标 " + visibility.get(key))
                .toList();
        return String.join("、", wrong);
    }

    /**
     * 用挂了钩的画坊出一张报告，先清掉上一张收的字
     */
    private List<String> paintReport(Path dir, boolean showRevenue) throws Exception {
        drawnText.clear();

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
                        }
                        return super.drawTextWithStyle(texts, drawLocation, autoWrap, marginRight);
                    }
                };
            }
        };

        DemoAssets.DemoReportPainter painter = DemoAssets.demoPainter(demo, fonts, factory);
        JSONObject params = new JSONObject();
        params.put("box_ranking", 5);
        Optional<String> painted = painter.paint(BilibiliPlatform.BILIBILI.id(), STREAMER,
                BilibiliLiveReportOptions.of(params, showRevenue));
        assertTrue(painted.isPresent(), "报告没画出来，这一版无从量起（showRevenue=" + showRevenue + "）");
        return List.copyOf(drawnText);
    }
}
