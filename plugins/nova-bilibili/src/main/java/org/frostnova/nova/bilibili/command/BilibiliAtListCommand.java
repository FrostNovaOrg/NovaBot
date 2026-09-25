package org.frostnova.nova.bilibili.command;

import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.SessionMemberNames;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「{@code @名单}」命令
 * <p>
 * 开播与动态的订阅名单收成一条：不点名两类都看，点名（旧名「{@code 开播@名单}」
 * 或首参「{@code 开播}」）只看那一类。
 *
 * <h2>名单里只写名字，不写账号</h2>
 * 名单是要发回群里给人看的，群里的人认的是群昵称。取不到群昵称时退回账号昵称，
 * 再取不到就写一句占位——<b>怎么都不把账号写出去</b>：那是隐私，
 * 而群里本来就能看见的只是昵称那一栏。
 */
@NovaComponent
public class BilibiliAtListCommand extends BilibiliAtCommand {
    /**
     * 名单中最多列出的人数，超出只报总数
     * <p>
     * 名单动辄上百人时全部列出既刷屏又没人真的会看完。
     */
    private static final int MAX_SHOWN = 30;

    /**
     * 取不到展示名时的占位
     * <p>
     * 而不是把账号写出来：名单是发回群里的，账号是隐私。
     */
    private static final String UNKNOWN_NAME = "（昵称未知）";

    /**
     * 取名字那一支，按平台挑
     * <p>
     * 装了哪个平台的适配器就用哪个的，都没装就整条命令照跑、名字全写占位——
     * 这条命令在没有推送平台适配器的机器上也得立得起来。
     */
    private final ObjectProvider<SessionMemberNames> memberNames;

    private final CommandSettingsService settings;

