package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置页实际下发的说明里，不许再出现类名、注解名、花括号写法和包名。
 * <p>
 * 说明先走 {@link ConfigurationMetadataService#cleanDescription(String)}，扫的是使用者看见的那一串，
 * 不是源码里的注释原文。产品名单独放行，理由写在 {@link #ALLOWED} 里。
 */
@DisplayName("设置项说明")
class SettingDescriptionPlainTest {
    /**
     * 这些名字中间有大写，扫描会把它们看成驼峰，但它们是产品名或使用者会碰到的叫法，说明里照留。
     * <p>
     * OneBot：推送协议的名字，设置项用它称呼这一路连接。
     * NapCat：使用者安装的 QQ 机器人程序。
     * WebUI：NapCat 自带的网页管理界面，地址和令牌是填给它的。
     * NovaBot：本程序的产品名。
     * WebSocket：连接方式，配事件输出和心跳时会碰到。
     */
    private static final Map<String, String> ALLOWED = Map.of(
            "WebSocket", "连接方式，配事件输出和心跳时会碰到",
            "NovaBot", "本程序的产品名",
            "WebUI", "NapCat 自带的网页管理界面，地址和令牌是填给它的",
            "NapCat", "使用者安装的 QQ 机器人程序",
            "OneBot", "推送协议的名字，设置项用它称呼这一路连接");

    private static final Pattern CAMEL = Pattern.compile(
            "\\b(?:[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+|[a-z][a-z0-9]*(?:[A-Z][a-z0-9]*)+)\\b");

    private static final Pattern ANNOTATION = Pattern.compile("@[A-Z][A-Za-z0-9]+");

    private static final Pattern BRACE = Pattern.compile(
            "\\{@(?:code|link|linkplain|literal|value)\\b[^}]*}");

    private static final Pattern PACKAGE = Pattern.compile(
            "\\b(?:org|com|net|java|javax)\\.(?:[a-zA-Z_]\\w*\\.)+[A-Za-z_]\\w*");

    @Test
    @DisplayName("下发的说明里没有类名、注解名、花括号写法和包名")
    void deliveredDescriptionsStayPlain() throws IOException {
        for (Map.Entry<String, String> allowed : ALLOWED.entrySet()) {
            assertFalse(allowed.getValue().isBlank(), allowed.getKey() + " 放行了却没写为什么");
        }
        assertTrue(jargon("用 OneBot 连 NapCat 的 WebUI，走 WebSocket").isEmpty(),
                "产品名不该被当成开发术语");
        assertFalse(jargon("见 BackupAtAllAspect").isEmpty(), "类名应当被抓住");
        assertFalse(jargon("见 @ConditionalOnProperty").isEmpty(), "注解应当被抓住");
        assertFalse(jargon("见 {@code BackupAtAllAspect}").isEmpty(), "花括号写法应当被抓住");
        assertFalse(jargon("见 org.frostnova.nova.core.FooBar").isEmpty(), "包名应当被抓住");

        ConfigurationMetadataService service = new ConfigurationMetadataService();
        List<String> hits = new ArrayList<>();
        int scanned = 0;
        for (Map.Entry<String, String> entry : delivered(service).entrySet()) {
            String text = entry.getValue();
            if (text == null || text.isBlank()) {
                continue;
            }
            scanned++;
            List<String> found = jargon(text);
            if (!found.isEmpty()) {
                hits.add(entry.getKey() + " " + String.join(" ", found));
            }
        }
        System.out.println("设置项说明扫了 " + scanned + " 条，命中 " + hits.size() + " 条");
        assertTrue(scanned >= 100, "扫到的说明太少，这一格没盖住设置页：" + scanned);
        assertTrue(hits.isEmpty(), "设置项说明里还有开发术语:\n" + String.join("\n", hits));
    }

    /**
     * 按设置页下发前的同一套清理，收集字段说明和组说明
     */
    private static Map<String, String> delivered(ConfigurationMetadataService service) throws IOException {
        Path root = repoRoot();
        List<String> modules = modulesFromPom(root);
        assertFalse(modules.isEmpty(), "聚合构建没有列出模块，说明没扫到");
        Map<String, String> descriptions = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        int files = 0;
        for (String module : modules) {
            Path file = root.resolve(module).resolve("target/classes/META-INF/spring-configuration-metadata.json");
            if (!Files.exists(file)) {
                if (declaresConfiguration(root.resolve(module))) {
                    missing.add(module);
                }
                continue;
            }
            files++;
            JSONObject document = JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            JSONArray properties = document.getJSONArray("properties");
            if (properties == null) {
                continue;
            }
            for (int i = 0; i < properties.size(); i++) {
                JSONObject property = properties.getJSONObject(i);
                String name = property.getString("name");
                if (name == null || !name.startsWith("novabot.")) {
                    continue;
                }
                if (property.containsKey("deprecated") || property.containsKey("deprecation")) {
                    continue;
                }
                descriptions.put(name, service.cleanDescription(property.getString("description")));
            }
        }
        assertTrue(missing.isEmpty(), "这些模块的说明没读到:\n" + String.join("\n", missing));
        assertTrue(files >= 3, "读到的配置元数据太少，说明没扫到：" + files);
        assertTrue(descriptions.containsKey("novabot.core.event-stream.enabled"), "核心说明没扫到");
        assertTrue(descriptions.containsKey("novabot.adapter.onebot.senders"), "推送平台说明没扫到");
        assertTrue(descriptions.containsKey("novabot.adapter.onebot.extension.napcat.enable-backup-at-all"),
                "扩展说明没扫到");
        assertTrue(descriptions.keySet().stream().anyMatch(name -> name.startsWith("novabot.bilibili.")),
                "直播平台说明没扫到");

        for (ConfigurationMetadataService.ConfigurationField field : ExternalConfigurationFields.fields()) {
            descriptions.put(field.name(), field.description());
        }
        for (ConfigurationGroups.Group group : ConfigurationGroups.all()) {
            descriptions.put("组 " + group.id(), group.description());
        }
        return descriptions;
    }

    /**
     * 源码里声明了配置类的模块才该有说明。没有配置类的模块不产说明，不算缺。
     */
    private static boolean declaresConfiguration(Path moduleDir) throws IOException {
        Path main = moduleDir.resolve("src/main/java");
        if (!Files.isDirectory(main)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(main)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .anyMatch(SettingDescriptionPlainTest::mentionsConfiguration);
        }
    }

    private static boolean mentionsConfiguration(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains("@ConfigurationProperties(");
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + path + " 失败", e);
        }
    }

    /**
     * 一条说明里的开发术语。产品名先遮掉，免得它们被数成类名。
     */
    private static List<String> jargon(String text) {
        String masked = text == null ? "" : text;
        List<String> names = new ArrayList<>(ALLOWED.keySet());
        names.sort(Comparator.comparingInt(String::length).reversed());
        for (String name : names) {
            masked = masked.replaceAll("\\b" + Pattern.quote(name) + "\\b", " ");
        }
        List<String> found = new ArrayList<>();
        collect(found, "驼峰", CAMEL, masked);
        collect(found, "注解", ANNOTATION, masked);
        collect(found, "花括号", BRACE, masked);
        collect(found, "包名", PACKAGE, masked);
        return found;
    }

    private static void collect(List<String> found, String kind, Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(kind + ":" + matcher.group());
        }
    }

    /**
     * 模块目录从聚合构建的清单现读，不在这里写死路径。
     */
    private static List<String> modulesFromPom(Path root) throws IOException {
        String pom = Files.readString(root.resolve("pom.xml"), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("<module>([^<]+)</module>").matcher(pom);
        List<String> modules = new ArrayList<>();
        while (matcher.find()) {
            modules.add(matcher.group(1).trim());
        }
        return modules;
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }
}
