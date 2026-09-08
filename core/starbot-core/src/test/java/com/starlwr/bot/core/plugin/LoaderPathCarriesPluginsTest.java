package com.starlwr.bot.core.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 全仓每一处写出 {@code -Dloader.path=} 取值的地方，都得含 {@code plugins} 这一段
 *
 * <h2>这一格补的是什么洞</h2>
 * 插件 jar 只有在应用类路径上，Spring Boot 才收得到它们的自报文件。类路径由启动参数
 * {@code -Dloader.path} 给出，而写出这个参数的地方不止一处：两个启动脚本、起动冒烟尺、
 * 若干篇文档里的示例命令。漏掉任何一处，<b>那种部署方式下插件会安安静静地整个不见</b>——
 * 不报错、不退非零，只是所有插件功能一起没有了。
 *
 * <h2>为什么按「段」判而不是按「含 plugins 这几个字母」判</h2>
 * {@code plugins-lib} 里就含着 {@code plugins}。按子串判的话，一处都没改的旧写法
 * {@code lib,plugins-lib} 照样绿——那是一把量什么都说「对」的尺。所以取值按逗号切开，
 * 判的是切出来的段里有没有恰好等于 {@code plugins} 的那一段。
 *
 * <h2>分母自证</h2>
 * 命中数为 0 时判红：写点被整体改名或本尺的正则写歪了，表现同样是「一处不合格的都没有」，
 * 与真的全都合格在读数上分不开。
 */
@DisplayName("启动参数 loader.path")
class LoaderPathCarriesPluginsTest {
    /** 插件目录那一段的名字，与 {@code build.sh} 摆放插件 jar 的目录同名 */
    private static final String PLUGINS_SEGMENT = "plugins";

    /**
     * 取值的字符集刻意不含 {@code }} 与反引号：这个参数在 javadoc 与 Markdown 里也出现，
     * 用 {@code \S+} 的话会把 {@code {@code ...}} 的右花括号一起吃进取值里
     */
    private static final Pattern WRITE_POINT =
            Pattern.compile("-Dloader\\.path=([A-Za-z0-9,._/\\-]+)");

    /** 构建产物与本地草稿：里面的启动脚本是上一次构建留下的旧字节，不是本仓的写点 */
    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", "target", "node_modules", "scratch", ".idea");

    /** 相对仓根的整条路径，只跳这一处（别处叫 build 的目录不跟着被跳过） */
    private static final Path SKIPPED_PATH = Path.of("dist", "build");

    @Test
    @DisplayName("每一处 -Dloader.path= 的取值都含 plugins 这一段, 且命中数非零")
    void everyWritePointCarriesThePluginsSegment() {
        List<String> red = new ArrayList<>();

        List<String> hits = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        try {
            scan(hits, missing);
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            assertTrue(hits.size() >= 4,
                    "全仓只找到 " + hits.size() + " 处 -Dloader.path= 写点, 分母不对, 这一格此刻几乎什么都没量");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        try {
            assertEquals(List.of(), missing,
                    "这些写点的取值里没有单独的 " + PLUGINS_SEGMENT + " 那一段, 那种起法下插件会整个不见"
                            + "（共查 " + hits.size() + " 处）");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    private static void scan(List<String> hits, List<String> missing) throws IOException {
        Path root = repoRoot();
        Files.walkFileTree(root, new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                        || root.relativize(dir).equals(SKIPPED_PATH)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String content;
                try {
                    content = Files.readString(file, StandardCharsets.UTF_8);
                } catch (MalformedInputException | UncheckedIOException e) {
                    return FileVisitResult.CONTINUE;
                } catch (IOException e) {
                    return FileVisitResult.CONTINUE;
                }

                Matcher matcher = WRITE_POINT.matcher(content);
                while (matcher.find()) {
                    String value = matcher.group(1);
                    String where = root.relativize(file) + " → " + value;
                    hits.add(where);
                    if (!Arrays.asList(value.split(",")).contains(PLUGINS_SEGMENT)) {
                        missing.add(where);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 定位仓库根目录。测试既可能由 Maven 在模块目录下执行，也可能由 IDE 在仓库根目录下执行
     */
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
