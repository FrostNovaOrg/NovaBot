package com.starlwr.bot.adapter.onebot.napcat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭据不许走查询参数
 * <p>
 * NapCat 的鉴权中间件除了 {@code Authorization} 头，也收 {@code ?webui_token=}。
 * 用它代登录只要一次跳转就完事，<b>诱惑很大，所以要有东西盯着</b>：
 * 凭据一旦进地址栏就会进反向代理的访问日志，与刚关掉的「地址栏启动令牌」是同一条理由
 * （硬性判据）。
 * <p>
 * 这类改动不会让任何功能变坏——用查询参数照样能进去，而且更省事——
 * 因此它不可能靠人复查拦住，只能靠每次构建都查一遍。
 */
@DisplayName("NapCat 凭据不走查询参数")
class NapCatQueryTokenBanTest {
    private static final String BANNED = "webui_token";

    /**
     * 阳性对照。**「一个都没搜到」和「搜索根本没工作」长得一模一样**，
     * 所以这条判据自己先得能在一段已知的文本上命中。
     */
    private static final String CONTROL = "location.replace('/config/napcat/webui/?webui_token=' + credential)";

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }

    private List<Path> sources() {
        Path root = repositoryRoot();
        List<Path> out = new ArrayList<>();
        for (String dir : List.of("starbot-core/src/main/java", "starbot-core/src/main/resources/config-ui")) {
            Path base = root.resolve(dir);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(base)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return name.endsWith(".java") || name.endsWith(".js") || name.endsWith(".html");
                        })
                        .forEach(out::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    private List<String> hits(String name, String text) {
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // 注释里提这个名字是允许的，而且是必要的——说明「为什么不用它」的那段
            // 就写着 ?webui_token=。第一版判据把自己的这段注释抓了出来：
            // **判据该看的是代码，不是说明代码的那些话**
            String stripped = line.strip();
            if (stripped.startsWith("*") || stripped.startsWith("//")
                    || stripped.startsWith("/*") || stripped.startsWith("<!--")) {
                continue;
            }
            if (line.contains("?" + BANNED) || line.contains("&" + BANNED) || line.contains(BANNED + "=")) {
                out.add(name + ":" + (i + 1) + "  " + stripped);
            }
        }
        return out;
    }

    @Test
    @DisplayName("判据自己先能在已知文本上命中")
    void theRulerWorks() {
        assertEquals(1, hits("<对照>", CONTROL).size(),
                "阳性对照没命中，说明这条判据在下面那个测试里也是恒真绿");
    }

    @Test
    @DisplayName("代码与页面里没有把凭据拼进地址栏的写法")
    void credentialNeverGoesIntoTheUrl() {
        Path root = repositoryRoot();
        List<String> bad = new ArrayList<>();

        for (Path file : sources()) {
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            bad.addAll(hits(root.relativize(file).toString(), text));
        }

        assertTrue(bad.isEmpty(), "凭据不许进地址栏（会落进反代访问日志），以下位置违反:\n  "
                + String.join("\n  ", bad));
    }
}
