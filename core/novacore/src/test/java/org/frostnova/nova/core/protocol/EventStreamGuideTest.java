package org.frostnova.nova.core.protocol;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class EventStreamGuideTest {
    private Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (!Files.exists(path.resolve("docs/protocol.md"))) {
            path = path.getParent();
            assertNotNull(path);
        }
        return path;
    }

    @Test
    void originNotation() throws Exception {
        for (String file : new String[]{"docs/protocol.md", "docs/runbook-local-source.md",
                "manual/appendix-b-advanced.md", "manual/13-upgrade-and-backup.md"}) {
            String text = Files.readString(root().resolve(file));
            assertTrue(text.contains("默认端口"), file);
            assertTrue(text.contains("Punycode"), file);
            assertTrue(text.contains("IPv6"), file);
            assertTrue(text.contains("方括号"), file);
            assertTrue(text.contains("末尾斜线"), file);
            assertTrue(text.contains("事件输出暂停"), file);
        }
    }

    @Test
    void authenticationWording() throws Exception {
        String text = Files.readString(root().resolve("docs/protocol.md"));
        String row = text.lines().filter(line -> line.startsWith("| 认证窗口 |")).findFirst().orElseThrow();
        assertFalse(row.contains("要求口令时"));
        String heading = text.lines().filter(line -> line.startsWith("### 4.2 ")).findFirst().orElseThrow();
        assertFalse(heading.contains("要求口令时"));
    }

    @Test
    void templateColumns() throws Exception {
        var lines = Files.readAllLines(root().resolve("dist/templates/application.example.yml"));
        String origins = lines.stream().filter(line -> line.stripLeading().startsWith("allowed-origins:")).findFirst().orElseThrow();
        String required = lines.stream().filter(line -> line.stripLeading().startsWith("require-token:")).findFirst().orElseThrow();
        assertEquals(required.indexOf('#'), origins.indexOf('#'));
    }
}