    @Autowired
    public BilibiliAtListCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice,
                                 AtSubscriptionService subscriptions,
                                 ObjectProvider<SessionMemberNames> memberNames, CommandSettingsService settings) {
        super(dataSource, choice, subscriptions);
        this.memberNames = memberNames;
        this.settings = settings;
    }

    @Override
    public String name() {
        return "@名单";
    }

    @Override
    public List<String> aliases() {
        return List.of("开播@名单", "动态@名单");
    }

    @Override
    public String description() {
        return "查看提醒订阅名单，可带「开播」或「动态」";
    }

    @Override
    public String usage() {
        return "[开播|动态] [主播 uid 或昵称]";
    }

    @Override
    protected List<BilibiliAtNoticeKind> kinds(CommandContext context) {
        BilibiliAtNoticeKind named = kindFrom(context);
        return named == null ? List.of(BilibiliAtNoticeKind.values()) : List.of(named);
    }

    /**
     * 这一句点名了哪一类
     * <p>
     * 拼写优先（旧名「{@code 开播@名单}」直接说清了），首参其次（「{@code @名单 开播}」），
     * 都没点就是两类都问。
     */
    private BilibiliAtNoticeKind kindFrom(CommandContext context) {
        String typed = context.getCommand();
        for (BilibiliAtNoticeKind kind : BilibiliAtNoticeKind.values()) {
            if (listKey(kind).equals(typed)) {
                return kind;
            }
        }
        String first = context.arg(0);
        if (first != null) {
            for (BilibiliAtNoticeKind kind : BilibiliAtNoticeKind.values()) {
                if (kind.noticeName().equals(first)) {
                    return kind;
                }
            }
        }
        return null;
    }

    /**
     * 这一类的用法名，即开关与禁用清单里记的那一格
     */
    private String listKey(BilibiliAtNoticeKind kind) {
        return kind.noticeName() + "@名单";
    }

    @Override
    public List<String> usageKeys(CommandContext context) {
        String typed = context.getCommand();
        if (name().equals(typed) || aliases().contains(typed)) {
            BilibiliAtNoticeKind named = kindFrom(context);
            // 点了名只算那一格；没点名（含菜单那一路）一行盖着两格，全关了才该整条消失
            return named == null
                    ? allKeys()
                    : List.of(listKey(named));
        }
        return allKeys();
    }

    private List<String> allKeys() {
        List<String> keys = new ArrayList<>();
        for (BilibiliAtNoticeKind kind : BilibiliAtNoticeKind.values()) {
            keys.add(listKey(kind));
        }
        return keys;
    }

    @Override
    protected String streamerKeyword(CommandContext context) {
        // 类别开关不是主播名：先摘掉，剩下的第一个才是点名的那位
        List<String> args = new ArrayList<>(context.getArgs());
        args.removeIf(arg -> BilibiliAtNoticeKind.LIVE.noticeName().equals(arg)
                || BilibiliAtNoticeKind.DYNAMIC.noticeName().equals(arg));
        return args.isEmpty() ? null : args.get(0);
    }

    @Override
    protected CommandReply act(CommandContext context, PushUser streamer) {
        // 先收齐要列的人，名字一次问全：取名字这一支是要出远门的，一人一趟的话
        // 两类各 30 人就是 60 趟远门，一次回话能把群接口打穿
        List<Row> rows = rows(context, streamer);
        List<Long> toName = new ArrayList<>();
        for (Row row : rows) {
            if (!row.everyone() && !row.subscribers().isEmpty() && row.subscribers().size() <= MAX_SHOWN) {
                toName.addAll(row.subscribers());
            }
        }
        Map<Long, String> names = displayNames(context, toName);

        StringBuilder text = new StringBuilder();
        for (Row row : rows) {
            BilibiliAtNoticeKind kind = row.kind();
            if (row.everyone()) {
                if (!text.isEmpty()) {
                    text.append("\n");
                }
                text.append(kind.noticeName()).append("那一类配了 @全体成员，用不着单独订阅");
                continue;
            }

            List<Long> subscribers = row.subscribers();
            if (subscribers.isEmpty()) {
                if (!text.isEmpty()) {
                    text.append("\n");
                }
                text.append("还没有人订阅 ").append(nameOf(streamer)).append(" 的")
                        .append(kind.typeName()).append("提醒");
                continue;
            }

            if (!text.isEmpty()) {
                text.append("\n");
            }
            text.append(nameOf(streamer)).append(" 的").append(kind.typeName())
                    .append("提醒共 ").append(subscribers.size()).append(" 人订阅");

            if (subscribers.size() > MAX_SHOWN) {
                text.append("（人数较多，不逐一列出）");
                continue;
            }

            text.append("：");
            for (Long uid : subscribers) {
                text.append("\n· ").append(names.getOrDefault(uid, UNKNOWN_NAME));
            }
        }
        return CommandReply.of(text.isEmpty() ? "这一类现在看不了" : text.toString());
    }

    /**
     * 这一轮要写出来的一类名单，以及这一类要列出来的人
     *
     * @param kind 通知类别
     * @param subscribers 要列出来的人；配成 {@code @全体成员} 的那几类是空的
     * @param everyone 这一类是不是配成了 {@code @全体成员}
     */
    private record Row(BilibiliAtNoticeKind kind, List<Long> subscribers, boolean everyone) {
    }

    /**
     * 要写出来的那几类，以及每一类的人
     * <p>
     * 关掉的那一类不进这张表：列了也没人能从它订阅或退订。
     */
    private List<Row> rows(CommandContext context, PushUser streamer) {
        List<Row> rows = new ArrayList<>();
        for (BilibiliAtNoticeKind kind : kinds(context)) {
            if (settings.isDisabled(context.getPlatform(), context.getNum(), listKey(kind))) {
                continue;
            }
            // 配成 @全体成员的那一类没有「单独订阅」这回事，说清而不是列一张空名单
            if (atsEveryone(context, kind)) {
                rows.add(new Row(kind, List.of(), true));
                continue;
            }
            rows.add(new Row(kind, subscriptions.list(
                    context.getPlatform(), context.getNum(), streamer.getUid(), kind.type()), false));
        }
        return rows;
    }

    /**
     * 一次问全这批人在这个会话里的展示名
     * <p>
     * 装了哪个平台的适配器就问哪个的。取不到名字的账号不在表里，由这边写占位——
     * <b>取不到名字不是把账号写出去的理由</b>，名单是发回群里的，账号是隐私。
     * 那一支整条不顶用（没装适配器、问到一半抛了）时也一样：名单照出，名字全写占位。
     */
    private Map<Long, String> displayNames(CommandContext context, Collection<Long> uids) {
        if (uids.isEmpty()) {
            return Map.of();
        }
        SessionMemberNames service = null;
        for (SessionMemberNames candidate : memberNames) {
            if (candidate.supports(context.getPlatform())) {
                service = candidate;
                break;
            }
        }
        if (service == null) {
            return Map.of();
        }
        try {
            Map<Long, String> asked = service.displayNames(
                    context.getPlatform(), context.getType(), context.getNum(), uids);
            if (asked == null || asked.isEmpty()) {
                return Map.of();
            }
            Map<Long, String> names = new LinkedHashMap<>();
            for (Map.Entry<Long, String> entry : asked.entrySet()) {
                String name = entry.getValue() == null ? "" : entry.getValue().trim();
                if (!name.isEmpty()) {
                    names.put(entry.getKey(), name);
                }
            }
            return names;
        } catch (Throwable e) {
            // 那一支是外头的接口，抛什么都不能把整张名单带下水：照出名单，名字全写占位
            return Map.of();
        }
    }
}
