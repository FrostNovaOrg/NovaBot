package org.frostnova.nova.adapter.onebot.config;

import org.frostnova.nova.core.config.ui.ConfigurationMetadataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 适配器配置面定盘星
 * <p>
 * 告警目标三项从核心迁到本适配器之后，键名｜类型｜默认｜说明必须有自己的钉，
 * 不能只靠核心那三份样本少三行来证明迁走了。
 */
@DisplayName("适配器配置面定盘星")
class ConfigurationSurfaceBaselineTest {
    private static final String PREFIX = "novabot.adapter.onebot.";

    private static final String BASELINE = "configuration-baseline/";

    private static final String KEYS_FILE = "config-keys.txt";

    @Test
    @DisplayName("适配器键全集与样本逐行同，行数等于前缀键数，且不混入核心键")
    void adapterKeysMatchBaseline() throws IOException {
        List<String> red = new ArrayList<>();
        List<String> actual = describeAdapterFields();
        writeActual(KEYS_FILE, actual);

        try {
            assertEquals(readBaseline(KEYS_FILE), actual,
                    "适配器配置键全集与样本不符。确认是有意改动后，用 target/configuration-baseline/"
                            + KEYS_FILE + " 更新样本");
        } catch (AssertionError e) {
            red.add("① " + e.getMessage());
        }

        try {
            long prefixCount = new ConfigurationMetadataService().getFields().stream()
                    .filter(field -> field.name() != null && field.name().startsWith(PREFIX))
                    .count();
            assertEquals(prefixCount, actual.size(),
                    "样本行数须等于前缀 " + PREFIX + " 的键数");
        } catch (AssertionError e) {
            red.add("② " + e.getMessage());
        }

        try {
            List<String> mixed = actual.stream()
                    .filter(line -> line.startsWith("novabot.core."))
                    .toList();
            assertTrue(mixed.isEmpty(), "适配器定盘星混入了核心键: " + mixed);
        } catch (AssertionError e) {
            red.add("③ " + e.getMessage());
        }

        if (!red.isEmpty()) {
            fail("适配器定盘星三问中 " + red.size() + " 问未销: " + String.join("; ", red));
        }
    }

    private static List<String> describeAdapterFields() {
        List<String> lines = new ArrayList<>();

        for (ConfigurationMetadataService.ConfigurationField field : new ConfigurationMetadataService().getFields()) {
            if (field.name() == null || !field.name().startsWith(PREFIX)) {
                continue;
            }
            lines.add(String.join("|",
                    field.name(),
                    String.valueOf(field.type()),
                    literal(field.defaultValue()),
                    literal(field.description())));
        }

        Collections.sort(lines);
        return lines;
    }

    private static String literal(Object value) {
        if (value == null) {
            return "<null>";
        }
        return String.valueOf(value).replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n");
    }

    private static List<String> readBaseline(String name) throws IOException {
        Path file = baselineFile(name);
        if (!Files.exists(file)) {
            throw new IOException("找不到适配器定盘星样本 " + file.toAbsolutePath()
                    + " —— 本模块的 resources 插件不把 src/test/resources 拷进 classpath，故直接读源码树");
        }
        List<String> lines = new ArrayList<>();
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static Path baselineFile(String name) {
        Path local = Path.of("src/test/resources", BASELINE, name);
        if (Files.exists(local)) {
            return local;
        }
        return Path.of("plugins/nova-onebot-adapter/src/test/resources", BASELINE, name);
    }

    private static void writeActual(String name, List<String> lines) {
        try {
            Path directory = Files.createDirectories(Path.of("target", "configuration-baseline"));
            Files.writeString(directory.resolve(name), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
