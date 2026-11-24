package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.assistant.AssistantClient;
import org.frostnova.nova.core.assistant.AssistantException;
import org.frostnova.nova.core.assistant.AssistantLocalStore;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandFallback;
import org.frostnova.nova.core.command.CommandFollowUp;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.command.CommandReplyHint;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.ChannelSettings;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.MessagePlaceholders;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.ReportDeliveryListener;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.SessionKey;
import org.frostnova.nova.core.service.StreamerNames;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@NovaComponent
public class QqAssistantCommand implements CommandFallback, CommandFollowUp, ReportDeliveryListener {
    private static final String LIVE_PLATFORM = "bilibili";
    private static final String CHANNEL_OFF = "这里还没开智能助手问答，请管理员在控制台的推送页开启";
    private static final String BUSY = "上一个问题还在查，稍等";
    private static final String UNAVAILABLE = "智能助手暂时无法回答，请稍后再试";
    private static final String LABEL = "\n（由人工智能生成）";
    private static final String NO_FOLLOW_UP = "没有可接着问的这个编号，请用「问 〈问题〉」提问";
    private static final String ANALYSIS_NOTICE = "这个问题要多想一会儿，大约半分钟到一分钟";
    private static final String USAGE = "请用「问 〈问题〉」发送 1–300 字的问题";
    private static final String MORE_EXAMPLES = """
            问 最近 30 天各钟点平均在线
            问 上周送礼榜前十""";
    private static final int ANSWER_LIMIT = 300;
    private static final int COMPARISON_LIMIT = 5;
    private static final int ACK_SECONDS = 8;
    private static final Duration CHOICE_TTL = Duration.ofMinutes(2);
    private static final Duration FOLLOW_UP_TTL = Duration.ofMinutes(10);
    private static final Duration HISTORY_TTL = Duration.ofMinutes(30);
    private static final int REPORT_HISTORY_LIMIT = 2;
    private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(StatisticsDay.ZONE);
    private static final String CHOSEN_UID_PREFIX = "uid:";
    private static final Pattern ORDINAL = Pattern.compile("[0-9０-９]+");
    private static final Pattern RAW_ORDINAL = Pattern.compile("\\p{IsWhite_Space}*" + ORDINAL.pattern() + "\\p{IsWhite_Space}*");

