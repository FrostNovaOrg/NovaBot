package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.api.NovaApiFilter;
import org.frostnova.nova.core.api.NovaApiMemberSignIn;
import org.frostnova.nova.core.api.NovaApiRegistry;
import org.frostnova.nova.core.api.NovaApiScope;
import org.frostnova.nova.core.api.NovaApiToken;
import org.frostnova.nova.core.api.NovaApiTokenStore;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 控制台里的「访问令牌」：签发、查看、吊销
 * <p>
 * 管理员与成员都能进。每个人只给<b>自己的账号</b>签发；成员只看得到、只吊销得了自己的，
 * 管理员看得到、吊销得了全部账号的。别人的令牌对成员一律回「没有找到」，不透露它在不在。
 * <p>
 * 明文只在签发那一次的回包里出现；列表、吊销、日志与操作记录只用编号，摘要从不出现。
 * <p>
 * {@link NovaApiScope#adminOnly() 仅管理员}的范围只有管理员签得出来；成员的列表里照样列出它，标明成员勾不了。
 * 列表里每一把的状态与统一接口认不认它是同一个判法：成员此刻登不进控制台时，成员名下的令牌显示「暂不可用」。
 */
@Slf4j
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/api-tokens")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiApiTokenController {
    /** 有效期可选的天数；null 是不过期 */
    static final Set<Integer> VALIDITY_DAYS = Set.of(30, 90, 365);

    static final String NOT_FOUND = "没有找到这把令牌";

    /** 成员此刻登不进控制台时，成员名下的令牌在列表里写的原因 */
    static final String SIGN_IN_CLOSED = "单点登录此刻没有开启，成员登录不了控制台，这把令牌也暂停使用；"
            + "重新开启后自动恢复，不用重签";

    /** 所属账号已停用或已删除、令牌却还没吊销时写的原因 */
    static final String OWNER_GONE = "所属账号已停用或已删除，这把令牌不被认";

    private final NovaApiTokenStore tokens;

    private final NovaApiRegistry registry;

    private final ConfigUiAccountStore accounts;

    private final NovaApiMemberSignIn memberSignIn;

    public ConfigUiApiTokenController(NovaApiTokenStore tokens, NovaApiRegistry registry, ConfigUiAccountStore accounts,
                                      NovaApiMemberSignIn memberSignIn) {
        this.tokens = tokens;
        this.registry = registry;
        this.accounts = accounts;
        this.memberSignIn = memberSignIn;
    }

    @GetMapping
    public ResponseEntity<String> list(HttpServletRequest request) {
        ConfigUiAccount me = account(request);
        boolean admin = me.role() == ConfigUiAccount.Role.ADMIN;
        Instant now = Instant.now();
        boolean signInOpen = memberSignIn.open();
        Map<String, ConfigUiAccount> owners = new java.util.HashMap<>();
        accounts.list().forEach(account -> owners.put(account.id(), account));

        JSONArray items = new JSONArray();
        tokens.list().stream()
                .filter(token -> admin || token.accountId().equals(me.id()))
                .sorted(Comparator.comparing(NovaApiToken::issuedAt).reversed())
                .forEach(token -> items.add(item(token, owners.get(token.accountId()), me, now, signInOpen)));

        JSONArray scopes = new JSONArray();
        for (NovaApiScope scope : registry.scopes()) {
            JSONObject item = JSONObject.of("id", scope.id(), "name", scope.name(), "description", scope.description());
            item.put("adminOnly", scope.adminOnly());
            scopes.add(item);
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("admin", admin);
        result.put("accountId", me.id());
        result.put("limit", NovaApiTokenStore.MAX_ACTIVE_PER_ACCOUNT);
        result.put("active", tokens.list().stream()
                .filter(token -> token.accountId().equals(me.id()) && token.active(now)).count());
        result.put("prefix", NovaApiFilter.V1 + "/");
        result.put("scopes", scopes);
        result.put("tokens", items);
        return json(HttpStatus.OK, result);
    }

    @PostMapping
    public ResponseEntity<String> issue(@RequestBody(required = false) JSONObject body, HttpServletRequest request) {
        ConfigUiAccount me = account(request);
        JSONObject payload = body == null ? new JSONObject() : body;

        Object rawScopes = payload.get("scopes");
        if (rawScopes != null && !(rawScopes instanceof List<?>)) {
            return refuse(HttpStatus.BAD_REQUEST, "范围应为一个列表");
        }
        Set<String> scopes = new LinkedHashSet<>();
        if (rawScopes instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof String id) || registry.scope(id).isEmpty()) {
                    return refuse(HttpStatus.BAD_REQUEST, "勾选的范围里有一项不存在，请刷新页面后重选");
                }
                scopes.add(id);
            }
        }
        if (me.role() != ConfigUiAccount.Role.ADMIN) {
            for (String id : scopes) {
                NovaApiScope scope = registry.scope(id).orElseThrow();
                if (scope.adminOnly()) {
                    return refuse(HttpStatus.FORBIDDEN, "「" + scope.name() + "」（" + scope.id()
                            + "）只有管理员能勾选：它管的是整个实例，不是哪一位主播。请去掉这一项再签发");
                }
            }
        }

        Object rawDays = payload.get("expiresInDays");
        Duration validity;
        if (rawDays == null) {
            validity = null;
        } else if (rawDays instanceof Integer days && VALIDITY_DAYS.contains(days)) {
            validity = Duration.ofDays(days);
        } else {
            return refuse(HttpStatus.BAD_REQUEST, "有效期只能选不过期、30 天、90 天或 365 天");
        }

        Object rawName = payload.get("name");
        if (rawName != null && !(rawName instanceof String)) {
            return refuse(HttpStatus.BAD_REQUEST, "令牌名字应为文字");
        }

        NovaApiTokenStore.Issued issued;
        try {
            issued = tokens.issue(me.id(), (String) rawName, List.copyOf(scopes), validity);
        } catch (NovaApiTokenStore.LimitReached full) {
            return refuse(HttpStatus.CONFLICT, full.getMessage());
        } catch (IllegalArgumentException invalid) {
            return refuse(HttpStatus.BAD_REQUEST, invalid.getMessage());
        } catch (IllegalStateException unsaved) {
            return refuse(HttpStatus.SERVICE_UNAVAILABLE, unsaved.getMessage());
        }

        NovaApiToken token = issued.token();
        ConfigUiOperationAdvice.apiToken(token.id(), token.accountId());
        log.info("{} 签发了一把访问令牌: 编号 {}, 范围 {}, {}", me.actor(), token.id(), token.scopes(),
                token.expiresAt() == null ? "不过期" : "到期 " + token.expiresAt());

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("token", issued.plaintext());
        result.put("item", item(token, me, me, Instant.now(), memberSignIn.open()));
        result.put("message", "这是这把令牌唯一一次显示，请立即复制保存；离开本页后无法再次查看");
        return json(HttpStatus.OK, result);
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<String> revoke(@PathVariable String id, HttpServletRequest request) {
        ConfigUiAccount me = account(request);
        boolean admin = me.role() == ConfigUiAccount.Role.ADMIN;
        Optional<NovaApiToken> found = tokens.find(id)
                .filter(token -> admin || token.accountId().equals(me.id()));
        if (found.isEmpty()) {
            return refuse(HttpStatus.NOT_FOUND, NOT_FOUND);
        }
        ConfigUiOperationAdvice.apiToken(found.get().id(), found.get().accountId());
        Optional<NovaApiTokenStore.Revoked> revoked = tokens.revoke(id);
        if (revoked.isEmpty()) {
            return refuse(HttpStatus.NOT_FOUND, NOT_FOUND);
        }
        NovaApiTokenStore.Revoked outcome = revoked.get();
        if (outcome.changed()) {
            log.warn("{} 吊销了一把访问令牌: 编号 {}, 所属账号 {}", me.actor(), id, ConfigUiAudit.safe(outcome.token().accountId()));
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("item", item(outcome.token(), accounts.find(outcome.token().accountId()).orElse(null), me, Instant.now(),
                memberSignIn.open()));
        result.put("message", !outcome.changed() ? "这把令牌早已吊销"
                : outcome.saved() ? "已吊销，从下一个请求起不再认它"
                : "已吊销，从下一个请求起不再认它；但这一次没能写进磁盘，稍后自动保存时会再写");
        return json(HttpStatus.OK, result);
    }

    private static ConfigUiAccount account(HttpServletRequest request) {
        if (request.getAttribute(ConfigUiAccess.ACCOUNT) instanceof ConfigUiAccount account) {
            return account;
        }
        throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "尚未登录");
    }

    /**
     * 列表里的一行
     * <p>
     * 🔴 只有编号，没有摘要：摘要从不出 {@link NovaApiTokenStore}。
     * <p>
     * 状态四种：{@code revoked}、{@code expired}、{@code unavailable}（没吊销也没过期，但此刻不被认，
     * {@code unavailableReason} 写明为什么）、{@code active}。判法与统一接口认不认它是同一套。
     */
    private static JSONObject item(NovaApiToken token, ConfigUiAccount owner, ConfigUiAccount viewer, Instant now,
                                   boolean signInOpen) {
        JSONObject item = new JSONObject();
        item.put("id", token.id());
        item.put("name", token.name());
        item.put("scopes", new JSONArray(new ArrayList<>(token.scopes())));
        item.put("issuedAt", token.issuedAt().toEpochMilli());
        item.put("expiresAt", token.expiresAt() == null ? null : token.expiresAt().toEpochMilli());
        item.put("lastUsedAt", token.lastUsedAt() == null ? null : token.lastUsedAt().toEpochMilli());
        item.put("revokedAt", token.revokedAt() == null ? null : token.revokedAt().toEpochMilli());
        String unavailable = owner == null || owner.status() != ConfigUiAccount.Status.ENABLED ? OWNER_GONE
                : owner.role() != ConfigUiAccount.Role.ADMIN && !signInOpen ? SIGN_IN_CLOSED : null;
        item.put("state", token.revoked() ? "revoked" : token.expired(now) ? "expired"
                : unavailable != null ? "unavailable" : "active");
        item.put("unavailableReason", token.active(now) ? unavailable : null);
        item.put("mine", token.accountId().equals(viewer.id()));
        JSONObject who = new JSONObject();
        who.put("id", token.accountId());
        who.put("displayName", owner == null ? "已删除的账号"
                : ConfigUiAccount.LOCAL_ID.equals(owner.id()) ? "本地管理员" : owner.displayName());
        who.put("role", owner == null ? null : owner.role().name());
        who.put("status", owner == null ? "DELETED" : owner.status().name());
        item.put("owner", who);
        return item;
    }

    private static ResponseEntity<String> refuse(HttpStatus status, String message) {
        JSONObject result = new JSONObject();
        result.put("success", false);
        result.put("message", message);
        HttpServletRequest request = ConfigUiAccess.request();
        if (request != null) {
            request.setAttribute(ConfigUiOperationFilter.REASON, message);
        }
        return json(status, result);
    }

    /** 自己序列化：到期、最后使用这些为 null 的字段要照样出现在回包里 */
    private static ResponseEntity<String> json(HttpStatus status, JSONObject body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(body.toJSONString(JSONWriter.Feature.WriteNulls));
    }
}
