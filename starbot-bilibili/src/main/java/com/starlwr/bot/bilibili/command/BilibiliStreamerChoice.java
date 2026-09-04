package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.BilibiliPlatform;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandFollowUp;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.model.LiveSession;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveSessionArchive;
import com.starlwr.bot.core.service.StarBotStateStore;
import com.starlwr.bot.core.util.StringUtil;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多主播的「先猜、再问、再记住」
 * <p>
 * 一个群里配着好几位主播时，「直播间数据」这四个字说的是谁？此前的答复是把人名列一遍
 * 让使用者重发一次，而重发时还得把名字打对。这里换成三步：
 * <ol>
 *     <li><b>先猜</b>：这个人上次选过谁，这次还是谁；没选过而只有一位在播，那就是那一位。</li>
 *     <li><b>再问</b>：猜不出来才问，而且问成一份带序号的清单——回一个数字比重打一遍名字容易得多。
 *         在播的排最前，今天播过的其次，剩下的折起来，因为要找的十有八九在前几位。</li>
 *     <li><b>再记住</b>：选过一次就记着，直到这个人自己点名换人。记的是「这个会话里的这个人」，
 *         不是整个群——同一个群里两个人各看各的主播是常事。</li>
 * </ol>
 *
 * <h2>序号在追问发出的那一刻就定死</h2>
 * 清单连同序号一起存进那次追问里，而不是等回答到了再排一遍。理由是排序的依据会动：
 * 追问与回答之间隔着的那一两分钟里，某位主播开播了，重排出来的第 2 位就换了人——
 * 使用者照着自己看到的清单回了个 2，收到的是另一位主播的数据，<b>而那张图看起来完全正常</b>。
 */
@Slf4j
@StarBotComponent
public class BilibiliStreamerChoice implements CommandFollowUp {
    /**
     * 「记住的选择」在状态存储中的命名空间
     */
    private static final String NAMESPACE = "StreamerChoice";

    /**
     * 追问的有效期。长到够人读完清单再打个数字，短到不会在半小时后被一句「3」突然翻出来
     */
    private static final Duration PENDING_TTL = Duration.ofMinutes(2);

    /**
     * 清单默认展示几位
     * <p>
     * 折叠的界线取「在播与今天播过」这两档，但这两档很可能一个人都没有——夜里问的时候
     * 通常就是这样。那时若照章折叠，回出去的清单会一位都不列，而这份清单存在的意义
     * 正是让人挑一位。所以两档之外再兜一个下限。
     */
    private static final int DEFAULT_SHOWN = 5;

    /**
     * 展开清单的口令
     */
    private static final String EXPAND = "全部";

    /**
     * 序号的最大位数。主播 uid 也是纯数字，但它有八位以上，落不进清单的范围
     */
    private static final int MAX_INDEX_DIGITS = 3;

    /**
     * 直播数据所用的时区，与报告里的时间显示一致
     */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final LiveDataService liveDataService;

    private final LiveSessionArchive archive;

    private final StarBotStateStore store;

    /**
     * 时钟。追问那两分钟的窗口只有靠它才量得到——真等两分钟的测试没人会跑第二遍
     */
    private final Clock clock;

    /**
     * 尚未作答的追问，按「会话里的某个人」记
     * <p>
     * 只放在内存里：它活两分钟，而重启一次就得两分钟以上，落盘存的是一份必然已经过期的东西
     */
    private final Map<String, Pending> pendings = new ConcurrentHashMap<>();

    @Autowired
    public BilibiliStreamerChoice(LiveDataService liveDataService, LiveSessionArchive archive,
                                  StarBotStateStore store) {
        this(liveDataService, archive, store, Clock.systemDefaultZone());
    }

    BilibiliStreamerChoice(LiveDataService liveDataService, LiveSessionArchive archive,
                           StarBotStateStore store, Clock clock) {
        this.liveDataService = liveDataService;
        this.archive = archive;
        this.store = store;
        this.clock = clock;
    }

