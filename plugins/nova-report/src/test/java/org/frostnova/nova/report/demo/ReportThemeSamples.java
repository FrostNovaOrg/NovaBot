package org.frostnova.nova.report.demo;

import org.frostnova.nova.report.painter.ReportPalette;
import org.frostnova.nova.report.util.FontUtil;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 报告图配色样张：三种报告图 × 每套具名预设，缩成 JPEG 内嵌进一张自包含网页，页尾附对比度表。
 * <p>
 * 重出：{@code bash tools/demo-assets/render-report-themes.sh}，写到 samples/report-themes/index.html。
 */
final class ReportThemeSamples {
    static final int SAMPLE_WIDTH = 540;

    static final float JPEG_QUALITY = 0.85f;

    static final long PAGE_MAX_BYTES = 6L * 1024 * 1024;

    /** 页面底色；图的圆角透明处压在同一个颜色上，深色那套的圆角才不露白边。 */
    static final Color PAGE_BACKGROUND = new Color(0xE6E8EC);

    static final double BODY_MIN = 4.5;

    static final double LARGE_MIN = 3.0;

    private static final Map<String, String> DESCRIPTIONS = Map.of(
            "classic", "升级前推送出去的样子，白底、粉色主播名，曲线与名次各用各的颜色；想要原来的样子就选它。",
            "pink-blue", "出厂默认的一套：近白微粉的底、淡蓝卡片，粉色为主、蓝色为辅，名次依次是粉、蓝、紫。",
            "warm", "奶油色的底、杏色卡片，橙与砖红为主调，文字用深棕。",
            "night", "深蓝灰的底、浅色文字，粉、蓝、金点缀，头像描边用底色，适合夜里看。");

    record Check(String group, String label, double min,
                 Function<ReportPalette, Color> foreground, Function<ReportPalette, Color> background) {
        double ratio(ReportPalette palette) {
            return contrast(foreground.apply(palette), background.apply(palette));
        }
    }

    static final List<Check> CHECKS = checks();

