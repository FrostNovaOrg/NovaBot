package org.frostnova.nova.bilibili.command;

import org.frostnova.nova.core.command.*;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.event.remote.NovaRemoteMessageEvent;
import org.frostnova.nova.core.model.*;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.*;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BilibiliGreetingFollowUpTest {
    @TempDir Path dir;

    @ParameterizedTest @ValueSource(booleans = {true, false})
    @DisplayName("真实容器中选主播追问先拿到数字，避免回答被招呼吃掉并误改档位")
    void streamerChoicePrecedesGreetingRegardlessOfRegistrationOrder(boolean greetingFirst) throws Exception {
        var clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        properties.getPush().setQuietStart(""); properties.getPush().setQuietEnd("");
        var owner = new PushUser(); owner.setPlatform("bilibili"); owner.setUid(990000000101L);
        var other = new PushUser(); other.setPlatform("bilibili"); other.setUid(990000000102L);
        var target = new PushTarget(); target.setPlatform("qq-onebot"); target.setType(PushTargetType.FRIEND);
        target.setNum(990000001001L); target.setStreamerSelf(true); target.setUser(owner);
        owner.setTargets(List.of(target));
        var source = mock(AbstractDataSource.class);
        when(source.getAllUsers()).thenReturn(List.of(owner, other));
        var live = mock(LiveDataService.class);
        when(live.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.of(false));
        var archive = mock(LiveSessionArchive.class);
        var profiles = new StreamerProfileStore(dir.resolve("profiles"));
        var sender = mock(NovaMessageSender.class);
        var replies = new ArrayList<String>();
        doAnswer(call -> { replies.add(((Message) call.getArgument(0)).getContent()); return null; }).when(sender).send(any());
        doAnswer(call -> {
            ((Message) call.getArgument(0)).markDelivered("synthetic-greeting").forEach(Runnable::run); return null;
        }).when(sender).sendWeeklyReport(any());
        var greeting = new SessionRecapGreetingService(new SessionRecapGreetingStore(dir.resolve("greetings.json")),
                profiles, new StreamerSelfResolver(source), live, sender, new PushGate(properties), clock);
        var choice = new BilibiliStreamerChoice(live, archive, clock);
        try (var beans = new AnnotationConfigApplicationContext()) {
            if (greetingFirst) beans.registerBean(SessionRecapGreetingService.class, () -> greeting);
            beans.registerBean(BilibiliStreamerChoice.class, () -> choice);
            if (!greetingFirst) beans.registerBean(SessionRecapGreetingService.class, () -> greeting);
            beans.registerBean("lookup", NovaCommand.class, () -> new NovaCommand() {
                public String name() { return "测试查询"; }
                public String description() { return "查询所选主播"; }
                public boolean groupOnly() { return false; }
                public CommandReply execute(CommandContext context) { return CommandReply.of("所选主播：" + context.getArgs().get(0)); }
            });
            beans.refresh();
            assertEquals(2, beans.getBeansOfType(CommandFollowUp.class).size());
            greeting.greet("bilibili", owner.getUid(), target);
            verify(sender).sendWeeklyReport(any());
            var context = new CommandContext("qq-onebot", PushTargetType.FRIEND, target.getNum(), target.getNum(),
                    "测试查询", List.of(), "测试查询");
            choice.ask(context, choice.rank(List.of(owner, other)));
            var dispatcher = new CommandDispatcher(beans.getBeanProvider(NovaCommand.class),
                    beans.getBeanProvider(CommandFollowUp.class), new CommandSettingsService(new NovaStateStore(properties)),
                    source, sender, properties, TimelineWriter.NONE, clock);
            dispatcher.onRemoteMessage(new NovaRemoteMessageEvent("qq-onebot", "private", target.getNum(), target.getNum(), "2", null, true));
            assertEquals(List.of("所选主播：" + other.getUid()), replies);
            assertNull(profiles.read("bilibili", owner.getUid()).tierSetAt());
            assertEquals(2, profiles.read("bilibili", owner.getUid()).tier());
            assertNotNull(greeting.claim(new CommandContext("qq-onebot", PushTargetType.FRIEND,
                    target.getNum(), target.getNum(), "2", List.of(), "2")));
            assertEquals(2, profiles.read("bilibili", owner.getUid()).tier());
        }
    }
}
