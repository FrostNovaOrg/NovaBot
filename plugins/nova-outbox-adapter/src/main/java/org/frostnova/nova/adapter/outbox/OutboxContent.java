package org.frostnova.nova.adapter.outbox;

import org.frostnova.nova.adapter.outbox.OutboxRecord.Segment;
import org.frostnova.nova.adapter.outbox.OutboxRecord.SegmentType;
import org.frostnova.nova.core.model.MessagePlaceholders;
import org.frostnova.nova.core.util.MessageImageAddressPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 按占位符顺序取段。网址保持原文，内联图片单独交给文件存储。
 * <p>
 * 图片网址先过 {@link MessageImageAddressPolicy}：取件程序照地址取图时，是从取件那台设备所在的网络去取。
 * 指向本机、内网，或主机名解析不出的，这一段不交出（既不是网址段也不是文字），其余照旧，记一条不带地址的警告；
 * 写法不对的（不是 http、https，读不出主机名，带 {@code ..} 段等）整个占位符原样留成文字。
 */
record OutboxContent(List<Segment> segments, Map<Integer, byte[]> pictures, boolean atAll) {
    private static final Logger log = LoggerFactory.getLogger(OutboxContent.class);
    private static final Pattern TOKEN = Pattern.compile("(" + Pattern.quote(MessagePlaceholders.IMAGE_BASE64_PREFIX)
            + "|" + Pattern.quote(MessagePlaceholders.IMAGE_URL_PREFIX) + "|\\{face=|\\{at=)([^}]*)}");

    static OutboxContent parse(String part, MessageImageAddressPolicy imageAddresses) {
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
                    if (value.isBlank()) break;
                    switch (imageAddresses.check(value)) {
                        case ALLOWED -> {
                            flushText(segments, text);
                            segments.add(new Segment(SegmentType.IMAGE_URL, value));
                        }
                        // 警告里不带地址：地址里可能有令牌
                        case BLOCKED -> log.warn("外发内容里有一张图片的地址指向本机或内网，这张图没有交给取件程序，其余内容照常交出；"
                                + "确实要从内网取图时，在设置页「推送」组高级里的「消息图片 · 允许的内网网段」"
                                + "（novabot.core.push.image-allowed-networks）写上图床的地址并重启");
                        case UNRESOLVED -> log.warn("外发内容里有一张图片的地址主机名解析不出，这张图没有交给取件程序，其余内容照常交出；"
                                + "请检查图片地址和这台机器的域名解析");
                        case INVALID -> text.append(match.group());
                    }
                }
                case MessagePlaceholders.IMAGE_BASE64_PREFIX -> {
                    if (value.isEmpty()) break;
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
        if (segments.stream().allMatch(segment -> segment.type() == SegmentType.TEXT && segment.value().isBlank())) {
            throw new IllegalArgumentException("外发内容为空");
        }
        return new OutboxContent(segments, pictures, atAll);
    }

    private static void flushText(List<Segment> segments, StringBuilder text) {
        if (text.length() != 0) {
            segments.add(new Segment(SegmentType.TEXT, text.toString()));
            text.setLength(0);
        }
    }
}
