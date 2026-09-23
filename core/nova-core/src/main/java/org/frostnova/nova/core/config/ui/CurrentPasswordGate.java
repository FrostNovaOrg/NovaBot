package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Optional;

/**
 * 敏感操作前再核一次现在的密码，判定到回包共用这一份
 * <p>
 * 改密码、登记通行密钥、绑定验证器、关闭二次验证四处同一套：没填、填错、连错满额、认不出会话，
 * 各是哪一句、回哪一号，都写在这里。抄四份的话，措辞或剩余次数的算法会在某一路上悄悄分叉。
 * <p>
 * 连错次数记在同一把会话上（见 {@link ConfigUiAuthService#checkCurrentPassword}）：
 * 分开记的话，偷到 Cookie 的人能在三个口子上各猜一轮，等于把可猜次数悄悄乘了三。
 */
final class CurrentPasswordGate {
    private CurrentPasswordGate() {
    }

    /**
     * 核一遍请求体里的 {@code current}
     * @param authService 认证服务
     * @param body 请求体，取 {@code current}；null 与没带同一处理
     * @param request 用来认会话与来源地址
     * @param again 认不出会话时那句的后半截，按场合点名（再改密码／再登记／再绑定验证器／再关二次验证）
     * @return 拒的时候是回包；过了这道闸时是空
     */
    static Optional<ResponseEntity<JSONObject>> require(ConfigUiAuthService authService, JSONObject body,
                                                        HttpServletRequest request, String again) {
        char[] current = Optional.ofNullable(body == null ? null : body.getString("current"))
                .orElse("")
                .toCharArray();
        ConfigUiAuthService.CurrentPasswordCheck check;
        try {
            check = authService.checkCurrentPassword(current, sessionId(request), request.getRemoteAddr());
        } finally {
            Arrays.fill(current, '\0');
        }
        return toResponse(check, again);
    }

    private static Optional<ResponseEntity<JSONObject>> toResponse(ConfigUiAuthService.CurrentPasswordCheck check,
                                                                   String again) {
        if (check.verdict() == ConfigUiAuthService.CurrentPasswordVerdict.MATCH) {
            return Optional.empty();
        }

        JSONObject result = new JSONObject();
        result.put("success", false);

        if (check.verdict() == ConfigUiAuthService.CurrentPasswordVerdict.MISSING) {
            result.put("message", "请填现在的密码");
            return Optional.of(ResponseEntity.badRequest().body(result));
        }

        if (check.verdict() == ConfigUiAuthService.CurrentPasswordVerdict.MISMATCH) {
            result.put("message", "现在的密码不对，再输错 " + check.remaining() + " 次会退出这次登录");
            return Optional.of(ResponseEntity.badRequest().body(result));
        }

        result.put("message", check.verdict() == ConfigUiAuthService.CurrentPasswordVerdict.SIGNED_OUT
                ? "现在的密码连续输错 " + ConfigUiAuthService.CURRENT_PASSWORD_MISSES_BEFORE_SIGN_OUT
                        + " 次，这次登录已退出，请重新登录"
                : "认不出这次登录，请重新登录后" + again);
        return Optional.of(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result));
    }

    private static String sessionId(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> ConfigUiSecurityFilter.SESSION_COOKIE.equals(c.getName()))
                .findFirst()
                .map(Cookie::getValue)
                .orElse(null);
    }
}
