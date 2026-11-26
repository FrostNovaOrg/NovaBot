package org.frostnova.nova.adapter.outbox;

import org.frostnova.nova.adapter.outbox.OutboxRecord.Segment;
import org.frostnova.nova.adapter.outbox.OutboxRecord.SegmentType;
import org.frostnova.nova.core.model.MessagePlaceholders;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.regex.Pattern;

/** 按占位符顺序取段。网址保持原文，内联图片单独交给文件存储。 */
record OutboxContent(List<Segment> segments, Map<Integer, byte[]> pictures, boolean atAll) {
    private static final Pattern TOKEN = Pattern.compile("(" + Pattern.quote(MessagePlaceholders.IMAGE_BASE64_PREFIX)
            + "|" + Pattern.quote(MessagePlaceholders.IMAGE_URL_PREFIX) + "|\\{face=|\\{at=)([^}]*)}");

    static OutboxContent parse(String part) {
        var segments = new ArrayList<Segment>();
        var pictures = new LinkedHashMap<Integer, byte[]>();
        var text = new StringBuilder();
        var match = TOKEN.matcher(part);
        int start = 0;
        boolean atAll = false;
        while (match.find()) {
            text.append(part, start, match.start());
            start = match.end();
            String value = match.group(2);
            switch (match.group(1)) {
                case "{at=" -> {
                    if ("all".equals(value)) atAll = true;
                    else if (!value.matches("[0-9]+")) text.append(match.group());
                }
                case "{face=" -> { }
                case MessagePlaceholders.IMAGE_URL_PREFIX -> {
                    flushText(segments, text);
                    segments.add(new Segment(SegmentType.IMAGE_URL, value));
                }
                case MessagePlaceholders.IMAGE_BASE64_PREFIX -> {
                    flushText(segments, text);
                    try {
                        pictures.put(segments.size(), Base64.getDecoder().decode(value));
                    } catch (IllegalArgumentException invalid) {
                        throw new IllegalArgumentException("外发图片编码无效");
                    }
                    segments.add(new Segment(SegmentType.IMAGE_BYTES, null));
                }
            }
        }
        text.append(part, start, part.length());
        flushText(segments, text);
        return new OutboxContent(segments, pictures, atAll);
    }

    private static void flushText(List<Segment> segments, StringBuilder text) {
        if (text.length() != 0) {
            segments.add(new Segment(SegmentType.TEXT, text.toString()));
            text.setLength(0);
        }
    }
}
