package org.frostnova.nova.adapter.qqbot;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 将群消息正文和可信提及的位置分开保存，重发时仍可取回原文。 */
final class QqBotMessageText {
    static final String AT_ALL_TAG = "<qqbot-at-everyone />";
    private static final Pattern MENTION = Pattern.compile("<(?=qqbot-|@)|@(?=everyone)", Pattern.CASE_INSENSITIVE);
    private static final String MARKDOWN_SYMBOLS = "\\`*_~[]<>|";
    private static final Pattern LINE_MARKER = Pattern.compile("[#+-]|[0-9]+\\.");
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    private final StringBuilder literal = new StringBuilder();
    private final List<Mention> mentions = new ArrayList<>();
    private boolean hasMembers;

    void append(String content, int start, int end) {
        literal.append(content, start, end);
    }

    void mentionEveryone() {
        mentions.add(new Mention(literal.length(), AT_ALL_TAG));
    }

    void mentionMember(String openid) {
        String attribute = openid.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
        mentions.add(new Mention(literal.length(), "<qqbot-at-user id=\"" + attribute + "\" />"));
        hasMembers = true;
    }

    boolean hasMembers() {
        return hasMembers;
    }

    boolean hasMentions() {
        return !mentions.isEmpty();
    }

    boolean isBlank() {
        return mentions.isEmpty() && literal.toString().isBlank();
    }

    String withoutMentions() {
        return escapeMentions(literal);
    }

    String render(boolean includeEveryone) {
        if (mentions.isEmpty()) return withoutMentions();
        var output = new StringBuilder();
        int start = 0;
        for (Mention mention : mentions) {
            if (!includeEveryone && AT_ALL_TAG.equals(mention.tag())) continue;
            appendLiteral(output, start, mention.offset());
            output.append(mention.tag());
            start = mention.offset();
        }
        appendLiteral(output, start, literal.length());
        return output.toString();
    }

    private void appendLiteral(StringBuilder output, int start, int end) {
        if (start == end) return;
        String text = escapeMentions(literal.substring(start, end));
        if (hasMembers) {
            appendMarkdown(output, text);
        } else {
            output.append(text);
        }
    }

    // 摘除占位符后，两侧文字可能拼成新的提及标签。
    static String escapeMentions(CharSequence text) {
        return MENTION.matcher(text).replaceAll(match -> "<".equals(match.group()) ? "＜" : "＠");
    }

    private static void appendMarkdown(StringBuilder output, String text) {
        var breaks = LINE_BREAK.matcher(text);
        boolean lineStart = output.isEmpty();
        int start = 0;
        while (breaks.find()) {
            appendMarkdownLine(output, text.substring(start, breaks.start()), lineStart);
            output.append(breaks.group());
            start = breaks.end();
            lineStart = true;
        }
        appendMarkdownLine(output, text.substring(start), lineStart);
    }

    private static void appendMarkdownLine(StringBuilder output, String line, boolean lineStart) {
        var marker = LINE_MARKER.matcher(line);
        int markerEnd = lineStart && marker.lookingAt() ? marker.end() - 1 : -1;
        for (int i = 0; i < line.length(); i++) {
            char symbol = line.charAt(i);
            if (i == markerEnd || MARKDOWN_SYMBOLS.indexOf(symbol) >= 0) output.append('\\');
            output.append(symbol);
        }
    }

    private record Mention(int offset, String tag) {}
}
