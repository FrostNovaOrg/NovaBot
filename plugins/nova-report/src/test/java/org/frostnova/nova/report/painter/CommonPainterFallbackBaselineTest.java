package org.frostnova.nova.report.painter;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补上的字与中文同一条基线
 * <p>
 * 一行字里，表里第一个字体（中文字体）显示不出的字由排在后面的字体补上：表情由表情字体画，č、Ł 这类西文字母由符号字体画。
 * 各字体从字顶到基线的距离（ascent）不一样，逐字按各自的 ascent 往下摆，补上的字就整个偏上或偏下——
 * 40 号字下中文字体是 47、表情字体与符号字体都是 38，补上的字比中文高 9 像素，看起来像上标。
 * <p>
 * 量的是画出来的像素，不读绘图器用了哪个字体的 ascent：每个字另在一块空白画布上、画在已知的基线上，得出它自己的底行；
 * 再在绘图器画出的那一行里找到它，两个底行一减，就是绘图器给这个字用的基线。
 * 同一行里每个字的基线都该与中文那个字相同，中文自己也该留在原处（行顶加中文字体的 ascent），
 * 不能为了对齐把整行挪到别的字体的基线上。
 * <p>
 * 绘图器有两处逐字画字：画一行文字（{@code drawTextWithStyle}）与画单个字符（{@code drawElement}），各量一次。
 * 只装随程序发布的三份字体：系统字体装没装取决于跑测试的机器。
 */
@DisplayName("补上的字与中文同一条基线")
class CommonPainterFallbackBaselineTest {
    /** 中文字体排第一，表情字体、符号字体在后面补字 */
    private static final List<String> FONTS = List.of("内置", "内置表情", "内置符号");

    /** 报告图小节标题的字号 */
    private static final int SIZE = 40;

    /** 行顶 */
    private static final int TOP = 40;

    /** 基准字，由表里第一个字体画 */
    private static final int HAN = '字';

    /** 补上的字：两个表情、两个西文字母 */
    private static final int[] FALLBACKS = {0x1F44D, 0x2764, 0x010D, 0x0141};

    /** 单独画一个字时用的基线 */
    private static final int REFERENCE_BASELINE = SIZE * 2;

    /** 不透明度到这个值才算笔画，绘图器画出的那一行与单独画的那个字用同一个值 */
    private static final int INK_ALPHA = 128;

