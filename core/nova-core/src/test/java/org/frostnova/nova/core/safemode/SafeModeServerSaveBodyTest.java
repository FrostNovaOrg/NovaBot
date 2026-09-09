package org.frostnova.nova.core.safemode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全模式保存请求的正文本体：走保存路径就要真的备份、真的写盘
 * <p>
 * 此前判据只到 {@code applySave}，而 HTTP 那层调它的那一行没人看着：
 * 把它改回只校验不落盘的旧形状，页面照样说「已保存」，全仓仍是绿的。
 * 这里从 {@code handleSaveBody} 这一户进门，把「说了保存就得保存」钉在
 * 备份份数与本体内容这两个盘上事实上。
 */
@DisplayName("安全模式保存正文本体")
class SafeModeServerSaveBodyTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("合法正文经保存路径：本体换成新内容，备份目录多一份")
    void saveBodyWritesConfigAndLeavesBackup() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, "seed: 1\n", StandardCharsets.UTF_8);
        SafeModeServer server = new SafeModeServer(config, "测试用的启动失败原因");

        assertEquals(0, backupCount(config), "保存前不应已有备份");
        String page = server.handleSaveBody("ok: true\n");

        assertTrue(page.contains("已保存"), "保存成功应给出已保存的提示");
        assertEquals("ok: true\n", Files.readString(config, StandardCharsets.UTF_8),
                "说已保存就得真的把新内容写进本体");
        assertEquals(1, backupCount(config), "经保存路径写一次应多出一份备份");
    }

    @Test
    @DisplayName("非法正文被拒：页面带问题、盘上分毫未动")
    void saveBodyRejectsInvalidYamlWithoutTouchingDisk() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, "seed: 1\n", StandardCharsets.UTF_8);
        SafeModeServer server = new SafeModeServer(config, "测试用的启动失败原因");

        String page = server.handleSaveBody("a: [unclosed");

        assertFalse(page.contains("已保存"), "没保存成不许说已保存");
        assertTrue(page.contains("解析失败"), "被拒时应把问题摆到页面上");
        assertEquals("seed: 1\n", Files.readString(config, StandardCharsets.UTF_8),
                "被拒的正文不许写进本体");
        assertEquals(0, backupCount(config), "被拒时不应产生备份");
    }

    /**
     * 只数备份这件事发生了没有，份数裁剪与命名形状是备份组件那组判据的事，不在此重复
     */
    private static int backupCount(Path config) throws IOException {
        String prefix = config.getFileName() + ".";
        try (Stream<Path> files = Files.list(config.getParent())) {
            List<String> names = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix) && name.endsWith(".bak"))
                    .toList();
            return names.size();
        }
    }
}
