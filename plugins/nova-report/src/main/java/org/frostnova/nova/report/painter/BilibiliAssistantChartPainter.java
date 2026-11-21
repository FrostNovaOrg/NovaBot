package org.frostnova.nova.report.painter;

import org.frostnova.nova.core.assistant.AssistantChartRenderer;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;

import java.awt.Color;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 智能助手的定幅数据图，沿用数据查询图的报告画布与配色。 */
@NovaComponent
public class BilibiliAssistantChartPainter implements AssistantChartRenderer {
    private static final int WIDTH = 760;
    private static final int PLOT_HEIGHT = 560;
    private static final int TABLE_HEIGHT = 816;
    private static final int MARGIN = 35;
    private static final int CONTENT_WIDTH = WIDTH - MARGIN * 2;
    private static final int TOP = 162;
    private static final int BOTTOM = 438;
    private static final int LEFT = 113;
    private static final int RIGHT = WIDTH - MARGIN - 18;
    private static final int CATEGORY_WIDTH = 100;
    private static final String NOTICE = "由人工智能生成";
    private static final List<Integer> STEPS = List.of(1, 2, 5, 10);
    private static final MathContext POSITION_PRECISION = MathContext.DECIMAL64;

    private final NovaCommonPainterFactory factory;
    private final FontUtil fonts;
    private final Font chinese;

    public BilibiliAssistantChartPainter(NovaCommonPainterFactory factory, FontUtil fonts) {
        this.factory = factory;
        this.fonts = fonts;
        this.chinese = fonts.parseFont("内置").orElse(null);
    }

    @Override
    public Optional<String> render(Chart chart) {
        if (chinese == null) return Optional.empty();
        int height = chart.type() == Type.TABLE ? TABLE_HEIGHT : PLOT_HEIGHT;
        var painter = factory.create(WIDTH, height);
        var palette = painter.palette();
        painter.drawRoundedRectangle(MARGIN, 22, 40, 5, 5, palette.accent());
        text(painter, chart.title(), MARGIN, 39, CONTENT_WIDTH, 36, 22, palette.text(), Font.BOLD, Align.LEFT, true);
        if (chart.type() == Type.TABLE) drawTable(painter, chart);
        else drawPlot(painter, chart);
        painter.drawRectangle(MARGIN, height - 49, CONTENT_WIDTH, 1, palette.calendarBorder());
        text(painter, chart.source(), MARGIN, height - 38, CONTENT_WIDTH - 158, 17, 17, muted(painter), Font.PLAIN, Align.LEFT, true);
        text(painter, NOTICE, WIDTH - MARGIN - 150, height - 38, 150, 18, 18, muted(painter), Font.PLAIN, Align.RIGHT, false);
        painter.setPos(0, height).createSolidRoundedRectangleBackground(palette.background(), 25);
        return painter.base64();
    }

    Font chineseFont() { return chinese; }

    private void drawPlot(CommonPainter painter, Chart chart) {
        var palette = painter.palette();
        var colors = List.of(palette.curveOnline(), palette.curveWatched(), palette.curveDanmu(), palette.rankFirst());
        drawLegend(painter, chart, colors);
        painter.drawRoundedRectangle(MARGIN, 128, CONTENT_WIDTH, 358, 18, palette.card());

        Scale scale = Scale.of(chart);
        for (BigDecimal tick = scale.low(); tick.compareTo(scale.high()) <= 0; tick = tick.add(scale.step())) {
            int y = scale.y(tick);
            painter.drawRectangle(LEFT, y, RIGHT - LEFT + 1, 1, tick.signum() == 0 ? palette.curveGapEdge() : palette.calendarBorder());
            text(painter, number(tick), MARGIN + 7, y - 12, LEFT - MARGIN - 17, 17, 10, muted(painter), Font.PLAIN, Align.RIGHT, false);
        }
        if (chart.yLabel() != null && chart.series().size() > 1) {
            text(painter, chart.yLabel(), LEFT, 132, (RIGHT - LEFT) / 2, 17, 16, muted(painter), Font.PLAIN, Align.LEFT, true);
        }
        if (chart.referenceLine() != null) drawReference(painter, scale, chart.referenceLine());
        int count = chart.rows().size();
        var labels = new ArrayList<ValueLabel>();
        for (int series = 0; series < chart.series().size(); series++) {
            if (chart.type() == Type.LINE) drawLine(painter, chart, series, colors.get(series), scale, labels);
            else drawBars(painter, chart, series, colors.get(series), scale, labels);
        }
        for (ValueLabel label : labels) {
            Rectangle bounds = label.bounds();
            painter.drawRoundedRectangle(bounds.x, bounds.y, bounds.width, bounds.height, 5, palette.card());
            text(painter, label.text(), bounds.x, bounds.y, bounds.width, 16, 16, palette.text(), Font.PLAIN, Align.CENTER, false);
        }

        for (int index : ticks(count, chart.type())) {
            int x = x(index, count, chart.type());
            painter.drawRectangle(x, BOTTOM, 1, 6, palette.curveGapEdge());
            text(painter, chart.rows().get(index).category(), categoryLeft(x), BOTTOM + 9, CATEGORY_WIDTH, 17, 16,
                    muted(painter), Font.PLAIN, Align.CENTER, true);
        }
        text(painter, chart.xLabel(), MARGIN, 487, CONTENT_WIDTH, 18, 16, muted(painter), Font.PLAIN, Align.CENTER, true);
    }

