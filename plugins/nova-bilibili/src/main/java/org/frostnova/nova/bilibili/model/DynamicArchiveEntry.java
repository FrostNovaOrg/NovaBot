package org.frostnova.nova.bilibili.model;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.BilibiliPlatform;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 文本归档的固定字段，不持有接口原始报文或任何媒体对象。 */
public record DynamicArchiveEntry(String platform, long uid, String id, String type,
                                  Long publishedAt, long capturedAt, String text,
                                  List<String> links, List<ContentObject> objects, Original origin) {
    public record ContentObject(String type, String title, String description, String summary, List<String> links) { }

    /** 只保留一层原动态，不递归保存转发链。 */
    public record Original(Long uid, String id, String type, String text,
                           List<String> links, List<ContentObject> objects) { }

    private static final Pattern URL = Pattern.compile("(?:https?:)?//[^\\s<>\\\"\\[\\]（）【】，。！？]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEDIA = Pattern.compile(".*\\.(?:png|jpe?g|gif|webp|avif|bmp|svg|ico|heic|mp4|m4s|flv|mp3|wav|aac|ogg)(?:[!@].*)?", Pattern.CASE_INSENSITIVE);

    public static DynamicArchiveEntry from(long uid, Dynamic dynamic, long capturedAt) {
        Original content = content(dynamic);
        Long seconds = number(object(dynamic.getModules(), "module_author"), "pub_ts");
        Long publishedAt = null;
        if (seconds != null) {
            try {
                publishedAt = Math.multiplyExact(seconds, 1000L);
            } catch (ArithmeticException ignored) {
                // 无法表示的发布时间按未知记录。
            }
        }
        Original origin = dynamic.isForward() && dynamic.getOrigin() != null ? content(dynamic.getOrigin()) : null;
        return new DynamicArchiveEntry(BilibiliPlatform.BILIBILI.id(), uid, dynamic.getId(), content.type(),
                publishedAt, capturedAt, content.text(), content.links(), content.objects(), origin);
    }

    private static Original content(Dynamic dynamic) {
        JSONObject module = object(dynamic.getModules(), "module_dynamic");
        JSONObject major = object(module, "major");
        JSONObject desc = object(module, "desc");
        JSONObject opus = object(major, "opus");
        String text = richText(desc);
        if (text == null || text.isBlank()) text = richText(object(opus, "summary"));
        Set<String> links = new LinkedHashSet<>();
        if (dynamic.getId() != null && !dynamic.getId().isBlank()) addLink(links, dynamic.getUrl());
        textLinks(links, text);
        richLinks(links, desc);
        richLinks(links, object(opus, "summary"));

        List<ContentObject> objects = new ArrayList<>();
        for (String key : List.of("archive", "article", "opus", "live", "music", "pgc", "ugc_season", "common")) {
            JSONObject attached = object(major, key);
            if (attached == null) continue;
            String description = key.equals("archive") ? clean(string(attached, "desc")) : null;
            String summary = key.equals("opus") ? richText(object(attached, "summary"))
                    : key.equals("article") ? clean(string(attached, "desc")) : null;
            Set<String> objectLinks = new LinkedHashSet<>();
            addLink(objectLinks, string(attached, "jump_url"));
            if (objectLinks.isEmpty()) fallbackLink(objectLinks, key, attached);
            textLinks(objectLinks, description);
            textLinks(objectLinks, summary);
            richLinks(objectLinks, object(attached, "summary"));
            objects.add(new ContentObject(key, clean(string(attached, "title")), description, summary, List.copyOf(objectLinks)));
        }
        JSONObject live = object(major, "live_rcmd");
        JSONObject liveInfo = object(parseObject(live == null ? null : live.get("content")), "live_play_info");
        if (liveInfo != null) {
            Set<String> liveLinks = new LinkedHashSet<>();
            addLink(liveLinks, string(liveInfo, "link"));
            addLink(liveLinks, string(liveInfo, "jump_url"));
            if (liveLinks.isEmpty()) fallbackLink(liveLinks, "live", liveInfo);
            objects.add(new ContentObject("live_rcmd", clean(string(liveInfo, "title")), null, null, List.copyOf(liveLinks)));
        }
        JSONObject additional = object(module, "additional");
        for (String key : List.of("reserve", "ugc", "common", "vote")) {
            JSONObject attached = object(additional, key);
            if (attached == null) continue;
            Set<String> objectLinks = new LinkedHashSet<>();
            addLink(objectLinks, string(attached, "jump_url"));
            addLink(objectLinks, string(object(attached, "button"), "jump_url"));
            objects.add(new ContentObject(key, clean(string(attached, "title")), null, null, List.copyOf(objectLinks)));
        }
        objects.forEach(attached -> links.addAll(attached.links()));
        String type = dynamic.getType() == null || dynamic.getType().isBlank() ? "UNKNOWN" : dynamic.getType();
        return new Original(number(object(dynamic.getModules(), "module_author"), "mid"), dynamic.getId(), type,
                text, List.copyOf(links), List.copyOf(objects));
    }

    private static void fallbackLink(Set<String> links, String kind, JSONObject object) {
        String id = string(object, "id");
        if (kind.equals("archive")) {
            String bvid = string(object, "bvid");
            if (bvid != null && bvid.matches("BV[0-9A-Za-z]+")) addLink(links, "https://www.bilibili.com/video/" + bvid);
            else if (id != null && id.matches("[0-9]+")) addLink(links, "https://www.bilibili.com/video/av" + id);
        } else if (kind.equals("live")) {
            Long room = number(object, "room_id");
            if (room != null && room > 0) addLink(links, "https://live.bilibili.com/" + room);
        } else if (id != null && id.matches("[0-9]+")) {
            if (kind.equals("article")) addLink(links, "https://www.bilibili.com/read/cv" + id);
            if (kind.equals("opus")) addLink(links, "https://www.bilibili.com/opus/" + id);
        }
    }

    private static String richText(JSONObject textObject) {
        if (textObject == null) return null;
        String complete = string(textObject, "text");
        // 完整正文优先，节点可能只有部分内容；没有全文时才拼接节点。
        if (complete != null && !complete.isBlank()) return clean(complete);
        Object raw = textObject.get("rich_text_nodes");
        if (raw instanceof JSONArray nodes && !nodes.isEmpty()) {
            StringBuilder result = new StringBuilder();
            for (Object rawNode : nodes) {
                if (!(rawNode instanceof JSONObject node)) continue;
                String type = string(node, "type");
                if ("RICH_TEXT_NODE_TYPE_EMOJI".equals(type)) {
                    String name = string(node, "text");
                    if (name == null) name = string(object(node, "emoji"), "text");
                    if (name != null) result.append(name.startsWith("[") && name.endsWith("]") ? name : "[" + name + "]");
                } else if (!"RICH_TEXT_NODE_TYPE_PICTURE".equals(type)) {
                    String value = string(node, "text");
                    if (value != null) result.append(value);
                }
            }
            if (!result.isEmpty()) return clean(result.toString());
        }
        return clean(complete);
    }

    private static void richLinks(Set<String> links, JSONObject textObject) {
        if (textObject == null || !(textObject.get("rich_text_nodes") instanceof JSONArray nodes)) return;
        for (Object raw : nodes) {
            if (raw instanceof JSONObject node && "RICH_TEXT_NODE_TYPE_WEB".equals(string(node, "type"))) {
                addLink(links, string(node, "jump_url"));
                addLink(links, string(node, "orig_text"));
            }
        }
    }

    private static void textLinks(Set<String> links, String text) {
        if (text == null) return;
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) addLink(links, matcher.group().replaceAll("[),.;!?]+$", ""));
    }

    private static String clean(String text) {
        if (text == null) return null;
        Matcher matcher = URL.matcher(text);
        return matcher.replaceAll(match -> pageLink(match.group()) == null ? "" : Matcher.quoteReplacement(match.group()));
    }

    private static void addLink(Set<String> links, String value) {
        String link = pageLink(value);
        if (link != null) links.add(link);
    }

    private static String pageLink(String value) {
        if (value == null || value.isBlank()) return null;
        String link = value.startsWith("//") ? "https:" + value : value;
        if (link.startsWith("/")) link = "https://www.bilibili.com" + link;
        try {
            URI uri = URI.create(link);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) return null;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("hdslb.com") || host.endsWith(".hdslb.com") || host.equals("bilivideo.com") || host.endsWith(".bilivideo.com")) return null;
            if (MEDIA.matcher(uri.getPath()).matches()) return null;
            return link;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static JSONObject parseObject(Object raw) {
        if (raw instanceof JSONObject object) return object;
        if (raw instanceof String value) {
            try {
                return JSON.parseObject(value);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static JSONObject object(JSONObject parent, String key) {
        return parent != null && parent.get(key) instanceof JSONObject object ? object : null;
    }

    private static String string(JSONObject parent, String key) {
        if (parent == null) return null;
        Object value = parent.get(key);
        return value instanceof String || value instanceof Number ? value.toString() : null;
    }

    private static Long number(JSONObject parent, String key) {
        try {
            String value = string(parent, key);
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
