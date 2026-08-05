package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSONObject;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 账号绑定
 * <p>
 * 记录「某个推送平台的账号」对应「某个直播平台的账号」，个人数据查询据此知道你是谁。
 * 与「@我」订阅同属群成员自己产生的数据，因此一并存在运行状态文件里。
 * <p>
 * <b>绑定只能核对，不能验证归属。</b>没有任何接口能证明「这个 QQ 号的主人就是这个
 * 哔哩哔哩账号的主人」，所以绑定流程做的是**让人确认昵称对不对**，防的是打错 uid，
 * 而不是防冒用。可查到的也只是此人在本群所配置主播的直播间里的互动量，
 * 这些数据在直播间里本就是公开可见的。
 */
@Service
public class UserBindingService {
    /**
     * 状态存储中的命名空间
     */
    private static final String NAMESPACE = "UserBindings";

    private final StarBotStateStore store;

    @Autowired
    public UserBindingService(StarBotStateStore store) {
        this.store = store;
    }

    /**
     * 绑定
     * @param pushPlatform 推送平台，如 qq-onebot
     * @param livePlatform 直播平台，如 bilibili
     * @param senderUid 推送平台的账号
     * @param liveUid 直播平台的 UID
     */
    public void bind(@NonNull String pushPlatform, @NonNull String livePlatform,
                     @NonNull Long senderUid, @NonNull Long liveUid) {
        store.write(NAMESPACE, data -> data.put(key(pushPlatform, livePlatform, senderUid), liveUid));
    }

    /**
     * 解除绑定
     * @return 此前是否绑定过
     */
    public boolean unbind(@NonNull String pushPlatform, @NonNull String livePlatform, @NonNull Long senderUid) {
        if (get(pushPlatform, livePlatform, senderUid).isEmpty()) {
            return false;
        }

        store.write(NAMESPACE, data -> data.remove(key(pushPlatform, livePlatform, senderUid)));
        return true;
    }

    /**
     * 查询绑定的直播平台 UID
     * @return 绑定的 UID，未绑定时为空
     */
    public Optional<Long> get(@NonNull String pushPlatform, @NonNull String livePlatform, @NonNull Long senderUid) {
        String key = key(pushPlatform, livePlatform, senderUid);
        try {
            return Optional.ofNullable(store.namespace(NAMESPACE).getLong(key));
        } catch (Exception e) {
            // 手工编辑状态文件时可能写入非数字，当作未绑定处理即可
            return Optional.empty();
        }
    }

    /**
     * 列出全部绑定关系
     * <p>
     * 供管理后台查看与解绑。绑定既然无法验证归属，就必须留一条**事后纠正**的通道：
     * 有人绑错了 uid 又不在群里、或有人冒用他人 uid 时，机器人的主人得能处理。
     * @return 绑定关系，按推送平台账号排序
     */
    public List<Binding> all() {
        JSONObject data = store.namespace(NAMESPACE);
        List<Binding> result = new ArrayList<>();

        for (String key : data.keySet()) {
            // 键为「推送平台:直播平台:账号」。平台名本身不含冒号，但也不必假设——
            // 后两段的位置是固定的，从右侧数即可，剩下的整段都是推送平台名
            String[] parts = key.split(":");
            if (parts.length < 3) {
                continue;
            }

            try {
                Long liveUid = data.getLong(key);
                if (liveUid == null) {
                    continue;
                }
                result.add(new Binding(String.join(":", Arrays.copyOf(parts, parts.length - 2)),
                        parts[parts.length - 2], Long.parseLong(parts[parts.length - 1]), liveUid));
            } catch (Exception ignored) {
                // 手工编辑状态文件时可能混入非法键，跳过即可
            }
        }

        result.sort(Comparator.comparing(Binding::pushPlatform)
                .thenComparing(Binding::livePlatform)
                .thenComparingLong(Binding::senderUid));
        return result;
    }

    private String key(String pushPlatform, String livePlatform, Long senderUid) {
        return pushPlatform + ":" + livePlatform + ":" + senderUid;
    }

    /**
     * 一条绑定关系
     * @param pushPlatform 推送平台
     * @param livePlatform 直播平台
     * @param senderUid 推送平台的账号
     * @param liveUid 直播平台的 UID
     */
    public record Binding(String pushPlatform, String livePlatform, long senderUid, long liveUid) {
    }
}
