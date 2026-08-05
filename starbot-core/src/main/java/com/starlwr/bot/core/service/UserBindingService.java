package com.starlwr.bot.core.service;

import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    private String key(String pushPlatform, String livePlatform, Long senderUid) {
        return pushPlatform + ":" + livePlatform + ":" + senderUid;
    }
}
