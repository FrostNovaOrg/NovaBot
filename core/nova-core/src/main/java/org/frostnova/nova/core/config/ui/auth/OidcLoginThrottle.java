package org.frostnova.nova.core.config.ui.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Browser login attempts have their own budgets, independent of local credentials. */
public final class OidcLoginThrottle {
    private static final int PER_IP_ATTEMPTS = 8;
    private static final int MAX_IPS = 1024;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private final Map<String, Window> byIp = new HashMap<>();
    private final LoginThrottle global;

    public OidcLoginThrottle() {
        this(new LoginThrottle(1, WINDOW));
    }

    OidcLoginThrottle(LoginThrottle global) {
        this.global = global;
    }

    public synchronized boolean tryAcquire(String ip, Instant now) {
        byIp.values().removeIf(window -> !now.isBefore(window.started().plus(WINDOW)));
        Window window = byIp.get(ip);
        if ((window == null && byIp.size() >= MAX_IPS)
                || (window != null && window.attempts() >= PER_IP_ATTEMPTS)) return false;
        byIp.put(ip, new Window(window == null ? now : window.started(), window == null ? 1 : window.attempts() + 1));
        if (!global.tryAcquireGlobal(now)) return false;
        if (!global.tryAcquireSlot()) {
            global.refundGlobal();
            return false;
        }
        return true;
    }

    public void releaseSlot() {
        global.releaseSlot();
    }

    private record Window(Instant started, int attempts) { }
}