    /**
     * 把候选主播排序并分档
     * @param candidates 本会话配置了推送的主播
     * @return 排过序的候选
     */
    public Ranked rank(@NonNull List<PushUser> candidates) {
        long dayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant().toEpochMilli();
        Set<Long> archivedToday = archivedToday(dayStart);

        List<PushUser> living = new ArrayList<>();
        List<PushUser> today = new ArrayList<>();
        List<PushUser> rest = new ArrayList<>();

        for (PushUser user : candidates) {
            if (liveDataService.getLiveStatus(BilibiliPlatform.BILIBILI.id(), user.getUid()).orElse(false)) {
                living.add(user);
            } else if (playedToday(user, archivedToday, dayStart)) {
                today.add(user);
            } else {
                rest.add(user);
            }
        }

        List<PushUser> ordered = new ArrayList<>(living);
        ordered.addAll(today);
        ordered.addAll(rest);

        List<String> labels = new ArrayList<>();
        living.forEach(user -> labels.add("· 直播中"));
        today.forEach(user -> labels.add("· 今天播过"));
        rest.forEach(user -> labels.add(""));

        return new Ranked(List.copyOf(ordered), List.copyOf(labels), living.size(), living.size() + today.size());
    }

    /**
     * 归档里今天开播的那些主播
     */
    private Set<Long> archivedToday(long dayStart) {
        Set<Long> uids = new HashSet<>();
        for (LiveSession session : archive.find(dayStart, Long.MAX_VALUE)) {
            if (session.uid() != null) {
                uids.add(session.uid());
            }
        }
        return uids;
    }

    /**
     * 今天播过没有
     * <p>
     * 两处来源取<b>并集</b>，各自都有够不着的地方：
     * <ul>
     *     <li>场次归档按<b>开播时刻</b>归属日期，跨零点的那一场记在昨天，今天问它答不上来；</li>
     *     <li>「最近一场的结束时刻」补得上跨零点那一场，但它只记得最近一场，
     *         而且直播数据被清空过就没了——归档不会。</li>
     * </ul>
     */
    private boolean playedToday(PushUser user, Set<Long> archivedToday, long dayStart) {
        return archivedToday.contains(user.getUid())
                || liveDataService.getLiveEndTime(BilibiliPlatform.BILIBILI.id(), user.getUid())
                        .filter(end -> end >= dayStart).isPresent();
    }

    /**
     * 这个人在这个会话里上次选的是谁
     * @param context 执行上下文
     * @param candidates 当前候选；选过的那位若已不在其中，视为没选过
     * @return 上次选中的主播
     */
    public Optional<PushUser> remembered(@NonNull CommandContext context, @NonNull List<PushUser> candidates) {
        String key = key(context);
        Long uid = store.read(NAMESPACE, key, data -> data.getLong(key)).orElse(null);
        if (uid == null) {
            return Optional.empty();
        }
        return candidates.stream().filter(user -> uid.equals(user.getUid())).findFirst();
    }

    /**
     * 记住这个人在这个会话里选的是谁
     * @param context 执行上下文
     * @param uid 主播 uid
     */
    public void remember(@NonNull CommandContext context, @NonNull Long uid) {
        String key = key(context);
        store.write(NAMESPACE, data -> data.put(key, uid));
    }

    /**
     * 发起一次追问，并给出问出去的那句话
     * <p>
     * 同一个人的上一次追问就此作废：清单已经重排过，拿旧序号来答就是答错人。
     * @param context 执行上下文
     * @param ranked 排过序的候选
     * @return 要回给使用者的那句话
     */
    public String ask(@NonNull CommandContext context, @NonNull Ranked ranked) {
        List<String> lines = new ArrayList<>();
        List<Long> uids = new ArrayList<>();
        for (int i = 0; i < ranked.ordered().size(); i++) {
            PushUser user = ranked.ordered().get(i);
            lines.add((i + 1) + ". " + display(user) + "（" + user.getUid() + "）" + ranked.labels().get(i));
            uids.add(user.getUid());
        }

        Pending pending = new Pending(List.copyOf(uids), List.copyOf(lines), shown(ranked),
                display(ranked.ordered().get(0)), context.getCommand(), List.copyOf(context.getArgs()),
                clock.instant().plus(PENDING_TTL), false);

        sweep();
        pendings.put(key(context), pending);
        return render(pending, false);
    }

    /**
     * 清单展示几位：两档之外兜一个下限，剩下的折起来。只剩一位可折时不折——
     * 「还有 1 位，回「全部」展开」比直接把那一位列出来还长
     */
    private int shown(Ranked ranked) {
        int total = ranked.ordered().size();
        int shown = Math.min(total, Math.max(ranked.tiered(), DEFAULT_SHOWN));
        return total - shown <= 1 ? total : shown;
    }

