package org.frostnova.nova.core.template;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 示例插件模板的形状：自报文件、自报类、装载叙述、版本号与 includes 一处都不能缺
 * <p>
 * 模板不参与本工程构建（根 pom 不把它列进模块），它改坏了没有任何编译期信号会红——
 * 这五格是模板唯一的守卫。量的是仓内 templates/ 下的源文件本身而不是构建产物：
 * 构建脚本根本不构建模板，target 里量不到东西。
 * <p>
 * 模板是给第三方照抄的底稿：它自己走错一步，照它写出来的每一个插件都会跟着错。
 */
@DisplayName("示例插件模板形状")
class ExamplePluginTemplateShapeTest {
    private static final String IMPORTS_RELATIVE =
            "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** 五个内置插件模块 + 模板，六份 pom 的插件描述文件 includes 段都得有注释说明为什么只取两个文件 */
    private static final List<String> POMS_WITH_DESCRIPTOR_INCLUDES = List.of(
            "plugins/nova-onebot-adapter/pom.xml",
            "plugins/nova-onebot-adapter-napcat-extension/pom.xml",
            "plugins/nova-bilibili/pom.xml",
            "plugins/nova-console/pom.xml",
            "plugins/nova-report/pom.xml",
            "templates/nova-example-plugin/pom.xml");

    private static final Pattern NOVA_CORE_VERSION = Pattern.compile(
            "nova-core</artifactId>\\s*<version>([^<]+)</version>");

    @Test
    @DisplayName("① 自报文件恰一行, 所指自报类在且挂 @AutoConfiguration 与 @ComponentScan")
    void importsFilePointsAtAutoConfiguration() throws IOException {
        Path imports = templateDir().resolve(IMPORTS_RELATIVE);
        assertTrue(Files.isRegularFile(imports), "模板没有自报文件 " + IMPORTS_RELATIVE);
        List<String> lines = Files.readAllLines(imports, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        assertEquals(1, lines.size(), "自报文件应为恰一行, 实际 " + lines.size() + " 行: " + lines);
        String declared = lines.get(0);
        Path classFile = templateDir().resolve("src/main/java/" + declared.replace('.', '/') + ".java");
        assertTrue(Files.isRegularFile(classFile), "自报指向的类文件不存在: " + declared);
        String source = read(classFile);
        assertTrue(source.contains("@AutoConfiguration"),
                declared + " 没挂 @AutoConfiguration, 写进自报文件也不会被装进容器");
        // 与仓内五份自报类同形：basePackages 圈定自己的包, excludeFilters 排除自身,
        // 不排除自身的话同一个配置类会经 .imports 与组件扫描进容器两次
        String simpleName = declared.substring(declared.lastIndexOf('.') + 1);
        assertTrue(source.contains("basePackages"),
                declared + " 的 @ComponentScan 没写 basePackages, 装进来了也扫不到插件的组件");
        assertTrue(source.contains("excludeFilters"),
                declared + " 的 @ComponentScan 没有 excludeFilters, 配置类会被 .imports 与扫描双注册");
        assertTrue(source.contains("classes = " + simpleName + ".class"),
                declared + " 的 excludeFilters 排除的不是本类");
    }

    @Test
    @DisplayName("② README 与 pom 不再讲旧加载机与自动下载")
    void readmeAndPomDropLegacyLoaderStory() {
        String readme = read(templateDir().resolve("README.md"));
        String pom = read(templateDir().resolve("pom.xml"));
        // README 与 pom 是模板仅有的两份叙述性文件；旧装载机制的说法留在哪一份里，照抄的人就会跟着错
        for (String word : List.of("StarBotPluginLoader", "自动下载")) {
            int inReadme = count(readme, word);
            int inPom = count(pom, word);
            assertEquals(0, inReadme + inPom,
                    "模板里不允许再出现「" + word + "」: README " + inReadme + " 处, pom " + inPom + " 处");
        }
    }

    @Test
    @DisplayName("③ README 示例的 nova-core 版本与模板 pom 一致")
    void readmeCoreVersionMatchesPom() {
        String inReadme = novaCoreVersion(read(templateDir().resolve("README.md")));
        String inPom = novaCoreVersion(read(templateDir().resolve("pom.xml")));
        assertTrue(inReadme != null, "README 里没找到 nova-core 依赖示例的版本号");
        assertTrue(inPom != null, "模板 pom 里没找到 nova-core 依赖的版本号");
        assertEquals(inPom, inReadme,
                "README 示例依赖的 nova-core 版本与 pom 不一致: README " + inReadme + ", pom " + inPom);
    }

    @Test
    @DisplayName("④ copy-dependencies 只取 plugin.json 与 dependency.json 两个描述文件")
    void copyDependenciesTakesOnlyDescriptors() {
        String pom = read(templateDir().resolve("pom.xml"));
        // 模板照搬整个 target 的话，照它写出来的插件会把 test-classes 一起打进 jar
        String execution = copyDependenciesExecution(pom);
        assertTrue(execution.contains("<include>plugin.json</include>"),
                "copy-dependencies 没有只取 plugin.json");
        assertTrue(execution.contains("<include>dependency.json</include>"),
                "copy-dependencies 没有只取 dependency.json");
    }

    @Test
    @DisplayName("⑤ 六份 pom 的 includes 段各有「只取描述文件」注释, 且首行逐字同")
    void sixPomsCommentTheirIncludes() {
        List<String> withoutComment = new ArrayList<>();
        for (String relative : POMS_WITH_DESCRIPTOR_INCLUDES) {
            if (!hasCommentAboveDescriptorIncludes(read(repoRoot().resolve(relative)))) {
                withoutComment.add(relative);
            }
        }
        assertTrue(withoutComment.isEmpty(), "includes 段缺「只取描述文件」注释的 pom: " + withoutComment);
        // 说法还得一致：六份的注释首行逐字同, 分叉了照抄的人不知道以哪份为准
        List<String> firstLines = new ArrayList<>();
        for (String relative : POMS_WITH_DESCRIPTOR_INCLUDES) {
            firstLines.add(descriptorIncludesCommentFirstLine(read(repoRoot().resolve(relative))));
        }
        List<String> distinct = firstLines.stream().distinct().toList();
        assertEquals(1, distinct.size(), "六份 pom includes 段注释首行不一致: " + distinct);
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("build.sh")) && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }

