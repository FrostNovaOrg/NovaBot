package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.assistant.AssistantClient;
import org.frostnova.nova.core.assistant.AssistantException;
import org.frostnova.nova.core.assistant.AssistantLocalStore;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandFallback;
import org.frostnova.nova.core.command.CommandFollowUp;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.ChannelSettings;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.MessagePlaceholders;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.SessionKey;
import org.frostnova.nova.core.service.StreamerNames;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

@Slf4j
@NovaComponent
public class QqAssistantCommand implements CommandFallback, CommandFollowUp {
    private static final String LIVE_PLATFORM = "bilibili";
    private static final String CHANNEL_OFF = "这里还没开智能助手问答，请管理员在控制台的推送页开启";
    private static final String BUSY = "上一个问题还在查，稍等";
    private static final String UNAVAILABLE = "智能助手暂时无法回答，请稍后再试";
    private static final String LABEL = "\n（由人工智能生成）";
    private static final String NO_FOLLOW_UP = "没有可接着问的这个编号，请用「问 〈问题〉」提问";
    private static final String USAGE = "请用「问 〈问题〉」发送 1–300 字的问题";
    private static final String MORE_EXAMPLES = """
            问 最近 30 天各钟点平均在线
            问 上周送礼榜前十""";
    private static final int ANSWER_LIMIT = 300;
    private static final int ACK_SECONDS = 8;
    private static final Duration CHOICE_TTL = Duration.ofMinutes(2);
    private static final Duration HISTORY_TTL = Duration.ofMinutes(10);
    private static final int HISTORY_LIMIT = 3;
    private static final String CHOSEN_UID_PREFIX = "uid:";
    private static final Pattern NUMBER = Pattern.compile("[0-9]+");
    private static final Pattern ORDINAL = Pattern.compile("[0-9０-９]+");
    private static final Pattern RAW_ORDINAL = Pattern.compile("\\p{IsWhite_Space}*" + ORDINAL.pattern() + "\\p{IsWhite_Space}*");
    private static final Pattern NON_HAN_EDGES = Pattern.compile("^[^\\p{IsHan}]+|[^\\p{IsHan}]+\\z");
    private static final Pattern QUESTION_PREFIX = Pattern.compile("^[\\p{IsWhite_Space}\\p{P}]+");
    private static final Set<String> QUERY_WORDS = Set.of(
            "最近", "近期", "上周", "本周", "这周", "上月", "本月", "这个", "今天", "昨天", "昨晚", "前天", "今晚",
            "请问", "帮我", "看看", "查查", "我想",
            "统计", "对比", "比较", "哪天", "哪场", "什么", "怎么", "多少", "每天", "每周", "过去",
            "一周", "两周", "三周", "四周",
            "流水", "收入", "直播", "弹幕", "观众", "粉丝", "礼物", "在线", "在線", "开播", "下播", "场次", "时间", "时长",
            "数据", "情况", "变化", "趋势", "平均", "高峰", "人数", "排行", "排名",
            "上个月", "这个月", "个数", "合计", "心动盲盒", "盲盒", "钟点", "各钟点平均在线", "平均在线", "送礼榜前十", "前十",
            "主播备忘", "备忘", "第一项", "目标", "目前", "完成", "收了", "播了", "多久", "几点", "几场", "怎么样");
    private static final Comparator<NameMatch> NAME_MATCH_PRIORITY = Comparator.comparingInt(NameMatch::length)
            .thenComparing(NameMatch::exact).reversed();

    private final AssistantAskService assistant;
    private final AssistantClient client;
    private final AssistantLocalStore local;
    private final AbstractDataSource dataSource;
    private final StreamerNames names;
    private final RevenueVisibilityService visibility;
    private final CommandSettingsService settings;
    private final NovaMessageSender sender;
    private final Clock clock;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<Asker, Choice> choices = new ConcurrentHashMap<>();
    private final Map<Asker, PendingFollowUps> pendingFollowUps = new ConcurrentHashMap<>();
    private final Map<HistoryKey, List<Turn>> histories = new ConcurrentHashMap<>();
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
        if (allowed.isEmpty()) return CommandReply.of(CHANNEL_OFF);

        String question = String.join(" ", context.getArgs());
        if (question.isBlank()) return CommandReply.of(examples(allowed));
        if (question.equals("1") || question.equals("１")) return resumeFollowUp(context, asker, 0);
        if (question.equals("2") || question.equals("２")) return resumeFollowUp(context, asker, 1);

