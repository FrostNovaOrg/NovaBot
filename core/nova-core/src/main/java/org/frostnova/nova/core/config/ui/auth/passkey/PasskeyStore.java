package org.frostnova.nova.core.config.ui.auth.passkey;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.service.NovaStateStore;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 已登记的通行密钥存在哪
 * <p>
 * 落在运行状态文件里，<b>不进 application.yml</b>。理由与订阅名单、命令开关同一条：
 * 配置文件是使用者手写的东西，程序去改会盖掉人的编辑意图；而通行密钥是程序自己产生的状态，
 * 使用者从来不会手写一把公钥。
 * <p>
 * 🔴 登记与删除都<b>当场落盘</b>，不等自动保存那一轮。这两件事各有各的理由：
 * 登记完不落盘，进程若在下一轮自动保存前退出，使用者手上那把刚建好的钥匙就成了孤儿——
 * 浏览器认为它存在，服务端不认；删除不落盘，则「我已经把丢了的那台设备撤销了」这句话
 * 在重启后不成立，而那正是使用者最需要它成立的时刻。
 */
@Slf4j
public class PasskeyStore {
    /**
     * 运行状态文件里的命名空间
     */
    static final String NAMESPACE = "passkey";

    private static final String NAME = "name";

    private static final String PUBLIC_KEY = "publicKey";

    private static final String ALGORITHM = "algorithm";

    private static final String SIGN_COUNT = "signCount";

    private static final String CREATED_AT = "createdAt";

    private static final String LAST_USED_AT = "lastUsedAt";

    private final NovaStateStore state;

    public PasskeyStore(NovaStateStore state) {
        this.state = state;
    }

    /**
     * 全部已登记的通行密钥，按登记时间从早到晚
     * <p>
     * 读不出来的条目<b>跳过并记一行日志</b>，而不是让整张列表连带失败：
     * 状态文件被手工改坏时，剩下那几把还能用的钥匙不该跟着一起消失。
     * @return 凭据列表
     */
    public List<PasskeyCredential> list() {
        JSONObject namespace = state.namespace(NAMESPACE);
        List<PasskeyCredential> credentials = new ArrayList<>(namespace.size());

        for (String id : namespace.keySet()) {
            try {
                credentials.add(read(id, namespace.getJSONObject(id)));
            } catch (Exception e) {
                log.warn("配置界面: 运行状态里的通行密钥 {} 读不出来, 已跳过: {}", id, e.getMessage());
            }
        }

        credentials.sort(Comparator.comparing(PasskeyCredential::createdAt));
        return credentials;
    }

    /**
     * 按凭据 ID 找一把钥匙
     * @param id 凭据 ID，base64url
     * @return 凭据，不存在时为空
     */
    public Optional<PasskeyCredential> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }

        // 整个转换都在读锁里做完，不把 JSON 本体带出来：带出来的是活对象，
        // 而写入来自另一条线程（同一时刻可能正有一次登录在更新计数器）
        return state.read(NAMESPACE, id, namespace -> {
            try {
                return read(id, namespace.getJSONObject(id));
            } catch (Exception e) {
                log.warn("配置界面: 通行密钥 {} 读不出来: {}", id, e.getMessage());
                return null;
            }
        });
    }

    /**
     * 写入一把新钥匙
     * <p>
     * 只给登记用。登录更新计数器走 {@link #updateIfSignCount}：
     * 无条件覆盖会把「已经删掉」或「别人刚用过」的那一份写回去。
     * @param credential 凭据
     */
    public void save(PasskeyCredential credential) {
        state.write(NAMESPACE, namespace -> namespace.put(credential.id(), toJson(credential)));
        state.save();
    }

    /**
     * 仅当这把钥匙还在、且计数仍是期望的旧值时，写入新值
     * @param id 凭据 ID
     * @param expectedSignCount 写入前必须仍是这个计数
     * @param credential 要写成的新值
     * @return 是否写进去了。钥匙已经不在、或计数已经被别人改过，都是 false
     */
    public boolean updateIfSignCount(String id, long expectedSignCount, PasskeyCredential credential) {
        if (id == null || id.isBlank()) {
            return false;
        }

        boolean[] updated = {false};
        state.write(NAMESPACE, namespace -> {
            JSONObject existing = namespace.getJSONObject(id);
            if (existing == null) {
                return;
            }
            if (existing.getLongValue(SIGN_COUNT) != expectedSignCount) {
                return;
            }
            namespace.put(id, toJson(credential));
            updated[0] = true;
        });
        if (updated[0]) {
            state.save();
        }
        return updated[0];
    }

    /**
     * 删掉一把钥匙，仅当它此刻还在
     * @param id 凭据 ID
     * @return 原本是否存在
     */
    public boolean remove(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }

        boolean[] existed = {false};
        state.write(NAMESPACE, namespace -> existed[0] = namespace.remove(id) != null);
        if (existed[0]) {
            state.save();
        }

        return existed[0];
    }

    private JSONObject toJson(PasskeyCredential credential) {
        JSONObject json = new JSONObject();
        json.put(NAME, credential.name());
        json.put(PUBLIC_KEY, credential.publicKey());
        json.put(ALGORITHM, credential.algorithm());
        json.put(SIGN_COUNT, credential.signCount());
        json.put(CREATED_AT, credential.createdAt().toString());
        if (credential.lastUsedAt() != null) {
            json.put(LAST_USED_AT, credential.lastUsedAt().toString());
        }
        return json;
    }

    private PasskeyCredential read(String id, JSONObject json) {
        if (json == null) {
            throw new IllegalArgumentException("条目为空");
        }

        String lastUsedAt = json.getString(LAST_USED_AT);
        return new PasskeyCredential(
                id,
                json.getString(NAME),
                json.getString(PUBLIC_KEY),
                json.getIntValue(ALGORITHM),
                json.getLongValue(SIGN_COUNT),
                Instant.parse(json.getString(CREATED_AT)),
                lastUsedAt == null ? null : Instant.parse(lastUsedAt));
    }
}
