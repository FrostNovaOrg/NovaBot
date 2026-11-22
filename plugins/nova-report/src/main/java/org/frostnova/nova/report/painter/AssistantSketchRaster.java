package org.frostnova.nova.report.painter;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.frostnova.nova.core.assistant.AssistantChartRenderer.PLOT_HEIGHT;
import static org.frostnova.nova.core.assistant.AssistantChartRenderer.PLOT_WIDTH;

/** 手绘转图子进程的入口。输入和 PNG 通过管道传输。 */
public final class AssistantSketchRaster {
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
        var input = new DataInputStream(System.in);
        String title = input.readUTF();
        String svg = input.readUTF();
        Font font = Font.createFont(Font.TRUETYPE_FONT, Path.of(args[0]).toFile());
        int status = writePng(title, svg, font);
        if (status != 0) System.exit(status);
    }

    private static int writePng(String title, String svg, Font font) throws Exception {
        if (font.canDisplayUpTo(title) >= 0) return MISSING_GLYPH;
        var coverage = new FontCoverage(font);
        var loader = LoaderContext.builder()
                .externalResourcePolicy(ResourcePolicy.DENY_ALL)
                .documentLimits(new DocumentLimits(12, 0, 128))
                .preProcessor(coverage::apply)
                .build();
        var document = new SVGLoader().load(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)), null, loader);
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
