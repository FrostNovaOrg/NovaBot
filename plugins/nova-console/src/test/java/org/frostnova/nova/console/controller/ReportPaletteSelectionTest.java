package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.Test;

class ReportPaletteSelectionTest {
    @Test
    void paletteSelectionSavesAndPreviewsDrafts() throws Exception {
        FrontendFixture.runFromModule("src/test/resources/frontend/report-palette-selection-fixture.mjs",
                "报告图配色选择");
    }
}
