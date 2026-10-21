package org.frostnova.nova.core.config.ui.auth;

import java.util.Objects;

/** Parsed streamer assignments; invalid historical entries never grant access. */
public record ConfigUiStreamerId(String platform, long uid) {
    public static ConfigUiStreamerId parse(String value) {
        if (value == null || value.length() > 256) return null;
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':')) return null;
        String platform = value.substring(0, separator);
        Long uid = positiveDecimal(value.substring(separator + 1));
        if (platform.isBlank() || !platform.equals(platform.strip()) || uid == null) return null;
        return new ConfigUiStreamerId(platform, uid);
    }

    /** Same integer forms as the subscription handler; no fractional or boolean coercion. */
    public static Long positiveDecimal(Object raw) {
        long value;
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            value = ((Number) raw).longValue();
        } else if (raw instanceof String text && text.matches("[0-9]{1,19}")) {
            try { value = Long.parseLong(text); }
            catch (NumberFormatException error) { return null; }
        } else return null;
        return value > 0 ? value : null;
    }

    public static boolean owns(ConfigUiAccount account, String platform, Long uid) {
        return uid != null && uid > 0 && account.streamers().stream().map(ConfigUiStreamerId::parse)
                .filter(Objects::nonNull).anyMatch(id -> id.uid == uid && Objects.equals(id.platform, platform));
    }

    public static boolean ownsUid(ConfigUiAccount account, Long uid) {
        return uid != null && uid > 0 && account.streamers().stream().map(ConfigUiStreamerId::parse)
                .filter(Objects::nonNull).anyMatch(id -> id.uid == uid);
    }
}
