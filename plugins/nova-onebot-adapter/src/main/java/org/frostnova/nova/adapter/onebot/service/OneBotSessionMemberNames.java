package org.frostnova.nova.adapter.onebot.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.SessionMemberNames;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OneBot 群成员展示名
 * <p>
 * 群昵称是 QQ 的 {@code card}，没设时才看账号昵称 {@code nickname}：
 * 名单是发回群里给人看的，群里的人认的是群昵称那一栏。
 * <p>
 * 成员表一趟拿全、按人查表取名：名单上几十个人就是几十趟远门，
 * 一次回话能把群接口打穿。查不动就返空，由调用方写占位——名单少一行名字
 * 比整张名单卡住强，而把账号写出去是另一回事：那是隐私，
 * 不在「取不到名字」的补救范围内。
 */
@Slf4j
@NovaComponent
public class OneBotSessionMemberNames implements SessionMemberNames {
    private final OneBotHttpAdapter http;

    /**
     * 发送服务反过来经核心的发送器间接依赖本类，直接注入会形成循环依赖
     */
    private final ObjectProvider<OneBotHttpService> httpService;

    public OneBotSessionMemberNames(OneBotHttpAdapter http, ObjectProvider<OneBotHttpService> httpService) {
        this.http = http;
        this.httpService = httpService;
    }

    @Override
    public boolean supports(@NonNull String platform) {
        return resolveSender(platform) != null;
    }

    @Override
    public Map<Long, String> displayNames(@NonNull String platform, @NonNull PushTargetType type,
                                          @NonNull Long sessionNum, @NonNull Collection<Long> memberUids) {
        // 群成员信息才带群昵称；私聊那一路没有这回事
        if (PushTargetType.GROUP != type) {
            return Map.of();
        }
        OneBotSender sender = resolveSender(platform);
        if (sender == null) {
            return Map.of();
        }

        try {
            JSONObject params = new JSONObject().fluentPut("group_id", sessionNum);
            JSONArray members = http.getGroupMemberList(sender, params);
            if (members == null) {
                return Map.of();
            }

            Map<Long, String> table = new HashMap<>();
            for (int i = 0; i < members.size(); i++) {
                JSONObject member = members.getJSONObject(i);
                if (member == null) {
                    continue;
                }
                Long uid = member.getLong("user_id");
                if (uid == null) {
                    continue;
                }
                // 群昵称优先：群里的人认的是这个名字；没设群昵称时才退回账号昵称
                String card = member.getString("card");
                if (card != null && !card.isBlank()) {
                    table.put(uid, card);
                    continue;
                }
                String nickname = member.getString("nickname");
                if (nickname != null && !nickname.isBlank()) {
                    table.put(uid, nickname);
                }
            }

            Map<Long, String> names = new LinkedHashMap<>();
            for (Long uid : memberUids) {
                String name = uid == null ? null : table.get(uid);
                if (name != null) {
                    names.put(uid, name);
                }
            }
            return names;
        } catch (Exception e) {
            // 查不动不阻塞名单：调用方会写占位
            log.debug("查询群 {} 成员展示名失败: {}", sessionNum, e.getMessage());
            return Map.of();
        }
    }

    private OneBotSender resolveSender(String platform) {
        OneBotHttpService service = httpService.getIfAvailable();
        return service == null ? null : service.getSender(platform);
    }
}
