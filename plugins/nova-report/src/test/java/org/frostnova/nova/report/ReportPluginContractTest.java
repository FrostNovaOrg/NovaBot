package org.frostnova.nova.report;

import org.frostnova.nova.core.service.LiveReportRedrawer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 报告插件对哔哩哔哩插件的声明，以及重画扩展点能被加载
 */
@DisplayName("报告插件契约")
class ReportPluginContractTest {
    @Test
    @DisplayName("plugin.json／dependency.json 声明依赖 nova-bilibili、LiveReportRedrawer 实现可加载")
    void pluginDeclaresBilibiliAndRedrawerIsLoadable() {
        List<String> red = new ArrayList<>();

        try {
            String text = readResource("plugin.json");
            assertTrue(text.contains("nova-report"), "plugin.json 未声明本模块 artifactId");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            String text = readResource("dependency.json");
            assertTrue(text.contains("nova-bilibili"), "dependency.json 未声明依赖 nova-bilibili");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        try {
            Class<?> clazz = Class.forName("org.frostnova.nova.report.service.BilibiliLiveReportRedrawer");
            assertTrue(LiveReportRedrawer.class.isAssignableFrom(clazz),
                    "BilibiliLiveReportRedrawer 未实现 LiveReportRedrawer");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    private static String readResource(String name) throws Exception {
        InputStream in = ReportPluginContractTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(in, name + " 不在 classpath");
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}
