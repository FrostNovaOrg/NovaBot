package org.frostnova.nova.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日志级别配置不许落回「留空即 DEBUG」
 * <p>
 * 实测过的失效形态：模板把 {@code console:} / {@code file:} 发成<b>留空</b>，
 * 而留空传给 logback 的是空串、它解析不了空串时退回 DEBUG——
 * 开箱即用的部署把 DEBUG 全写进日志文件（25 秒 140 行 DEBUG 对 87 行 INFO），
 * 且这些行里带着请求地址与他人昵称。
 * <p>
 * 🔴 <b>这个失效不报错也不告警，只是安静地多写</b>，所以只能靠构建期检查守住。
 * 两条都要：模板不许发空值（管新部署），{@code springProperty} 要有 defaultValue（管整行被删）。
 */
@DisplayName("日志级别配置")
class LogLevelConfigTest {
    private Path root() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }

    private String read(String relative) throws IOException {
        return Files.readString(root().resolve(relative), StandardCharsets.UTF_8);
    }

    /**
     * 匹配 `      console: 值` 这样的一行，捕获冒号后、注释前的部分
     */
    private static String valueOf(String yaml, String key) {
        Matcher m = Pattern.compile("^\\s+" + key + ":([^#\\n]*)", Pattern.MULTILINE).matcher(yaml);
        return m.find() ? m.group(1).strip() : null;
    }

    @Test
    @DisplayName("判据自己先能认出留空的那一行")
    void theRulerRecognisesABlankValue() {
        String blank = "    log:\n      console:                      # 控制台日志级别\n      file: INFO\n";
        assertEquals("", valueOf(blank, "console"), "认不出留空，下面那条判据就是恒真绿");
        assertEquals("INFO", valueOf(blank, "file"));
    }

    @Test
    @DisplayName("模板不许把日志级别发成空值")
    void templateShipsExplicitLevels() throws IOException {
        String yaml = read("dist/templates/application.example.yml");
        for (String key : new String[]{"console", "file"}) {
            String value = valueOf(yaml, key);
            assertTrue(value != null && !value.isEmpty(),
                    "模板里 log." + key + " 是空的。留空不会用默认值，会被 logback 解析成 DEBUG——"
                            + "开箱即用的部署会把 DEBUG 连同请求地址与他人昵称一起写进日志文件");
        }
    }

    @Test
    @DisplayName("springProperty 必须带 defaultValue，管住配置项被整行删掉的情形")
    void springPropertyHasDefaultValue() throws IOException {
        String xml = read("core/starbot-core/src/main/resources/logback.xml");
        Matcher m = Pattern.compile("<springProperty[^>]*source=\"novabot\\.core\\.log\\.(console|file)\"[^>]*>")
                .matcher(xml);

        int found = 0;
        while (m.find()) {
            found++;
            assertTrue(m.group().contains("defaultValue="),
                    "缺 defaultValue，配置项被删时会退回 DEBUG: " + m.group());
        }
        assertEquals(2, found, "应当有 console 与 file 两条 springProperty");
    }
}
