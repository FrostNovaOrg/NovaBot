package org.frostnova.nova.core.service;

import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandFollowUp;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.command.StreamerProfileCommands;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.MessagePlaceholders;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 在小结调度中先问档位；普通聊天不消耗持久保存的选档机会。 */
@Slf4j
@Service
@Order(100)
public class SessionRecapGreetingService implements CommandFollowUp {
    private static final String GREETING = """
            你好，我是 Nova。每场下播后我可以给你发一条小结，你想要哪种？
            1　只要小结
            2　小结 + 从你过去直播里看到的规律（不回就按这个发）
            最近最在意什么也可以告诉我（比如涨粉、留住新人），我会多看这方面。
            以后随时回「改成 1」「别提开播时间」「先别发了」都行""";
    private static final Pattern CHOICE = Pattern.compile("^[\\p{Z}\\s]*([1-3１-３])"
            + "(?:(?:[\\p{P}\\p{Z}\\s]+|[\\p{S}\\p{Cf}\uFE0E\uFE0F]+)(.*))?$", Pattern.DOTALL);
    private static final Pattern EDGE = Pattern.compile("^[\\p{Z}\\s]+|[\\p{Z}\\s]+$");
    private final SessionRecapGreetingStore store;
    private final StreamerProfileStore profiles;
    private final StreamerSelfResolver resolver;
    private final LiveDataService live;
    private final NovaMessageSender sender;
    private final PushGate gate;
    private final Clock clock;

    @Autowired
    public SessionRecapGreetingService(SessionRecapGreetingStore store, StreamerProfileStore profiles,
            StreamerSelfResolver resolver, LiveDataService live, NovaMessageSender sender, PushGate gate) {
        this(store, profiles, resolver, live, sender, gate, Clock.systemUTC());
    }

    public SessionRecapGreetingService(SessionRecapGreetingStore store, StreamerProfileStore profiles,
            StreamerSelfResolver resolver, LiveDataService live, NovaMessageSender sender, PushGate gate, Clock clock) {
        this.store = store; this.profiles = profiles; this.resolver = resolver; this.live = live;
        this.sender = sender; this.gate = gate; this.clock = clock;
    }

    public synchronized void greet(String platform, long uid, PushTarget target) throws IOException {
        String key = SessionRecapDeliveryService.key(platform, uid, target);
        if (!store.eligible(key, clock.instant())) return;
        var context = new CommandContext(target.getPlatform(), target.getType(), target.getNum(), target.getNum(), "", List.of(), "");
        var owner = resolver.resolve(context);
        if (owner.isEmpty() || !platform.equals(owner.get().getPlatform()) || uid != owner.get().getUid()) return;
        var profile = profiles.read(platform, uid);
        var now = clock.instant();
        if (profile.status() != StreamerProfile.Status.READY || profile.paused() || profile.tierSetAt() != null
                || live.getLiveStatus(platform, uid).orElse(false)
                || !gate.allowedAt(now.atZone(WeeklyReportDeliveryService.ZONE).toLocalTime(), target)) return;
        Message message = Message.create(target.getPlatform(), target.getType(), target.getNum(), GREETING).get(0);
        message.setChannel(target);
        if (!store.begin(key, now)) return;
        message.addOnSuccessCallback(() -> completed(key, SessionRecapGreetingStore.Status.SENT));
        message.addOnUncertainCallback(() -> completed(key, SessionRecapGreetingStore.Status.UNCERTAIN));
        message.addOnFailureCallback(() -> retry(key));
        try {
            if (sender.sendWeeklyReport(message) != null) defer(key);
        } catch (RuntimeException failure) { completed(key, SessionRecapGreetingStore.Status.UNCERTAIN); }
    }

    @Override public synchronized Claimed claim(CommandContext context) {
        var choice = CHOICE.matcher(context.getRawText() == null ? "" : context.getRawText());
        if (!choice.matches()) return null;
        var owner = resolver.resolve(context);
        if (owner.isEmpty()) return null;
        var user = owner.get();
        var target = user.getTargets().stream().filter(t -> Boolean.TRUE.equals(t.getStreamerSelf())).findFirst().orElseThrow();
        String key = SessionRecapDeliveryService.key(user.getPlatform(), user.getUid(), target);
        var now = clock.instant();
        var profile = profiles.read(user.getPlatform(), user.getUid());
        if (profile.status() != StreamerProfile.Status.READY || !store.pending(key, now, profile.tierSetAt())) return null;
        int tier = Character.digit(choice.group(1).charAt(0), 10);
        String concern = choice.group(2) == null ? "" : EDGE.matcher(choice.group(2)).replaceAll("");
        String normalized = StreamerProfileCommands.normalize(concern);
        if (!normalized.isEmpty() && Character.isDigit(normalized.codePointAt(0))) return null;
        if (normalized.isEmpty()) concern = "";
        concern = concern.substring(0, concern.offsetByCodePoints(0, Math.min(30, concern.codePointCount(0, concern.length()))));
        String text = concern;
        try {
            if (tier == 3) return Claimed.answer(CommandReply.of("第 3 档还没开放，先按第 " + profile.tier() + " 档发"));
            if (!store.consume(key, now)) return null;
            profiles.update(user.getPlatform(), user.getUid(), p -> {
                var concerns = new ArrayList<>(p.concerns());
                if (!text.isBlank()) {
                    concerns.removeIf(item -> item.text().equals(text));
                    concerns.add(new StreamerProfile.Concern(text, now.toEpochMilli()));
                }
                return new StreamerProfile(tier, now.toEpochMilli(), concerns, p.exclusions(), p.paused(), p.pauseSetAt(), p.preLiveLeadMinutes(), p.preLiveDailyTime(), p.preLiveDisabled(), p.status());
            });
            return Claimed.answer(CommandReply.of("记下了：第 " + tier + " 档" + (text.isBlank() ? "" :
                    "；最近在意：" + MessagePlaceholders.escapeExternalText(text))));
        } catch (IOException failure) {
            return Claimed.answer(CommandReply.of("档案没能保存，请稍后用「改成 1」或「改成 2」再试"));
        }
    }

    private void completed(String key, SessionRecapGreetingStore.Status status) {
        try { store.complete(key, status, clock.instant()); }
        catch (IOException failure) { log.warn("小结招呼结果未能保存，保留发送意图，不自动重发"); }
    }
    private void retry(String key) {
        try { store.failed(key, clock.instant()); }
        catch (IOException failure) { log.warn("小结招呼未能重新登记待发，保留发送意图"); }
    }
    private void defer(String key) {
        try { store.defer(key); }
        catch (IOException failure) { log.warn("小结招呼未能延后，保留发送意图"); }
    }
}