    private void drawLegend(CommonPainter painter, Chart chart, List<Color> colors) {
        int cell = Math.min(235, CONTENT_WIDTH / chart.series().size());
        for (int i = 0; i < chart.series().size(); i++) {
            int x = MARGIN + cell * i;
            painter.drawRoundedRectangle(x, 104, 17, 8, 5, colors.get(i));
            String label = chart.series().size() == 1 && chart.yLabel() != null ? chart.yLabel() : chart.series().get(i);
            text(painter, label, x + 25, 94, cell - 30, 20, 17, painter.palette().text(), Font.PLAIN, Align.LEFT, true);
        }
    }

    private void drawReference(CommonPainter painter, Scale scale, ReferenceLine reference) {
        int y = scale.y(reference.value());
        var color = muted(painter);
        for (int x = LEFT; x < RIGHT; x += 15) painter.drawRectangle(x, y, Math.min(8, RIGHT - x), 2, color);
        int area = (RIGHT - LEFT) / 2;
        String number = number(reference.value());
        int numberWidth = Math.min(area / 2, line(painter, number, 17, color, Font.BOLD).width());
        text(painter, reference.label(), RIGHT - area, 132, area - numberWidth - 6, 17, 15, color, Font.BOLD, Align.RIGHT, true);
        text(painter, number, RIGHT - numberWidth, 132, numberWidth, 17, 11, color, Font.BOLD, Align.RIGHT, false);
    }

    private void drawLine(CommonPainter painter, Chart chart, int series, Color color, Scale scale, List<ValueLabel> labels) {
        var segment = new ArrayList<Point>();
        int count = chart.rows().size();
        for (int row = 0; row < count; row++) {
            BigDecimal value = chart.rows().get(row).values().get(series).number();
            if (value == null) {
                drawSegment(painter, color, segment, count);
                segment.clear();
                continue;
            }
            int x = x(row, count, Type.LINE), y = scale.y(value);
            segment.add(new Point(x, y));
            if (count <= 10 || chart.series().size() == 1 && row % Math.max(1, count / 5) == 0 && count <= 45) {
                placeValue(painter, value, x, y - 26, labels);
            }
        }
        drawSegment(painter, color, segment, count);
    }

    private static void drawSegment(CommonPainter painter, Color color, List<Point> segment, int count) {
        painter.drawPolyline(segment, color, count > 100 ? 2 : 3);
        if (count <= 40 || segment.size() == 1) {
            for (Point point : segment) painter.drawRoundedRectangle(point.x - 4, point.y - 4, 8, 8, 8, color);
        }
    }

    private void drawBars(CommonPainter painter, Chart chart, int series, Color color, Scale scale, List<ValueLabel> labels) {
        int count = chart.rows().size();
        int seriesCount = chart.series().size();
        int width = Math.max(2, Math.min(40, (RIGHT - LEFT) * 4 / (5 * count * seriesCount)));
        int zero = scale.y(BigDecimal.ZERO);
        for (int row = 0; row < count; row++) {
            BigDecimal value = chart.rows().get(row).values().get(series).number();
            if (value == null) continue;
            int center = x(row, count, Type.BAR) - (seriesCount * (width + 1)) / 2 + series * (width + 1) + width / 2;
            int y = scale.y(value);
            painter.drawRoundedRectangle(center - width / 2, Math.min(y, zero), width,
                    Math.max(2, Math.abs(zero - y)), Math.min(7, width), color);
            if (count * seriesCount <= 16) placeValue(painter, value, center, y - 26, labels);
        }
    }

