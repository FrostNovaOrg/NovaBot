package org.frostnova.nova.core.config.ui.auth;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcClient;
import org.frostnova.nova.core.service.NovaStateStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.frostnova.nova.core.config.ui.auth.ConfigUiAccount.*;

/** Account updates, identity uniqueness and pending-account merges share one lock. */
@lombok.extern.slf4j.Slf4j
public class ConfigUiAccountStore {
    public static final String NAMESPACE = "config-ui-accounts";
    private final NovaStateStore state;
    private final ConfigUiAccount localFallback = ConfigUiAccount.local(Instant.now());

    public ConfigUiAccountStore(NovaStateStore state) { this.state = state; }

    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    /**
     * 登记「账号表刚写进磁盘」的通知
     * <p>
     * 事件输出用它：账号被停用、删除或改了角色，用它名下令牌连着的连接要当场再核一次。
     * 每写一次都通知，不分改的是哪一项；通知那一头自己判要不要断。
     * @param listener 通知，在改动的那条线程上调（往往还攥着这个对象的锁），只能做不阻塞的事
     */
    public void addChangeListener(Runnable listener) { changeListeners.add(listener); }

    /** 撤掉 {@link #addChangeListener} 登记的通知 */
    public void removeChangeListener(Runnable listener) { changeListeners.remove(listener); }

    public synchronized List<ConfigUiAccount> list() {
        JSONObject data = state.namespace(NAMESPACE);
        List<ConfigUiAccount> accounts = new ArrayList<>();
        for (String id : data.keySet()) {
            try {
                accounts.add(read(id, data.getJSONObject(id)));
            } catch (RuntimeException error) {
                log.warn("配置界面: 无法读取一条账号记录，已拒绝该账号登录");
            }
        }
        if (accounts.stream().noneMatch(a -> LOCAL_ID.equals(a.id()))) accounts.add(localFallback);
        accounts.sort(Comparator.comparing(ConfigUiAccount::createdAt).thenComparing(ConfigUiAccount::id));
        return List.copyOf(accounts);
    }

    public synchronized Optional<ConfigUiAccount> find(String id) {
        return list().stream().filter(a -> a.id().equals(id)).findFirst();
    }

