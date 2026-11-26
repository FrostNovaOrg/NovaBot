package org.frostnova.nova.adapter.outbox;

import org.frostnova.nova.core.config.ui.page.ConsolePageProvider;
import org.frostnova.nova.core.config.ui.page.ConsolePageSlot;
import org.frostnova.nova.core.plugin.NovaComponent;

@NovaComponent
public class OutboxConsolePageProvider implements ConsolePageProvider {
    @Override public String id() { return "outbox"; }
    @Override public String displayName() { return "外发箱"; }
    @Override public String script() { return "outbox.js"; }
    @Override public ConsolePageSlot slot() { return ConsolePageSlot.TOP; }
    @Override public boolean memberAccessible() { return true; }
    @Override public int order() { return 40; }
    @Override public String icon() {
        return "<path d=\"M2 10l2-6h8l2 6v3H2z\"/><path d=\"M2 10h4l1 1h2l1-1h4\"/>";
    }
}
