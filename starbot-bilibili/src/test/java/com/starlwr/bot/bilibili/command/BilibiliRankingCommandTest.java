package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.painter.BilibiliDataQueryPainter;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.model.UserScore;
import com.starlwr.bot.core.service.LiveDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @BeforeEach
    void setUp() {
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers("bilibili")).thenReturn(List.of(streamer(STREAMER, "测试主播")));

        liveDataService = mock(LiveDataService.class);
        painter = mock(BilibiliDataQueryPainter.class);
        when(painter.paintRanking(any(), any(), anyInt(), any(), any())).thenReturn(Optional.of("QUJD"));

        command = new BilibiliLiveRankingCommand(dataSource, liveDataService, painter);
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
