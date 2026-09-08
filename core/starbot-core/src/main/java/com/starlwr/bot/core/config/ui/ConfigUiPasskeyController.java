package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.config.ui.auth.passkey.PasskeyRelyingParty;
import com.starlwr.bot.core.config.ui.auth.passkey.PasskeyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 通行密钥接口
 * <p>
 * 四条路分成两类，<b>身份要求不同</b>，因此没有并进 {@link ConfigUiAuthController}：
 * <ul>
 *   <li>{@code passkey/register/*} 与 {@code passkeys}——<b>要先进得来</b>。
 *       登记一把钥匙等于给这台机器多配一把能开门的钥匙，签这个字的人必须先被认出来。
 *       这一条由安全过滤器保证：它们不在放行名单里</li>
 *   <li>{@code passkey/login/*}——<b>不要求已登录</b>，它本身就是进门的那一步。
 *       这两条写在安全过滤器的放行名单里，与口令登录那一条并列</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/auth")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiPasskeyController {
    private final PasskeyService passkeyService;

    private final NovaCoreProperties.ConfigUi.Auth properties;

    public ConfigUiPasskeyController(PasskeyService passkeyService, NovaCoreProperties properties) {
        this.passkeyService = passkeyService;
        this.properties = properties.getConfigUi().getAuth();
    }

    /**
     * 取登记一把新钥匙所需的参数
     * @return 交给浏览器的那份参数
     */
    @PostMapping("/passkey/register/options")
    public JSONObject registerOptions(HttpServletRequest request) {
        return passkeyService.registerOptions(PasskeyRelyingParty.of(request));
    }

    /**
     * 提交认证器建好的钥匙
     * @param body 客户端回传的响应，含 name 与 response
     * @return 登记结果
     */
    @PostMapping("/passkey/register/verify")
    public JSONObject registerVerify(@RequestBody JSONObject body, HttpServletRequest request) {
        return passkeyService.registerVerify(body, PasskeyRelyingParty.of(request));
    }

    /**
     * 取用通行密钥登录所需的参数
     * @return 交给浏览器的那份参数
     */
    @PostMapping("/passkey/login/options")
    public JSONObject loginOptions(HttpServletRequest request) {
        return passkeyService.loginOptions(PasskeyRelyingParty.of(request));
    }

    /**
     * 用通行密钥登录
     * <p>
     * 成功时下发的东西与口令登录<b>一模一样</b>：一枚会话 Cookie 加一个 CSRF 令牌。
     * 两条路走到这一步之后就没有区别了——区别只在于它<b>不经二次验证</b>。
     * @param body 客户端回传的响应
     * @return 登录结果
     */
    @PostMapping("/passkey/login/verify")
    public ResponseEntity<JSONObject> loginVerify(@RequestBody JSONObject body, HttpServletRequest request) {
        PasskeyService.PasskeyLogin outcome =
                passkeyService.loginVerify(body, PasskeyRelyingParty.of(request), request.getRemoteAddr());

        JSONObject result = new JSONObject();
        if (!outcome.success()) {
            result.put("success", false);
            result.put("message", outcome.message());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
        }

        result.put("success", true);
        result.put("csrfToken", outcome.session().getCsrfToken());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(outcome.session().getId(), request).toString())
                .body(result);
    }

    /**
     * 已登记的通行密钥
     * @return 名字、登记时间与上次使用时间
     */
    @GetMapping("/passkeys")
    public JSONObject list() {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("passkeys", passkeyService.list());
        result.put("nameLimit", PasskeyService.MAX_NAME_LENGTH);
        return result;
    }

    /**
     * 删掉一把
     * <p>
     * 删不存在的那一把回 404 而不是「成功」：使用者点的是列表里看得见的一条，
     * 删完却什么都没变的话，他会以为自己撤销了一台已经丢掉的设备。
     * @param id 凭据 ID
     * @return 删除结果
     */
    @DeleteMapping("/passkeys/{id}")
    public ResponseEntity<JSONObject> delete(@PathVariable String id) {
        JSONObject result = new JSONObject();

        if (!passkeyService.delete(id)) {
            result.put("success", false);
            result.put("message", "这把通行密钥已经不在了，可能刚在别处删过");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        }

        result.put("success", true);
        result.put("message", "已删除。这台设备之后只能用口令登录");
        return ResponseEntity.ok(result);
    }

    /**
     * 构造会话 Cookie
     * <p>
     * 与口令登录那一份保持一致：{@code HttpOnly} 挡住页面脚本读取，
     * {@code SameSite=Strict} 让跨站请求根本带不上它，{@code Secure} 跟随当前连接是否为 https。
     */
    private ResponseCookie sessionCookie(String value, HttpServletRequest request) {
        return ResponseCookie.from(ConfigUiSecurityFilter.SESSION_COOKIE, value)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path(ConfigUiController.BASE_PATH)
                .maxAge(Duration.ofHours(Math.max(1, properties.getSessionHours())))
                .build();
    }
}
