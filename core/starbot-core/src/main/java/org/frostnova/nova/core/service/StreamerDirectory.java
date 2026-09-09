package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 在册主播名单
 * <p>
 * <b>为什么不问数据源要。</b>{@code AbstractDataSource#getAllUsers} 里只有<b>启用着的</b>主播——
 * 停用的那些在加载时就被剔掉了，连同被停用的通道与消息一起。于是「已停用」这个状态
 * 在内存里根本不存在，问内存要名单，界面上的表现是那位主播<b>整行消失</b>，
 * 而使用者刚刚做的动作叫「停用」，不叫「删除」。
 * <p>
 * 因此名单取自推送配置文件本身——控制台的推送页读的也是这一份
 * （{@code /config/api/datasource}），两处看到的「有哪些主播」才是同一批。
 * <p>
 * 只解析状态需要的那几项（是谁、启不启用、有没有在推），不重复数据源那一套完整解析：
 * 那一套要负责把配置变成能跑的对象，缺字段就该报错；这里回答的是「界面上该显示成什么样」，
 * 一条读不懂的记录不该让整张主播页打不开。
 */
@Slf4j
@Service
public class StreamerDirectory {
    private final NovaCoreProperties properties;

    @Autowired
    public StreamerDirectory(NovaCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 读推送配置文件里的主播名单
     * @return 名单，文件不存在或读不动时为空表
     */
    public List<Entry> entries() {
        Path path = Path.of(properties.getDatasource().getJsonPath());

        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            // 还没配过任何主播，空表即可——这是首次安装时的正常态
            return List.of();
        } catch (IOException e) {
            log.error("读取推送配置失败, 主播页将看不到在册名单", e);
            return List.of();
        }
    }

    /**
     * 解析推送配置内容
     * <p>
     * 逐条容错：读不懂的那一条跳过而不是整份判死。一条手改坏了的记录
     * 不该让其余十位主播也从界面上消失。
     * @param content 推送配置内容
     * @return 名单，内容不是数组时为空表
     */
    public static List<Entry> parse(String content) {
        JSONArray users;
        try {
            users = JSONArray.parseArray(content);
        } catch (Exception e) {
            return List.of();
        }

        if (users == null) {
            return List.of();
        }

        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            try {
                JSONObject user = users.getJSONObject(i);
                if (user == null) {
                    continue;
                }

                Long uid = user.getLong("uid");
                String platform = user.getString("platform");
                if (uid == null || platform == null || platform.isBlank()) {
                    continue;
                }

                result.add(new Entry(platform, uid, enabled(user), pushing(user)));
            } catch (Exception e) {
                log.debug("跳过推送配置中读不懂的第 {} 条: {}", i, e.getMessage());
            }
        }

        return result;
    }

    /**
     * 这一条启不启用
     * <p>
     * <b>缺 {@code enabled} 字段按启用计</b>，与数据源加载时的认法一致
     * （{@code JsonDataSource#parse} 里同样是「没写就是开着」）。
     * 反过来按停用计的话，一份没写过这个字段的配置会整份静默失效。
     * @param node 配置里的一条记录，可为 null
     * @return 是否启用
     */
    public static boolean enabled(JSONObject node) {
        return node != null && !Boolean.FALSE.equals(node.getBoolean("enabled"));
    }

    /**
     * 这位主播会不会真的推出去
     * <p>
     * 要求「有一个启用的通道，且那个通道里有一条启用的消息」。<b>只数通道数是不够的</b>：
     * 一个通道里的四种通知全关掉，与没有通道的效果完全相同——照常采集、出场次与报告，
     * 但一条消息都不发，而界面上那位主播看起来配得好好的。
     */
    private static boolean pushing(JSONObject user) {
        JSONArray targets = user.getJSONArray("targets");
        if (targets == null) {
            return false;
        }

        for (int i = 0; i < targets.size(); i++) {
            JSONObject target = targets.getJSONObject(i);
            if (!enabled(target)) {
                continue;
            }

            JSONArray messages = target.getJSONArray("messages");
            if (messages == null) {
                continue;
            }
            for (int j = 0; j < messages.size(); j++) {
                if (enabled(messages.getJSONObject(j))) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 名单里的一位主播
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param enabled 启用着（停用的仍留在配置里，只是不采集不推送）
     * @param pushing 至少有一条启用的推送消息落在一个启用的通道上
     */
    public record Entry(@NonNull String platform, @NonNull Long uid, boolean enabled, boolean pushing) {
    }
}
