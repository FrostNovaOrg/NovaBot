package org.frostnova.nova.core.config.ui;

import jakarta.servlet.http.HttpServletRequest;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/** Identity established by the console filter for this request. */
public final class ConfigUiAccess {
    public static final String ACCOUNT = "nova.console.account";
    public static final String SESSION = "nova.console.session";
    public static final String FRESH_LOCAL = "nova.console.fresh-local";
    public static final String LOCAL_REQUIRED = "这一步要先用本地方式重新登录";

    private ConfigUiAccess() { }

    public static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }

    public static String actor() {
        HttpServletRequest request = request();
        return request != null && request.getAttribute(ACCOUNT) instanceof ConfigUiAccount account
                ? account.actor() : "本地管理员";
    }

    public static boolean member(HttpServletRequest request) {
        return request != null && request.getAttribute(ACCOUNT) instanceof ConfigUiAccount account
                && account.role() == ConfigUiAccount.Role.MEMBER;
    }

    public static ConfigUiAccount memberAccount() {
        var request = request();
        return member(request) ? (ConfigUiAccount) request.getAttribute(ACCOUNT) : null;
    }

    public static boolean visible(String platform, Long uid) {
        var member = memberAccount();
        return member == null || org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId.owns(member, platform, uid);
    }

    public static boolean visible(org.frostnova.nova.core.timeline.TimelineEvent event) {
        var member = memberAccount();
        if (member == null) return true;
        if (event.streamer() == null) return false;
        return org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId.owns(member,
                event.detail().get("streamerPlatform"),
                org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId.positiveDecimal(event.detail().get("streamerUid")));
    }

    public static ConfigUiAccount admin(HttpServletRequest request) {
        if (!(request.getAttribute(ACCOUNT) instanceof ConfigUiAccount account)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "尚未登录");
        }
        if (account.role() != ConfigUiAccount.Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ConfigUiApiInterceptor.MEMBER_DENIED);
        }
        return account;
    }

    public static void requireFreshLocal(HttpServletRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.getAttribute(FRESH_LOCAL))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, LOCAL_REQUIRED);
        }
    }

    public static ConfigUiSession session(HttpServletRequest request) {
        return (ConfigUiSession) request.getAttribute(SESSION);
    }
}
