package org.frostnova.nova.core.assistant;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** 将本机查询结果绘制为带生成标识的模板图。 */
public interface AssistantChartRenderer {
    enum Type { LINE, BAR, TABLE }

    record Value(String text, BigDecimal number) { }
    record Row(String category, List<Value> values) { }
    record ReferenceLine(BigDecimal value, String label) { }
    record Chart(Type type, String title, String xLabel, String yLabel, List<String> series,
                 List<Row> rows, ReferenceLine referenceLine, String source, int availableRows) { }

    /** 返回 PNG 的 Base64 编码。 */
    Optional<String> render(Chart chart);
}