    public synchronized ConfigUiAccount require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("账号不存在"));
    }

    public synchronized ConfigUiAccount register(OidcClient.Identity identity, Instant now) {
        var existing = list().stream().filter(a -> identity.issuer().equals(a.issuer())
                && identity.subject().equals(a.subject())).findFirst();
        ConfigUiAccount account = existing.orElseGet(() -> new ConfigUiAccount(UUID.randomUUID().toString(),
                Role.MEMBER, Status.PENDING, identity.name(), identity.email(), identity.issuer(), identity.subject(),
                List.of(), now, null, false));
        account = new ConfigUiAccount(account.id(), account.role(), account.status(),
                LOCAL_ID.equals(account.id()) ? "本地管理员" : identity.name(), identity.email(), identity.issuer(),
                identity.subject(), account.streamers(), account.createdAt(), account.lastLoginAt(), account.everEnabled());
        save(account);
        return account;
    }

    public synchronized void loggedIn(String id, Instant now) {
        var a = require(id);
        save(new ConfigUiAccount(a.id(), a.role(), a.status(), a.displayName(), a.email(), a.issuer(), a.subject(),
                a.streamers(), a.createdAt(), now, a.everEnabled()));
    }

    public synchronized ConfigUiAccount status(String id, Status status) {
        var a = require(id);
        if (LOCAL_ID.equals(id)) throw new IllegalArgumentException("本地管理员不能停用");
        if (status == Status.PENDING) throw new IllegalArgumentException("不能改回待授权");
        var updated = new ConfigUiAccount(a.id(), a.role(), status, a.displayName(), a.email(), a.issuer(), a.subject(),
                a.streamers(), a.createdAt(), a.lastLoginAt(), a.everEnabled() || status == Status.ENABLED);
        save(updated);
        return updated;
    }

    public synchronized ConfigUiAccount role(String id, Role role) {
        var a = require(id);
        if (LOCAL_ID.equals(id)) throw new IllegalArgumentException("本地管理员不能降级");
        var updated = new ConfigUiAccount(a.id(), role, a.status(), a.displayName(), a.email(), a.issuer(), a.subject(),
                a.streamers(), a.createdAt(), a.lastLoginAt(), a.everEnabled());
        save(updated);
        return updated;
    }

    public synchronized ConfigUiAccount streamers(String id, List<String> streamers) {
        var a = require(id);
        if (streamers.size() > 10000 || streamers.stream().anyMatch(s -> ConfigUiStreamerId.parse(s) == null)) {
            throw new IllegalArgumentException("指定主播格式不正确");
        }
        var updated = new ConfigUiAccount(a.id(), a.role(), a.status(), a.displayName(), a.email(), a.issuer(), a.subject(),
                streamers.stream().distinct().toList(), a.createdAt(), a.lastLoginAt(), a.everEnabled());
        save(updated);
        return updated;
    }

    public synchronized void delete(String id) {
        var a = require(id);
        if (LOCAL_ID.equals(id) || a.role() != Role.MEMBER || a.status() == Status.ENABLED) {
            throw new IllegalArgumentException("只能删除待授权或停用的成员账号");
        }
        JSONObject data = state.namespace(NAMESPACE);
        data.remove(id);
        persist(data);
    }

    public synchronized void bind(OidcClient.Identity identity) {
        var local = require(LOCAL_ID);
        JSONObject data = state.namespace(NAMESPACE);
        for (var a : list()) {
            if (!LOCAL_ID.equals(a.id()) && identity.issuer().equals(a.issuer()) && identity.subject().equals(a.subject())) {
                if (a.status() != Status.PENDING || a.everEnabled()) {
                    throw new IllegalArgumentException("身份已属于其他账号，请先处理那个账号");
                }
                data.remove(a.id());
            }
        }
        data.put(LOCAL_ID, json(new ConfigUiAccount(local.id(), local.role(), local.status(), local.displayName(),
                identity.email(), identity.issuer(), identity.subject(), local.streamers(), local.createdAt(),
                local.lastLoginAt(), true)));
        persist(data);
    }

    public synchronized void unbind() {
        var a = require(LOCAL_ID);
        save(new ConfigUiAccount(a.id(), a.role(), a.status(), a.displayName(), null, null, null,
                a.streamers(), a.createdAt(), a.lastLoginAt(), true));
    }

    private void save(ConfigUiAccount account) {
        JSONObject data = state.namespace(NAMESPACE);
        data.putIfAbsent(LOCAL_ID, json(localFallback));
        data.put(account.id(), json(account));
        persist(data);
    }

    private void persist(JSONObject data) {
        JSONObject previous = state.namespace(NAMESPACE);
        state.write(NAMESPACE, target -> { target.clear(); target.putAll(data); });
        if (!state.trySave()) {
            state.write(NAMESPACE, target -> { target.clear(); target.putAll(previous); });
            throw new IllegalStateException("账号未能写入磁盘，请稍后再试");
        }
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (RuntimeException error) {
                // 改动已经写进去了，通知出错不该让调用方以为没改成；通知不到的由事件输出的定时复核兜着
                log.warn("配置界面: 账号改动后的通知出错 ({}), 已忽略", error.getClass().getSimpleName());
            }
        }
    }

    public static JSONObject json(ConfigUiAccount a) {
        JSONObject json = new JSONObject();
        json.put("id", a.id());
        json.put("role", a.role().name());
        json.put("status", a.status().name());
        json.put("displayName", a.displayName());
        json.put("email", a.email());
        json.put("issuer", a.issuer());
        json.put("subject", a.subject());
        json.put("streamers", a.streamers());
        json.put("createdAt", a.createdAt().toString());
        json.put("lastLoginAt", a.lastLoginAt() == null ? null : a.lastLoginAt().toString());
        json.put("everEnabled", a.everEnabled());
        return json;
    }

    private static ConfigUiAccount read(String id, JSONObject json) {
        return new ConfigUiAccount(id, Role.valueOf(json.getString("role")), Status.valueOf(json.getString("status")),
                json.getString("displayName"), json.getString("email"), json.getString("issuer"), json.getString("subject"),
                json.getList("streamers", String.class), Instant.parse(json.getString("createdAt")),
                json.getString("lastLoginAt") == null ? null : Instant.parse(json.getString("lastLoginAt")),
                json.getBooleanValue("everEnabled"));
    }
}
