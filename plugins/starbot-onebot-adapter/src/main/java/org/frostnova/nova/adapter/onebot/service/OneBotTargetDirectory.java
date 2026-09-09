package org.frostnova.nova.adapter.onebot.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.core.plugin.NovaComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机器人在哪些群里、加了哪些好友
 * <p>
 * 配推送目标时不该再有手填号码的格子：能推给谁，机器人自己知道。这里把这份名单取回来，
 * 供界面挑选。<b>取的是「现在」的名单</b>——退了群、删了好友之后，界面上就该没有那一条，
 * 否则使用者会配出一个永远推不出去的目标，而失败只表现为「没收到」。
 *
 * <h2>为什么带缓存</h2>
 * 挑选界面会随输入反复查询，而每查一次都去问一遍 OneBot 意味着一次搜索敲下十个字
 * 就是十轮往返。名单本身变得很慢（进群退群是以天计的事），因此按 {@link #TTL} 缓存，
 * 使用者觉得不对时点刷新强制重取。
 *
 * <h2>取不到时保留旧表</h2>
 * OneBot 掉线时把名单清空，界面上会变成「一个群都没有」——那与「机器人真的不在任何群里」
 * 长得一模一样，而两者该做的事完全相反。因此拉取失败时旧表原样留着，
 * 另外标明它是什么时候取的、以及现在已经取不到了，由界面照实告诉使用者。
 */
@Slf4j
@NovaComponent
public class OneBotTargetDirectory {
    /**
     * 名单的有效期
     * <p>
     * 进群退群是以天计的事，十分钟内重复取只是在给 OneBot 添麻烦；
     * 而真改了群之后，使用者最迟等十分钟、或者点一下刷新就能看到。
     */
    static final Duration TTL = Duration.ofMinutes(10);

    private final OneBotHttpAdapter http;

    private final OneBotHttpService httpService;

    private final OneBotAdapterPluginProperties properties;

    private final Clock clock;

    /**
     * 推送平台名到该平台名单的映射
     */
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    @Autowired
    public OneBotTargetDirectory(OneBotHttpAdapter http, OneBotHttpService httpService, OneBotAdapterPluginProperties properties) {
        this(http, httpService, properties, Clock.systemUTC());
    }

    OneBotTargetDirectory(OneBotHttpAdapter http, OneBotHttpService httpService, OneBotAdapterPluginProperties properties, Clock clock) {
        this.http = http;
        this.httpService = httpService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 一个群
     *
     * @param num 群号
     * @param name 群名
     * @param memberCount 群成员数，OneBot 实现未上报时为 null
     * @param admin 机器人在群里是不是群主或管理员，<b>查不出时为 null</b>——
     *              查不出与「不是管理员」是两件事，混成 false 会让界面报出一个没查过的结论
     */
    public record Group(long num, String name, Integer memberCount, Boolean admin) {}

    /**
     * 一个好友
     *
     * @param num 账号
     * @param nickname 昵称
     * @param remark 备注，没设置时为空串
     */
    public record Friend(long num, String nickname, String remark) {}

    /**
     * 某个推送平台的一份名单
     *
     * @param sender 推送平台名
     * @param groups 群
     * @param friends 好友
     * @param fetchedAt <b>表里这些条目是什么时候取回来的</b>，一次都没取成过时为 null。
     *                  它跟着数据走，不跟着尝试走：拉取失败时保留的是上一次成功的时刻
     * @param attemptedAt 最近一次尝试拉取的时刻，有效期按它算——
     *                    否则连不上时每来一个请求都要再撞一次超时
     * @param stale 表里的内容是不是已经取不到最新的了
     * @param message 取不到时的原因，一切正常时为 null
     */
    public record Snapshot(String sender, List<Group> groups, List<Friend> friends,
                           Instant fetchedAt, Instant attemptedAt, boolean stale, String message) {}

    /**
     * 取各推送平台的名单
     * <p>
     * 只列已注册的推送平台。没注册的（例如缺 OneBot HTTP Token）本来就调不动接口，
     * 列出来只会得到一行「取不到」——而那句话在注册时已经报过一次了。
     * @param force 是否强制重新拉取，忽略有效期
     * @return 各推送平台的名单，按配置顺序
     */
    public List<Snapshot> snapshots(boolean force) {
        List<Snapshot> result = new ArrayList<>();

        for (OneBotSender configured : properties.getSenders()) {
            OneBotSender sender = httpService.getSender(configured.getName());
            if (sender == null) {
                continue;
            }

            result.add(snapshotOf(sender, force));
        }

        return result;
    }

    /**
     * 取单个推送平台的名单
     * <p>
     * <b>整个方法上锁</b>，因此同时来的多个请求只会触发一次拉取，后到的等着用同一份结果。
     * 挑选界面随输入查询时正是这种形状：不上锁的话，第一次查询的十来个并发请求
     * 会各自向 OneBot 发一整轮，而它们要的是同一份名单。
     */
    private synchronized Snapshot snapshotOf(OneBotSender sender, boolean force) {
        Snapshot current = snapshots.get(sender.getName());
        if (!force && current != null && clock.instant().isBefore(current.attemptedAt().plus(TTL))) {
            return current;
        }

        Snapshot fresh = fetch(sender, current);
        snapshots.put(sender.getName(), fresh);
        return fresh;
    }

    /**
     * 向 OneBot 要一份名单
     * <p>
     * 群与好友<b>一起成一起败</b>：两张表是同一份名单的两半，只成了一半时若各记各的时刻，
     * 表级的「什么时候取的」就没有答案了。
     * @param sender 推送平台信息
     * @param previous 上一份名单，从未取成过时为 null
     * @return 新的名单；拉取失败时是一份保留了旧内容的名单
     */
    private Snapshot fetch(OneBotSender sender, Snapshot previous) {
        Instant now = clock.instant();

        try {
            JSONArray groups = http.getGroupList(sender, new JSONObject());
            JSONArray friends = http.getFriendList(sender, new JSONObject());

            List<Group> groupRows = toGroups(sender, groups);
            List<Friend> friendRows = toFriends(sender, friends);

            log.debug("已取得 {} 的推送目标名单: 群 {} 个, 好友 {} 位", sender.getName(), groupRows.size(), friendRows.size());
            return new Snapshot(sender.getName(), groupRows, friendRows, now, now, false, null);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("取 {} 的推送目标名单失败, 保留上一次取到的内容: {}", sender.getName(), reason);

            return previous == null
                    ? new Snapshot(sender.getName(), List.of(), List.of(), null, now, true, reason)
                    : new Snapshot(sender.getName(), previous.groups(), previous.friends(), previous.fetchedAt(), now, true, reason);
        }
    }

    /**
     * 解析群列表
     * <p>
     * 没有群号的条目直接丢掉：一个不知道推给谁的目标在界面上点不出任何有效配置。
     * 丢了要说出丢了几条——静悄悄少几个群，使用者只会以为自己记错了。
     */
    private List<Group> toGroups(OneBotSender sender, JSONArray rows) {
        List<Group> groups = new ArrayList<>();
        int skipped = 0;

        // 一个群都没有时不必问自己是谁：那一趟只为判管理员，没有群就没有可判的
        Long selfId = rows == null || rows.isEmpty() ? null : selfId(sender);
        for (int i = 0; rows != null && i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            Long num = row == null ? null : row.getLong("group_id");
            if (num == null) {
                skipped++;
                continue;
            }

            groups.add(new Group(num, row.getString("group_name"), row.getInteger("member_count"), admin(sender, num, selfId)));
        }

        if (skipped > 0) {
            log.warn("{} 的群列表里有 {} 条没有群号, 已跳过", sender.getName(), skipped);
        }

        return groups;
    }

    /**
     * 解析好友列表
     */
    private List<Friend> toFriends(OneBotSender sender, JSONArray rows) {
        List<Friend> friends = new ArrayList<>();
        int skipped = 0;

        for (int i = 0; rows != null && i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            Long num = row == null ? null : row.getLong("user_id");
            if (num == null) {
                skipped++;
                continue;
            }

            friends.add(new Friend(num, row.getString("nickname"), row.getString("remark")));
        }

        if (skipped > 0) {
            log.warn("{} 的好友列表里有 {} 条没有账号, 已跳过", sender.getName(), skipped);
        }

        return friends;
    }

    /**
     * 取得机器人自己的账号
     * <p>
     * 单独兜住异常：它只用来判管理员，取不到时名单本身照常出——
     * 让一个装饰性的字段把整份名单拖垮，代价与收益完全不成比例。
     * @return 账号，取不到时为 null
     */
    private Long selfId(OneBotSender sender) {
        try {
            JSONObject info = http.getLoginInfo(sender, new JSONObject());
            return info == null ? null : info.getLong("user_id");
        } catch (Exception e) {
            log.debug("未能取得 {} 的登录账号, 群管理员一栏将留空", sender.getName());
            return null;
        }
    }

    /**
     * 判断机器人在某个群里是不是群主或管理员
     * <p>
     * OneBot 的群列表不带自己的角色，只能逐群问一次——因此一次拉取要发的请求数是
     * <b>群数加三</b>（登录信息、群列表、好友列表各一）。这也正是要缓存的理由。
     * @return 是不是管理员，查不出时为 null
     */
    private Boolean admin(OneBotSender sender, long groupId, Long selfId) {
        if (selfId == null) {
            return null;
        }

        try {
            JSONObject params = new JSONObject()
                    .fluentPut("group_id", groupId)
                    .fluentPut("user_id", selfId);
            JSONObject member = http.getGroupMemberInfo(sender, params);

            String role = member == null ? null : member.getString("role");
            if (role == null) {
                return null;
            }

            return "owner".equalsIgnoreCase(role) || "admin".equalsIgnoreCase(role);
        } catch (Exception e) {
            log.debug("未能取得机器人在群 {} 的角色, 管理员一栏留空", groupId);
            return null;
        }
    }
}
