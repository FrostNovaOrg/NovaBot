package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationScalarListTest {
    private static final String UIDS = "novabot.assistant.excluded-uids";
    private static final String FONTS = "novabot.core.paint.fonts";

    @TempDir
    Path directory;

    private Path config() {
        return directory.resolve("application.yml");
    }

    private ConfigurationFileService service(String text) throws IOException {
        Files.writeString(config(), text);
        return new ConfigurationFileService(config());
    }

    private Binder binder() throws IOException {
        var sources = new YamlPropertySourceLoader().load("application", new FileSystemResource(config()));
        return new Binder(ConfigurationPropertySources.from(sources));
    }

    /** 抓的使用者故障：界面把机器实际使用的两个 uid 显示成一项。 */
    @ParameterizedTest
    @ValueSource(strings = {"5,6", "' 5 , 6 '", "\"5, 6\""})
    @DisplayName("单值逗号名单回显与启动绑定一致，去掉各项前后空格")
    void scalarUidListDisplaysBoundItems(String scalar) throws IOException {
        var service = service("novabot:\n  assistant:\n    excluded-uids: " + scalar + " # 名单\n");

        assertEquals(List.of(5L, 6L), binder().bind(UIDS, Bindable.listOf(Long.class)).orElseThrow(IllegalStateException::new));
        assertEquals("5\n6", service.read().get(UIDS));
    }

    /** 抓的使用者故障：在旧逗号名单后加一行，保存成功却让下次启动绑定失败。 */
    @Test
    @DisplayName("旧逗号 uid 名单在界面追加一项后，文件三项均可启动绑定")
    void scalarUidListCanGrowWithoutBreakingBinding() throws IOException {
        var service = service("novabot:\n  assistant:\n    excluded-uids: 5,6 # 名单\n");

        assertEquals(List.of(UIDS), service.write(Map.of(UIDS, service.read().get(UIDS) + "\n7")));

        assertEquals(List.of(5L, 6L, 7L), binder().bind(UIDS, Bindable.listOf(Long.class)).orElseThrow(IllegalStateException::new));
        assertTrue(Files.readString(config()).contains("\n      - 5\n      - 6\n      - 7\n"));
        assertTrue(Files.readString(config()).contains("# 名单"));
        assertEquals("5\n6\n7", service.read().get(UIDS));
    }

    /** 抓的使用者故障：旧文字逗号名单保存一次就把原先两个字体合并成一个字体名。 */
    @Test
    @DisplayName("旧单值字体名单追加一项后，保存和绑定都保留三个字体")
    void scalarFontListCanGrowWithoutMergingItems() throws IOException {
        var service = service("novabot:\n  core:\n    paint:\n      fonts: A, B\n");

        service.write(Map.of(FONTS, service.read().get(FONTS) + "\nC"));

        assertEquals(List.of("A", "B", "C"), binder().bind(FONTS, Bindable.listOf(String.class)).orElseThrow(IllegalStateException::new));
        assertTrue(Files.readString(config()).contains("\n        - A\n        - B\n        - C\n"));
        assertEquals("A\nB\nC", service.read().get(FONTS));
    }

    /** 抓的使用者故障：数字名单保存了逗号或非法数字，重启才暴露错误；同批别的改动也不能落盘。 */
    @ParameterizedTest
    @ValueSource(strings = {"5,6", "abc", "9223372036854775808", "1.5"})
    @DisplayName("数字名单非法项当场拒绝，指出配置键和第二项，整批文件保持不动")
    void invalidNumericItemDoesNotChangeFile(String invalid) throws IOException {
        for (String initial : List.of("", "    excluded-uids: 5\n", "    excluded-uids:\n      - 5\n")) {
            var service = service("server:\n  port: 7827\nnovabot:\n  assistant:\n" + initial);
            String before = Files.readString(config());
            Map<String, String> changes = new LinkedHashMap<>();
            changes.put("server.port", "7828");
            changes.put(UIDS, "5\n" + invalid);

            IOException error = assertThrows(IOException.class, () -> service.write(changes));

            assertTrue(error.getMessage().contains(UIDS), error.getMessage());
            assertTrue(error.getMessage().contains("第 2 项"), error.getMessage());
            assertTrue(error.getMessage().contains(invalid), error.getMessage());
            assertEquals(before, Files.readString(config()));
        }
    }

    /** 抓的使用者故障：修复 uid 名单时漏掉其他数字名单，非法管理员账号仍可写入。 */
    @Test
    @DisplayName("管理员名单同样按数字元素类型拒绝无效账号")
    void invalidAdminItemIsRejected() throws IOException {
        String key = "novabot.core.command.admins";
        var service = service("novabot:\n  core:\n    command:\n      admins: []\n");
        String before = Files.readString(config());

        IOException error = assertThrows(IOException.class, () -> service.write(Map.of(key, "abc")));

        assertTrue(error.getMessage().contains(key), error.getMessage());
        assertTrue(error.getMessage().contains("第 1 项"), error.getMessage());
        assertEquals(before, Files.readString(config()));
    }

    /** 抓的使用者故障：管理员号码中间的空白被转换器去掉，保存后悄悄变成另一个号码。 */
    @ParameterizedTest
    @ValueSource(strings = {"6 7", "6\t7", "6\u20037", "6\f7"})
    @DisplayName("管理员数字项内部有空白时拒绝整批保存，文件一字不动")
    void adminItemWithInternalWhitespaceIsRejected(String invalid) throws IOException {
        String key = "novabot.core.command.admins";
        var service = service("server:\n  port: 7827\nnovabot:\n  core:\n    command:\n      admins: []\n");
        byte[] before = Files.readAllBytes(config());
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("server.port", "7828");
        changes.put(key, "5\n" + invalid);

        IOException error = assertThrows(IOException.class, () -> service.write(changes));

        assertTrue(error.getMessage().contains(key), error.getMessage());
        assertTrue(error.getMessage().contains("第 2 项「" + invalid + "」"), error.getMessage());
        assertTrue(error.getMessage().contains("不是有效的数字"), error.getMessage());
        assertTrue(error.getMessage().contains("本批全部未保存"), error.getMessage());
        assertArrayEquals(before, Files.readAllBytes(config()));
    }

    /** 抓的使用者故障：拒绝项内空白时，误把号码前后的空白也拒绝。 */
    @Test
    @DisplayName("数字项前后的空白仍可去掉并正常保存绑定")
    void adminItemsAllowSurroundingWhitespace() throws IOException {
        String key = "novabot.core.command.admins";
        var service = service("novabot:\n  core:\n    command:\n      admins: []\n");

        service.write(Map.of(key, " 5 \n\t6\u2003"));

        assertEquals(List.of(5L, 6L), binder().bind(key, Bindable.listOf(Long.class)).orElseThrow(IllegalStateException::new));
    }

    /** 抓的使用者故障：把已逐项填写的文字名单中的逗号拆开，破坏原本正确的一项。 */
    @ParameterizedTest
    @ValueSource(strings = {"\n        - a,b\n        - C", "[\"a,b\", C]"})
    @DisplayName("已有逐项字体名单以及界面新填的一行都保留逗号")
    void sequenceAndSubmittedItemsKeepCommas(String sequence) throws IOException {
        var service = service("novabot:\n  core:\n    paint:\n      fonts: " + sequence + "\n");
        assertEquals("a,b\nC", service.read().get(FONTS));

        service.write(Map.of(FONTS, "a,b\nD,E"));

        assertEquals(List.of("a,b", "D,E"), binder().bind(FONTS, Bindable.listOf(String.class)).orElseThrow(IllegalStateException::new));
        assertEquals("a,b\nD,E", service.read().get(FONTS));
    }
}
