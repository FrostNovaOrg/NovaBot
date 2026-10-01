package org.frostnova.nova.report.command;

import org.frostnova.nova.bilibili.command.BilibiliStreamerChoice;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.report.painter.BilibiliDataQueryPainter;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.model.UserScore;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「数据排行榜」的流水榜：口径与累计说明
 * <p>
 * 流水榜按人合计礼物、醒目留言与上舰金额，读的是分人流水表；累计那一半从这一版起才有记录，
 * 脚注必须把这件事说出来（照礼物榜口径变更说明的写法）。
 * 指标名写面值的理由见 {@code BilibiliRevenueUsersMetricTest} 的类注释。
 */
@DisplayName("数据排行榜·流水榜")
class BilibiliRankingRevenueBoardTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 30003L;

    private static final Long STREAMER = 10001L;

    /**
     * 分人流水表
     */
    private static final String REVENUE_USERS = "revenue_users";

    private LiveDataService liveDataService;

    private BilibiliDataQueryPainter painter;

    private BilibiliRankingCommand command;

    @BeforeEach
    void setUp() {
        RevenueVisibilityService revenueVisibility = new RevenueVisibilityService(new NovaStateStore(new NovaCoreProperties()));
        revenueVisibility.set(PLATFORM, GROUP, true);

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers("bilibili")).thenReturn(List.of(streamer()));

        liveDataService = mock(LiveDataService.class);
        painter = mock(BilibiliDataQueryPainter.class);
        when(painter.paintRanking(any(), any(), anyInt(), any(), any())).thenReturn(Optional.of("QUJD"));
        when(painter.measureRankingHeight(any(), anyInt(), any())).thenReturn(0);

        command = new BilibiliRankingCommand(dataSource, mock(BilibiliStreamerChoice.class),
                liveDataService, painter, revenueVisibility, new NovaBilibiliProperties());
    }

    @Test
    @DisplayName("本场：按分人流水表出榜，脚注带口径说明、不带累计口径变更说明")
    void liveBoardReadsTheRevenueTable() {
        when(liveDataService.getLiveMetricUserCount(anyString(), anyLong(), anyString())).thenReturn(2);
        when(liveDataService.getLiveUserRanking(anyString(), anyLong(), eq(REVENUE_USERS), anyInt()))
                .thenReturn(List.of(
                        new UserScore(1L, "甲乙丙", 320),
                        new UserScore(2L, "丁戊己", 130)));

        command.execute(context("流水"));

        ArgumentCaptor<String> footnote = ArgumentCaptor.forClass(String.class);
        verify(painter).paintRanking(any(), any(), eq(1), any(), footnote.capture());
        List<String> red = new java.util.ArrayList<>();
        try {
            assertTrue(footnote.getValue().contains("前 2 名 · 共 2 人"), footnote.getValue());
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertTrue(footnote.getValue().contains("礼物、醒目留言与大航海"), "口径说明要写清合的是什么：" + footnote.getValue());
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            assertTrue(!footnote.getValue().contains("累计榜"), "本场那一半不谈累计口径：" + footnote.getValue());
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("本场：升级前开播的场次没有分人流水表，按礼物＋醒目留言回落，说明与下播报告同一句")
    void liveBoardFallsBackToLegacyTablesForOldSessions() {
        // 旧场次：分人流水表没有记到，礼物与醒目留言的分人金额表早就有
        when(liveDataService.getLiveMetricUserCount(anyString(), anyLong(), eq(REVENUE_USERS))).thenReturn(0);
        when(liveDataService.getLiveUserRanking(anyString(), anyLong(), eq(BilibiliLiveMetric.GIFT_USERS), anyInt()))
                .thenReturn(List.of(new UserScore(1L, "甲乙丙", 200)));
        when(liveDataService.getLiveUserRanking(anyString(), anyLong(), eq(BilibiliLiveMetric.SUPER_CHAT_USERS), anyInt()))
                .thenReturn(List.of(new UserScore(1L, "甲乙丙", 30), new UserScore(2L, "丁戊己", 50)));

        command.execute(context("流水"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserScore>> rows = ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<String> footnote = ArgumentCaptor.forClass(String.class);
        verify(painter).paintRanking(any(), rows.capture(), eq(1), any(), footnote.capture());
        List<String> red = new java.util.ArrayList<>();
        try {
            assertTrue(footnote.getValue().contains("共 2 人"),
                    "回落榜的人数按合成后的名单数：" + footnote.getValue());
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            // 字面比对而不是引常量：这句话与下播报告那边的回落说明必须是同一串字
            assertTrue(footnote.getValue().contains("旧场次没有分人流水记录，按礼物与醒目留言的分人金额排，上舰部分未计入"),
                    "回落时的口径说明要与下播报告同一句：" + footnote.getValue());
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            // 礼物与醒目留言按人相加：甲乙丙 200＋30 在丁戊己 50 前
            assertTrue(rows.getValue().size() == 2
                            && rows.getValue().get(0).userUid().equals(1L)
                            && Math.abs(rows.getValue().get(0).score() - 230) < 0.0001
                            && rows.getValue().get(1).userUid().equals(2L)
                            && Math.abs(rows.getValue().get(1).score() - 50) < 0.0001,
                    "榜按礼物＋醒目留言的分人金额合成：" + rows.getValue());
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("累计：同样按分人流水表，脚注写明这一版起才有记录")
    void totalBoardNotesWhenRecordingStarted() {
        when(liveDataService.supportsTotalData()).thenReturn(true);
        when(liveDataService.getTotalMetricUserCount(anyString(), anyLong(), eq(REVENUE_USERS))).thenReturn(1);
        when(liveDataService.getTotalUserRanking(anyString(), anyLong(), eq(REVENUE_USERS), anyInt()))
                .thenReturn(List.of(new UserScore(1L, "甲乙丙", 320)));

        command.execute(context("流水", "总"));

        ArgumentCaptor<String> footnote = ArgumentCaptor.forClass(String.class);
        verify(painter).paintRanking(any(), any(), eq(1), any(), footnote.capture());
        List<String> red = new java.util.ArrayList<>();
        try {
            assertTrue(footnote.getValue().contains("5.7.8"), "累计说明要写清从哪一版起才记：" + footnote.getValue());
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertTrue(footnote.getValue().contains("之前的场次不在其中"), footnote.getValue());
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    private static PushUser streamer() {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.GROUP);
        target.setNum(GROUP);

        PushUser user = new PushUser();
        user.setUid(STREAMER);
        user.setUname("测试主播");
        user.setTargets(List.of(target));
        return user;
    }

    private static CommandContext context(String... args) {
        return new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP, 2000000002L,
                "数据排行榜", List.of(args), "数据排行榜");
    }
}
