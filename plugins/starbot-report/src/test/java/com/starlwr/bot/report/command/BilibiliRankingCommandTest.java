package com.starlwr.bot.report.command;

import com.starlwr.bot.bilibili.command.BilibiliStreamerChoice;
import com.starlwr.bot.report.painter.BilibiliDataQueryPainter;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.model.UserScore;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.RevenueVisibilityService;
import com.starlwr.bot.core.service.StarBotStateStore;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleFunction;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据排行榜命令测试
 * <p>
 * 重点在参数解析与翻页边界：命令的参数顺序灵活（榜单、页码、主播可混排），
 * 而翻页算错一格就会漏人或重复展示。
 */
@DisplayName("数据排行榜命令")
class BilibiliRankingCommandTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 30003L;

    private static final Long STREAMER = 10001L;

    private LiveDataService liveDataService;

    private BilibiliDataQueryPainter painter;

    private BilibiliLiveRankingCommand command;

    private RevenueVisibilityService revenueVisibility;

    @BeforeEach
    void setUp() {
        revenueVisibility = new RevenueVisibilityService(new StarBotStateStore(new StarBotCoreProperties()));
        // 本类测的是翻页与匹配，与金额可见性无关。群聊默认不展示金额，
        // 若不显式放开，礼物榜会被直接拒掉，测到的就不是翻页了
        revenueVisibility.set(PLATFORM, GROUP, true);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers("bilibili")).thenReturn(List.of(streamer(STREAMER, "测试主播")));

        liveDataService = mock(LiveDataService.class);
        painter = mock(BilibiliDataQueryPainter.class);
        when(painter.paintRanking(any(), any(), anyInt(), any(), any())).thenReturn(Optional.of("QUJD"));

        // 本类只配了一位主播，「说的是哪一位」这一步不会走到追问，替身足够
        command = new BilibiliLiveRankingCommand(dataSource, mock(BilibiliStreamerChoice.class),
                liveDataService, painter, revenueVisibility);
    }

    @Test
    @DisplayName("未指定榜单时应列出可选榜单而非直接出图")
    void listsBoardsWhenUnspecified() {
        CommandReply reply = command.execute(context());

        assertTrue(reply.content().contains("弹幕"));
        assertTrue(reply.content().contains("盲盒盈亏"));
        verify(painter, never()).paintRanking(any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("榜单名写错时应给出可选榜单")
    void hintsOnUnknownBoard() {
        CommandReply reply = command.execute(context("人气"));

        assertTrue(reply.content().contains("礼物"));
    }

    @Test
    @DisplayName("不展示金额的会话应拒绝金额榜，并说明原因")
    void refusesMoneyBoardsWithoutRevenue() {
        revenueVisibility.set(PLATFORM, GROUP, false);

        for (String board : List.of("礼物", "醒目留言", "盲盒盈亏")) {
            CommandReply reply = command.execute(context(board));

            // 说清是「本会话不展示」而不是「没这张榜」，否则只会被反复重试
            assertTrue(reply.content().contains("不展示金额"), board + "：" + reply.content());
        }
        verify(painter, never()).paintRanking(any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("不展示金额的会话仍可查非金额榜")
    void allowsNonMoneyBoardsWithoutRevenue() {
        revenueVisibility.set(PLATFORM, GROUP, false);
        withRanking(3);

        command.execute(context("弹幕"));

        verify(painter).paintRanking(any(), any(), eq(1), any(), any());
    }

    @Test
    @DisplayName("提示里不应列出查了必被拒的榜")
    void hintOmitsMoneyBoardsWithoutRevenue() {
        revenueVisibility.set(PLATFORM, GROUP, false);

        CommandReply reply = command.execute(context());

        assertTrue(reply.content().contains("弹幕"), reply.content());
        assertFalse(reply.content().contains("礼物"), "列出来就是请人白跑一趟：" + reply.content());
        assertFalse(reply.content().contains("盲盒盈亏"), reply.content());
    }

    @Test
    @DisplayName("榜单别名应可用")
    void acceptsBoardAlias() {
        withRanking(3);

        command.execute(context("SC"));

        verify(painter).paintRanking(any(), any(), eq(1), any(), any());
    }

    @Test
    @DisplayName("无数据时应说明而非出一张空图")
    void repliesWhenNoData() {
        when(liveDataService.getLiveMetricUserCount(anyString(), anyLong(), anyString())).thenReturn(0);

        CommandReply reply = command.execute(context("礼物"));

        assertTrue(reply.content().contains("还没有"));
        verify(painter, never()).paintRanking(any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("第二页应从第 11 名起，且只含本页的人")
    void secondPageStartsAtEleven() {
        withRanking(23);

        command.execute(context("礼物", "2"));

        ArgumentCaptor<List<UserScore>> rows = captor();
        verify(painter).paintRanking(any(), rows.capture(), eq(11), any(), any());
        assertEquals(10, rows.getValue().size());
        assertEquals(11L, rows.getValue().get(0).userUid());
    }

    @Test
    @DisplayName("末页不足十人时应只展示实际人数")
    void lastPageMayBePartial() {
        withRanking(23);

        command.execute(context("礼物", "3"));

        ArgumentCaptor<List<UserScore>> rows = captor();
        verify(painter).paintRanking(any(), rows.capture(), eq(21), any(), any());
        assertEquals(3, rows.getValue().size());
    }

    @Test
    @DisplayName("页码超出范围时应说明共几页")
    void repliesWhenPageOutOfRange() {
        withRanking(23);

        CommandReply reply = command.execute(context("礼物", "4"));

        assertTrue(reply.content().contains("3 页"), reply.content());
        verify(painter, never()).paintRanking(any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("页码上限之外应直接拒绝，不去查数据")
    void rejectsAbsurdPage() {
        CommandReply reply = command.execute(context("礼物", "999"));

        assertTrue(reply.content().contains("页码"));
        verify(liveDataService, never()).getLiveUserRanking(anyString(), anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("长数字应当作主播 uid 而非页码")
    void longNumberIsStreamerNotPage() {
        withRanking(3);

        // 10001 是主播 uid：若被误当成页码，这里会回「页码需在 1 ~ 50 之间」
        command.execute(context("礼物", String.valueOf(STREAMER)));

        verify(painter).paintRanking(any(), any(), eq(1), any(), any());
    }

    @Test
    @DisplayName("盲盒盈亏零无号、正负带号")
    void boxProfitScoreTextZeroUnsignedPositiveAndNegativeSigned() {
        withRanking(1);

        command.execute(context("盲盒盈亏"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DoubleFunction<String>> scoreText = ArgumentCaptor.forClass(DoubleFunction.class);
        verify(painter).paintRanking(any(), any(), anyInt(), scoreText.capture(), any());
        DoubleFunction<String> fmt = scoreText.getValue();

        assertAll(
                () -> {
                    String label = fmt.apply(0.0);
                    assertFalse(label.startsWith("+"), label);
                    assertFalse(label.startsWith("-"), label);
                    assertTrue(label.contains("¥"), label);
                },
                () -> {
                    String label = fmt.apply(150.0);
                    assertTrue(label.startsWith("+¥"), label);
                },
                () -> {
                    String label = fmt.apply(-150.0);
                    assertTrue(label.startsWith("-¥"), label);
                }
        );
    }

    /**
     * 让排行榜接口按请求的名次数返回连号用户，得分随名次递减
     */
    private void withRanking(int total) {
        when(liveDataService.getLiveMetricUserCount(anyString(), anyLong(), anyString())).thenReturn(total);
        when(liveDataService.getLiveUserRanking(anyString(), anyLong(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    int limit = invocation.getArgument(3);
                    List<UserScore> scores = new ArrayList<>();
                    for (int i = 1; i <= Math.min(limit, total); i++) {
                        scores.add(new UserScore((long) i, "用户" + i, total - i + 1));
                    }
                    return scores;
                });
    }

    private CommandContext context(String... args) {
        return new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP, 2000000002L,
                "数据排行榜", Arrays.asList(args), "数据排行榜");
    }

    private PushUser streamer(Long uid, String uname) {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.GROUP);
        target.setNum(GROUP);

        PushUser user = new PushUser();
        user.setUid(uid);
        user.setUname(uname);
        user.setTargets(List.of(target));
        return user;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<UserScore>> captor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
