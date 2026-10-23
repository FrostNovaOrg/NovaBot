package org.frostnova.nova.core.config.ui;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Permissions for the method and path pattern selected by Spring MVC. */
public final class ConfigUiPermissions {
    public enum Access { EVERYONE, STREAMER, FILTERED, ADMIN }
    public enum Subject { NONE, PATH, QUERY, LOOKUP, SUBSCRIPTION, PREVIEW }
    public record Rule(Access access, Subject subject) { }
    private static final Rule DEFAULT = new Rule(Access.ADMIN, Subject.NONE);
    private static final Map<String, Rule> ENTRIES;
    static {
        var entries = new LinkedHashMap<String, Rule>();
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/assets/{name}");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/assets/{version}/{name}");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/manual/{name}");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/manual/images/{name}");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/api/auth/state");
        add(entries, Access.EVERYONE, Subject.NONE, "POST /config/api/auth/login");
        add(entries, Access.EVERYONE, Subject.NONE, "POST /config/api/auth/logout");
        add(entries, Access.EVERYONE, Subject.NONE, "POST /config/api/auth/passkey/login/options");
        add(entries, Access.EVERYONE, Subject.NONE, "POST /config/api/auth/passkey/login/verify");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/api/oidc/login");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/api/oidc/callback");
        add(entries, Access.EVERYONE, Subject.NONE, "POST /config/api/oidc/backchannel-logout");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/api/auth/agreement");
        add(entries, Access.FILTERED, Subject.NONE, "GET /config/api/pages");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/api/vocab");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/api/platforms");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/api/session-check");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/api/setup/state");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/api/handlers");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/api/templates");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/api/report/layout-options");
        add(entries, Access.EVERYONE, Subject.NONE, "GET /config/api/report/palettes");
        add(entries, Access.STREAMER, Subject.PATH, "GET /config/api/datasource/users/{platform}/{uid}");
        add(entries, Access.STREAMER, Subject.PATH, "GET /config/api/datasource/users/{platform}/{uid}/version");
        add(entries, Access.STREAMER, Subject.PATH, "POST /config/api/datasource/users/{platform}/{uid}");
        add(entries, Access.STREAMER, Subject.PATH, "GET /config/api/streamers/{platform}/{uid}");
        add(entries, Access.STREAMER, Subject.PATH, "GET /config/api/streamers/{platform}/{uid}/insights");
        add(entries, Access.STREAMER, Subject.PATH, "GET /config/api/streamers/{platform}/{uid}/audience");
        add(entries, Access.STREAMER, Subject.PATH, "GET /config/api/streamers/{platform}/{uid}/calendar");
        add(entries, Access.STREAMER, Subject.PATH, "GET /config/api/streamers/{platform}/{uid}/fans");
        add(entries, Access.STREAMER, Subject.PATH, "GET /config/api/streamers/{platform}/{uid}/sessions");
        add(entries, Access.STREAMER, Subject.PATH, "GET /config/api/streamers/{platform}/{uid}/trend");
        add(entries, Access.STREAMER, Subject.PATH, "GET /config/api/streamers/{platform}/{uid}/start-slots");
        add(entries, Access.STREAMER, Subject.PATH, "GET /config/api/streamers/{platform}/{uid}/weekly-report");
        add(entries, Access.STREAMER, Subject.PATH, "GET /config/api/streamers/{platform}/{uid}/live");
        add(entries, Access.STREAMER, Subject.PATH, "GET /config/api/streamers/{platform}/{uid}/sessions/{start}/report");
        add(entries, Access.STREAMER, Subject.QUERY, "GET /config/api/streamer/name");
        add(entries, Access.STREAMER, Subject.LOOKUP, "POST /config/api/streamer/lookup");
        add(entries, Access.STREAMER, Subject.SUBSCRIPTION, "POST /config/api/state/subscription");
        add(entries, Access.STREAMER, Subject.PATH, "GET /config/api/weekly-report/{platform}/{uid}/status");
        add(entries, Access.STREAMER, Subject.PATH, "GET /config/api/weekly-report/{platform}/{uid}/preview");
        add(entries, Access.STREAMER, Subject.PATH, "POST /config/api/weekly-report/{platform}/{uid}/trial");
        add(entries, Access.STREAMER, Subject.PATH, "GET /config/api/live-report/{platform}/{uid}/latest");
        add(entries, Access.STREAMER, Subject.PATH, "POST /config/api/live-report/{platform}/{uid}/send-latest");
        add(entries, Access.STREAMER, Subject.PREVIEW, "POST /config/api/report/preview");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/napcat-bootstrap");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/napcat-bootstrap/napcat-resume.js");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/auth/agreement/accept");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/auth/agreement/revoke");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/auth/totp/setup");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/auth/totp/enroll");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/auth/totp/disable");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/auth/totp/skip");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/auth/password/set");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/auth/password/change");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/auth/password/reset");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/auth/passkey/register/options");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/auth/passkey/register/verify");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/auth/passkeys");
        add(entries, Access.ADMIN, Subject.NONE, "DELETE /config/api/auth/passkeys/{id}");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/accounts");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/accounts/{id}/enable");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/accounts/{id}/disable");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/accounts/{id}/role");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/accounts/{id}/streamers");
        add(entries, Access.ADMIN, Subject.NONE, "DELETE /config/api/accounts/{id}");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/accounts/local/bind");
        add(entries, Access.ADMIN, Subject.NONE, "DELETE /config/api/accounts/local/binding");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/admin-session-check");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/schema");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/values");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/values");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/event-tokens");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/event-tokens");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/event-tokens/{fingerprint}/revoke");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/setup/bot");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/setup/bot");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/setup/test-bot");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/login");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/login/logout");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/test-message");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/datasource");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/datasource");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/datasource/users");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/datasource/users/{platform}/{uid}/delete");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/version/skip");
        add(entries, Access.FILTERED, Subject.NONE, "GET /config/api/datasource/users");
        add(entries, Access.FILTERED, Subject.NONE, "GET /config/api/status");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/alert/channels");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/alert/test");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/setup/test-sent");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/setup/rerun");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/setup/rerun/consumed");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/engineering-log");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/timeline/days");
        add(entries, Access.FILTERED, Subject.NONE, "GET /config/api/timeline");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/push/toggle");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/templates");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/report/palettes/default");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/state/command");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/state/commands");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/state/revenue");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/state/quiet");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/state/binding");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/analytics");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/analytics/sessions");
        add(entries, Access.FILTERED, Subject.NONE, "GET /config/api/streamers");
        add(entries, Access.FILTERED, Subject.NONE, "GET /config/api/state");
        add(entries, Access.FILTERED, Subject.NONE, "GET /config/api/push-history");
        add(entries, Access.FILTERED, Subject.NONE, "GET /config/api/at-all/quota");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/bot/console");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/napcat/credential");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/bot/targets");
        add(entries, Access.ADMIN, Subject.NONE, "POST /config/api/bot/targets/refresh");
        add(entries, Access.FILTERED, Subject.NONE, "GET /config/api/bot/targets/lookup");
        add(entries, Access.ADMIN, Subject.NONE, "GET /config/api/exec/notice");
        ENTRIES = Collections.unmodifiableMap(entries);
    }

    private ConfigUiPermissions() { }

    private static void add(Map<String, Rule> entries, Access access, Subject subject, String endpoint) {
        if (entries.put(endpoint, new Rule(access, subject)) != null) throw new IllegalStateException(endpoint);
    }

    public static Map<String, Rule> entries() { return ENTRIES; }

    public static Rule rule(HttpServletRequest request) {
        String method = request.getMethod().equals("HEAD") ? "GET" : request.getMethod();
        return ENTRIES.getOrDefault(method + " " + pattern(request), DEFAULT);
    }

    public static String pattern(HttpServletRequest request) {
        Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return matched == null ? "" : matched.toString();
    }
}