    private static Path templateDir() {
        return repoRoot().resolve("templates/nova-example-plugin");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不到 " + file, e);
        }
    }

    private static int count(String text, String word) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(word, index)) >= 0) {
            count++;
            index += word.length();
        }
        return count;
    }

    private static String novaCoreVersion(String text) {
        Matcher matcher = NOVA_CORE_VERSION.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String copyDependenciesExecution(String pom) {
        int id = pom.indexOf("<id>copy-dependencies</id>");
        assertTrue(id >= 0, "模板 pom 里没有 copy-dependencies 执行");
        int start = pom.lastIndexOf("<execution>", id);
        int end = pom.indexOf("</execution>", id);
        return pom.substring(start, end);
    }

    /** 六份同段的比对锚：注释块里 <!-- 之后第一个非空行, 拿它逐字比六份说法是否一致 */
    private static String descriptorIncludesCommentFirstLine(String pom) {
        int include = pom.indexOf("<include>plugin.json</include>");
        assertTrue(include >= 0, "pom 里没有 plugin.json 的 include");
        int resources = pom.lastIndexOf("<resources>", include);
        int open = pom.indexOf("<!--", resources);
        int close = pom.indexOf("-->", open);
        String comment = pom.substring(open + 4, close);
        for (String line : comment.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }

    /** 注释与 includes 都以「所属的 <resources> 块」为窗：注释写在 <resource> 之上还是之下，两种摆法都认 */
    private static boolean hasCommentAboveDescriptorIncludes(String pom) {
        int include = pom.indexOf("<include>plugin.json</include>");
        if (include < 0) {
            return false;
        }
        int resources = pom.lastIndexOf("<resources>", include);
        if (resources < 0) {
            return false;
        }
        String window = pom.substring(resources, include);
        return window.contains("<!--") && window.contains("-->");
    }
}
