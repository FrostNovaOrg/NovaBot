package org.frostnova.nova.core.config.ui.auth;

import org.frostnova.nova.core.config.ui.ConfigUiAudit;

import java.time.Instant;
import java.util.List;

/** Persistent console identity; credentials and provider tokens never belong here. */
public record ConfigUiAccount(String id, Role role, Status status, String displayName, String email,
                              String issuer, String subject, List<String> streamers, Instant createdAt,
                              Instant lastLoginAt, boolean everEnabled) {
    public static final String LOCAL_ID = "local-admin";
    public enum Role { ADMIN, MEMBER }
    public enum Status { PENDING, ENABLED, DISABLED }

    public ConfigUiAccount {
        streamers = List.copyOf(streamers);
        if (LOCAL_ID.equals(id)) {
            role = Role.ADMIN;
            status = Status.ENABLED;
            displayName = "本地管理员";
            everEnabled = true;
        }
    }

    public static ConfigUiAccount local(Instant now) {
        return new ConfigUiAccount(LOCAL_ID, Role.ADMIN, Status.ENABLED, "本地管理员", null,
                null, null, List.of(), now, null, true);
    }

    public String actor() {
        return LOCAL_ID.equals(id) ? "本地管理员" : ConfigUiAudit.safe(displayName) + " (" + id + ")";
    }

}