    private final AssistantAskService assistant;
    private final AssistantClient client;
    private final AssistantLocalStore local;
    private final AbstractDataSource dataSource;
    private final StreamerNames names;
    private final RevenueVisibilityService visibility;
    private final CommandSettingsService settings;
    private final NovaMessageSender sender;
    private final Clock clock;
    private CommandReplyHint replyHint = CommandReplyHint.DEFAULT;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<Asker, Choice> choices = new ConcurrentHashMap<>();
    private final Map<Asker, PendingFollowUps> pendingFollowUps = new ConcurrentHashMap<>();
    private final Map<HistoryKey, List<Turn>> histories = new ConcurrentHashMap<>();
    private final Map<ReportHistoryKey, List<Report>> reportHistories = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> daemon(task, "assistant-qq"));
    private final ScheduledThreadPoolExecutor reminders =
            new ScheduledThreadPoolExecutor(1, task -> daemon(task, "assistant-qq-reminder"));

    @Autowired
    public QqAssistantCommand(AssistantAskService assistant, AssistantClient client, AssistantLocalStore local,
                              AbstractDataSource dataSource, StreamerNames names, RevenueVisibilityService visibility,
                              CommandSettingsService settings, NovaMessageSender sender) {
        this(assistant, client, local, dataSource, names, visibility, settings, sender, Clock.systemUTC());
    }

    QqAssistantCommand(AssistantAskService assistant, AssistantClient client, AssistantLocalStore local,
                       AbstractDataSource dataSource, StreamerNames names, RevenueVisibilityService visibility,
                       CommandSettingsService settings, NovaMessageSender sender, Clock clock) {
        this.assistant = assistant;
        this.client = client;
        this.local = local;
        this.dataSource = dataSource;
        this.names = names;
        this.visibility = visibility;
        this.settings = settings;
        this.sender = sender;
        this.clock = clock;
        reminders.setRemoveOnCancelPolicy(true);
        reminders.scheduleWithFixedDelay(this::evictHistory, 1, 1, TimeUnit.MINUTES);
    }

    @Autowired
    void setReplyHint(CommandReplyHint replyHint) {
        this.replyHint = replyHint;
    }

    @Override public String name() { return "问"; }
    @Override public List<String> aliases() { return List.of("问数"); }
    @Override public String usage() { return "〈问题〉"; }
    @Override public String description() { return "向智能助手问直播数据"; }
    @Override public String category() { return "数据查询"; }
    @Override public boolean groupOnly() { return false; }
    @Override public boolean available() { return client.isConfigured(); }

    @Override
    public boolean acceptsFallback(CommandContext context) {
        return available() && context.getSenderUid() != null && dataSource.getUsers(LIVE_PLATFORM).stream()
                .filter(QqAssistantCommand::activeStreamer)
                .anyMatch(user -> assistantEnabled(channel(context, user)));
    }

    @Override
    public CommandReply beforeCooldown(CommandContext context) {
        if (!available() || context.getSenderUid() == null) return CommandReply.none();
        return inFlight.contains(session(context)) ? CommandReply.of(BUSY) : null;
    }

    @Override
    public CommandReply execute(CommandContext context) {
        if (!available() || context.getSenderUid() == null) return CommandReply.none();
        String session = session(context);
        if (inFlight.contains(session)) return CommandReply.of(BUSY);

        var asker = new Asker(session, context.getSenderUid());
        var all = candidates(context);
        var allowed = all.stream().filter(Candidate::allowed).toList();

        String question = String.join(" ", context.getArgs());
        if (question.isBlank()) return CommandReply.of(allowed.isEmpty() ? CHANNEL_OFF : examples(allowed));
        int followUp = switch (question) {
            case "1", "１" -> 0;
            case "2", "２" -> 1;
            default -> -1;
        };
        if (followUp >= 0) return allowed.isEmpty() ? CommandReply.of(CHANNEL_OFF) : resumeFollowUp(context, asker, followUp);

        choices.remove(asker);
        // 序号应答也走这条路，先用原清单上的 uid 定主播，再按当前通道重验。
        String first = context.arg(0);
        boolean fromChoice = first != null && first.startsWith(CHOSEN_UID_PREFIX);
        String chosenUid = fromChoice ? first.substring(CHOSEN_UID_PREFIX.length()) : null;
        var chosen = fromChoice ? all.stream().filter(item -> item.id().equals(chosenUid)).findFirst().orElse(null) : null;
        if (fromChoice && chosen == null) return CommandReply.of("这里没有为所选主播开启智能助手问答");
        if (fromChoice) question = String.join(" ", context.getArgs().subList(1, context.getArgs().size()));
        var mentions = fromChoice ? new AssistantStreamerMentions.Mentions<>(List.of(chosen), false)
                : AssistantStreamerMentions.namedIn(question, all);
        var named = mentions.streamers();
        if (question.isBlank() || question.codePointCount(0, question.length()) > 300
                || (named.size() == 1 && question.equals(named.get(0).id())))
            return CommandReply.of(USAGE);
        if (named.stream().anyMatch(item -> !item.allowed()))
            return CommandReply.of("这里没有为所点名的主播开启智能助手问答");
        if (allowed.isEmpty()) return CommandReply.of(CHANNEL_OFF);
        if (mentions.ambiguous()) return choose(context, asker, named, question);
        if (named.size() > COMPARISON_LIMIT) return CommandReply.of("一次最多比 5 位主播，请少点几位。");

        if (named.isEmpty()) {
            if (allowed.size() == 1) return start(context, asker, allowed, question, null, null);
            var recent = lastAnsweredStreamers(context, session);
            var byUid = new LinkedHashMap<Long, Candidate>();
            allowed.forEach(item -> byUid.put(item.uid(), item));
            if (!recent.isEmpty() && byUid.keySet().containsAll(recent))
                return start(context, asker, recent.stream().map(byUid::get).toList(), question, null, null);
            return choose(context, asker, allowed, question);
        }

        String rawText = context.getRawText();
        String chosenName = fromChoice && rawText != null && RAW_ORDINAL.matcher(rawText.trim()).matches()
                ? chosen.displayName() : null;
        return start(context, asker, named, question, null, chosenName);
    }

    private String examples(List<Candidate> allowed) {
        String first = "这个月心动盲盒收了多少个";
        if (allowed.size() == 1) {
            try {
                if (!local.memos().active(LIVE_PLATFORM, allowed.get(0).uid()).isEmpty())
                    first = "主播备忘里第一项目标目前完成多少了";
            } catch (AssistantException ignored) {
                // 无法读取备忘时，仍可给出通用问法。
            }
        }
        return USAGE + "\n问 " + first + "\n" + MORE_EXAMPLES;
    }

    private CommandReply resumeFollowUp(CommandContext context, Asker asker, int index) {
        var pending = pendingFollowUps.get(asker);
        if (pending == null) return CommandReply.of(NO_FOLLOW_UP);
        var access = groupAccess(context, pending.streamers());
        if (!clock.instant().isBefore(pending.expires()) || access.closed() != null
                || access.amountVisible() != pending.amountVisible()) {
            pendingFollowUps.remove(asker, pending);
            return CommandReply.of(NO_FOLLOW_UP);
        }
        if (index >= pending.questions().size()) return CommandReply.of(NO_FOLLOW_UP);
        return start(context, asker, pending.streamers(), pending.questions().get(index), pending, null);
    }

    private List<Candidate> candidates(CommandContext context) {
        var candidates = new ArrayList<Candidate>();
        for (PushUser user : dataSource.getUsers(LIVE_PLATFORM)) {
            if (!activeStreamer(user)) continue;
            PushTarget target = channel(context, user);
            if (usable(target)) candidates.add(new Candidate(user.getUid(), names.uname(user), user.getRoomId(), target,
                    Boolean.TRUE.equals(target.getAssistantEnabled())));
        }
        candidates.sort(Comparator.comparing(Candidate::uid));
        return candidates;
    }

    private CommandReply choose(CommandContext context, Asker asker, List<Candidate> options, String question) {
        pendingFollowUps.remove(asker);
        options = options.stream().sorted(Comparator.comparing(Candidate::uid)).toList();
        Instant now = clock.instant();
        choices.values().removeIf(choice -> !now.isBefore(choice.expires()));
        choices.put(asker, new Choice(options.stream().map(Candidate::id).toList(), question, now.plus(CHOICE_TTL)));
        StringBuilder text = new StringBuilder(replyHint.replyVerb(context, "请") + "序号选一位主播（2 分钟内有效）");
        for (int i = 0; i < options.size(); i++) {
            Candidate item = options.get(i);
            text.append('\n').append(i + 1).append(". ");
            if (item.nickname() != null && !item.nickname().isBlank())
                text.append(MessagePlaceholders.escapeExternalText(item.nickname())).append(" · ");
            text.append(item.id());
        }
        return CommandReply.of(text.toString());
    }

    @Override
    public Claimed claim(CommandContext context) {
        String word = context.getCommand();
        if (context.getSenderUid() == null || !context.getArgs().isEmpty()
                || word.length() > 9 || !ORDINAL.matcher(word).matches()) return null;
        var key = new Asker(session(context), context.getSenderUid());
        Choice choice = choices.get(key);
        if (choice == null) return null;
        if (!clock.instant().isBefore(choice.expires())) {
            choices.remove(key, choice);
            return null;
        }
        int index = Integer.parseInt(word) - 1;
        if (index < 0 || index >= choice.uids().size() || !choices.remove(key, choice)) return null;
        // 保留所选 uid，即便答序号前已撤掉该主播。重跑时找不到该 uid 就不查。
        return Claimed.rerun(name(), List.of(CHOSEN_UID_PREFIX + choice.uids().get(index), choice.question()));
    }

    private CommandReply start(CommandContext context, Asker asker, List<Candidate> streamers, String question,
                               PendingFollowUps selected, String chosenName) {
        String session = asker.session();
        if (!inFlight.add(session)) return CommandReply.of(BUSY);
        if (selected == null) pendingFollowUps.remove(asker);
        else if (!pendingFollowUps.remove(asker, selected)) {
            inFlight.remove(session);
            return CommandReply.of(NO_FOLLOW_UP);
        }
        var active = new ActiveQuestion(context, asker, streamers);
        var work = new FutureTask<Void>(() -> {
            active.finish(answer(context, session, streamers, question, selected, chosenName, active));
            return null;
        });
        try {
            active.start(work);
            workers.execute(() -> {
                try { work.run(); }
                finally { inFlight.remove(session); }
            });
        } catch (RuntimeException error) {
            active.finish(null);
            inFlight.remove(session);
            if (error instanceof RejectedExecutionException) return CommandReply.of(UNAVAILABLE);
            throw error;
        }
        return CommandReply.none();
    }

    private QqAnswer answer(CommandContext context, String session, List<Candidate> streamers, String question,
                            PendingFollowUps selected, String chosenName, ActiveQuestion active) {
        try {
            var access = groupAccess(context, streamers);
            if (access.closed() != null) return closedAnswer(access.closed());
            boolean amountVisible = access.amountVisible();
            if (selected != null && (selected.amountVisible() != amountVisible || !clock.instant().isBefore(selected.expires())))
                return QqAnswer.text(NO_FOLLOW_UP);
            var source = qqSource(context);
            var uids = streamers.stream().map(Candidate::uid).toList();
            var key = new HistoryKey(session, source.account(), Set.copyOf(uids));
            var history = recentHistory(key, amountVisible);
            var texts = history.stream().filter(turn -> turn.content() != null).toList();
            var request = JSONObject.of("question", question, "history",
                    requestHistory(session, uids, amountVisible, texts));
            String previousQuestion = texts.isEmpty() ? null : texts.get(texts.size() - 1).content().getString("question");
            var recent = history.subList(Math.max(0, history.size() - AssistantAskService.HISTORY_LIMIT), history.size());
            var previousResults = recent.stream().flatMap(turn -> turn.results().stream()).toList();
            var reportChannels = new LinkedHashMap<Long, PushTarget>();
            access.channels().forEach((uid, target) -> {
                var snapshot = new PushTarget();
                BeanUtils.copyProperties(target, snapshot);
                snapshot.setRevenueVisible(visibility.isVisible(snapshot));
                reportChannels.put(uid, snapshot);
            });
            var answer = assistant.askFromQq(LIVE_PLATFORM, uids, source, request, amountVisible, reportChannels, previousResults,
                    previousQuestion, () -> active.accepted(chosenName), active::difficultyResolved);
            JSONObject result = answer.response();
            if (result.containsKey("difficulty"))
                AssistantAskService.logQuestion(log, source.entry() + " " + source.account(), LIVE_PLATFORM, uids.get(0), result);
            if (Thread.currentThread().isInterrupted()) return null;
            var current = groupAccess(context, streamers);
            if (current.closed() != null) return closedAnswer(current.closed());
            boolean answered = result.getBooleanValue("answered");
            if (amountVisible && !current.amountVisible())
                return QqAnswer.text(answered ? AssistantAggregateRequest.HIDDEN_AMOUNT : result.getString("answer"));

            if (answered || !answer.results().isEmpty()) {
                Instant now = clock.instant();
                var turns = new ArrayList<>(history);
                turns.removeIf(turn -> !current(turn.at(), now));
                var content = answered ? JSONObject.of("question", question, "answerForHistory",
                        clip(result.getString("answerForHistory"), ANSWER_LIMIT)) : null;
                turns.add(new Turn(now, uids, amountVisible, content, answer.results()));
                histories.put(key, List.copyOf(turns));
            }
            if (!answered) return QqAnswer.text(result.getString("answer"));
            String text = result.getString("answer");
            String memoNotice = result.getString("memoNotice");
            if (memoNotice != null) text = text.substring(0, text.length() - memoNotice.length() - 1);
            String chartFailure = result.getString("chartFailure");
            String tail = LABEL;
            if (chartFailure != null) {
                tail += "\n" + chartFailure;
                text = text.substring(0, text.length() - chartFailure.length() - 1);
            }
            if (memoNotice != null) tail = "\n" + memoNotice + tail;
            String budgetNotice = result.getString("budgetNotice");
            if (budgetNotice != null) tail = "\n" + budgetNotice + tail;
            var images = result.getJSONArray("images");
            int limit = ANSWER_LIMIT - tail.codePointCount(0, tail.length());
            var followUps = answer.followUps();
            String guidance = numberedGuidance(context, followUps);
            int guidanceLength = guidance.codePointCount(0, guidance.length());
            if (guidanceLength >= limit) {
                guidance = "";
                guidanceLength = 0;
                followUps = List.of();
            }
            return new QqAnswer(clip(text, limit - guidanceLength) + guidance + tail,
                    images == null ? List.of() : images.toJavaList(String.class), answer.reportImages(), amountVisible,
                    followUps.stream().map(AssistantAskService.FollowUp::question).toList());
        } catch (AssistantException error) {
            return QqAnswer.text(error.getMessage());
        } catch (RuntimeException error) {
            log.warn("QQ 智能助手问答失败", error);
            return QqAnswer.text(UNAVAILABLE);
        }
    }

    private String numberedGuidance(CommandContext context, List<AssistantAskService.FollowUp> options) {
        if (options.isEmpty()) return "";
        String replyVerb = replyHint.replyVerb(context);
        String first = "\n1. " + options.get(0).label();
        if (options.size() == 1) return "\n" + replyVerb + "「问 1」接着问" + first;
        return "\n" + replyVerb + "「问 1」或「问 2」接着问" + first + "\n2. " + options.get(1).label();
    }

    private AssistantLocalStore.QqSource qqSource(CommandContext context) {
        return local.qqSource("QQ", context.getType(), context.getNum(), context.getSenderUid());
    }

    private List<Long> lastAnsweredStreamers(CommandContext context, String session) {
        if (histories.isEmpty()) return List.of();
        String account;
        try {
            account = qqSource(context).account();
        } catch (AssistantException ignored) {
            return List.of();
        }
        Instant now = clock.instant();
        Instant latest = null;
        List<Long> streamers = List.of();
        for (var history : histories.entrySet()) {
            HistoryKey key = history.getKey();
            if (!session.equals(key.session()) || !account.equals(key.account())) continue;
            for (Turn turn : history.getValue()) {
                if (turn.content() != null && current(turn.at(), now) && (latest == null || turn.at().isAfter(latest))) {
                    latest = turn.at();
                    streamers = turn.streamers();
                }
            }
        }
        return streamers;
    }

    private List<Turn> recentHistory(HistoryKey key, boolean amountVisible) {
        Instant now = clock.instant();
        var turns = histories.getOrDefault(key, List.of());
        return turns.stream().filter(turn -> turn.amountVisible() == amountVisible && current(turn.at(), now)).toList();
    }

    @Override
    public void reportDelivered(Report report) {
        String session = SessionKey.of(report.platform(), report.type(), report.num());
        if (session == null) return;
        var key = new ReportHistoryKey(session, report.streamerPlatform(), report.uid());
        reportHistories.compute(key, (ignored, previous) -> {
            Instant now = clock.instant();
            if (!current(report.deliveredAt(), now)) return previous;
            var recent = new ArrayList<>(previous == null ? List.<Report>of() : previous);
            recent.removeIf(entry -> !current(entry.deliveredAt(), now));
            recent.add(report);
            recent.sort(Comparator.comparing(Report::deliveredAt));
            return List.copyOf(recent.subList(Math.max(0, recent.size() - REPORT_HISTORY_LIMIT), recent.size()));
        });
    }

    private List<JSONObject> requestHistory(String session, List<Long> streamers, boolean amountVisible, List<Turn> texts) {
        Instant now = clock.instant();
        boolean comparison = streamers.size() > 1;
        var reports = streamers.stream()
                .flatMap(uid -> reportHistories.getOrDefault(new ReportHistoryKey(session, LIVE_PLATFORM, uid), List.of()).stream())
                .filter(report -> current(report.deliveredAt(), now))
                .filter(report -> comparison ? amountVisible || report.summaryWithoutAmounts() != null
                        : report.amountVisible() == amountVisible).toList();
        int first = Math.max(0, texts.size() + reports.size() - AssistantAskService.HISTORY_LIMIT);
        var history = new ArrayList<HistoryEntry>();
        for (Turn turn : texts.subList(first, texts.size())) {
            history.add(new HistoryEntry(turn.at(), turn.content()));
        }
        for (Report report : reports) {
            String label = switch (report.kind()) {
                case LIVE -> "直播报告";
                case WEEKLY -> "运营周报";
            };
            String question = "（机器人在 " + REPORT_TIME.format(report.deliveredAt()) + " 推送了" + label + "）";
            String summary = amountVisible ? report.summary() : report.summaryWithoutAmounts();
            history.add(new HistoryEntry(report.deliveredAt(),
                    JSONObject.of("question", question, "answerForHistory", summary)));
        }
        history.sort(Comparator.comparing(HistoryEntry::at));
        return history.stream().map(HistoryEntry::content).toList();
    }

    private void evictHistory() {
        Instant now = clock.instant();
        pendingFollowUps.values().removeIf(pending -> !now.isBefore(pending.expires()));
        histories.forEach((key, turns) -> {
            var recent = turns.stream().filter(turn -> current(turn.at(), now)).toList();
            if (recent.isEmpty()) histories.remove(key, turns);
            else if (recent.size() < turns.size()) histories.replace(key, turns, recent);
        });
        reportHistories.forEach((key, reports) -> {
            var recent = reports.stream().filter(report -> current(report.deliveredAt(), now)).toList();
            if (recent.isEmpty()) reportHistories.remove(key, reports);
            else if (recent.size() < reports.size()) reportHistories.replace(key, reports, recent);
        });
    }

    private static boolean current(Instant at, Instant now) {
        return !now.isBefore(at) && now.isBefore(at.plus(HISTORY_TTL));
    }

    private GroupAccess groupAccess(CommandContext context, List<Candidate> streamers) {
        PushTarget primary = null;
        var channels = new LinkedHashMap<Long, PushTarget>();
        boolean amountVisible = true;
        for (Candidate streamer : streamers) {
            PushTarget target = allowedChannel(context, streamer.uid());
            if (target == null) return new GroupAccess(primary, streamer, false, Map.of());
            if (primary == null) primary = target;
            channels.put(streamer.uid(), target);
            amountVisible &= visibility.isVisible(target);
        }
        return new GroupAccess(primary, null, amountVisible, channels);
    }

    private static QqAnswer closedAnswer(Candidate streamer) {
        return QqAnswer.text(streamer.displayName() + "在这里的问答刚被关掉了，这次没有问。");
    }

    private PushTarget allowedChannel(CommandContext context, Long uid) {
        if (!available() || settings.isDisabled(context.getPlatform(), context.getType(), context.getNum(), name()))
            return null;
        return dataSource.getUser(LIVE_PLATFORM, uid)
                .filter(QqAssistantCommand::activeStreamer)
                .map(user -> channel(context, user)).filter(QqAssistantCommand::assistantEnabled).orElse(null);
    }

    private static boolean activeStreamer(PushUser user) {
        return user.getUid() != null && !Boolean.FALSE.equals(user.getEnabled());
    }

    private static boolean assistantEnabled(PushTarget target) {
        return usable(target) && Boolean.TRUE.equals(target.getAssistantEnabled());
    }

    private static boolean usable(PushTarget target) { return target != null && !Boolean.FALSE.equals(target.getEnabled()); }

    private static PushTarget channel(CommandContext context, PushUser user) {
        return ChannelSettings.target(user, context.getPlatform(), context.getType(), context.getNum());
    }

    private static String session(CommandContext context) {
        return SessionKey.require(context.getPlatform(), context.getType(), context.getNum());
    }

    private static String clip(String text, int limit) {
        if (text.codePointCount(0, text.length()) <= limit) return text;
        return text.substring(0, text.offsetByCodePoints(0, limit - 1)).stripTrailing() + "…";
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    @PreDestroy
    public void close() {
        reminders.shutdownNow();
        workers.shutdownNow();
        histories.clear();
        reportHistories.clear();
        pendingFollowUps.clear();
    }

    private final class ActiveQuestion {
        private final CommandContext context;
        private final Asker asker;
        private final List<Candidate> streamers;
        private boolean finished;
        private boolean waitingForDifficulty;
        private boolean progressDeferred;
        private Future<?> work;
        private ScheduledFuture<?> ack;
        private ScheduledFuture<?> deadline;
        private long deadlineAt;

        private ActiveQuestion(CommandContext context, Asker asker, List<Candidate> streamers) {
            this.context = context;
            this.asker = asker;
            this.streamers = streamers;
        }

        synchronized void start(Future<?> work) {
            this.work = work;
            scheduleDeadline(Duration.ofSeconds(AssistantAskService.QUESTION_TIMEOUT_SECONDS));
        }

        synchronized void accepted(String chosenName) {
            if (finished) return;
            if (streamers.size() > 1) {
                progressReply(ANALYSIS_NOTICE);
                return;
            }
            waitingForDifficulty = assistant.adaptiveThinkingEnabled();
            if (chosenName == null) {
                ack = reminders.schedule(this::progress, ACK_SECONDS, TimeUnit.SECONDS);
            } else {
                progressReply("收到，查" + chosenName);
            }
        }

        private synchronized void progress() {
            if (finished || ack == null) return;
            if (waitingForDifficulty) progressDeferred = true;
            else progressReply("收到，正在查");
        }

        synchronized void difficultyResolved(AssistantAskService.QuestionTiming timing) {
            if (finished) return;
            waitingForDifficulty = false;
            scheduleDeadline(timing.remaining());
            if (timing.analysis() && streamers.size() == 1) {
                if (ack != null) {
                    ack.cancel(false);
                    ack = null;
                }
                progressReply(ANALYSIS_NOTICE);
            } else if (progressDeferred) {
                progress();
            }
        }

        private void scheduleDeadline(Duration remaining) {
            if (deadline != null) deadline.cancel(false);
            long delay = remaining.toNanos();
            long scheduledAt = System.nanoTime() + delay;
            deadlineAt = scheduledAt;
            deadline = reminders.schedule(() -> expire(scheduledAt), delay, TimeUnit.NANOSECONDS);
        }

        private synchronized void expire(long scheduledAt) {
            if (scheduledAt == deadlineAt && finish(QqAnswer.text("智能助手本次提问已超时，请问得具体一点")))
                work.cancel(true);
        }

        synchronized boolean finish(QqAnswer answer) {
            if (finished) return false;
            finished = true;
            if (ack != null) ack.cancel(false);
            if (deadline != null) deadline.cancel(false);
            if (answer != null) {
                var access = groupAccess(context, streamers);
                if (access.closed() != null) {
                    var target = access.primary() == null ? streamers.get(0).target() : access.primary();
                    send(closedAnswer(access.closed()), target, false, Map.of());
                } else {
                    send(answer, access.primary(), access.amountVisible(), access.channels());
                }
            }
            return true;
        }

        private void progressReply(String text) {
            var access = groupAccess(context, streamers);
            if (access.closed() == null) send(QqAnswer.text(text), access.primary(), access.amountVisible(), access.channels());
        }

        private void send(QqAnswer answer, PushTarget target, boolean amountVisible, Map<Long, PushTarget> channels) {
            boolean hidden = answer.amountVisible() && !amountVisible;
            String text = hidden ? AssistantAggregateRequest.HIDDEN_AMOUNT : answer.text();
            String content = MessagePlaceholders.escapeExternalText(text);
            if (!hidden) {
                for (String image : answer.images()) content += "{next}" + CommandReply.image(image).content();
            }
            for (var image : answer.reportImages()) {
                PushTarget channel = channels.get(image.uid());
                if (channel != null && (!image.amountVisible() || visibility.isVisible(channel)))
                    content += "{next}" + CommandReply.image(image.png()).content();
            }
            Message.create(context.getPlatform(), context.getType(), context.getNum(), content).forEach(message -> {
                        message.setChannel(target);
                        message.setReply(true);
                        message.setReplyTo(context.getMessageId());
                        sender.send(message);
                    });
            if (amountVisible == answer.amountVisible() && !answer.followUps().isEmpty())
                pendingFollowUps.put(asker, new PendingFollowUps(streamers, amountVisible,
                        clock.instant().plus(FOLLOW_UP_TTL), answer.followUps()));
        }
    }

    private record Candidate(Long uid, String nickname, Long roomId, PushTarget target, boolean allowed)
            implements AssistantStreamerMentions.Candidate {
        String id() { return uid.toString(); }
        String displayName() { return nickname == null || nickname.isBlank() ? id() : nickname; }
    }
    private record Asker(String session, long sender) { }
    private record Choice(List<String> uids, String question, Instant expires) { }
    private record GroupAccess(PushTarget primary, Candidate closed, boolean amountVisible, Map<Long, PushTarget> channels) { }
    private record PendingFollowUps(List<Candidate> streamers, boolean amountVisible, Instant expires, List<String> questions) { }
    private record HistoryKey(String session, String account, Set<Long> streamers) { }
    private record Turn(Instant at, List<Long> streamers, boolean amountVisible, JSONObject content,
                        List<AssistantChartResults.Result> results) { }
    private record ReportHistoryKey(String session, String platform, Long uid) { }
    private record HistoryEntry(Instant at, JSONObject content) { }
    private record QqAnswer(String text, List<String> images, List<AssistantLiveReports.Image> reportImages,
                            boolean amountVisible, List<String> followUps) {
        static QqAnswer text(String text) { return text == null ? null : new QqAnswer(text, List.of(), List.of(), false, List.of()); }
    }
}
