package com.starlwr.bot.core.sender;

import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.service.StarBotStateStore;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 首次推送后的用法提示
 * <p>
 * 机器人被拉进一个群之后，群里的人只见得到通知，见不到「它还能被问点什么」——
 * 命令表在控制台里，而看得见控制台的只有机器人的主人。于是这句提示跟在
 * <b>第一条真的送到的推送</b>后面发一次，把入口交代清楚。
 * <p>
 * <b>只发一次，按会话记。</b> 每条推送都附一句是打扰；一次都不发，这件事就只有主人知道。
 * 「已提示过」落在运行状态里而不是内存里：重启后重新提示一遍，与「机器人今天怎么话变多了」
 * 在群里长得一样。
 */
@Service
public class FirstPushTipService {
    /**
     * 状态存储中的命名空间
     */
    private static final String NAMESPACE = "FirstPushTip";

    private final StarBotStateStore store;

    @Autowired
    public FirstPushTipService(StarBotStateStore store) {
        this.store = store;
    }

    /**
     * 认领某个会话的这一次提示
     * <p>
     * 「查一下有没有提示过」与「记下提示过了」必须是同一个动作：分成两步的话，
     * 同一时刻推给同一个群的两条消息会双双查到「没提示过」，于是提示发两遍。
     * <p>
     * 调用方认领成功后<b>必须真的把提示发出去</b>：认领只做一次，认领了却没发出去，
     * 这个会话就此再也收不到这句话。因此凡是「这次不该发」的判断都要排在认领之前。
     * @param platform 推送平台
     * @param type 会话类型
     * @param num 会话号
     * @return 本次是否轮到发提示
     */
    public boolean claim(@NonNull String platform, @NonNull PushTargetType type, @NonNull Long num) {
        String key = key(platform, type, num);
        boolean[] first = {false};

        store.write(NAMESPACE, data -> {
            if (!data.containsKey(key)) {
                data.put(key, System.currentTimeMillis());
                first[0] = true;
            }
        });
        return first[0];
    }

    /**
     * 提示的正文
     * <p>
     * 群聊与私聊说的不是同一句话：群里要先 @ 才算命令，私聊直接发命令名，
     * 在私聊里教人「先 @ 我」是一句照着做也不管用的话。
     * @param type 会话类型
     * @return 提示正文
     */
    public String text(@NonNull PushTargetType type) {
        return PushTargetType.GROUP == type
                ? "想用我，先 @ 我。试试 @ 我并发「菜单」，看看我都能做什么"
                : "想用我，直接发「菜单」，看看我都能做什么";
    }

    /**
     * 键为「平台:会话类型:会话号」
     * <p>
     * 会话类型也进键：群号与好友账号取自两个互不相干的号段，只按号码算的话，
     * 两者撞号时一个群的提示会顶掉一个好友的
     */
    private String key(String platform, PushTargetType type, Long num) {
        return platform + ":" + type.getCode() + ":" + num;
    }
}
