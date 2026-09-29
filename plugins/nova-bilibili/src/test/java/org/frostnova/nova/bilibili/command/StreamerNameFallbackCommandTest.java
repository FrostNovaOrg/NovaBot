package org.frostnova.nova.bilibili.command;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 起动时没查到主播昵称，群里查主播时回话里的主播名
 * <p>
 * 推送配置里通常只写 uid，昵称要等起动时去平台查回来。查不回来时，群里「是哪一位」的清单、
 * 「本次用的是」那一行都只剩一串数字，点名也点不中；控制台上却显示得出最近一场的昵称。
 */
@DisplayName("群里查主播：补全没查到昵称时退回最近一场")
class StreamerNameFallbackCommandTest {
    private static final String PLATFORM = "qq-onebot";

    private static final long GROUP = 30003L;

    @TempDir
    Path dir;

    private LiveSessionArchive archive;

    @BeforeEach
    void setUp() throws IOException {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        archive = new LiveSessionArchive(properties);

        archive(10001L, "昵称甲");
        archive(10002L, "昵称乙");
    }

    @Test
    @DisplayName("两位都不在播、没点名：清单里写最近一场的昵称")
    void choiceListShowsArchivedNames() {
        String said = feed(Set.of());

        assertTrue(said.contains("1. 昵称甲（10001）"), said);
        assertTrue(said.contains("2. 昵称乙（10002）"), said);
    }

    @Test
    @DisplayName("只一位在播、没点名：「本次用的是」写最近一场的昵称")
    void onlyLivingNoticeShowsArchivedName() {
        String said = feed(Set.of(10002L));

        assertEquals("本次用的是：昵称乙（只有 TA 在播）\n已出图：昵称乙", said);
    }

    @Test
    @DisplayName("照清单上的名字点名：点得中")
    void matchesArchivedName() {
        assertEquals("已出图：昵称乙", feed(Set.of(), "昵称乙"));
    }

    @Test
    @DisplayName("点了没配过的名：可选名单里写最近一场的昵称，不写「未知主播」")
    void unknownNameListsArchivedNames() {
        String said = feed(Set.of(), "别人");

        assertTrue(said.contains("· 昵称甲（10001）"), said);
        assertTrue(said.contains("· 昵称乙（10002）"), said);
    }

    private String feed(Set<Long> living, String... args) {
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers("bilibili")).thenReturn(List.of(user(10001L), user(10002L)));

        LiveDataService liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStatus(anyString(), anyLong())).thenAnswer(invocation ->
                Optional.of(living.contains((Long) invocation.getArgument(1))));
        when(liveDataService.getLiveEndTime(anyString(), anyLong())).thenReturn(Optional.empty());

        ProbeCommand command = new ProbeCommand(dataSource, new BilibiliStreamerChoice(liveDataService, archive));
        CommandReply reply = command.execute(new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP, 40001L,
                command.name(), List.of(args), command.name()));
        return reply.content();
    }

    private void archive(long uid, String uname) throws IOException {
        JSONObject line = new JSONObject();
        line.put("platform", "bilibili");
        line.put("uid", uid);
        line.put("uname", uname);
        line.put("startTime", 1_700_000_000_000L);
        line.put("endTime", 1_700_003_600_000L);
        Files.writeString(dir.resolve("sessions.jsonl"), line.toJSONString() + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static PushUser user(long uid) {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.GROUP);
        target.setNum(GROUP);
        target.setMessages(new ArrayList<>());

        PushUser user = new PushUser();
        user.setUid(uid);
        user.setUname("");
        user.setPlatform("bilibili");
        user.setTargets(List.of(target));
        return user;
    }

    /**
     * 只报「用了哪位主播」的命令，回一句「已出图：某某」代替那张图
     */
    private static class ProbeCommand extends BilibiliStreamerCommand {
        ProbeCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice) {
            super(dataSource, choice);
        }

        @Override
        public String name() {
            return "直播间数据";
        }

        @Override
        public String description() {
            return "只报用了哪位主播";
        }

        @Override
        public boolean groupOnly() {
            return false;
        }

        @Override
        public CommandReply execute(CommandContext context) {
            Resolved resolved = resolve(context, context.arg(0));
            return resolved.failed()
                    ? resolved.error()
                    : withNotice(resolved, CommandReply.of("已出图：" + nameOf(resolved.streamer())));
        }
    }
}
