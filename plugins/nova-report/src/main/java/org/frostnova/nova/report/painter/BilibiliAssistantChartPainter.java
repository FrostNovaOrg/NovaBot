package org.frostnova.nova.report.painter;

import org.frostnova.nova.core.assistant.AssistantChartRenderer;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** 智能助手的定幅数据图，沿用数据查询图的报告画布与配色。 */
@NovaComponent
public class BilibiliAssistantChartPainter implements AssistantChartRenderer {
    private static final int WIDTH = PLOT_WIDTH;
    private static final int TABLE_HEIGHT = 816;
    private static final int MARGIN = 35;
    private static final int CONTENT_WIDTH = WIDTH - MARGIN * 2;
    private static final int TOP = 162;
    private static final int BOTTOM = 438;
    private static final int LEFT = 113;
    private static final int RIGHT = WIDTH - MARGIN - 18;
    private static final int CATEGORY_WIDTH = 100;
    private static final int CATEGORY_TOP = BOTTOM + 9;
    private static final int LABEL_SIZE = 16;
    private static final int MIN_LABEL_SIZE = 10;
    private static final int LINE_LABEL_GAP = 6;
    private static final int ALL_BAR_LABELS = 12;
    private static final String NOTICE = "由人工智能生成";
    private static final List<Integer> STEPS = List.of(1, 2, 5, 10);
    private static final Pattern AVERAGE_SERIES = Pattern.compile("平均|(?i:^(?:avg|average)(?:[:_].+)?$)");
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

    @Override
    public Optional<String> renderSketch(Sketch sketch) {
        return AssistantSketchPainter.render(sketch, fonts);
    }

    private void drawPlot(CommonPainter painter, Chart chart) {
        var palette = painter.palette();
        var colors = List.of(palette.curveOnline(), palette.curveWatched(), palette.curveDanmu(), palette.rankFirst());
        drawLegend(painter, chart, colors);
        painter.drawRoundedRectangle(MARGIN, 128, CONTENT_WIDTH, 358, 18, palette.card());

        Scale scale = Scale.of(chart);
        int decimalPlaces = chart.series().stream().allMatch(AVERAGE_SERIES.asPredicate()) ? 1 : 2;
        boolean bar = chart.type() == Type.BAR;
        var occupied = new ArrayList<Shape>();
        int tickWidth = LEFT - MARGIN - 17;
        for (BigDecimal tick = scale.low(); tick.compareTo(scale.high()) <= 0; tick = tick.add(scale.step())) {
            int y = scale.y(tick);
            painter.drawRectangle(LEFT, y, RIGHT - LEFT + 1, 1, tick.signum() == 0 ? palette.curveGapEdge() : palette.calendarBorder());
            text(painter, tickLabel(tick), MARGIN + 7, y - 12, tickWidth, 17, 10,
                    muted(painter), Font.PLAIN, Align.RIGHT, false);
            if (!bar) occupied.add(new Rectangle(MARGIN + 7, y - 12, tickWidth, 22));
        }
        if (chart.yLabel() != null && chart.series().size() > 1) {
            text(painter, chart.yLabel(), LEFT, 132, (RIGHT - LEFT) / 2, 17, 16, muted(painter), Font.PLAIN, Align.LEFT, true);
        }
        if (chart.referenceLine() != null) drawReference(painter, scale, chart.referenceLine(), decimalPlaces);
        int count = chart.rows().size();
        var values = new ArrayList<PlotValue>();
        var required = new LinkedHashSet<PlotValue>();
        for (int series = 0; series < chart.series().size(); series++) {
            var points = new ArrayList<PlotValue>();
            if (chart.type() == Type.LINE) {
                drawLine(painter, chart, series, colors.get(series), scale, points, occupied);
                keepExtrema(points, required);
                if (!points.isEmpty()) required.add(points.get(points.size() - 1));
            } else {
                drawBars(painter, chart, series, colors.get(series), scale, points);
            }
            values.addAll(points);
        }
        if (bar) {
            values.stream().map(PlotValue::bar).forEach(occupied::add);
            keepExtrema(values, required);
            values.stream().max(Comparator.comparingInt(PlotValue::row).thenComparingInt(PlotValue::x)).ifPresent(required::add);
        }
        boolean allBars = bar && values.size() <= ALL_BAR_LABELS;
        drawValueLabels(painter, layoutValues(painter, chart, values, required, occupied, allBars));
        if (bar) {
            var requiredRows = required.stream().map(PlotValue::row).distinct().toList();
            drawBarCategories(painter, chart, requiredRows, allBars);
        } else {
            for (int index : ticks(count, Type.LINE)) {
                int x = x(index, count, Type.LINE);
                painter.drawRectangle(x, BOTTOM, 1, 6, palette.curveGapEdge());
                text(painter, chart.rows().get(index).category(), categoryLeft(x), CATEGORY_TOP, CATEGORY_WIDTH, 17, 16,
                        muted(painter), Font.PLAIN, Align.CENTER, true);
            }
        }
        text(painter, chart.xLabel(), MARGIN, 487, CONTENT_WIDTH, 18, 16, muted(painter), Font.PLAIN, Align.CENTER, true);
    }

