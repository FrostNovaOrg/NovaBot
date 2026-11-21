package org.frostnova.nova.core.config.ui;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.regex.Pattern;

/** 固定动作名与路由对应；从不把原始 URL 或查询串当作说明写盘。 */
final class ConfigUiOperationCatalog {
    record Action(String name, String description, Map<String, String> variables) { }
    private record Entry(String method, String route, Pattern pattern, List<String> variables, String name, String description) { }
    private static final List<Entry> ENTRIES = new ArrayList<>();
    static {
        add("POST", "values", "SETTINGS_SAVE", "保存控制台设置");
        add("POST", "setup/bot", "BOT_SAVE", "保存机器人连接");
        add("POST", "setup/test-bot", "BOT_TEST", "测试机器人连接");
        add("POST", "login/logout", "PLATFORM_LOGOUT", "退出平台登录");
        add("POST", "test-message", "MESSAGE_TEST", "发送测试消息");
        add("POST", "datasource", "DATASOURCE_SAVE", "保存整份推送配置");
        add("POST", "datasource/users", "STREAMER_ADD", "添加主播");
        add("POST", "datasource/users/{platform}/{uid}", "STREAMER_SAVE", "保存主播与推送通道");
        add("POST", "datasource/users/{platform}/{uid}/delete", "STREAMER_DELETE", "删除主播");
        add("POST", "push/toggle", "PUSH_TOGGLE", "暂停或恢复全局推送");
        add("POST", "templates", "TEMPLATE_SAVE", "保存默认模板");
        add("POST", "state/command", "COMMAND_SAVE", "调整通道命令");
        add("POST", "state/commands", "COMMANDS_SAVE", "批量调整通道命令");
        add("POST", "state/revenue", "REVENUE_SAVE", "调整通道金额可见性");
        add("POST", "state/quiet", "QUIET_SAVE", "调整通道静音时段");
        add("POST", "state/subscription", "SUBSCRIPTION_REMOVE", "移除或清空订阅");
        add("POST", "state/binding", "BINDING_SAVE", "调整通道主播绑定");
        add("POST", "streamers/{platform}/{uid}/assistant/ask", "ASSISTANT_ASK", "使用主播智能助手");
        add("POST", "weekly-report/{platform}/{uid}/trial", "WEEKLY_REPORT_SEND", "发送运营周报");
        add("POST", "weekly-report/{platform}/{uid}/preview", "WEEKLY_REPORT_PREVIEW", "生成运营周报预览");
        add("POST", "live-report/{platform}/{uid}/send-latest", "LIVE_REPORT_SEND", "发送下播报告");
        add("POST", "napcat/credential", "NAPCAT_CREDENTIAL", "换取机器人管理凭据");
        add("POST", "report/palettes/default", "PALETTE_SAVE", "保存报告默认配色");
        add("POST", "report/preview", "REPORT_PREVIEW", "生成报告预览");
        add("POST", "alert/test", "ALERT_TEST", "试发告警");
        add("POST", "accounts/{id}/enable", "ACCOUNT_ENABLE", "启用成员");
        add("POST", "accounts/{id}/disable", "ACCOUNT_DISABLE", "停用成员");
        add("POST", "accounts/{id}/role", "ACCOUNT_ROLE", "修改成员角色");
        add("POST", "accounts/{id}/streamers", "ACCOUNT_STREAMERS", "分配成员主播");
        add("DELETE", "accounts/{id}", "ACCOUNT_DELETE", "删除成员");
        add("POST", "accounts/local/bind", "ACCOUNT_BIND", "开始绑定本地管理员");
        add("DELETE", "accounts/local/binding", "ACCOUNT_UNBIND", "解除本地管理员绑定");
        add("POST", "auth/login", "LOGIN", "登录控制台");
        add("POST", "auth/logout", "LOGOUT", "退出控制台");
        add("GET", "oidc/login", "OIDC_LOGIN_START", "开始统一登录");
        add("GET", "oidc/callback", "OIDC_LOGIN", "完成统一登录或账号登记");
        add("POST", "oidc/backchannel-logout", "OIDC_LOGOUT", "接收统一登录注销");
        add("POST", "auth/passkey/login/options", "PASSKEY_LOGIN_START", "开始通行密钥登录");
        add("POST", "auth/passkey/login/verify", "PASSKEY_LOGIN", "完成通行密钥登录");
        add("POST", "auth/passkey/register/options", "PASSKEY_REGISTER_START", "开始登记通行密钥");
        add("POST", "auth/passkey/register/verify", "PASSKEY_REGISTER", "登记通行密钥");
        add("DELETE", "auth/passkeys/{id}", "PASSKEY_DELETE", "删除通行密钥");
        add("GET", "auth/totp/setup", "TOTP_SETUP", "开始绑定验证器");
        add("POST", "auth/totp/enroll", "TOTP_ENROLL", "绑定验证器");
        add("POST", "auth/totp/disable", "TOTP_DISABLE", "关闭二次验证");
        add("POST", "auth/totp/skip", "TOTP_SKIP", "跳过绑定验证器");
        add("POST", "auth/password/set", "PASSWORD_SET", "设置登录口令");
        add("POST", "auth/password/change", "PASSWORD_CHANGE", "修改登录口令");
        add("POST", "auth/password/reset", "PASSWORD_RESET", "重设登录口令");
        add("POST", "auth/agreement/accept", "AGREEMENT_ACCEPT", "同意使用协议");
        add("POST", "auth/agreement/revoke", "AGREEMENT_REVOKE", "撤回使用协议");
        add("POST", "event-tokens", "EVENT_TOKEN_ISSUE", "签发只读口令");
        add("POST", "event-tokens/{fingerprint}/revoke", "EVENT_TOKEN_REVOKE", "吊销只读口令");
        add("POST", "version/skip", "VERSION_SKIP", "跳过版本提醒");
        add("POST", "setup/test-sent", "SETUP_TEST_SENT", "记录初始测试发送");
        add("POST", "setup/rerun", "SETUP_RERUN", "重开设置引导");
        add("POST", "setup/rerun/consumed", "SETUP_RERUN_CONSUME", "确认重开设置引导");
        add("POST", "bot/targets/refresh", "TARGETS_REFRESH", "刷新机器人会话");
        add("POST", "streamer/lookup", "STREAMER_LOOKUP", "查询主播");
        add("POST", "assistant/key", "ASSISTANT_KEY_SAVE", "保存智能助手密钥");
        add("DELETE", "assistant/key", "ASSISTANT_KEY_DELETE", "清除智能助手密钥");
        add("POST", "assistant/test", "ASSISTANT_TEST", "测试智能助手连接");
        add("POST", "assistant/acknowledge", "ASSISTANT_ACKNOWLEDGE", "确认智能助手提示");
    }
    private ConfigUiOperationCatalog() { }

