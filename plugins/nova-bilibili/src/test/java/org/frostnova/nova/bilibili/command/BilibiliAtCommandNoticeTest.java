package org.frostnova.nova.bilibili.command;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.AtSubscriptionService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「@我」类命令认中主播后，回复前也要带「本次用的是」那一行
 * <p>
 * 点的名对上的是最近一场归档里的昵称时，那个昵称可能已经换给了别人——订阅订上的是谁，
 * 回复里得说清，与数据查询命令同一句；点的就是现名时本就是他自己说的，不加这一行。
 */
@DisplayName("开播@我认的是归档昵称时，回复前也带说明行")
class BilibiliAtCommandNoticeTest {
    private static final String PLATFORM = "qq-onebot";

    private static final long GROUP = 30003L;

    private static final long SENDER = 2000000002L;

    @TempDir
    Path dir;

    private LiveSessionArchive archive;

    private BilibiliLiveAtMeCommand command;

    @BeforeEach
    void setUp() throws IOException {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        archive = new LiveSessionArchive(properties);

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers("bilibili")).thenReturn(List.of(
                user(10001L, ""), user(10002L, "阿光")));

        AtSubscriptionService subscriptions = mock(AtSubscriptionService.class);
        when(subscriptions.subscribe(anyString(), any(), any(), anyString(), any()))
                .thenReturn(AtSubscriptionService.Result.OK);

        command = new BilibiliLiveAtMeCommand(dataSource,
                new BilibiliStreamerChoice(liveDataService(), archive),
                subscriptions, mock(CommandSettingsService.class));
    }

    @Test
    @DisplayName("点的名对上的是归档昵称：订阅回复前说清按最近一场的昵称认的")
    void archivedNameMatchCarriesNotice() throws IOException {
        archive(10001L, "小月");

        assertEquals("本次用的是：小月（TA 的昵称没查回来，按最近一场的昵称认的）\n"
                        + "好的，小月开播时会 @ 你。再发「开播@我」可取消",
                said("小月"));
    }

    @Test
    @DisplayName("点的名对上的是现名：不加这一行，用的是谁本就是他自己说的")
    void currentNameMatchCarriesNoNotice() {
        assertEquals("好的，阿光开播时会 @ 你。再发「开播@我」可取消",
                said("阿光"));
    }

    private String said(String keyword) {
        CommandReply reply = command.execute(new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP,
                SENDER, "开播@我", List.of(keyword), "开播@我"));
        return reply.content();
    }

    private static LiveDataService liveDataService() {
        LiveDataService liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStatus(anyString(), anyLong())).thenAnswer(invocation ->
                Optional.of(false));
        when(liveDataService.getLiveEndTime(anyString(), anyLong())).thenReturn(Optional.empty());
        return liveDataService;
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

    private static PushUser user(long uid, String uname) {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.GROUP);
        target.setNum(GROUP);
        target.setMessages(new ArrayList<>());

        PushUser user = new PushUser();
        user.setUid(uid);
        user.setUname(uname);
        user.setPlatform("bilibili");
        user.setTargets(List.of(target));
        return user;
    }
}
