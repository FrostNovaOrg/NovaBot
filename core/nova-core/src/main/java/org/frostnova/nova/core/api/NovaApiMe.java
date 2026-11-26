package org.frostnova.nova.core.api;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.StreamerDirectory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 自查接口 {@code GET /nova/api/v1/me}
 * <p>
 * 任何有效令牌都能调，不要求范围：拿到令牌的程序第一件事就是问「我是谁、能看见谁」，
 * 这件事不该还得先勾一项范围。
 */
final class NovaApiMe {
    private final Supplier<List<NovaApiStreamer>> roster;

    NovaApiMe(Supplier<List<NovaApiStreamer>> roster) {
        this.roster = roster;
    }

    Object handle(NovaApiRequest request) {
        NovaApiCaller caller = request.caller();
        JSONObject account = new JSONObject();
        account.put("id", caller.accountId());
        account.put("displayName", caller.displayName());
        account.put("role", caller.role().name());

        JSONObject token = new JSONObject();
        token.put("id", caller.tokenId());
        token.put("name", caller.tokenName());
        token.put("scopes", new JSONArray(caller.scopes().stream().sorted().toList()));
        token.put("expiresAt", caller.expiresAt() == null ? null : caller.expiresAt().toString());

        JSONArray streamers = new JSONArray();
        for (NovaApiStreamer streamer : roster.get()) {
            if (!caller.canSee(streamer.platform(), streamer.uid())) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("platform", streamer.platform());
            item.put("uid", streamer.uid());
            item.put("name", streamer.name());
            streamers.add(item);
        }

        JSONObject data = new JSONObject();
        data.put("account", account);
        data.put("token", token);
        data.put("streamers", streamers);
        return data;
    }

    /**
     * 在册主播名单：推送配置里的（含停用的）加上已载入的，与控制台主播页同一份
     * <p>
     * 名字取已载入的那份；停用的主播没有载入，名字为 null。
     * @param configured 推送配置里的名单
     * @param loaded     已载入的主播
     * @return 名单
     */
    static List<NovaApiStreamer> roster(List<StreamerDirectory.Entry> configured, Collection<PushUser> loaded) {
        Map<String, String> names = new HashMap<>();
        for (PushUser user : loaded) {
            if (user.getPlatform() != null && user.getUid() != null && user.getUname() != null
                    && !user.getUname().isBlank()) {
                names.put(user.getPlatform() + "#" + user.getUid(), user.getUname());
            }
        }
        List<NovaApiStreamer> result = new ArrayList<>();
        for (StreamerDirectory.Entry entry : StreamerDirectory.roster(configured, loaded)) {
            result.add(new NovaApiStreamer(entry.platform(), entry.uid(), names.get(entry.platform() + "#" + entry.uid())));
        }
        return result;
    }
}
