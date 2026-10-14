package org.frostnova.nova.console;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.handler.BilibiliLiveOnPushHandler;
import org.frostnova.nova.console.controller.PushController;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigurationFileService;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplier;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.service.NovaEventHandlerService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.report.handler.BilibiliDynamicPushHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ScannedGenericBeanDefinition;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 推送页、本群设置与模板编辑器上，一样东西只用一个叫法
 * <p>
 * 通道页的段名、模板编辑器里的变量区与报告版式两区、三档 @ 方式都已换成书面叫法。
 * 哪一处漏改，同一页上就会新旧两种说法并排出现：上面段名写「推送类型」，下面提示却让人去「推什么」里开，
 * 读的人会以为是两个地方。查的只是给人看的字：脚本里注释之外的部分，样式字符串里的注释也算注释。
 */
@DisplayName("推送页与模板编辑器不再混用旧叫法")
class PushTemplateWordingTest {

    private static final List<String> FILES = List.of(
            "push.js", "push-model.js", "sessions.js", "session-draft.js", "template.js", "template-model.js");

    /** 换掉的旧叫法 */
    private static final List<String> OLD = List.of(
            "推什么", "消息长什么样", "可用的块", "报告里的卡片", "没放进报告的", "@订阅的人");

    /** 对应的新叫法：一个都读不到，说明扫的不是页上那些字 */
    private static final List<String> NEW = List.of(
            "推送类型", "消息模板", "可用变量", "已启用卡片", "未启用卡片", "@订阅者");

    @TempDir
    Path dir;

    @Test
    @DisplayName("🔴 页上的字里没有「推什么」「消息长什么样」「可用的块」「报告里的卡片」「没放进报告的」「@订阅的人」")
    void noOldNamesOnThePage() throws IOException {
        List<String> found = new ArrayList<>();
        StringBuilder visible = new StringBuilder();
        for (String file : FILES) {
            String text = withoutComments(Files.readString(pages().resolve(file), StandardCharsets.UTF_8));
            visible.append(text).append('\n');
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                for (String word : OLD) {
                    if (lines[i].contains(word)) {
                        found.add(file + ":" + (i + 1) + " 「" + word + "」 " + lines[i].strip());
                    }
                }
            }
        }

        assertTrue(found.isEmpty(), "页上还写着旧叫法，与同一页的新叫法混着出现:\n" + String.join("\n", found));
        List<String> missing = NEW.stream().filter(word -> visible.indexOf(word) < 0).toList();
        assertTrue(missing.isEmpty(), "阳性对照：页上的字里读不到这几个新叫法，扫的不是给人看的那些字: " + missing);
    }

    /**
     * 模板编辑器里块的名字不在脚本里：{@code {at}} 块显示的是处理器自报的名字，经
     * {@code /api/handlers} 的 placeholderLabels 送到编辑器。某个处理器还报旧名时，
     * 编辑器里的块写「@订阅的人」，旁边下拉写「@订阅者」，而脚本里一个旧字也没有。
     * <p>
     * 处理器从类路径上现找，不手写名单：手写的话，后加的处理器不在单子上，看起来和查过了一样。
     */
    @Test
    @DisplayName("🔴 处理器经 /api/handlers 报给编辑器的块名与通知名里没有旧叫法")
    void noOldNamesFromHandlers() throws Exception {
        List<NovaEventHandler> handlers = productHandlers();
        List<String> names = handlers.stream().map(h -> h.getClass().getName()).toList();
        for (Class<?> known : List.of(BilibiliLiveOnPushHandler.class, BilibiliDynamicPushHandler.class)) {
            assertTrue(names.contains(known.getName()),
                    "阳性对照：类路径上没找到 " + known.getSimpleName() + "，扫的不是编辑器读的那些处理器: " + names);
        }

        Map<String, NovaEventHandler> beans = new LinkedHashMap<>();
        handlers.forEach(h -> beans.put(h.getClass().getName(), h));
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeansOfType(NovaEventHandler.class)).thenReturn(beans);
        NovaEventHandlerService service = new NovaEventHandlerService(context);
        service.onContextRefreshedEvent();
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        JSONArray items = new PushController(mock(RuntimeConfigurationApplier.class),
                mock(ConfigurationFileService.class), service, new PushTemplateDefaults(properties),
                mock(PushActivityRecorder.class)).handlers().getJSONArray("handlers");
        assertEquals(handlers.size(), items.size(), "接口没把每个处理器都报出来");

        List<String> found = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            String owner = item.getString("className");
            List<String> texts = new ArrayList<>();
            texts.add(item.getString("displayName"));
            texts.add(item.getString("description"));
            item.getJSONObject("placeholderLabels").values().forEach(v -> texts.add(String.valueOf(v)));
            texts.add(String.valueOf(item.get("options")));
            for (String text : texts) {
                shown.add(text);
                for (String word : OLD) {
                    if (text != null && text.contains(word)) {
                        found.add(owner.substring(owner.lastIndexOf('.') + 1) + " 「" + word + "」 " + text);
                    }
                }
            }
        }

        assertTrue(found.isEmpty(), "处理器报给编辑器的字里还有旧叫法，与编辑器上的新叫法并排出现:\n"
                + String.join("\n", found));
        assertTrue(shown.contains("@订阅者"), "阳性对照：没读到任何处理器报的「@订阅者」，查的不是块名: " + shown);
    }

    /**
     * 类路径上产品代码里的每一个处理器（不含测试里的假处理器），按名字排好。
     * 只读它们自报的名字，构造参数无关，所以用调真方法的 mock 建实例
     */
    private static List<NovaEventHandler> productHandlers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(NovaEventHandler.class));
        List<NovaEventHandler> out = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("org.frostnova.nova")) {
            if (definition instanceof ScannedGenericBeanDefinition scanned
                    && String.valueOf(scanned.getSource()).contains("test-classes")) {
                continue;
            }
            Class<?> type = Class.forName(definition.getBeanClassName());
            out.add((NovaEventHandler) mock(type, CALLS_REAL_METHODS));
        }
        out.sort(Comparator.comparing(h -> h.getClass().getName()));
        return out;
    }

    @Test
    @DisplayName("去注释只去注释：行注释、块注释与样式串里的注释去掉，字符串里的字留着")
    void strippingKeepsStrings() {
        String src = "// 推什么\n/* 可用的块 */ const a = '消息模板'; // 消息长什么样\n"
                + "const css = `.x{color:red} /* 报告里的卡片 */`;\nconst b = \"// 不是注释\";\n";
        String out = withoutComments(src);

        for (String word : List.of("推什么", "可用的块", "消息长什么样", "报告里的卡片")) {
            assertTrue(!out.contains(word), "注释里的「" + word + "」没去掉:\n" + out);
        }
        assertTrue(out.contains("'消息模板'") && out.contains("\"// 不是注释\""), "字符串被当成注释去掉了:\n" + out);
        assertEquals(src.split("\n", -1).length, out.split("\n", -1).length, "行号变了");
    }

    private static Path pages() {
        return Path.of("").toAbsolutePath().resolve("src/main/resources/config-ui-pages");
    }

    /**
     * 脚本去掉注释，其余原样；反引号串里的 CSS 注释一并去掉。注释换成等长空白，行号不动
     */
    static String withoutComments(String src) {
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
                    if (c == '`' && src.startsWith("/*", j)) {
                        int end = src.indexOf("*/", j + 2);
                        end = end < 0 ? n : end + 2;
                        blank(out, j, end);
                        j = end;
                        continue;
                    }
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
