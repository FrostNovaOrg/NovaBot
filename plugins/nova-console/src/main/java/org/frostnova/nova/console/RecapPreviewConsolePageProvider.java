package org.frostnova.nova.console;

import org.frostnova.nova.core.config.ui.page.ConsolePageProvider;
import org.frostnova.nova.core.config.ui.page.ConsolePageSlot;
import org.frostnova.nova.core.plugin.NovaComponent;

@NovaComponent
public class RecapPreviewConsolePageProvider implements ConsolePageProvider {
    @Override public String id() { return "recap-preview"; }
    @Override public String displayName() { return "小结样稿（试验）"; }
    @Override public String script() { return "recap-preview.js"; }
    @Override public int order() { return 35; }
    @Override public ConsolePageSlot slot() { return ConsolePageSlot.TOP; }
    @Override public String icon() { return "<rect x=\"3\" y=\"1\" width=\"10\" height=\"14\" rx=\"2\"/><path d=\"M6 5h4M6 8h4M6 11h3\"/>"; }
}