    private static void add(String method, String route, String name, String description) {
        List<String> variables = new ArrayList<>();
        StringBuilder regex = new StringBuilder("^/config/api");
        for (String part : route.split("/")) {
            regex.append('/');
            if (part.startsWith("{")) {
                variables.add(part.substring(1, part.length() - 1));
                regex.append("([^/]+)");
            } else regex.append(Pattern.quote(part));
        }
        ENTRIES.add(new Entry(method, "/config/api/" + route, Pattern.compile(regex + "/?$"), variables, name, description));
    }

    static Action action(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        for (Entry entry : ENTRIES) {
            if (!entry.method.equals(request.getMethod())) continue;
            if (entry.route.equals(ConfigUiPermissions.pattern(request))) {
                Map<String, String> variables = new LinkedHashMap<>();
                Object bound = request.getAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                if (bound instanceof Map<?, ?> values) for (String name : entry.variables)
                    if (values.get(name) instanceof String value) variables.put(name, value);
                return new Action(entry.name, entry.description, variables);
            }
            var match = entry.pattern.matcher(path);
            if (!match.matches()) continue;
            Map<String, String> variables = new LinkedHashMap<>();
            for (int i = 0; i < entry.variables.size(); i++) variables.put(entry.variables.get(i), match.group(i + 1));
            return new Action(entry.name, entry.description, variables);
        }
        return new Action("CONSOLE_OPERATION", "执行控制台操作", Map.of());
    }

    static boolean operation(HttpServletRequest request) {
        return !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
                || !action(request).name.equals("CONSOLE_OPERATION");
    }
}