    private ReportThemeSamples() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path root = DemoAssets.repoRoot();
        Path page = root.resolve("samples").resolve("report-themes").resolve("index.html");
        Path demo = root.resolve("plugins").resolve("nova-report").resolve("target").resolve("report-themes-demo");
        long bytes = write(page, demo, DemoAssets.bundledFonts());
        System.out.println("wrote " + root.relativize(page) + " bytes=" + bytes);
    }

    /** 出 12 张图，写网页，返回网页字节数。超过 {@link #PAGE_MAX_BYTES} 抛错。 */
    static long write(Path page, Path demoDir, FontUtil fonts) throws IOException {
        var fixtures = new ReportSampleFixtures(demoDir, fonts);
        var cells = new ArrayList<List<String>>();
        for (var kind : ReportSampleFixtures.Kind.values()) {
            var row = new ArrayList<String>();
            for (var preset : ReportPalette.PRESETS) {
                row.add(jpegDataUri(fixtures.render(kind, preset.palette())));
            }
            cells.add(row);
        }
        String html = html(cells);
        Files.createDirectories(page.getParent());
        Files.writeString(page, html, StandardCharsets.UTF_8);
        long bytes = Files.size(page);
        if (bytes > PAGE_MAX_BYTES) {
            throw new IOException("sample page " + bytes + " bytes exceeds " + PAGE_MAX_BYTES);
        }
        return bytes;
    }

    static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color color) {
        return 0.2126 * channel(color.getRed()) + 0.7152 * channel(color.getGreen()) + 0.0722 * channel(color.getBlue());
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static List<Check> checks() {
        String body = "正文（不低于 4.5:1）";
        String large = "大标题、数字（不低于 3:1）";
        var list = new ArrayList<Check>();
        list.add(new Check(body, "正文字 / 画布底色", BODY_MIN, ReportPalette::text, ReportPalette::background));
        list.add(new Check(body, "正文字 / 卡片底色", BODY_MIN, ReportPalette::text, ReportPalette::card));
        list.add(new Check(body, "提示字 / 画布底色", BODY_MIN, ReportPalette::tip, ReportPalette::background));
        list.add(new Check(body, "提示字 / 卡片底色", BODY_MIN, ReportPalette::tip, ReportPalette::card));
        list.add(new Check(body, "通用正文 / 画布底色", BODY_MIN, ReportPalette::defaultText, ReportPalette::background));
        list.add(new Check(body, "版权与通用提示 / 画布底色", BODY_MIN, ReportPalette::defaultTip, ReportPalette::background));
        list.add(new Check(body, "转发原文 / 转发底色", BODY_MIN, ReportPalette::text, ReportPalette::originBackground));
        list.add(new Check(body, "转发作者 / 转发底色", BODY_MIN, ReportPalette::tip, ReportPalette::originBackground));
        list.add(new Check(body, "「本场」标签字 / 标签底色", BODY_MIN, ReportPalette::accent, ReportPalette::tagBackground));
        list.add(new Check(body, "周报场次标签字 / 标签底色", BODY_MIN, ReportPalette::tip, ReportPalette::sessionBadge));
        list.add(new Check(body, "周报上涨字 / 卡片底色", BODY_MIN, ReportPalette::up, ReportPalette::card));
        list.add(new Check(body, "周报下跌字 / 卡片底色", BODY_MIN, ReportPalette::down, ReportPalette::card));
        list.add(new Check(body, "日历日期 / 有直播格", BODY_MIN, ReportPalette::text, ReportPalette::calendarRecorded));
        list.add(new Check(body, "日历日期 / 确认没播格", BODY_MIN, ReportPalette::text, ReportPalette::calendarOff));
        list.add(new Check(body, "日历日期 / 说不准格", BODY_MIN, ReportPalette::text, ReportPalette::calendarUnknown));
        list.add(new Check(body, "链接 / 画布底色", BODY_MIN, ReportPalette::link, ReportPalette::background));
        list.add(new Check(body, "词云无内容提示 / 画布底色", BODY_MIN, ReportPalette::wordCloudEmpty, ReportPalette::background));
        String[] tail = {"词云辅助词", "词云交错词", "词云普通词", "词云点缀词"};
        for (int i = 0; i < tail.length; i++) {
            int index = i + 2;
            list.add(new Check(body, tail[i] + " / 画布底色", BODY_MIN, p -> p.wordCloud().get(index), ReportPalette::background));
        }
        list.add(new Check(large, "主播名 / 画布底色", LARGE_MIN, ReportPalette::streamerName, ReportPalette::background));
        list.add(new Check(large, "第一名名次 / 画布底色", LARGE_MIN, ReportPalette::rankFirst, ReportPalette::background));
        list.add(new Check(large, "第二名名次 / 画布底色", LARGE_MIN, ReportPalette::rankSecond, ReportPalette::background));
        list.add(new Check(large, "第三名名次 / 画布底色", LARGE_MIN, ReportPalette::rankThird, ReportPalette::background));
        list.add(new Check(large, "其余名次 / 画布底色", LARGE_MIN, ReportPalette::rankOther, ReportPalette::background));
        list.add(new Check(large, "高能时刻弹幕密度 / 画布底色", LARGE_MIN, ReportPalette::curveDanmu, ReportPalette::background));
        list.add(new Check(large, "词云主词 / 画布底色", LARGE_MIN, p -> p.wordCloud().get(0), ReportPalette::background));
        list.add(new Check(large, "词云次主词 / 画布底色", LARGE_MIN, p -> p.wordCloud().get(1), ReportPalette::background));
        return List.copyOf(list);
    }

    static String jpegDataUri(BufferedImage source) throws IOException {
        int height = Math.max(1, Math.round(source.getHeight() * (float) SAMPLE_WIDTH / source.getWidth()));
        Image scaled = source.getScaledInstance(SAMPLE_WIDTH, height, Image.SCALE_AREA_AVERAGING);
        BufferedImage flat = new BufferedImage(SAMPLE_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = flat.createGraphics();
        graphics.setColor(PAGE_BACKGROUND);
        graphics.fillRect(0, 0, SAMPLE_WIDTH, height);
        graphics.drawImage(scaled, 0, 0, null);
        graphics.dispose();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        var bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(flat, null, null), param);
        } finally {
            writer.dispose();
        }
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static String html(List<List<String>> cells) {
        var presets = ReportPalette.PRESETS;
        var out = new StringBuilder();
        out.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>报告图配色样张</title>\n<style>\n")
                .append("body{margin:0;padding:24px;background:").append(hex(PAGE_BACKGROUND))
                .append(";color:#22262e;font-family:-apple-system,\"PingFang SC\",\"Microsoft YaHei\",\"Noto Sans CJK SC\",sans-serif;}\n")
                .append("h1{font-size:24px;margin:0 0 8px;}h2{font-size:20px;margin:32px 0 12px;}\n")
                .append("p{margin:4px 0;line-height:1.6;}\n")
                .append("table{border-collapse:collapse;}\n")
                .append(".samples th,.samples td{vertical-align:top;padding:8px;}\n")
                .append(".samples thead th{position:sticky;top:0;background:").append(hex(PAGE_BACKGROUND))
                .append(";font-size:18px;z-index:1;}\n")
                .append(".samples tbody th{writing-mode:vertical-rl;font-size:18px;letter-spacing:4px;}\n")
                .append(".samples img{display:block;width:").append(SAMPLE_WIDTH).append("px;height:auto;}\n")
                .append(".contrast th,.contrast td{border:1px solid #c3c8d0;padding:4px 10px;text-align:left;background:#fff;}\n")
                .append(".contrast td.n{text-align:right;font-variant-numeric:tabular-nums;}\n")
                .append(".contrast .fail{color:#b3261e;font-weight:bold;}\n")
                .append(".contrast .group th{background:#f1f3f6;}\n")
                .append(".chip{display:inline-block;width:12px;height:12px;border:1px solid #888;vertical-align:-1px;margin-right:2px;}\n")
                .append("</style>\n</head>\n<body>\n")
                .append("<h1>报告图配色样张</h1>\n")
                .append("<p>同一份演示数据，按四套配色各出三种报告图。图已等比缩到宽 ").append(SAMPLE_WIDTH)
                .append(" 像素。人名、标题、数字都是虚构的演示数据。粉丝牌颜色来自平台数据，不随配色变。</p>\n");
        for (var preset : presets) {
            out.append("<p><b>").append(preset.name()).append("</b>：").append(DESCRIPTIONS.get(preset.id())).append("</p>\n");
        }
        out.append("<table class=\"samples\">\n<thead><tr><th></th>");
        for (var preset : presets) {
            out.append("<th>").append(preset.name()).append("</th>");
        }
        out.append("</tr></thead>\n<tbody>\n");
        var kinds = ReportSampleFixtures.Kind.values();
        for (int r = 0; r < kinds.length; r++) {
            out.append("<tr><th>").append(kinds[r].label).append("</th>");
            for (int c = 0; c < presets.size(); c++) {
                out.append("<td><img alt=\"").append(kinds[r].label).append(" · ").append(presets.get(c).name())
                        .append("\" src=\"").append(cells.get(r).get(c)).append("\"></td>");
            }
            out.append("</tr>\n");
        }
        out.append("</tbody>\n</table>\n");
        out.append("<h2>对比度</h2>\n<p>按 WCAG 2 的相对亮度算，前景色对它实际压着的底色。")
                .append("正文不低于 4.5:1，大标题与数字不低于 3:1；没达到的标红。")
                .append("「经典」与升级前的报告图一色不改，对比度不另作要求，所以照实列出。</p>\n");
        out.append("<table class=\"contrast\">\n<thead><tr><th>文字 / 底色</th>");
        for (var preset : presets) {
            out.append("<th>").append(preset.name()).append("</th>");
        }
        out.append("</tr></thead>\n<tbody>\n");
        String group = null;
        for (var check : CHECKS) {
            if (!check.group().equals(group)) {
                group = check.group();
                out.append("<tr class=\"group\"><th colspan=\"").append(presets.size() + 1).append("\">")
                        .append(group).append("</th></tr>\n");
            }
            out.append("<tr><td>").append(check.label()).append("</td>");
            for (var preset : presets) {
                var palette = preset.palette();
                double ratio = check.ratio(palette);
                boolean pass = ratio >= check.min();
                out.append("<td class=\"n").append(pass ? "" : " fail").append("\">")
                        .append(chip(check.foreground().apply(palette))).append(chip(check.background().apply(palette)))
                        .append(' ').append(String.format(Locale.ROOT, "%.2f", ratio)).append(pass ? "" : " ✗")
                        .append("</td>");
            }
            out.append("</tr>\n");
        }
        out.append("</tbody>\n</table>\n</body>\n</html>\n");
        return out.toString();
    }

    private static String chip(Color color) {
        return "<span class=\"chip\" style=\"background:" + hex(color) + "\" title=\"" + hex(color) + "\"></span>";
    }

    static String hex(Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }
}
