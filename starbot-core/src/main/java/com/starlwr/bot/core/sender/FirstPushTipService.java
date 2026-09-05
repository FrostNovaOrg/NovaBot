package com.starlwr.bot.core.sender;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.StarBotStateStore;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.List;

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
@Slf4j
@Service
public class FirstPushTipService {
    /**
     * 状态存储中的命名空间
     */
    private static final String NAMESPACE = "FirstPushTip";

    /**
     * 已经按当前配置补记过一次的标记
     * <p>
     * 与会话键同库、不同形：会话键是「平台:类型:号」，这一条没有冒号。
     * {@link #claim} 只按会话键读写，不会把它当成某个群或好友。
     */
    static final String SEEDED_KEY = "_seeded";

    private final StarBotStateStore store;

    private final StarBotCoreProperties properties;

    private final AbstractDataSource dataSource;

    /**
     * 判据台架：不注入配置时按默认（提示开着）
     */
    public FirstPushTipService(StarBotStateStore store) {
        this(store, new StarBotCoreProperties(), null);
    }

    FirstPushTipService(StarBotStateStore store, StarBotCoreProperties properties) {
        this(store, properties, null);
    }

    @Autowired
    public FirstPushTipService(StarBotStateStore store, StarBotCoreProperties properties,
                               @Autowired(required = false) AbstractDataSource dataSource) {
        this.store = store;
        this.properties = properties;
        this.dataSource = dataSource;
    }

    /**
     * 应用就绪后，把升级前就已经在用的会话记成已经提示过
     * <p>
     * 须排在状态加载（{@code StarBotStateStore} {@code @Order(-10000)}）
     * 与数据源加载（{@code LoadDataSourceListener} {@code @Order(0)}）之后、
     * 发件线程真正送出第一条之前。排在数据源之前会把空名单写成「已经补过」，
     * 之后配置里那些老群仍然会被当成第一次。
     */
    @Order(1)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        if (dataSource == null) {
            return;
        }
        seedExisting(dataSource.getAllUsers());
    }

    /**
     * 把当前已配置的推送会话记成已经提示过
     * <p>
     * 这套记录是后来才有的。升级上来时每个既有会话都是空白，会被当成第一次
     * 各发一遍——而那些群已经用了很久。只在还没有标记的时候做一次；
     * 之后新加进来的会话没有记录，仍会提示一次。
     * @param users 数据源里当前的推送用户
     */
    void seedExisting(List<PushUser> users) {
        if (seeded()) {
            return;
        }
        int marked = 0;
        if (users != null) {
            for (PushUser user : users) {
                if (user == null || user.getTargets() == null) {
                    continue;
                }
                for (PushTarget target : user.getTargets()) {
                    if (!complete(target)) {
                        continue;
                    }
                    claim(target.getPlatform(), target.getType(), target.getNum());
                    marked++;
                }
            }
        }
        store.write(NAMESPACE, data -> {
            if (!data.containsKey(SEEDED_KEY)) {
                data.put(SEEDED_KEY, System.currentTimeMillis());
            }
        });
        log.info("已将当前配置的 {} 个推送会话记为已提示过首次用法，之后新加进来的会话仍会提示一次", marked);
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
        if (SEEDED_KEY.equals(key)) {
            return false;
        }
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
     * 这句提示现在开着没有
     * <p>
     * 关掉时不认领：留给以后打开时的第一条。每次现读，改完不必重启。
     */
    boolean enabled() {
        return properties.getPush().isFirstPushTip();
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

    private boolean seeded() {
        return store.read(NAMESPACE, SEEDED_KEY, data -> data.get(SEEDED_KEY)).isPresent();
    }

    private static boolean complete(PushTarget target) {
        return target != null
                && target.getPlatform() != null
                && target.getType() != null
                && target.getType() != PushTargetType.UNKNOWN
                && target.getNum() != null;
    }
}
