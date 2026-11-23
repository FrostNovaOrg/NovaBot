package org.frostnova.nova.report.painter;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.DocumentLimits;
import com.github.weisj.jsvg.parser.DomElement;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.parser.resources.ResourcePolicy;
import com.github.weisj.jsvg.renderer.PlatformSupport;
import com.github.weisj.jsvg.view.ViewBox;

import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.frostnova.nova.core.assistant.AssistantChartRenderer.PLOT_HEIGHT;
import static org.frostnova.nova.core.assistant.AssistantChartRenderer.PLOT_WIDTH;

/** 手绘转图子进程的入口。字体就绪后通知父进程，再通过管道收 SVG、发 PNG。 */
public final class AssistantSketchRaster {
    static final int READY = 1;
    static final int BLANK = 2;
    static final int MISSING_GLYPH = 3;
    static final int INVALID = 4;
    static final int MAX_PNG_BYTES = PLOT_WIDTH * PLOT_HEIGHT * 4 + 65536;
    private static final int LEFT = 35;
    private static final int TOP = 80;
    private static final int BODY_WIDTH = PLOT_WIDTH - 2 * LEFT;
    private static final int BODY_HEIGHT = 416;
    private static final String FONT_FAMILY = "nova-sketch-bundled-noto";

    private AssistantSketchRaster() { }

    public static void main(String[] args) throws Exception {
        Font font = Font.createFont(Font.TRUETYPE_FONT, Path.of(args[0]).toFile());
        warmUpTextRendering(font);
        System.out.write(READY);
        System.out.flush();
        var input = new DataInputStream(System.in);
        String title = input.readUTF();
        String svg = input.readUTF();
        int status = writePng(title, svg, font);
        if (status != 0) System.exit(status);
    }

    private static void warmUpTextRendering(Font font) throws IOException {
        // JSVG 首次绘字会枚举系统字体，把这一步放在发出就绪信号前。
        String svg = """
                <svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'>
                  <text x='1' y='13' font-size='12'>中</text>
                </svg>
                """;
        var document = loadSvg(svg, new FontCoverage(font));
        if (document == null) throw new IOException("Could not load sketch warm-up SVG");
        var graphics = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB).createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            document.renderWithPlatform(new BundledFont(font), graphics, new ViewBox(16, 16));
        } finally {
            graphics.dispose();
        }
    }

    private static SVGDocument loadSvg(String svg, FontCoverage coverage) {
        var loader = LoaderContext.builder()
                .externalResourcePolicy(ResourcePolicy.DENY_ALL)
                .documentLimits(new DocumentLimits(12, 0, 128))
                .preProcessor(coverage::apply)
                .build();
        return new SVGLoader().load(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)), null, loader);
    }

    private static int writePng(String title, String svg, Font font) throws Exception {
        if (font.canDisplayUpTo(title) >= 0) return MISSING_GLYPH;
        var coverage = new FontCoverage(font);
        var document = loadSvg(svg, coverage);
        if (coverage.missing) return MISSING_GLYPH;
        if (document == null) return INVALID;

        var image = new BufferedImage(PLOT_WIDTH, PLOT_HEIGHT, BufferedImage.TYPE_INT_RGB);
        var palette = ReportPalette.DEFAULT;
        var graphics = image.createGraphics();
        try {
            graphics.setColor(palette.background());
            graphics.fillRect(0, 0, PLOT_WIDTH, PLOT_HEIGHT);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            var body = (java.awt.Graphics2D) graphics.create(LEFT, TOP, BODY_WIDTH, BODY_HEIGHT);
            try {
                document.renderWithPlatform(new BundledFont(font), body, new ViewBox(BODY_WIDTH, BODY_HEIGHT));
            } finally {
                body.dispose();
            }
            if (!hasVisibleContent(image)) return BLANK;

            graphics.setColor(palette.accent());
            graphics.fillRoundRect(LEFT, 23, 42, 5, 5, 5);
            graphics.setColor(palette.text());
            graphics.setFont(font.deriveFont(23f));
            graphics.drawString(title, LEFT, 61);
            graphics.setColor(palette.calendarBorder());
            graphics.drawLine(LEFT, 503, PLOT_WIDTH - LEFT, 503);
            graphics.setColor(palette.tip().darker());
            graphics.setFont(font.deriveFont(14f));
            graphics.drawString("助手手绘，仅示意，数字以文字为准", LEFT, 525);
            graphics.drawString("由人工智能生成", LEFT, 546);
        } finally {
            graphics.dispose();
        }
        ImageIO.setUseCache(false);
        return ImageIO.write(image, "png", System.out) ? 0 : INVALID;
    }

    private static boolean hasVisibleContent(BufferedImage image) {
        int first = image.getRGB(LEFT, TOP);
        for (int y = TOP; y < TOP + BODY_HEIGHT; y++) {
            for (int x = LEFT; x < LEFT + BODY_WIDTH; x++) {
                if (image.getRGB(x, y) != first) return true;
            }
        }
        return false;
    }

    private static final class FontCoverage {
        private final Font font;
        private boolean missing;

        FontCoverage(Font font) { this.font = font; }

        void apply(DomElement node) {
            node.setAttribute("font-family", FONT_FAMILY);
            node.setAttribute("font-style", "normal");
            node.setAttribute("font-weight", "normal");
            for (var segment : node.textContent().contentAfterChildIndex(-1)) {
                if (font.canDisplayUpTo(segment.text()) >= 0) missing = true;
            }
            node.children().forEach(this::apply);
        }
    }

    private record BundledFont(Font font) implements PlatformSupport {
        @Override public Font customFont(String family) { return FONT_FAMILY.equals(family) ? font : null; }
        @Override public ImageObserver imageObserver() { return null; }
        @SuppressWarnings("deprecation")
        @Override public TargetSurface targetSurface() { return null; }
    }
}
