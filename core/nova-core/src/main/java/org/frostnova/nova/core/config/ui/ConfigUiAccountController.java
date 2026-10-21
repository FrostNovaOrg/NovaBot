package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Administrator account operations; streamer assignments are stored for later authorization. */
@Slf4j
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/accounts")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiAccountController {
    private final ConfigUiAccountStore accounts;
    private final ConfigUiAuthService auth;
    private final OidcClient oidc;

    public ConfigUiAccountController(ConfigUiAccountStore accounts, ConfigUiAuthService auth, OidcClient oidc) {
        this.accounts = accounts;
        this.auth = auth;
        this.oidc = oidc;
    }

    @GetMapping
    public JSONObject list(HttpServletRequest request) {
        ConfigUiAccess.admin(request);
        JSONObject result = success();
        result.put("accounts", accounts.list().stream().map(ConfigUiAccountStore::json).toList());
        result.put("managementUri", oidc.managementUri().map(Object::toString).orElse(""));
        return result;
    }

    @PostMapping("/{id}/enable")
    public JSONObject enable(@PathVariable String id, HttpServletRequest request) {
        var actor = ConfigUiAccess.admin(request);
        synchronized (accounts) {
            var target = accounts.status(id, ConfigUiAccount.Status.ENABLED);
            log.info("配置界面账号启用: {} → {}", actor.actor(), target.actor());
            return account(target);
        }
    }

    @PostMapping("/{id}/disable")
    public JSONObject disable(@PathVariable String id, HttpServletRequest request) {
        var actor = ConfigUiAccess.admin(request);
        synchronized (accounts) {
            var target = accounts.status(id, ConfigUiAccount.Status.DISABLED);
            auth.logoutAccount(id);
            log.info("配置界面账号停用: {} → {}", actor.actor(), target.actor());
            return account(target);
        }
    }

    @PostMapping("/{id}/role")
    public JSONObject role(@PathVariable String id, @RequestBody RoleChange change, HttpServletRequest request) {
        var actor = ConfigUiAccess.admin(request);
        ConfigUiAccess.requireFreshLocal(request);
        if (change.role() == null) throw new IllegalArgumentException("角色不能为空");
        synchronized (accounts) {
            var target = accounts.role(id, change.role());
            log.info("配置界面账号设角色: {} → {}, {}", actor.actor(), target.actor(), target.role());
            return account(target);
        }
    }

    @PostMapping("/{id}/streamers")
    public JSONObject streamers(@PathVariable String id, @RequestBody StreamerChange change, HttpServletRequest request) {
        var actor = ConfigUiAccess.admin(request);
        if (change.streamers() == null) throw new IllegalArgumentException("指定主播不能为空");
        synchronized (accounts) {
            var target = accounts.streamers(id, change.streamers());
            log.info("配置界面账号设指定主播: {} → {}", actor.actor(), target.actor());
            return account(target);
        }
    }

    @DeleteMapping("/{id}")
    public JSONObject delete(@PathVariable String id, HttpServletRequest request) {
        var actor = ConfigUiAccess.admin(request);
        synchronized (accounts) {
            var target = accounts.require(id);
            accounts.delete(id);
            auth.logoutAccount(id);
            log.info("配置界面账号删除: {} → {}", actor.actor(), target.actor());
            return success();
        }
    }

    @DeleteMapping("/local/binding")
    public JSONObject unbind(HttpServletRequest request) {
        var actor = ConfigUiAccess.admin(request);
        ConfigUiAccess.requireFreshLocal(request);
        if (!ConfigUiAccount.LOCAL_ID.equals(actor.id())) throw new IllegalArgumentException("只有本地管理员可以解绑身份");
        synchronized (accounts) {
            accounts.unbind();
            auth.logoutOidcAccount(ConfigUiAccount.LOCAL_ID);
            log.info("配置界面账号解绑: {} → 本地管理员", actor.actor());
            return success();
        }
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, ResponseStatusException.class})
    public ResponseEntity<JSONObject> failure(RuntimeException error) {
        JSONObject result = new JSONObject();
        result.put("success", false);
        if (error instanceof ResponseStatusException status) {
            result.put("message", status.getReason());
            return ResponseEntity.status(status.getStatusCode()).body(result);
        }
        result.put("message", error.getMessage());
        return ResponseEntity.status(error instanceof IllegalStateException ? 503 : 400).body(result);
    }

    private static JSONObject success() { return new JSONObject(java.util.Map.of("success", true)); }
    private static JSONObject account(ConfigUiAccount account) {
        JSONObject result = success();
        result.put("account", ConfigUiAccountStore.json(account));
        return result;
    }

    public record RoleChange(ConfigUiAccount.Role role) { }
    public record StreamerChange(List<String> streamers) { }
}
