package org.frostnova.nova.console.assistant;

import org.frostnova.nova.core.assistant.AssistantChartRenderer;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static javax.xml.stream.XMLStreamConstants.*;
import static org.frostnova.nova.console.assistant.AssistantChartRequest.Invalid;

/** 只重写允许的几何图元和文字，原始 SVG 不进入绘图器。 */
final class AssistantSketchSvg {
    private static final String SVG_NAMESPACE = "http://www.w3.org/2000/svg";
    private static final int MAX_DEPTH = 12;
    private static final int MAX_ELEMENTS = 128;
    private static final int MAX_LABEL = 160;
    private static final int MAX_OUTPUT_BYTES = 16 * 1024;
    private static final int MAX_COORDINATE = 4096;
    private static final Pattern DECIMAL = Pattern.compile("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
    private static final Pattern PAINT = Pattern.compile("#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})|[a-zA-Z]{1,24}");
    private static final Pattern FONT_NAME = Pattern.compile("[\\p{L}\\p{N}_ ,'\"-]{1,128}");
    private static final Pattern PATH = Pattern.compile("[MmZzLlHhVvCcSsQqTtAa0-9+.,\\s-]+");
    private static final Pattern LIST = Pattern.compile("[0-9+.,\\s-]+");
    private static final Map<String, Set<String>> ELEMENTS = Map.of(
            "svg", Set.of("width", "height", "viewBox"),
            "g", Set.of(),
            "rect", Set.of("x", "y", "width", "height", "rx", "ry"),
            "circle", Set.of("cx", "cy", "r"),
            "ellipse", Set.of("cx", "cy", "rx", "ry"),
            "line", Set.of("x1", "y1", "x2", "y2"),
            "path", Set.of("d"),
            "polyline", Set.of("points"),
            "polygon", Set.of("points"),
            "text", Set.of("x", "y"));
    private static final Map<String, Set<String>> KEYWORDS = Map.of(
            "stroke-linecap", Set.of("butt", "round", "square"),
            "stroke-linejoin", Set.of("miter", "round", "bevel"),
            "fill-rule", Set.of("nonzero", "evenodd"),
            "text-anchor", Set.of("start", "middle", "end"));

    static String prepare(String svg, UnaryOperator<String> label) {
        var input = XMLInputFactory.newDefaultFactory();
        input.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        input.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        input.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        var buffer = new StringWriter(svg.length());
        try {
            var reader = input.createXMLStreamReader(new StringReader(svg));
            var writer = XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(buffer);
            try {
                copy(reader, writer, label);
            } finally {
                writer.close();
                reader.close();
            }
        } catch (XMLStreamException error) {
            throw new Invalid("手绘 SVG 格式不正确");
        }
        String prepared = buffer.toString();
        if (prepared.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES)
            throw new Invalid("手绘文字展开后过长，请缩短标注");
        return prepared;
    }

    private static void copy(XMLStreamReader reader, XMLStreamWriter writer, UnaryOperator<String> label) throws XMLStreamException {
        int depth = 0;
        int elements = 0;
        StringBuilder text = null;
        boolean canNest = true;
        while (reader.hasNext()) {
            switch (reader.next()) {
                case START_ELEMENT -> {
                    if (++depth > MAX_DEPTH) throw new Invalid("手绘 SVG 嵌套太深");
                    if (++elements > MAX_ELEMENTS) throw new Invalid("手绘 SVG 图元太多");
                    String tag = reader.getLocalName();
                    if (!canNest || !ELEMENTS.containsKey(tag) || ("svg".equals(tag) != (depth == 1))
                            || reader.getNamespaceURI() != null && !SVG_NAMESPACE.equals(reader.getNamespaceURI())) {
                        throw new Invalid("手绘 SVG 含有不允许的元素");
                    }
                    writer.writeStartElement(tag);
                    if (depth == 1) {
                        writer.writeDefaultNamespace(SVG_NAMESPACE);
                        writeCanvas(reader, writer);
                    }
                    writeAttributes(reader, writer, tag);
                    canNest = "svg".equals(tag) || "g".equals(tag);
                    if ("text".equals(tag)) text = new StringBuilder();
                }
                case END_ELEMENT -> {
                    if (text != null) {
                        if (text.codePointCount(0, text.length()) > MAX_LABEL) throw new Invalid("手绘单个标注最多 160 字");
                        String displayed = label.apply(text.toString());
                        if (displayed.codePointCount(0, displayed.length()) > MAX_LABEL)
                            throw new Invalid("手绘标注展开后过长，请缩短标注");
                        writer.writeCharacters(AssistantChartRequest.clean(displayed, MAX_LABEL));
                        text = null;
                    }
                    writer.writeEndElement();
                    canNest = true;
                    depth--;
                }
                case CHARACTERS, CDATA, SPACE -> {
                    if (text != null) text.append(reader.getText());
                    else if (!reader.isWhiteSpace()) throw new Invalid("手绘文字须放在 text 元素中");
                }
                case DTD, PROCESSING_INSTRUCTION, ENTITY_REFERENCE ->
                        throw new Invalid("手绘 SVG 不允许 XML 声明外的指令、文档类型或实体");
                default -> { }
            }
        }
    }

    private static void writeCanvas(XMLStreamReader reader, XMLStreamWriter writer) throws XMLStreamException {
        String width = reader.getAttributeValue(null, "width");
        String height = reader.getAttributeValue(null, "height");
        String viewBox = reader.getAttributeValue(null, "viewBox");
        double w = dimension(width, AssistantChartRenderer.PLOT_WIDTH);
        double h = dimension(height, AssistantChartRenderer.PLOT_HEIGHT);
        if (viewBox == null) {
            viewBox = "0 0 " + w + " " + h;
        } else {
            List<Double> bounds = numbers(viewBox, LIST, 4);
            if (bounds.size() != 4 || bounds.get(2) < 1 || bounds.get(2) > AssistantChartRenderer.PLOT_WIDTH
                    || bounds.get(3) < 1 || bounds.get(3) > AssistantChartRenderer.PLOT_HEIGHT) {
                throw new Invalid("手绘 SVG 画布须在 760×560 以内");
            }
        }
        writer.writeAttribute("viewBox", viewBox);
    }

    private static double dimension(String input, int max) {
        if (input == null) return max;
        String value = input.strip();
        if (value.endsWith("px")) value = value.substring(0, value.length() - 2);
        return decimal(value, 1, max, "手绘 SVG 画布须在 760×560 以内");
    }

    private static void writeAttributes(XMLStreamReader reader, XMLStreamWriter writer, String tag) throws XMLStreamException {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String name = reader.getAttributeLocalName(i);
            if (reader.getAttributeNamespace(i) != null) throw new Invalid("手绘 SVG 含有不允许的属性");
            if ("svg".equals(tag) && ELEMENTS.get(tag).contains(name)) continue;
            String value = reader.getAttributeValue(i).strip();
            if (Set.of("font-family", "font-weight", "font-style").contains(name) && FONT_NAME.matcher(value).matches())
                continue;
            boolean accepted = switch (name) {
                case "fill", "stroke" -> PAINT.matcher(value).matches();
                case "fill-opacity", "stroke-opacity" -> inRange(value, 0, 1);
                case "font-size" -> inRange(value, 1, 96);
                case "stroke-width" -> inRange(value, 0, 48);
                case "stroke-dashoffset" -> inRange(value, -MAX_COORDINATE, MAX_COORDINATE);
                case "stroke-dasharray" -> "none".equals(value)
                        || numbers(value, LIST, 16).stream().allMatch(n -> n >= 1);
                case "d" -> ELEMENTS.get(tag).contains(name) && !numbers(value, PATH, 768).isEmpty();
                case "points" -> ELEMENTS.get(tag).contains(name) && numbers(value, LIST, 256).size() % 2 == 0;
                default -> KEYWORDS.containsKey(name) ? KEYWORDS.get(name).contains(value)
                        : ELEMENTS.get(tag).contains(name) && inRange(value, -MAX_COORDINATE, MAX_COORDINATE);
            };
            if (!accepted) throw new Invalid("手绘 SVG 含有不允许的属性或属性值");
            writer.writeAttribute(name, value);
        }
    }

    private static List<Double> numbers(String input, Pattern allowed, int maxCount) {
        if (!allowed.matcher(input).matches()) throw new Invalid("手绘 SVG 含有不允许的坐标或路径");
        var list = new ArrayList<Double>();
        var matcher = DECIMAL.matcher(input);
        while (matcher.find()) {
            if (list.size() == maxCount) throw new Invalid("手绘 SVG 路径过于复杂");
            list.add(decimal(matcher.group(), -MAX_COORDINATE, MAX_COORDINATE, "手绘 SVG 坐标超出范围"));
        }
        if (list.isEmpty()) throw new Invalid("手绘 SVG 坐标不能为空");
        return list;
    }

    private static boolean inRange(String input, double min, double max) {
        if (input.length() > 32 || !DECIMAL.matcher(input).matches()) return false;
        double value = Double.parseDouble(input);
        return value >= min && value <= max;
    }

    private static double decimal(String input, double min, double max, String error) {
        if (!inRange(input, min, max)) throw new Invalid(error);
        return Double.parseDouble(input);
    }
}