    private void placeValue(CommonPainter painter, BigDecimal value, int x, int y, List<ValueLabel> labels) {
        String label = number(value);
        int width = line(painter, label, 16, painter.palette().text(), Font.PLAIN).width();
        var bounds = new Rectangle(x - width / 2 - 3, y, width + 6, 23);
        if (bounds.x < MARGIN || bounds.getMaxX() > WIDTH - MARGIN || labels.stream().anyMatch(other -> bounds.intersects(other.bounds()))) return;
        labels.add(new ValueLabel(bounds, label));
    }

    private static List<Integer> ticks(int count, Type type) {
        for (int marks = Math.min(6, count); marks > 1; marks--) {
            var indices = new ArrayList<Integer>();
            int previous = -CATEGORY_WIDTH;
            for (int i = 0; i < marks; i++) {
                int index = (int) Math.round((double) i * (count - 1) / (marks - 1));
                int left = categoryLeft(x(index, count, type));
                if (left - previous < CATEGORY_WIDTH + 4) break;
                indices.add(index);
                previous = left;
            }
            if (indices.size() == marks) return indices;
        }
        return List.of(0);
    }

    private static int categoryLeft(int x) {
        return Math.max(MARGIN, Math.min(x - CATEGORY_WIDTH / 2, WIDTH - MARGIN - CATEGORY_WIDTH));
    }

    private static int x(int row, int count, Type type) {
        if (count == 1) return (LEFT + RIGHT) / 2;
        if (type == Type.BAR) return LEFT + (2 * row + 1) * (RIGHT - LEFT) / (2 * count);
        return LEFT + 12 + row * (RIGHT - LEFT - 24) / (count - 1);
    }

    private void drawTable(CommonPainter painter, Chart chart) {
        var palette = painter.palette();
        int start = 101;
        int header = 41;
        int columns = chart.series().size() + 1;
        int first = columns == 2 ? 365 : CONTENT_WIDTH / columns + 26;
        int other = (CONTENT_WIDTH - first) / (columns - 1);
        int rowHeight = Math.min(61, (TABLE_HEIGHT - 167 - start + header) / chart.rows().size());
        painter.drawRoundedRectangle(MARGIN, start, CONTENT_WIDTH, header, 14, palette.tagBackground());
        text(painter, chart.xLabel(), MARGIN + 17, start + 7, first - 24, 22, 17, palette.text(), Font.BOLD, Align.LEFT, true);
        for (int i = 0; i < chart.series().size(); i++) {
            String title = columns == 2 && chart.yLabel() != null ? chart.yLabel() : chart.series().get(i);
            text(painter, title, MARGIN + first + i * other, start + 7, other - 17, 22, 17, palette.text(), Font.BOLD, Align.RIGHT, true);
        }
        int y = start + header + 5;
        for (int i = 0; i < chart.rows().size(); i++) {
            var row = chart.rows().get(i);
            if (i % 2 == 0) painter.drawRoundedRectangle(MARGIN, y, CONTENT_WIDTH, rowHeight, 10, palette.card());
            int textY = y + (rowHeight - 30) / 2;
            text(painter, row.category(), MARGIN + 17, textY, first - 24, 22, 17, palette.text(), Font.PLAIN, Align.LEFT, true);
            for (int col = 0; col < row.values().size(); col++) {
                var value = row.values().get(col);
                text(painter, value.text(), MARGIN + first + col * other, textY, other - 17, 22, 11,
                        palette.text(), Font.PLAIN, Align.RIGHT, value.number() == null);
            }
            y += rowHeight;
        }
        if (chart.rows().size() < chart.availableRows()) {
            text(painter, "展示查数结果的前 " + chart.rows().size() + " 行", MARGIN + 10, y + 7, CONTENT_WIDTH - 20,
                    17, 17, muted(painter), Font.PLAIN, Align.LEFT, true);
        }
    }

    private enum Align { LEFT, CENTER, RIGHT }
    private record TextLine(List<TextWithStyle> runs, int width) { }
    private record ValueLabel(Rectangle bounds, String text) { }

