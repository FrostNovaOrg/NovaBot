package org.frostnova.nova.core.assistant;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** 将本机查询结果绘制为带生成标识的图。 */
public interface AssistantChartRenderer {
    int PLOT_WIDTH = 760;
    int PLOT_HEIGHT = 560;

    enum Type { LINE, BAR, TABLE }

    record Value(String text, BigDecimal number) { }
    record Row(String category, List<Value> values) { }
    record ReferenceLine(BigDecimal value, String label) { }
    record Chart(Type type, String title, String xLabel, String yLabel, List<String> series,
                 List<Row> rows, ReferenceLine referenceLine, String source, int availableRows) { }

    /** 返回 PNG 的 Base64 编码。 */
    Optional<String> render(Chart chart);

    /** 已核对文字和元素的手绘示意。 */
    record Sketch(String title, String svg) { }

    /** 返回带标注的 PNG，或抛出可显示给提问者的固定拒绝理由。 */
    default Optional<String> renderSketch(Sketch sketch) { return Optional.empty(); }

    final class SketchRejected extends IllegalArgumentException {
        public SketchRejected(String message) { super(message); }
    }
}
