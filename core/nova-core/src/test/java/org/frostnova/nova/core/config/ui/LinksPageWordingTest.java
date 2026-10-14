package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接页上同一样东西只有一个叫法
 * <p>
 * 抓的用户故障：连接页的字一半写在 index.html 里，一半由 links.js、tokens.js 渲染出来。
 * 只改了脚本、没改页面的话，同一张卡上标题写「签发新令牌」，旁边的说明还写「只读口令」，
 * 人会以为那是两样东西。这里查的是页上看得见的字：index.html 连接页那一段去掉注释，
 * 加上几个脚本里注释之外的字。
 */
@DisplayName("连接页不混用新旧两种叫法")
class LinksPageWordingTest {
    /**
     * 已换掉的旧叫法
     */
    private static final List<String> OLD = List.of("发一条试试", "只读口令", "签发一把新口令", "已签发的口令");

    /**
     * 连接页上由脚本渲染出来的字所在的几件
     */
    private static final List<String> SCRIPTS = List.of("links.js", "links-model.js", "tokens.js", "tokens-model.js");

    @Test
    @DisplayName("🔴 连接页上不再出现「发一条试试」「只读口令」「签发一把新口令」「已签发的口令」")
    void linksPageUsesOneNamePerThing() throws IOException {
        Path ui = Path.of("").toAbsolutePath().resolve("src/main/resources/config-ui");
        List<String> found = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        StringBuilder rendered = new StringBuilder();

        String index = Files.readString(ui.resolve("index.html"), StandardCharsets.UTF_8);
        int from = index.indexOf("<section class=\"page\" id=\"page-links\">");
        int to = from < 0 ? -1 : index.indexOf("</section>", from);
        assertTrue(from >= 0 && to > from, "index.html 里找不到连接页那一段，下面的查法量不到东西");
        collect("index.html", withoutHtmlComments(index.substring(0, to)),
                index.substring(0, from).split("\n", -1).length, found, page);

        for (String file : SCRIPTS) {
            String src = Files.readString(ui.resolve(file), StandardCharsets.UTF_8);
            collect(file, withoutJsComments(src), 1, found, rendered);
        }

        // 阳性对照只认结构与脚本，不认页面上的字：页面上的字正是要查的东西，拿它当对照的话，
        // 改回旧字时先红的是对照，旧叫法那一条反而读不到
        assertTrue(page.indexOf("id=\"card-test\"") >= 0 && page.indexOf("id=\"token-list\"") >= 0,
                "没读到测试消息与令牌清单那两块，扫的不是连接页");
        assertTrue(rendered.indexOf("只读访问令牌") >= 0,
                "脚本里一处新叫法都没读到，扫的不是页上渲染出来的字");
        assertTrue(found.isEmpty(), "连接页上还有旧叫法，与新叫法混在同一页:\n" + String.join("\n", found));
    }

    /**
     * 逐行找旧叫法，连同行号记下；只看起始行之后的部分
     */
    private static void collect(String file, String text, int firstLine, List<String> found, StringBuilder seen) {
        String[] lines = text.split("\n", -1);
        for (int i = firstLine - 1; i < lines.length; i++) {
            seen.append(lines[i]).append('\n');
            for (String old : OLD) {
                if (lines[i].contains(old)) {
                    found.add(file + ":" + (i + 1) + " 「" + old + "」 " + lines[i].strip());
                }
            }
        }
    }

    /**
     * 页面去掉 HTML 注释；注释换成等长空白，行号不动
     */
    private static String withoutHtmlComments(String src) {
        StringBuilder out = new StringBuilder(src);
        Matcher comment = Pattern.compile("<!--.*?-->", Pattern.DOTALL).matcher(src);
        while (comment.find()) {
            blank(out, comment.start(), comment.end());
        }
        return out.toString();
    }

    /**
     * 脚本去掉注释，字符串原样；注释换成等长空白，行号不动
     */
    private static String withoutJsComments(String src) {
        StringBuilder out = new StringBuilder(src);
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (src.startsWith("//", i)) {
                int end = src.indexOf('\n', i);
                end = end < 0 ? n : end;
                blank(out, i, end);
                i = end;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                blank(out, i, end);
                i = end;
            } else if (c == '\'' || c == '"' || c == '`') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c) {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    private static void blank(StringBuilder out, int from, int to) {
        for (int k = from; k < to; k++) {
            if (out.charAt(k) != '\n') {
                out.setCharAt(k, ' ');
            }
        }
    }
}