        choices.remove(asker);
        // 序号应答也走这条路，先用原清单上的 uid 定主播，再按当前通道重验。
        String first = context.arg(0);
        boolean fromChoice = first != null && first.startsWith(CHOSEN_UID_PREFIX);
        String leadingUid = fromChoice ? first.substring(CHOSEN_UID_PREFIX.length()) : first;
        Candidate leading = all.stream().filter(item -> item.id().equals(leadingUid)).findFirst().orElse(null);
        if (fromChoice && leading == null) return CommandReply.of("这里没有为所选主播开启智能助手问答");
        List<Candidate> named = leading == null ? namedIn(question, all) : List.of(leading);
        if (leading != null) question = String.join(" ", context.getArgs().subList(1, context.getArgs().size()));
        if (question.isBlank() || question.codePointCount(0, question.length()) > 300)
            return CommandReply.of(USAGE);
        if (named.stream().anyMatch(item -> !item.allowed()))
            return CommandReply.of("这里没有为所点名的主播开启智能助手问答");

        var options = named.isEmpty() ? allowed : named;
        if (options.size() > 1) {
            pendingFollowUps.remove(asker);
            return choose(asker, options, question);
        }
        Candidate streamer = options.get(0);
        String rawText = context.getRawText();
        String chosenName = fromChoice && rawText != null && RAW_ORDINAL.matcher(rawText.trim()).matches()
                ? streamer.displayName() : null;
        return start(context, asker, streamer.uid(), question, null, chosenName);
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
        var target = allowedChannel(context, pending.uid());
        if (!clock.instant().isBefore(pending.expires()) || target == null || visibility.isVisible(target) != pending.amountVisible()) {
            pendingFollowUps.remove(asker, pending);
            return CommandReply.of(NO_FOLLOW_UP);
        }
        if (index >= pending.questions().size()) return CommandReply.of(NO_FOLLOW_UP);
        return start(context, asker, pending.uid(), pending.questions().get(index), pending, null);
    }

    private List<Candidate> candidates(CommandContext context) {
        var candidates = new ArrayList<Candidate>();
        for (PushUser user : dataSource.getUsers(LIVE_PLATFORM)) {
            if (!activeStreamer(user)) continue;
            PushTarget target = channel(context, user);
            if (usable(target)) candidates.add(new Candidate(user.getUid(), names.uname(user), user.getRoomId(),
                    Boolean.TRUE.equals(target.getAssistantEnabled())));
        }
        candidates.sort(Comparator.comparing(Candidate::uid));
        return candidates;
    }

    private static List<Candidate> namedIn(String question, List<Candidate> candidates) {
        var prefix = QUESTION_PREFIX.matcher(question);
        int questionStart = prefix.lookingAt() ? prefix.end() : 0;
        var numbers = NUMBER.matcher(question).results().toList();
        var matches = new ArrayList<NameMatch>();
        for (Candidate item : candidates) {
            if (item.nickname() != null && !item.nickname().isBlank()) {
                addNameMatches(question, item.nickname(), item, true, matches);
                addShortNameMatches(question, questionStart, item, matches);
            }
            String uid = item.id();
            String roomId = item.roomId() == null ? null : item.roomId().toString();
            for (MatchResult number : numbers) {
                String digits = number.group();
                if (digits.equals(uid) || digits.equals(roomId)) {
                    matches.add(new NameMatch(number.start(), number.end(), digits.length(), true, item));
                }
            }
        }

        matches.sort(NAME_MATCH_PRIORITY);
        // 每个位置记录已保留的最高优先级，同级命中可以并存。
        var covered = new NameMatch[question.length()];
        var named = new HashSet<Candidate>();
        for (NameMatch match : matches) {
            if (match.overlapsStrongerMatch(covered)) continue;
            Arrays.fill(covered, match.start(), match.end(), match);
            named.add(match.candidate());
        }
        return candidates.stream().filter(named::contains).toList();
    }

    private static void addNameMatches(String question, String name, Candidate item, boolean exact,
                                       List<NameMatch> matches) {
        int length = name.codePointCount(0, name.length());
        for (int start = question.indexOf(name); start >= 0; start = question.indexOf(name, start + 1)) {
            matches.add(new NameMatch(start, start + name.length(), length, exact, item));
        }
    }

    private static void addShortNameMatches(String question, int questionStart, Candidate item, List<NameMatch> matches) {
        String stem = NON_HAN_EDGES.matcher(item.nickname()).replaceAll("");
        int hanCount = 0;
        for (int end = 0; end < stem.length(); ) {
            int codePoint = stem.codePointAt(end);
            end += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) continue;
            hanCount++;
            if (hanCount < 2) continue;
            String prefix = stem.substring(0, end);
            if (prefix.equals(item.nickname()) || (hanCount == 2 && QUERY_WORDS.contains(prefix))) continue;
            if (hanCount == 2 && end < stem.length()) {
                if (question.startsWith(prefix, questionStart)) {
                    matches.add(new NameMatch(questionStart, questionStart + end,
                            prefix.codePointCount(0, end), false, item));
                }
            } else {
                addNameMatches(question, prefix, item, false, matches);
            }
        }
    }

    private CommandReply choose(Asker asker, List<Candidate> options, String question) {
        Instant now = clock.instant();
        choices.values().removeIf(choice -> !now.isBefore(choice.expires()));
        choices.put(asker, new Choice(options.stream().map(Candidate::id).toList(), question, now.plus(CHOICE_TTL)));
        StringBuilder text = new StringBuilder("请回序号选一位主播（2 分钟内有效）");
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

    private CommandReply start(CommandContext context, Asker asker, Long uid, String question, PendingFollowUps selected,
                               String chosenName) {
        String session = asker.session();
        if (!inFlight.add(session)) return CommandReply.of(BUSY);
        if (selected == null) pendingFollowUps.remove(asker);
        else if (!pendingFollowUps.remove(asker, selected)) {
            inFlight.remove(session);
            return CommandReply.of(NO_FOLLOW_UP);
        }
        var active = new ActiveQuestion(context, asker, uid);
        var work = new FutureTask<Void>(() -> {
            active.finish(answer(context, session, uid, question, selected));
            return null;
        });
        try {
            if (chosenName == null) {
                active.ack = reminders.schedule(() -> active.acknowledge("收到，正在查"), ACK_SECONDS, TimeUnit.SECONDS);
            } else {
                active.acknowledge("收到，查" + chosenName);
            }
            active.deadline = reminders.schedule(() -> {
                if (active.finish(QqAnswer.text("智能助手本次提问已超时，请问得具体一点"))) work.cancel(true);
            }, AssistantAskService.QUESTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

    private QqAnswer answer(CommandContext context, String session, Long uid, String question, PendingFollowUps selected) {
        try {
            PushTarget target = allowedChannel(context, uid);
            if (target == null) return null;
            boolean amountVisible = visibility.isVisible(target);
            if (selected != null && (selected.amountVisible() != amountVisible || !clock.instant().isBefore(selected.expires())))
                return QqAnswer.text(NO_FOLLOW_UP);
            var source = local.qqSource("QQ", context.getPlatform(), context.getType(), context.getNum(), context.getSenderUid());
            var key = new HistoryKey(session, source.account(), uid);
            var history = recentHistory(key, amountVisible);
            var texts = history.stream().filter(turn -> turn.content() != null).map(Turn::content).toList();
            var request = JSONObject.of("question", question, "history", texts.subList(Math.max(0, texts.size() - HISTORY_LIMIT), texts.size()));
            var previousResults = history.stream().flatMap(turn -> turn.results().stream()).toList();
            var answer = assistant.askFromQq(LIVE_PLATFORM, uid, source, request, amountVisible, previousResults);
            JSONObject result = answer.response();
            PushTarget current = allowedChannel(context, uid);
            if (current == null || Thread.currentThread().isInterrupted()) return null;
            boolean answered = result.getBooleanValue("answered");
            if (amountVisible && !visibility.isVisible(current))
                return QqAnswer.text(answered ? AssistantAggregateRequest.HIDDEN_AMOUNT : result.getString("answer"));

            if (answered || !answer.results().isEmpty()) {
                var turns = new ArrayList<>(history);
                var content = answered ? JSONObject.of("question", question, "answerForHistory",
                        clip(result.getString("answerForHistory"), ANSWER_LIMIT)) : null;
                turns.add(new Turn(clock.instant(), amountVisible, content, answer.results()));
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
            var images = result.getJSONArray("images");
            int limit = ANSWER_LIMIT - tail.codePointCount(0, tail.length());
            var followUps = answer.followUps();
            String guidance = numberedGuidance(followUps);
            int guidanceLength = guidance.codePointCount(0, guidance.length());
            if (guidanceLength >= limit) {
                guidance = "";
                guidanceLength = 0;
                followUps = List.of();
            }
            return new QqAnswer(clip(text, limit - guidanceLength) + guidance + tail,
                    images == null ? List.of() : images.toJavaList(String.class), amountVisible,
                    followUps.stream().map(AssistantAskService.FollowUp::question).toList());
        } catch (AssistantException error) {
            return QqAnswer.text(error.getMessage());
        } catch (RuntimeException error) {
            log.warn("QQ 智能助手问答失败", error);
            return QqAnswer.text(UNAVAILABLE);
        }
    }

    private static String numberedGuidance(List<AssistantAskService.FollowUp> options) {
        if (options.isEmpty()) return "";
        String first = "\n1. " + options.get(0).label();
        if (options.size() == 1) return "\n回「问 1」接着问" + first;
        return "\n回「问 1」或「问 2」接着问" + first + "\n2. " + options.get(1).label();
    }

    private List<Turn> recentHistory(HistoryKey key, boolean amountVisible) {
        Instant now = clock.instant();
        var turns = histories.getOrDefault(key, List.of());
        return turns.stream().filter(turn -> turn.amountVisible() == amountVisible && current(turn, now)).toList();
    }

    private void evictHistory() {
        Instant now = clock.instant();
        pendingFollowUps.values().removeIf(pending -> !now.isBefore(pending.expires()));
        histories.forEach((key, turns) -> {
            var recent = turns.stream().filter(turn -> current(turn, now)).toList();
            if (recent.isEmpty()) histories.remove(key, turns);
            else if (recent.size() < turns.size()) histories.replace(key, turns, recent);
        });
    }

    private static boolean current(Turn turn, Instant now) {
        return !now.isBefore(turn.at()) && now.isBefore(turn.at().plus(HISTORY_TTL));
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
        pendingFollowUps.clear();
    }

    private final class ActiveQuestion {
        private final CommandContext context;
        private final Asker asker;
        private final Long uid;
        private boolean finished;
        private ScheduledFuture<?> ack;
        private ScheduledFuture<?> deadline;

        private ActiveQuestion(CommandContext context, Asker asker, Long uid) {
            this.context = context;
            this.asker = asker;
            this.uid = uid;
        }

        synchronized void acknowledge(String text) {
            if (!finished) send(QqAnswer.text(text));
        }

        synchronized boolean finish(QqAnswer answer) {
            if (finished) return false;
            finished = true;
            if (ack != null) ack.cancel(false);
            if (deadline != null) deadline.cancel(false);
            if (answer != null) send(answer);
            return true;
        }

        private void send(QqAnswer answer) {
            PushTarget target = allowedChannel(context, uid);
            if (target == null) return;
            boolean amountVisible = visibility.isVisible(target);
            boolean hidden = answer.amountVisible() && !amountVisible;
            String text = hidden ? AssistantAggregateRequest.HIDDEN_AMOUNT : answer.text();
            String content = MessagePlaceholders.escapeExternalText(text);
            if (!hidden) {
                for (String image : answer.images()) content += "{next}" + CommandReply.image(image).content();
            }
            Message.create(context.getPlatform(), context.getType(), context.getNum(), content).forEach(message -> {
                        message.setChannel(target);
                        message.setReply(true);
                        sender.send(message);
                    });
            if (amountVisible == answer.amountVisible() && !answer.followUps().isEmpty())
                pendingFollowUps.put(asker, new PendingFollowUps(uid, amountVisible, clock.instant().plus(HISTORY_TTL), answer.followUps()));
        }
    }

    private record Candidate(Long uid, String nickname, Long roomId, boolean allowed) {
        String id() { return uid.toString(); }
        String displayName() { return nickname == null || nickname.isBlank() ? id() : nickname; }
    }
    private record NameMatch(int start, int end, int length, boolean exact, Candidate candidate) {
        boolean overlapsStrongerMatch(NameMatch[] covered) {
            for (int i = start; i < end; i++) {
                if (covered[i] != null && NAME_MATCH_PRIORITY.compare(covered[i], this) < 0) return true;
            }
            return false;
        }
    }
    private record Asker(String session, long sender) { }
    private record Choice(List<String> uids, String question, Instant expires) { }
    private record PendingFollowUps(Long uid, boolean amountVisible, Instant expires, List<String> questions) { }
    private record HistoryKey(String session, String account, Long uid) { }
    private record Turn(Instant at, boolean amountVisible, JSONObject content, List<AssistantChartResults.Result> results) { }
    private record QqAnswer(String text, List<String> images, boolean amountVisible, List<String> followUps) {
        static QqAnswer text(String text) { return text == null ? null : new QqAnswer(text, List.of(), false, List.of()); }
    }
}
