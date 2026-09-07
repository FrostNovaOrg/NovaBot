package com.starlwr.bot.report.command;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.command.BilibiliStreamerChoice;
import com.starlwr.bot.report.handler.BilibiliLiveReportPushHandler;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportOptions;
import com.starlwr.bot.report.painter.BilibiliLiveReportPainter;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveSessionArchive;
import com.starlwr.bot.core.service.RevenueVisibilityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「直播报告」命令：挑主播与版式
 * <p>
 * 这条命令与「直播间数据」同一档：这里配了的主播在播就出报告，
 * 不要求这里还配过报告推送。版式有推送才用推送的，没有就走默认。
 */
@DisplayName("直播报告命令")
class BilibiliLiveReportCommandTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long FRIEND = 20001L;

    private static final Long SENDER = 40001L;

    private static final List<String> NAMES = List.of("主播甲", "主播乙");

    /**
     * 场次归档所在目录。本类不写归档，只是不让选择器读到别处那一份
     */
    @TempDir
    static Path dataDir;

    @Test
    @DisplayName("私聊没有报告推送、主播在播：出图，画手拿到默认版式")
    void paintsDefaultLayoutWhenSessionHasNoReportPush() {
        Fixture fixture = new Fixture(List.of(streamer(1, null)), Set.of(1));

        CommandReply reply = fixture.command.execute(context());

        assertEquals("{image_base64=QUJD}", reply.content());
        BilibiliLiveReportOptions options = fixture.capturedOptions();
        assertTrue(options.isCover());
        assertTrue(options.isCards());
        assertTrue(options.isDanmuCloud());
        assertEquals(5, options.getDanmuRanking());
        assertEquals(0, options.getBoxRanking());
        assertTrue(options.isShowRevenue(), "私聊默认展示金额");
    }

    @Test
    @DisplayName("主播不在播时应明说「X 现在没在直播」")
    void saysNotLiveWhenStreamerIsOff() {
        Fixture fixture = new Fixture(List.of(streamer(1, null)), Set.of());

        CommandReply reply = fixture.command.execute(context());

        assertEquals("主播甲 现在没在直播", reply.content());
        verify(fixture.painter, never()).paint(anyString(), any(), any());
    }

    @Test
    @DisplayName("两位主播只一位在播：画在播的那位，图回复不带「本次用的是」")
    void picksTheOnlyLivingStreamerWithoutPrefixingTheImage() {
        Fixture fixture = new Fixture(List.of(streamer(1, null), streamer(2, null)), Set.of(2));

        CommandReply reply = fixture.command.execute(context());

        assertEquals("{image_base64=QUJD}", reply.content());
        assertFalse(reply.content().contains("本次用的是"));
        assertEquals(10002L, fixture.capturedSource().getUid());
    }

    @Test
    @DisplayName("带主播名时应出点名的那位")
    void honorsNamedStreamer() {
        Fixture fixture = new Fixture(List.of(streamer(1, null), streamer(2, null)), Set.of(1, 2));

        CommandReply reply = fixture.command.execute(context("主播乙"));

        assertEquals("{image_base64=QUJD}", reply.content());
        assertEquals(10002L, fixture.capturedSource().getUid());
    }

    @Test
    @DisplayName("该主播有推到本会话的报告推送：画手拿到那条推送的版式")
    void usesPushLayoutWhenThisSessionHasAReportHandler() {
        JSONObject params = new JSONObject();
        params.put("cover", false);
        params.put("danmu_ranking", 10);
        Fixture fixture = new Fixture(List.of(streamer(1, params)), Set.of(1));

        fixture.command.execute(context());

        BilibiliLiveReportOptions options = fixture.capturedOptions();
        assertFalse(options.isCover());
        assertEquals(10, options.getDanmuRanking());
        assertTrue(options.isCards(), "未写的项仍是默认");
    }

    private static CommandContext context(String... args) {
        return new CommandContext(PLATFORM, PushTargetType.FRIEND, FRIEND, SENDER,
                "直播报告", List.of(args), args.length == 0 ? "直播报告" : "直播报告 " + String.join(" ", args));
    }

    private static PushUser streamer(int index, JSONObject reportParams) {
        PushUser user = new PushUser();
        user.setUid(10000L + index);
        user.setUname(NAMES.get(index - 1));
        user.setPlatform("bilibili");
        user.setEnabled(true);

        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.FRIEND);
        target.setNum(FRIEND);
        target.setEnabled(true);
        if (reportParams != null) {
            PushMessage message = new PushMessage();
            message.setHandler(BilibiliLiveReportPushHandler.class.getName());
            message.setEnabled(true);
            message.setParamsJsonObject(reportParams);
            target.setMessages(new ArrayList<>(List.of(message)));
        } else {
            target.setMessages(new ArrayList<>());
        }
        user.setTargets(List.of(target));
        return user;
    }

    /**
     * 一次用例用的零件：主播名单与在播状态按用例不同，共用一份会把上一例的盘面带到下一例。
     */
    private static class Fixture {
        private final BilibiliLiveReportPainter painter;

        private final BilibiliLiveReportCommand command;

        private final ArgumentCaptor<LiveStreamerInfo> source =
                ArgumentCaptor.forClass(LiveStreamerInfo.class);

        private final ArgumentCaptor<BilibiliLiveReportOptions> options =
                ArgumentCaptor.forClass(BilibiliLiveReportOptions.class);

        private boolean captured;

        Fixture(List<PushUser> users, Set<Integer> living) {
            AbstractDataSource dataSource = mock(AbstractDataSource.class);
            when(dataSource.getUsers("bilibili")).thenReturn(users);

            LiveDataService liveDataService = mock(LiveDataService.class);
            when(liveDataService.getLiveStatus(anyString(), anyLong())).thenAnswer(invocation ->
                    Optional.of(living.contains((int) ((Long) invocation.getArgument(1) - 10000L))));

            painter = mock(BilibiliLiveReportPainter.class);
            when(painter.paint(anyString(), any(), any())).thenReturn(Optional.of("QUJD"));

            RevenueVisibilityService revenueVisibility = mock(RevenueVisibilityService.class);
            when(revenueVisibility.isVisible(anyString(), any(), anyLong())).thenReturn(true);

            StarBotCoreProperties properties = new StarBotCoreProperties();
            properties.getLive().setLiveDataPath(dataDir.resolve("data.json").toString());
            command = new BilibiliLiveReportCommand(dataSource,
                    new BilibiliStreamerChoice(liveDataService, new LiveSessionArchive(properties)),
                    liveDataService, painter, revenueVisibility);
        }

        private void capture() {
            if (!captured) {
                verify(painter).paint(anyString(), source.capture(), options.capture());
                captured = true;
            }
        }

        LiveStreamerInfo capturedSource() {
            capture();
            return source.getValue();
        }

        BilibiliLiveReportOptions capturedOptions() {
            capture();
            return options.getValue();
        }
    }
}
