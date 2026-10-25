package org.frostnova.nova.core.command;

import org.frostnova.nova.core.model.MessagePlaceholders;
import org.frostnova.nova.core.service.StreamerProfile;
import org.frostnova.nova.core.service.StreamerProfileStore;
import org.frostnova.nova.core.service.StreamerSelfResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static org.frostnova.nova.core.service.StreamerProfile.*;

/** 固定说法仍注册为命令 Bean，只有当前确认的主播本人能匹配和执行。 */
@Configuration
public class StreamerProfileCommands {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private static final String OMIT_HELP = "可以说：别提开播时间、别提新人、别提老观众、别提 PK";
    private final StreamerProfileStore store;
    private final StreamerSelfResolver resolver;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public StreamerProfileCommands(StreamerProfileStore store, StreamerSelfResolver resolver) {
        this(store, resolver, Clock.systemUTC());
    }
    StreamerProfileCommands(StreamerProfileStore store, StreamerSelfResolver resolver, Clock clock) {
        this.store = store; this.resolver = resolver; this.clock = clock;
    }

    @Bean ScopedCommand profileTier() {
        return command("改成", List.of(), true, text -> true, (p, arg, now) -> {
            String value = normalize(arg);
            if (value.equals("3")) return new Result(p, "第 3 档还没开放，先按第 " + p.tier() + " 档发");
            if (!value.equals("1") && !value.equals("2")) return new Result(p, "可以说：改成 1、改成 2");
            int tier = Integer.parseInt(value);
            return new Result(new StreamerProfile(tier, now, p.concerns(), p.exclusions(), p.paused(), p.pauseSetAt(), p.status()),
                    "记下了：第 " + tier + " 档");
        });
    }
    @Bean ScopedCommand profileOmitSlot() { return omit(Pattern.slot_compare, "别提开播时间"); }
    @Bean ScopedCommand profileOmitNewcomer() { return omit(Pattern.newcomer_speak_return, "别提新人"); }
    @Bean ScopedCommand profileOmitRegulars() { return omit(Pattern.regulars_absent, "别提老观众"); }
    @Bean ScopedCommand profileOmitPk() { return omit(Pattern.pk_share, "别提 PK"); }
    @Bean ScopedCommand profileOmitUnknown() {
        return command("别提", List.of(), true, text -> Arrays.stream(Pattern.values()).noneMatch(p -> omitNames(p).stream()
                .anyMatch(name -> normalize(name).equals(text))), (p, arg, now) -> new Result(p, OMIT_HELP));
    }
    @Bean ScopedCommand profileConcern() {
        return command("最近在意", List.of(), true, text -> true, (p, arg, now) -> {
            if (arg.isBlank()) return new Result(p, "可以说：最近在意 新观众有没有再来");
            String text = arg.substring(0, arg.offsetByCodePoints(0, Math.min(30, arg.codePointCount(0, arg.length()))));
            var items = new ArrayList<>(p.concerns());
            items.removeIf(item -> item.text().equals(text));
            items.add(new Concern(text, now));
            return new Result(copy(p, items, p.exclusions(), p.paused(), p.pauseSetAt()), "记下了");
        });
    }
    @Bean ScopedCommand profilePause() {
        return command("先别发了", List.of(), false, text -> true, (p, arg, now) ->
                new Result(copy(p, p.concerns(), p.exclusions(), true, now), "好的，先不发了；回「恢复」就接着发"));
    }
    @Bean ScopedCommand profileResume() {
        return command("恢复", List.of(), false, text -> true, (p, arg, now) ->
                new Result(copy(p, p.concerns(), p.exclusions(), false, now), "好的"));
    }
    @Bean ScopedCommand profileRecall() {
        return command("你记得我什么", List.of(), false, text -> true, (p, arg, now) -> new Result(p, describe(p)));
    }
    @Bean ScopedCommand profileForgetAll() {
        return command("忘掉全部", List.of(), false, text -> true, (p, arg, now) ->
                new Result(copy(p, List.of(), List.of(), p.paused(), p.pauseSetAt()), "删了"));
    }
    @Bean ScopedCommand profileForget() {
        return command("忘掉", List.of(), true, text -> !text.equals("忘掉全部"), (p, arg, now) -> {
            var concerns = new ArrayList<>(p.concerns());
            var exclusions = new ArrayList<>(p.exclusions());
            String key = normalize(arg);
            boolean removed = concerns.removeIf(item -> normalize(item.text()).equals(key));
            String patternKey = key.startsWith("别提") ? key.substring(2) : key;
            removed |= exclusions.removeIf(item -> patternNames(item.type()).stream().anyMatch(name -> normalize(name).equals(patternKey)));
            return removed ? new Result(copy(p, concerns, exclusions, p.paused(), p.pauseSetAt()), "删了")
                    : new Result(p, "没找到。现有的：\n" + describe(p));
        });
    }
    private ScopedCommand omit(Pattern pattern, String name) {
        return command(name, omitNames(pattern).stream().filter(n -> !n.equals(name)).toList(), false,
                text -> true, (p, arg, now) -> {
                    var items = new ArrayList<>(p.exclusions());
                    items.removeIf(item -> item.type() == pattern);
                    items.add(new Exclusion(pattern, now));
                    return new Result(copy(p, p.concerns(), items, p.paused(), p.pauseSetAt()), "记下了：以后不提" +
                            (pattern == Pattern.pk_share ? " PK" : pattern.label()));
                });
    }
    private static List<String> omitNames(Pattern pattern) {
        return pattern.aliases().stream().map(name -> "别提" + name).toList();
    }
    private static List<String> patternNames(Pattern pattern) {
        var names = new ArrayList<>(pattern.aliases()); names.add(pattern.label()); names.add(pattern.name()); return names;
    }
    private static StreamerProfile copy(StreamerProfile p, List<Concern> concerns, List<Exclusion> exclusions, boolean paused, Long pauseSetAt) {
        return new StreamerProfile(p.tier(), p.tierSetAt(), concerns, exclusions, paused, pauseSetAt, p.status());
    }
    public static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("[\\p{P}\\p{Z}\\p{S}\\p{Cf}\\s]", "").toLowerCase(Locale.ROOT);
    }
    private static String argument(String raw, String prefix) {
        String gap = "[\\p{P}\\p{Z}\\p{S}\\p{Cf}\\s]*";
        String regex = "^" + gap + String.join(gap, prefix.split("")) + "[\\p{Z}\\s:：，,）)」』]*";
        return raw.replaceFirst(regex, "").strip();
    }
    public static String describe(StreamerProfile p) {
        var text = new StringBuilder();
        if (p.status() == Status.TEMPORARILY_UNREADABLE) return "档案暂时读不出，本次停发，请稍后重试";
        if (p.status() == Status.UNREADABLE) text.append("档案读不出，自动发送已暂停。回「恢复」可重置档案并接着发。以下是目前能读到的设置。\n");
        text.append("档位：第 ").append(p.tier()).append(" 档（").append(time(p.tierSetAt())).append("）\n")
                .append("是否暂停：").append(p.paused() ? "是" : "否").append("（").append(time(p.pauseSetAt())).append("）\n在意什么：");
        if (p.concerns().isEmpty()) text.append("无\n");
        else for (var item : p.concerns()) text.append("\n").append(MessagePlaceholders.escapeExternalText(item.text()))
                .append("（").append(time(item.setAt())).append("）");
        text.append("\n别提什么：");
        if (p.exclusions().isEmpty()) text.append("无");
        else for (var item : p.exclusions()) text.append("\n").append(item.type().label()).append("（").append(time(item.setAt())).append("）");
        return text.toString();
    }
    private static String time(Long timestamp) { return timestamp == null ? "默认，尚未设置" : TIME.format(Instant.ofEpochMilli(timestamp)); }
    private record Result(StreamerProfile profile, String reply) { }
    @FunctionalInterface private interface Action { Result apply(StreamerProfile p, String argument, long now); }

    private ScopedCommand command(String name, List<String> aliases, boolean prefix, Predicate<String> extra, Action action) {
        return new ScopedCommand() {
            public String name() { return name; }
            public List<String> aliases() { return aliases; }
            public String description() { return "设置或查看自己的主播档案"; }
            public String menuSummary() { return "档案设置：改成 1／2、别提……、最近在意……、先别发了、恢复、你记得我什么、忘掉……"; }
            public boolean groupOnly() { return false; }
            public boolean disableable() { return false; }
            public boolean availableIn(CommandContext context) { return resolver.resolve(context).isPresent(); }
            public boolean matches(CommandContext context) {
                if (!availableIn(context)) return false;
                String text = normalize(context.getRawText());
                if (!extra.test(text)) return false;
                return java.util.stream.Stream.concat(java.util.stream.Stream.of(name), aliases.stream())
                        .map(StreamerProfileCommands::normalize).anyMatch(n -> prefix ? text.startsWith(n) : text.equals(n));
            }
            public CommandReply execute(CommandContext context) {
                var owner = resolver.resolve(context);
                if (owner.isEmpty() || !matches(context)) return CommandReply.none();
                var user = owner.get();
                String arg = prefix ? argument(context.getRawText(), name) : "";
                var result = new Result[1];
                try {
                    if (name.equals("恢复")) {
                        store.resume(user.getPlatform(), user.getUid(), clock.millis());
                        return CommandReply.of("好的");
                    }
                    store.update(user.getPlatform(), user.getUid(), p -> {
                        result[0] = action.apply(p, arg, clock.millis()); return result[0].profile();
                    });
                    return CommandReply.of(result[0].reply());
                } catch (IOException failure) {
                    return CommandReply.of("档案没能保存，请稍后再试");
                }
            }
        };
    }
}