    private void text(CommonPainter painter, String text, int x, int y, int width, int size, int minimum,
                      Color color, int weight, Align align, boolean truncate) {
        var line = line(painter, text, size, color, weight);
        while (line.width() > width && size > minimum) {
            line = line(painter, text, --size, color, weight);
        }
        if (line.width() > width) {
            if (!truncate) return;
            if (line.runs().size() == 1) {
                var run = line.runs().get(0);
                run.setText(painter.truncateToWidth(run, width));
                line = new TextLine(List.of(run), painter.getStringWidthAndHeight(run).getFirst());
            } else {
                int end = text.length();
                do {
                    end = text.offsetByCodePoints(end, -1);
                    line = line(painter, text.substring(0, end) + CommonPainter.ELLIPSIS, size, color, weight);
                } while (end > 0 && line.width() > width);
                if (line.width() > width) return;
            }
        }
        int offset = switch (align) {
            case LEFT -> 0;
            case CENTER -> (width - line.width()) / 2;
            case RIGHT -> width - line.width();
        };
        painter.drawTextWithStyle(line.runs(), new Point(x + offset, y));
    }

    private TextLine line(CommonPainter painter, String value, int size, Color color, int weight) {
        var primary = chinese.deriveFont(weight, size);
        if (primary.canDisplayUpTo(value) < 0) {
            var run = new TextWithStyle(value, primary, size, color, weight);
            return new TextLine(List.of(run), painter.getStringWidthAndHeight(run).getFirst());
        }
        var runs = new ArrayList<TextWithStyle>();
        var text = new StringBuilder();
        Font current = primary;
        for (int point : value.codePoints().toArray()) {
            if (FontUtil.isVariantSelector(point)) continue;
            Font font = primary.canDisplay(point) ? primary : fonts.findFontForCharacter(point).deriveFont(weight, size);
            if (!font.canDisplay(point)) {
                font = primary;
                point = '…';
            }
            if (!current.equals(font) && !text.isEmpty()) {
                runs.add(new TextWithStyle(text.toString(), current, size, color, weight));
                text.setLength(0);
            }
            current = font;
            text.appendCodePoint(point);
        }
        if (!text.isEmpty()) runs.add(new TextWithStyle(text.toString(), current, size, color, weight));
        int width = runs.stream().mapToInt(run -> painter.getStringWidthAndHeight(run).getFirst()).sum();
        return new TextLine(runs, width);
    }

    private static Color muted(CommonPainter painter) { return painter.palette().tip().darker(); }

    private static String number(BigDecimal value) {
        var normalized = value.stripTrailingZeros();
        return normalized.scale() > 8 || normalized.scale() < -8 ? normalized.toEngineeringString() : normalized.toPlainString();
    }

    private record Scale(BigDecimal low, BigDecimal high, BigDecimal step) {
        static Scale of(Chart chart) {
            var values = new ArrayList<>(chart.rows().stream().flatMap(row -> row.values().stream())
                    .map(Value::number).filter(Objects::nonNull).toList());
            if (chart.referenceLine() != null) values.add(chart.referenceLine().value());
            if (chart.type() == Type.BAR) values.add(BigDecimal.ZERO);
            BigDecimal min = values.stream().min(BigDecimal::compareTo).orElseThrow();
            BigDecimal max = values.stream().max(BigDecimal::compareTo).orElseThrow();
            if (min.compareTo(max) == 0) {
                BigDecimal padding = min.signum() == 0 ? BigDecimal.ONE : min.abs().movePointLeft(1);
                if (chart.type() == Type.LINE && min.signum() != 0) min = min.subtract(padding);
                max = max.add(padding);
            }
            BigDecimal raw = max.subtract(min).divide(BigDecimal.valueOf(5), POSITION_PRECISION).stripTrailingZeros();
            BigDecimal power = BigDecimal.ONE.scaleByPowerOfTen(raw.precision() - raw.scale() - 1);
            BigDecimal step = STEPS.stream().map(multiplier -> power.multiply(BigDecimal.valueOf(multiplier)))
                    .filter(candidate -> candidate.compareTo(raw) >= 0).findFirst().orElseThrow();
            BigDecimal low = min.divide(step, 0, RoundingMode.FLOOR).multiply(step);
            BigDecimal high = max.divide(step, 0, RoundingMode.CEILING).multiply(step);
            return new Scale(low, high, step);
        }

        int y(BigDecimal value) {
            double fraction = value.subtract(low).divide(high.subtract(low), POSITION_PRECISION).doubleValue();
            return BOTTOM - (int) Math.round((BOTTOM - TOP) * fraction);
        }
    }
}
