package com.starlwr.bot.core.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 架构文档里两张扩展点表与源码、以及两张表彼此之间的对齐
 * <p>
 * 第 1 节的 SPI 表与第 7 节的扩展点清单表讲的是同一批接口，却是两份手写的名单：
 * 加一个 SPI 时只改其中一张，另一张不会有任何信号。照第 7 节写新插件的人于是
 * 看不到那个扩展点，而两张表各自读起来都天衣无缝——这正是 `snapshotMetrics()`
 * 加进接口之后第 1 节仍写着旧话、第 7 节里三个扩展点整行缺席的由来。
 * <p>
 * 三格分别钉住：表里的路径确实是仓里的类（表 ⊆ 源码）、第 1 节的每个 SPI 在第 7 节里
 * 都有一行（§1 ⊆ §7）、以及本笔补上的那三行在场。
 * <p>
 * 路径按文档自己的话「相对 {@code com.starlwr.bot.core} 包根」解析，
 * 因此两个源码根都要找：<b>这个包根横跨 novacore 与 starbot-core 两个模块</b>
 * （{@code model/Sender} 在前者，其余在后者），只翻一个模块会把在册的行判成缺件。
 */
@DisplayName("架构文档的扩展点表")
class ArchitectureDocTest {

    /** 第 7 节表里「核心接口」那一栏的路径形（带斜杠的反引号词），把 `@ConfigurationProperties` 这类非路径词排除在外 */
    private static final Pattern INTERFACE_PATH = Pattern.compile("`([A-Za-z][A-Za-z0-9]*(?:/[A-Za-z][A-Za-z0-9]*)+)`");

    /** 第 1 节 SPI 表的一行：`接口名` | `core.包.名` | … */
    private static final Pattern SPI_ROW = Pattern.compile(
            "^\\|\\s*`([A-Za-z][A-Za-z0-9]*)`\\s*\\|\\s*`(core(?:\\.[a-z][a-z0-9]*)+)`\\s*\\|");

    /** {@code com.starlwr.bot.core} 包根落在这两个模块下 */
    private static final List<String> CORE_SOURCE_ROOTS = List.of(
            "novacore/src/main/java/com/starlwr/bot/core",
            "starbot-core/src/main/java/com/starlwr/bot/core");

    /** 这三个扩展点曾经只写在第 1 节里，第 7 节整行缺席，正是两张表分叉的实例 */
    private static final List<String> MUST_BE_LISTED = List.of(
            "analytics/LiveMetricCatalog",
            "alert/AlertChannel",
            "config/ui/vocab/ConsoleVocabulary");

    @Test
    @DisplayName("① 扩展点清单里每个核心接口路径都在源码里")
    void everyListedInterfacePathExistsInSource() {
        Set<String> listed = listedInterfacePaths();
        assertTrue(listed.size() >= 8, "扩展点清单没解析出几行, 多半是表的形状变了: " + listed);

        List<String> missing = new ArrayList<>();
        for (String path : listed) {
            if (resolve(path) == null) {
                missing.add(path);
            }
        }
        assertTrue(missing.isEmpty(), "扩展点清单里这些路径在 com.starlwr.bot.core 包根下找不到对应的类: " + missing);
    }

    @Test
    @DisplayName("② 第 1 节 SPI 表里的每个接口, 第 7 节扩展点清单里都有一行")
    void everySpiFromSectionOneIsListedInSectionSeven() {
        Set<String> spis = spiTablePaths();
        assertTrue(spis.size() >= 8, "第 1 节 SPI 表没解析出几行, 多半是表的形状变了: " + spis);

        Set<String> listed = listedInterfacePaths();
        List<String> absent = spis.stream().filter(path -> !listed.contains(path)).toList();
        assertEquals(List.of(), absent,
                "第 1 节声明了这些 SPI, 第 7 节扩展点清单里却没有对应行——照第 7 节写插件的人看不到它们");
    }

    @Test
    @DisplayName("③ 直播指标目录、告警通道、控制台词表三行在扩展点清单里")
    void theThreeLateAddedExtensionPointsAreListed() {
        Set<String> listed = listedInterfacePaths();
        List<String> absent = MUST_BE_LISTED.stream().filter(path -> !listed.contains(path)).toList();
        assertEquals(List.of(), absent, "扩展点清单里缺这几行: " + absent);
    }

    /** 第 7 节表的「核心接口」栏里出现过的全部路径；一栏里写了两个（消息出口那行）就都算 */
    private static Set<String> listedInterfacePaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (String row : extensionPointRows()) {
            Matcher matcher = INTERFACE_PATH.matcher(interfaceColumn(row));
            while (matcher.find()) {
                paths.add(matcher.group(1));
            }
        }
        return paths;
    }

    /** 第 1 节 SPI 表的每行折成第 7 节那种相对路径：`AlertChannel` + `core.alert` → alert/AlertChannel */
    private static Set<String> spiTablePaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (String line : lines()) {
            Matcher matcher = SPI_ROW.matcher(line);
            if (matcher.find()) {
                String pkg = matcher.group(2).substring("core".length()).replace('.', '/');
                paths.add(pkg.isEmpty() ? matcher.group(1) : pkg.substring(1) + "/" + matcher.group(1));
            }
        }
        return paths;
    }

    /** 第 7 节「扩展点清单」小节里的表体行（跳表头与分隔行） */
    private static List<String> extensionPointRows() {
        List<String> lines = lines();
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("### 扩展点清单")) {
                start = i;
                break;
            }
        }
        assertTrue(start >= 0, "architecture.md 里没有「扩展点清单」小节");

        List<String> rows = new ArrayList<>();
        boolean inTable = false;
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("#")) {
                break;
            }
            if (!line.startsWith("|")) {
                // 表结束后小节里还有正文，别把后面别的表也吃进来
                if (inTable) {
                    break;
                }
                continue;
            }
            inTable = true;
            if (line.startsWith("|---") || line.startsWith("| 扩展点")) {
                continue;
            }
            rows.add(line);
        }
        assertTrue(!rows.isEmpty(), "「扩展点清单」小节下没解析到表体行");
        return rows;
    }

    /** 表行的第二栏＝「核心接口（相对路径）」 */
    private static String interfaceColumn(String row) {
        String[] cells = row.split("\\|");
        // split 后第 0 段是行首竖线前的空串，第 1 段才是第一栏
        assertTrue(cells.length > 2, "这一行栏数不够, 取不到「核心接口」栏: " + row);
        return cells[2];
    }

    /** 相对 com.starlwr.bot.core 的路径落到哪个源码根下；两个根都没有时回 null */
    private static Path resolve(String relative) {
        for (String root : CORE_SOURCE_ROOTS) {
            Path candidate = repoRoot().resolve(root).resolve(relative + ".java");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<String> lines() {
        Path doc = repoRoot().resolve("docs/architecture.md");
        try {
            return Files.readAllLines(doc, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不到 " + doc, e);
        }
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
}