    private FontUtil fontUtil;
    private CommonPainter painter;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPaint().getFonts().addAll(FONTS);
        fontUtil = new FontUtil(new DefaultResourceLoader(), properties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "0.0.0");
        painter = new CommonPainter(new BuildProperties(buildInfo), properties, fontUtil, 480, 200, false);
    }

    @Test
    @DisplayName("画一行文字：表情与 č、Ł 和中文同一条基线，中文留在原处")
    void textLineKeepsOneBaseline() {
        assertFallbacksComeFromOtherFonts();
        painter.drawTextWithStyle(List.of(new TextWithStyle(new String(lineCodePoints(), 0, lineCodePoints().length), SIZE)),
                new Point(20, TOP), false, 0);
        assertOneBaseline(painter.getImage(), "画一行文字");
    }

    @Test
    @DisplayName("逐字画字符：表情与 č、Ł 和中文同一条基线，中文留在原处")
    void elementsKeepOneBaseline() {
        assertFallbacksComeFromOtherFonts();
        painter.setPos(20, TOP);
        for (int codePoint : lineCodePoints()) {
            painter.drawElement(codePoint, Integer.valueOf(SIZE));
        }
        assertOneBaseline(painter.getImage(), "逐字画字符");
    }

    /**
     * 锚：补上的字真由中文字体之外的字体画，且那个字体显示得出。
     * 都由中文字体画的时候（比如表情字体没装上），大家本来就在同一条基线上，量出 0 也说明不了什么
     */
    private void assertFallbacksComeFromOtherFonts() {
        String primary = fontUtil.findFontForCharacter(HAN).getFamily(Locale.ROOT);
        List<String> problems = new ArrayList<>();
        for (int codePoint : FALLBACKS) {
            Font font = fontUtil.findFontForCharacter(codePoint);
            if (!font.canDisplay(codePoint)) {
                problems.add(describe(codePoint) + " 没有字体显示得出");
            } else if (primary.equals(font.getFamily(Locale.ROOT))) {
                problems.add(describe(codePoint) + " 也由 " + primary + " 画");
            }
        }
        assertTrue(problems.isEmpty(), "锚: 补上的字该由中文字体之外的字体画, 否则量不出错位: " + String.join("、", problems));
    }

    private void assertOneBaseline(BufferedImage image, String how) {
        int[] drawn = drawnCodePoints();
        List<int[]> glyphs = inkedColumnRuns(image);
        assertEquals(drawn.length, glyphs.size(),
                "锚: " + how + "后画布上该有 " + drawn.length + " 块互不相连的字迹, 数出 " + glyphs.size() + " 块, 分不清哪块是哪个字");

        List<String> failures = new ArrayList<>();
        int hanBaseline = baselineOf(image, glyphs.get(0), HAN);
        int hanAscent = ascentOf(HAN);
        if (hanBaseline != TOP + hanAscent) {
            failures.add(describe(HAN) + " 的基线在第 " + hanBaseline + " 行, 应在第 " + (TOP + hanAscent)
                    + " 行（行顶 " + TOP + " 加中文字体的 ascent " + hanAscent + "）");
        }
        for (int i = 1; i < drawn.length; i++) {
            int offset = baselineOf(image, glyphs.get(i), drawn[i]) - hanBaseline;
            if (offset != 0) {
                failures.add(describe(drawn[i]) + " 比中文" + (offset < 0 ? "高 " + -offset : "低 " + offset) + " 像素（"
                        + fontUtil.findFontForCharacter(drawn[i]).getFamily(Locale.ROOT) + "）");
            }
        }

        assertTrue(failures.isEmpty(), how + "时补上的字没和中文对齐, 红格数 " + failures.size() + ":\n" + String.join("\n", failures));
    }

    /**
     * 绘图器给这个字用的基线：画布上这块字迹的底行，减去这个字单独画时底行比基线低出的行数
     */
    private int baselineOf(BufferedImage image, int[] columns, int codePoint) {
        int bottom = bottomInkRow(image, columns[0], columns[1]);
        return bottom - (referenceBottom(codePoint) - REFERENCE_BASELINE);
    }

    /**
     * 这个字单独画在 {@link #REFERENCE_BASELINE} 上时的底行，字体与字号和绘图器挑的一样，渲染提示也一样
     */
    private int referenceBottom(int codePoint) {
        int side = SIZE * 4;
        BufferedImage canvas = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D draw = canvas.createGraphics();
        try {
            hint(draw);
            draw.setColor(Color.BLACK);
            draw.setFont(fontOf(codePoint));
            draw.drawString(new String(Character.toChars(codePoint)), SIZE, REFERENCE_BASELINE);
        } finally {
            draw.dispose();
        }
        int bottom = bottomInkRow(canvas, 0, side);
        assertTrue(bottom >= 0, "锚: " + describe(codePoint) + " 单独画出来没有笔画");
        return bottom;
    }

    private int ascentOf(int codePoint) {
        Graphics2D draw = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try {
            hint(draw);
            return draw.getFontMetrics(fontOf(codePoint)).getAscent();
        } finally {
            draw.dispose();
        }
    }

    private Font fontOf(int codePoint) {
        return fontUtil.findFontForCharacter(codePoint).deriveFont(Font.PLAIN, SIZE);
    }

    private static void hint(Graphics2D draw) {
        draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        draw.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    /**
     * 画布上有笔画的列，连成一段算一块字迹，左起依次排列；每块是 {起始列, 结束列（不含）}
     */
    private static List<int[]> inkedColumnRuns(BufferedImage image) {
        List<int[]> runs = new ArrayList<>();
        int start = -1;
        for (int x = 0; x <= image.getWidth(); x++) {
            boolean inked = x < image.getWidth() && bottomInkRow(image, x, x + 1) >= 0;
            if (inked && start < 0) {
                start = x;
            } else if (!inked && start >= 0) {
                runs.add(new int[]{start, x});
                start = -1;
            }
        }
        return runs;
    }

    /**
     * 指定列范围里最靠下的笔画所在行，没有笔画时为 -1
     */
    private static int bottomInkRow(BufferedImage image, int fromColumn, int toColumn) {
        for (int y = image.getHeight() - 1; y >= 0; y--) {
            for (int x = fromColumn; x < toColumn; x++) {
                if ((image.getRGB(x, y) >>> 24) >= INK_ALPHA) {
                    return y;
                }
            }
        }
        return -1;
    }

    /**
     * 这一行：中文在前，补上的字依次跟在后面，字与字之间隔一个空格，好在画布上分开
     */
    private static int[] lineCodePoints() {
        int[] drawn = drawnCodePoints();
        int[] line = new int[drawn.length * 2 - 1];
        for (int i = 0; i < drawn.length; i++) {
            line[i * 2] = drawn[i];
            if (i > 0) {
                line[i * 2 - 1] = ' ';
            }
        }
        return line;
    }

    private static int[] drawnCodePoints() {
        int[] drawn = new int[FALLBACKS.length + 1];
        drawn[0] = HAN;
        System.arraycopy(FALLBACKS, 0, drawn, 1, FALLBACKS.length);
        return drawn;
    }

    private static String describe(int codePoint) {
        return new String(Character.toChars(codePoint)) + "（U+" + Integer.toHexString(codePoint).toUpperCase(Locale.ROOT) + "）";
    }
}