    /**
     * 排出问句
     * @param expanded 是否展开全部
     */
    private String render(Pending pending, boolean expanded) {
        int shown = expanded ? pending.lines().size() : pending.shown();

        StringBuilder text = new StringBuilder("本会话配了 " + pending.lines().size()
                + " 位主播，回序号选一位（" + PENDING_TTL.toMinutes() + " 分钟内有效）：\n");
        text.append(String.join("\n", pending.lines().subList(0, shown)));

        if (shown < pending.lines().size()) {
            text.append("\n还有 ").append(pending.lines().size() - shown)
                    .append(" 位，回「").append(EXPAND).append("」展开");
        }
        text.append("\n选过一次就记住了，要换人直接说名字：")
                .append(pending.command()).append(" ").append(pending.example());
        return text.toString();
    }

    @Override
    public Claimed claim(CommandContext context) {
        String key = key(context);
        Pending pending = pendings.get(key);
        if (pending == null) {
            return null;
        }
        if (!clock.instant().isBefore(pending.expiresAt())) {
            // 过期的追问就地清掉，免得它一直占着这个人的位置
            pendings.remove(key);
            return null;
        }

        String word = context.getCommand();
        if (EXPAND.equals(word)) {
            // 展开只做一次：再做也无非是同一份清单，而每做一次就多绕过一次冷却
            if (pending.expanded()) {
                return null;
            }
            pendings.put(key, pending.expand());
            return Claimed.answer(CommandReply.of(render(pending, true)));
        }

        int index = number(word);
        if (index < 1 || index > pending.uids().size()) {
            // 不是序号就当没听见，交还给分发器照常处理。<b>尤其不能把它夹到范围里去</b>：
            // 把「99」收成最后一位，回出去的是另一位主播的数据，而那张图看起来完全正常
            return null;
        }

        // 认领即消费：一个序号能重放的话，隔十分钟的一句「3」会把这个人的选择改掉
        pendings.remove(key);
        Long chosen = pending.uids().get(index - 1);
        remember(context, chosen);
        log.info("会话 {} 中 {} 回了序号 {}, 按主播 {} 重跑命令 {}",
                context.getNum(), context.getSenderUid(), index, chosen, pending.command());
        return Claimed.rerun(pending.command(), pending.args());
    }

    /**
     * 把一个词读成序号，读不出来时为 0
     */
    private int number(String word) {
        if (StringUtil.isBlank(word) || word.length() > MAX_INDEX_DIGITS
                || !word.chars().allMatch(Character::isDigit)) {
            return 0;
        }
        return Integer.parseInt(word);
    }

    /**
     * 清掉已经过期的追问
     * <p>
     * 每发起一次追问顺手扫一遍：这张表按「会话里的人」记，不扫的话，来过一次的人会一直留在表里
     */
    private void sweep() {
        Instant now = clock.instant();
        pendings.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }

    /**
     * 键为「平台:会话类型:会话号:发送者」
     * <p>
     * 会话类型进键的理由与命令冷却相同：群号与好友账号取自两个互不相干的号段，撞号时会互相顶掉。
     * 发送者也进键：同一个群里两个人各看各的主播是常事，按群记的话，后选的那个人把先选的顶掉了
     */
    private String key(CommandContext context) {
        return context.getPlatform() + ":" + context.getType().getCode() + ":"
                + context.getNum() + ":" + context.getSenderUid();
    }

    private static String display(PushUser user) {
        return StringUtil.isBlank(user.getUname()) ? String.valueOf(user.getUid()) : user.getUname();
    }

    /**
     * 排过序的候选主播
     *
     * @param ordered 在播、今天播过、其余，档内保持原有顺序
     * @param labels 与 ordered 一一对应的档位标注，没有标注时为空串
     * @param living 其中正在直播的位数，即 ordered 最前面这几位
     * @param tiered 在播与今天播过合起来的位数
     */
    public record Ranked(List<PushUser> ordered, List<String> labels, int living, int tiered) {
    }

    /**
     * 一次尚未作答的追问
     *
     * @param uids 清单里各序号对应的主播，<b>问出去的那一刻就定死</b>
     * @param lines 清单的各行，同样定死；重排会让同一个序号换人
     * @param shown 折叠前展示几位
     * @param example 例句里用的那位主播的展示名
     * @param command 追问是哪条命令引起的，答完接着跑它
     * @param args 那条命令原本的参数
     * @param expiresAt 失效时刻
     * @param expanded 是否已经展开过
     */
    private record Pending(List<Long> uids, List<String> lines, int shown, String example, String command,
                           List<String> args, Instant expiresAt, boolean expanded) {
        Pending expand() {
            return new Pending(uids, lines, shown, example, command, args, expiresAt, true);
        }
    }
}
