package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.frostnova.nova.console.assistant.AssistantChartRequest.*;

record AssistantSketchRequest(int resultNumber, String title, String svg) {
    static final String NAME = "draw_sketch";
    static final String DESCRIPTION = "先用 draw_chart 的折线、柱状或表格，只有模板画不了的示意图才手绘。"
            + "只画十几个点以内的示意。任务进度的具体数值、收入等需要准确量读，必须用模板。"
            + "只用不涉及金额的数据，礼物占比只画数量占比。"
            + "手绘标题和名称标注用中文。手绘标题和每个文字标注里的阿拉伯数字必须能在引用结果里找到。"
            + "图上统一标注手绘、仅示意。两种出图每问合计最多两张。";
    static final List<String> REQUIRED = List.of("resultNumber", "title", "svg");
    private static final Set<String> FIELDS = Set.copyOf(REQUIRED);
    private static final int MAX_BYTES = 12 * 1024;

    static JSONObject fields() {
        return JSONObject.of(
                "resultNumber", JSONObject.of("type", "integer", "minimum", 1, "description", "引用一份查数返回的 resultNumber"),
                "title", string(MAX_TITLE, "示意图标题，最多 30 字"),
                "svg", string(MAX_BYTES, "完整 SVG，最多 12 KiB，建议 viewBox=\"0 0 690 416\"，幅面最多 760×560。"
                        + "使用 g、path、rect、circle、ellipse、line、polyline、polygon 和 text。"
                        + "用坐标、fill、stroke 等属性直接绘制，颜色用十六进制，不用样式、链接、变换、嵌套文字或外部资源。"
                        + "标注使用简短文字和结果里的数字或时分，字体统一使用内置字体。"));
    }

    static AssistantSketchRequest parse(JSONObject raw) {
        if (raw == null || !raw.keySet().equals(FIELDS)) throw new Invalid("手绘只接受结果编号、标题和 SVG");
        int resultNumber = integer(raw.get("resultNumber"), Integer.MAX_VALUE, "结果编号须为正整数");
        String title = requiredText(raw.get("title"), MAX_TITLE, "标题");
        if (!(raw.get("svg") instanceof String svg) || svg.isBlank()) throw new Invalid("请填写 SVG");
        if (svg.length() > MAX_BYTES || svg.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new Invalid("手绘 SVG 最多 12 KiB");
        return new AssistantSketchRequest(resultNumber, title, svg);
    }
}
