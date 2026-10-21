package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.enums.PushTargetType;

/** Exact bot membership lookup supplied by a push adapter. */
public interface PushTargetLookup {
    boolean supports(String platform);
    Result lookup(String platform, PushTargetType type, long num, ConfigUiAccount account);
    record Result(boolean found, String name, String message) { }
}