    private void drawValueLabels(CommonPainter painter, List<ValueLabel> labels) {
        for (ValueLabel label : labels) {
            if (!label.shifted()) continue;
            var bounds = label.bounds();
            var point = label.point();
            int edge = bounds.y > point.y() ? bounds.y : bounds.y + bounds.height;
            painter.drawPolyline(List.of(new Point(point.x(), point.y()), new Point(point.x(), edge)), muted(painter), 1);
        }
        for (ValueLabel label : labels) {
            Rectangle bounds = label.bounds();
            painter.drawRoundedRectangle(bounds.x, bounds.y, bounds.width, bounds.height, 5, painter.palette().card());
            drawCentered(painter, label.text(), bounds.x + bounds.width / 2, bounds.y);
        }
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

    private void drawReference(CommonPainter painter, Scale scale, ReferenceLine reference, int decimalPlaces) {
        int y = scale.y(reference.value());
        var color = muted(painter);
        for (int x = LEFT; x < RIGHT; x += 15) painter.drawRectangle(x, y, Math.min(8, RIGHT - x), 2, color);
        int area = (RIGHT - LEFT) / 2;
        String number = number(reference.value(), decimalPlaces);
        int numberWidth = Math.min(area / 2, line(painter, number, 17, color, Font.BOLD).width());
        text(painter, reference.label(), RIGHT - area, 132, area - numberWidth - 6, 17, 15, color, Font.BOLD, Align.RIGHT, true);
        text(painter, number, RIGHT - numberWidth, 132, numberWidth, 17, 11, color, Font.BOLD, Align.RIGHT, false);
    }

    private void drawLine(CommonPainter painter, Chart chart, int series, Color color, Scale scale,
                          List<PlotValue> values, List<Shape> occupied) {
        var segment = new ArrayList<Point>();
        int count = chart.rows().size();
        for (int row = 0; row < count; row++) {
            var cell = chart.rows().get(row).values().get(series);
            BigDecimal value = cell.number();
            if (value == null) {
                drawSegment(painter, color, segment, count, occupied);
                segment.clear();
                continue;
            }
            int x = x(row, count, Type.LINE), y = scale.y(value);
            segment.add(new Point(x, y));
            values.add(new PlotValue(row, x, y, value, cell.text(), null));
        }
        drawSegment(painter, color, segment, count, occupied);
    }

    private static void drawSegment(CommonPainter painter, Color color, List<Point> segment, int count, List<Shape> occupied) {
        if (segment.size() > 1) {
            int width = count > 100 ? 2 : 3;
            painter.drawPolyline(segment, color, width);
            var path = new Path2D.Double();
            path.moveTo(segment.get(0).x, segment.get(0).y);
            segment.subList(1, segment.size()).forEach(point -> path.lineTo(point.x, point.y));
            // 避开整条笔画，并给抗锯齿边缘留出空隙。
            var stroke = new BasicStroke(width + 4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            occupied.add(stroke.createStrokedShape(path));
        }
        if (count <= 40 || segment.size() == 1) {
            for (Point point : segment) {
                painter.drawRoundedRectangle(point.x - 4, point.y - 4, 8, 8, 8, color);
                occupied.add(new Rectangle(point.x - 5, point.y - 5, 10, 10));
            }
        }
    }

    private void drawBars(CommonPainter painter, Chart chart, int series, Color color, Scale scale,
                          List<PlotValue> values) {
        int count = chart.rows().size();
        int seriesCount = chart.series().size();
        int width = Math.max(2, Math.min(40, (RIGHT - LEFT) * 4 / (5 * count * seriesCount)));
        int zero = scale.y(BigDecimal.ZERO);
        for (int row = 0; row < count; row++) {
            var cell = chart.rows().get(row).values().get(series);
            BigDecimal value = cell.number();
            if (value == null) continue;
            int center = x(row, count, Type.BAR) - (seriesCount * (width + 1)) / 2 + series * (width + 1) + width / 2;
            int y = scale.y(value);
            var bar = new Rectangle(center - width / 2, Math.min(y, zero), width, Math.max(2, Math.abs(zero - y)));
            painter.drawRoundedRectangle(bar.x, bar.y, bar.width, bar.height, Math.min(7, width), color);
            values.add(new PlotValue(row, center, y, value, cell.text(), bar));
        }
    }

    private static void keepExtrema(List<PlotValue> values, Set<PlotValue> required) {
        var byValue = Comparator.comparing(PlotValue::number);
        values.stream().max(byValue).ifPresent(required::add);
        values.stream().min(byValue).ifPresent(required::add);
    }

    private List<ValueLabel> layoutValues(CommonPainter painter, Chart chart, List<PlotValue> values,
                                          Set<PlotValue> required, List<Shape> occupied, boolean allBars) {
        var labels = new ArrayList<ValueLabel>();
        boolean bar = chart.type() == Type.BAR;
        int half = (RIGHT - LEFT) / 2;
        if (chart.yLabel() != null && chart.series().size() > 1) occupied.add(new Rectangle(LEFT, 132, half, 23));
        if (chart.referenceLine() != null) occupied.add(new Rectangle(RIGHT - half, 132, half, 23));
        if (allBars) {
            for (int size = LABEL_SIZE; size >= MIN_LABEL_SIZE; size--) {
                labels.clear();
                for (PlotValue value : values) placeValue(painter, value, size, labels, occupied);
                if (labels.size() == values.size()) return labels;
            }
        }
        labels.clear();
        for (PlotValue value : required) {
            if (fitValue(painter, value, labels, occupied) || !bar || shiftValue(painter, value, labels, occupied)) continue;
            if (!fitValue(painter, value, labels, List.of())) shiftValue(painter, value, labels, List.of());
        }
        int count = chart.rows().size();
        for (PlotValue value : values) {
            if (required.contains(value)) continue;
            if (allBars) {
                if (!fitValue(painter, value, labels, occupied)) shiftValue(painter, value, labels, occupied);
            } else if (bar ? count * chart.series().size() <= 16 : count <= 10) {
                placeValue(painter, value, LABEL_SIZE, labels, occupied);
            }
        }
        return labels;
    }

    private boolean fitValue(CommonPainter painter, PlotValue value, List<ValueLabel> labels, List<Shape> occupied) {
        for (int size = LABEL_SIZE; size >= MIN_LABEL_SIZE; size--) {
            if (placeValue(painter, value, size, labels, occupied)) return true;
        }
        return false;
    }

    private boolean placeValue(CommonPainter painter, PlotValue value, int size, List<ValueLabel> labels, List<Shape> occupied) {
        var text = line(painter, value.text(), size, painter.palette().text(), Font.PLAIN);
        int height = size + 7;
        int width = text.width() + 6;
        if (width > CONTENT_WIDTH) return false;
        boolean line = value.bar() == null;
        int gap = line ? LINE_LABEL_GAP : 3;
        var bounds = new Rectangle(centeredLeft(value.x(), width), value.y() - height - gap, width, height);
        if (!valueFits(value, bounds, labels, occupied) && (!line || !moveLineLabel(value, bounds, labels, occupied))) return false;
        labels.add(new ValueLabel(bounds, text, value, false));
        return true;
    }

    private static boolean moveLineLabel(PlotValue value, Rectangle bounds, List<ValueLabel> labels, List<Shape> occupied) {
        int above = bounds.y;
        bounds.y = value.y() + LINE_LABEL_GAP;
        if (bounds.y + bounds.height <= BOTTOM && valueFits(value, bounds, labels, occupied)) return true;
        bounds.y = above;
        // 在点的上方左右错开，仍让标注紧挨其对应的数据点。
        int offset = bounds.width / 2;
        for (int center : new int[]{value.x() - offset, value.x() + offset}) {
            bounds.x = centeredLeft(center, bounds.width);
            if (valueFits(value, bounds, labels, occupied)) return true;
        }
        bounds.y = value.y() - bounds.height / 2;
        if (bounds.y + bounds.height > BOTTOM) return false;
        for (int left : new int[]{value.x() - LINE_LABEL_GAP - bounds.width, value.x() + LINE_LABEL_GAP}) {
            bounds.x = left;
            if (left >= MARGIN && left + bounds.width <= WIDTH - MARGIN && valueFits(value, bounds, labels, occupied)) return true;
        }
        return false;
    }

    private boolean shiftValue(CommonPainter painter, PlotValue value, List<ValueLabel> labels, List<Shape> occupied) {
        var text = line(painter, value.text(), MIN_LABEL_SIZE, painter.palette().text(), Font.PLAIN);
        int height = MIN_LABEL_SIZE + 7;
        int width = text.width() + 6;
        if (width > CONTENT_WIDTH) return false;
        int left = centeredLeft(value.x(), width);
        // 先保留离柱顶最近的空位，移开后用细线指回原位。
        int top = value.y() - height - 3;
        for (int offset = 1; offset <= BOTTOM - TOP + 26; offset++) {
            for (int y : new int[]{top - offset, top + offset}) {
                if (y < TOP - 26 || y + height > BOTTOM || y <= value.y() && y + height > value.y()) continue;
                var bounds = new Rectangle(left, y, width, height);
                if (valueFits(value, bounds, labels, occupied)) {
                    labels.add(new ValueLabel(bounds, text, value, true));
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean valueFits(PlotValue value, Rectangle bounds, List<ValueLabel> labels, List<Shape> occupied) {
        return labels.stream().noneMatch(other -> bounds.intersects(other.bounds()))
                && occupied.stream().noneMatch(area -> area != value.bar() && area.intersects(bounds));
    }

    private void drawBarCategories(CommonPainter painter, Chart chart, List<Integer> required, boolean allBars) {
        int count = chart.rows().size();
        var categories = new ArrayList<CategoryLabel>();
        if (allBars) {
            int width = (RIGHT - LEFT) / count - 4;
            for (int index = 0; index < count; index++) {
                var label = fitCategory(painter, chart, index, width);
                if (label != null) categories.add(label);
            }
        }
        if (categories.size() < count) {
            var fitted = List.copyOf(categories);
            categories.clear();
            var ticks = ticks(count, Type.BAR);
            for (int index : required) {
                var label = fitted.stream().filter(category -> category.index() == index).findFirst()
                        .orElseGet(() -> fitCategory(painter, chart, index, CATEGORY_WIDTH));
                if (label != null && categoryFits(label, categories)) categories.add(label);
            }
            if (categories.size() < required.size()) {
                categories.clear();
                for (int index : required) staggerCategory(painter, chart, index, categories);
            }
            for (var label : fitted) {
                if (!required.contains(label.index()) && categoryFits(label, categories)) categories.add(label);
            }
            var candidates = allBars ? IntStream.range(0, count).boxed().toList() : ticks;
            for (int index : candidates) {
                if (!allBars && categories.size() >= ticks.size()) break;
                if (categories.stream().anyMatch(label -> label.index() == index)) continue;
                var label = allBars ? fitCategory(painter, chart, index, CATEGORY_WIDTH)
                        : singleCategory(painter, chart, index, CATEGORY_TOP, LABEL_SIZE);
                if (label != null && categoryFits(label, categories)) categories.add(label);
            }
        }
        for (CategoryLabel label : categories) {
            painter.drawRectangle(x(label.index(), count, Type.BAR), BOTTOM, 1, 6, painter.palette().curveGapEdge());
            for (int i = 0; i < label.lines().size(); i++) {
                drawCentered(painter, label.lines().get(i), label.center(), label.top() + i * label.lineHeight());
            }
        }
    }

    private CategoryLabel fitCategory(CommonPainter painter, Chart chart, int index, int width) {
        String category = chart.rows().get(index).category();
        int center = x(index, chart.rows().size(), Type.BAR);
        Color color = muted(painter);
        for (int size = LABEL_SIZE; size >= MIN_LABEL_SIZE; size--) {
            var single = line(painter, category, size, color, Font.PLAIN);
            if (single.width() <= width) return new CategoryLabel(index, center, CATEGORY_TOP, size + 3, List.of(single));
        }
        for (int size = LABEL_SIZE; size >= MIN_LABEL_SIZE; size--) {
            // 优先在词间换行，例如把日期和时刻分到两行。
            for (int split = category.indexOf(' '); split >= 0; split = category.indexOf(' ', split + 1)) {
                var label = splitCategory(painter, category, split, index, center, width, size, color);
                if (label != null) return label;
            }
        }
        for (int size = LABEL_SIZE; size >= MIN_LABEL_SIZE; size--) {
            for (int split = category.offsetByCodePoints(0, 1); split < category.length();
                 split = category.offsetByCodePoints(split, 1)) {
                var label = splitCategory(painter, category, split, index, center, width, size, color);
                if (label != null) return label;
            }
        }
        return null;
    }

    private CategoryLabel splitCategory(CommonPainter painter, String category, int split, int index,
                                        int center, int width, int size, Color color) {
        var first = line(painter, category.substring(0, split).stripTrailing(), size, color, Font.PLAIN);
        if (first.width() > width) return null;
        var second = line(painter, category.substring(split).stripLeading(), size, color, Font.PLAIN);
        if (second.width() > width) return null;
        return new CategoryLabel(index, center, CATEGORY_TOP, size + 3, List.of(first, second));
    }

    private void staggerCategory(CommonPainter painter, Chart chart, int index, List<CategoryLabel> categories) {
        for (int lane = 0; lane < 3; lane++) {
            var label = singleCategory(painter, chart, index, CATEGORY_TOP + lane * (MIN_LABEL_SIZE + 3), MIN_LABEL_SIZE);
            if (categoryFits(label, categories)) {
                categories.add(label);
                return;
            }
        }
    }

    private CategoryLabel singleCategory(CommonPainter painter, Chart chart, int index, int top, int size) {
        var text = fitText(painter, chart.rows().get(index).category(), CATEGORY_WIDTH, size, size,
                muted(painter), Font.PLAIN, true);
        return new CategoryLabel(index, x(index, chart.rows().size(), Type.BAR), top, size + 3, List.of(text));
    }

    private static boolean categoryFits(CategoryLabel label, List<CategoryLabel> categories) {
        return categories.stream().noneMatch(other -> label.bounds().intersects(other.bounds()));
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
        return centeredLeft(x, CATEGORY_WIDTH);
    }

    private static int centeredLeft(int x, int width) {
        return Math.max(MARGIN, Math.min(x - width / 2, WIDTH - MARGIN - width));
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
    private record PlotValue(int row, int x, int y, BigDecimal number, String text, Rectangle bar) { }
    private record ValueLabel(Rectangle bounds, TextLine text, PlotValue point, boolean shifted) { }
    private record CategoryLabel(int index, int center, int top, int lineHeight, List<TextLine> lines) {
        Rectangle bounds() {
            int width = lines.stream().mapToInt(TextLine::width).max().orElseThrow() + 4;
            return new Rectangle(centeredLeft(center, width), top, width, lineHeight * lines.size());
        }
    }

    private void text(CommonPainter painter, String text, int x, int y, int width, int size, int minimum,
                      Color color, int weight, Align align, boolean truncate) {
        var line = fitText(painter, text, width, size, minimum, color, weight, truncate);
        if (line == null) return;
        int offset = switch (align) {
            case LEFT -> 0;
            case CENTER -> (width - line.width()) / 2;
            case RIGHT -> width - line.width();
        };
        painter.drawTextWithStyle(line.runs(), new Point(x + offset, y));
    }

    private void drawCentered(CommonPainter painter, TextLine line, int center, int y) {
        painter.drawTextWithStyle(line.runs(), new Point(centeredLeft(center, line.width()), y));
    }

    private TextLine fitText(CommonPainter painter, String text, int width, int size, int minimum,
                             Color color, int weight, boolean truncate) {
        var line = line(painter, text, size, color, weight);
        while (line.width() > width && size > minimum) {
            line = line(painter, text, --size, color, weight);
        }
        if (line.width() > width) {
            if (!truncate) return null;
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
                if (line.width() > width) return null;
            }
        }
        return line;
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

    private static String tickLabel(BigDecimal value) {
        var normalized = value.stripTrailingZeros();
        return normalized.scale() > 8 || normalized.scale() < -8 ? normalized.toEngineeringString() : normalized.toPlainString();
    }

    private static String number(BigDecimal value, int decimalPlaces) {
        if (value.scale() > decimalPlaces) value = value.setScale(decimalPlaces, RoundingMode.HALF_UP);
        return value.stripTrailingZeros().toPlainString();
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
