package org.frostnova.nova.report.painter;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportPaletteStructureTest {
    // Platform medal colors are supplied as data; no literal exceptions are needed.
    private static final Pattern COLOR_VALUE = Pattern.compile(
            "new\\s+(?:java\\.awt\\.)?Color\\s*\\(|(?:java\\.awt\\.)?Color\\s*\\.\\s*(?:[A-Z_]{3,}|black|white|gray|lightGray|darkGray|red|green|blue|pink|orange|yellow|cyan|magenta|decode|getColor|getHSBColor|HSBtoRGB)\\b"
            + "|import\\s+static\\s+java\\.awt\\.Color\\.|(?:setRGB|setColor|setPaint)\\s*\\(\\s*(?:0x|[0-9])");

    @Test
    void painterColorsComeFromPalette() throws Exception {
        Path directory = Path.of("src/main/java/org/frostnova/nova/report/painter");
        var violations = new ArrayList<String>();
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.getFileName().toString().equals("ReportPalette.java")).sorted().toList()) {
                String source = Files.readString(file);
                var colors = COLOR_VALUE.matcher(source);
                while (colors.find()) {
                    long line = source.substring(0, colors.start()).chars().filter(c -> c == '\n').count() + 1;
                    violations.add(file.getFileName() + ":" + line);
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "Colors outside ReportPalette: " + violations.size() + " " + violations);
    }
}
