package org.frostnova.nova.report.command;

import org.frostnova.nova.bilibili.command.BilibiliStreamerChoice;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.report.painter.BilibiliDataQueryPainter;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandReply;
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
 * 重点在参数解析与长图的取舍：命令的参数顺序灵活（榜单、主播可混排），
 * 而「一次列全还是只列前 N 名」算错一格就会漏人。
 */
@DisplayName("数据排行榜命令")
class BilibiliRankingCommandTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 30003L;

    private static final Long STREAMER = 10001L;

    private static final Long OTHER = 10002L;

    private LiveDataService liveDataService;

    private BilibiliDataQueryPainter painter;

    private AbstractDataSource dataSource;

    private BilibiliStreamerChoice choice;

    private BilibiliRankingCommand command;

    private RevenueVisibilityService revenueVisibility;

    @BeforeEach
    void setUp() {
        revenueVisibility = new RevenueVisibilityService(new NovaStateStore(new NovaCoreProperties()));
        // 本类测的是参数匹配与列哪些名次，与金额可见性无关。群聊默认不展示金额，
        // 若不显式放开，礼物榜会被直接拒掉，测到的就不是这些了
        revenueVisibility.set(PLATFORM, GROUP, true);
        dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers("bilibili")).thenReturn(List.of(streamer(STREAMER, "测试主播")));

        liveDataService = mock(LiveDataService.class);
        painter = mock(BilibiliDataQueryPainter.class);
        when(painter.paintRanking(any(), any(), anyInt(), any(), any())).thenReturn(Optional.of("QUJD"));
        // 画手是替身时量高度回 0，也就是「量得下」：本类不测高度截行，那归 BilibiliRankingHeightLimitTest
        when(painter.measureRankingHeight(any(), anyInt(), any())).thenReturn(0);

        // 只配一位主播时「说的是哪一位」这一步不会走到追问；两位主播那一格再补追问的替身
        choice = mock(BilibiliStreamerChoice.class);
        command = new BilibiliRankingCommand(dataSource, choice,
                liveDataService, painter, revenueVisibility, new NovaBilibiliProperties());
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
    @DisplayName("问累计榜却没说哪张时，回的示例照着发要真查得到累计那一半")
    void exampleWhenBoardMissingShowsTheHalfBeingAsked() {
        // 示例是给人照着发的。写成本场那一半的拼法，照着发查到的就是本场，
        // 而问的人明明说的是累计——这一句答非所问
        when(liveDataService.supportsTotalData()).thenReturn(true);

        // 旧名仍认得，但示例给的是现在的写法：一次一张长图，也没有「翻页」那行了
        String oldSpelling = command.execute(contextAs("总数据排行榜")).content();
        assertEquals("例如：数据排行榜 礼物 总", exampleOf(oldSpelling), oldSpelling);
        assertFalse(oldSpelling.contains("翻页"), oldSpelling);

        String newSpelling = command.execute(contextAs("数据排行榜", "总")).content();
        assertEquals("例如：数据排行榜 礼物 总", exampleOf(newSpelling), newSpelling);
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
    @DisplayName("有弹幕却认不出发送者时，回缘由而非「还没有数据」")
    void repliesUnidentifiedWhenDanmuHasCountButNoUsers() {
        when(liveDataService.getLiveMetricUserCount(anyString(), anyLong(), anyString())).thenReturn(0);
        when(liveDataService.getLiveMetric(anyString(), anyLong(), eq(BilibiliLiveMetric.DANMU_COUNT)))
                .thenReturn(7.0);

        CommandReply reply = command.execute(context("弹幕"));

        assertTrue(reply.content().contains("认不出发送者"), reply.content());
        assertFalse(reply.content().contains("还没有"), reply.content());
        verify(painter, never()).paintRanking(any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("弹幕条数也为 0 时仍回「还没有…数据」")
    void repliesNoDataWhenDanmuHasNeitherCountNorUsers() {
        when(liveDataService.getLiveMetricUserCount(anyString(), anyLong(), anyString())).thenReturn(0);

        CommandReply reply = command.execute(context("弹幕"));

        assertTrue(reply.content().contains("还没有"), reply.content());
        verify(painter, never()).paintRanking(any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("用户查累计榜（数据排行榜 弹幕 总），有弹幕却认不出发送者时，得到的是缘由，而不是误导人的「还没有数据」")
    void totalRankingExplainsWhenDanmuCountedButSenderUnknown() {
        when(liveDataService.supportsTotalData()).thenReturn(true);
        when(liveDataService.getTotalMetricUserCount(anyString(), anyLong(), anyString())).thenReturn(0);
        when(liveDataService.getTotalMetric(anyString(), anyLong(), eq(BilibiliLiveMetric.DANMU_COUNT)))
                .thenReturn(7.0);

        CommandReply reply = command.execute(context("弹幕", "总"));

        assertEquals("测试主播的直播间累计弹幕认不出发送者，没有排行", reply.content());
        verify(painter, never()).paintRanking(any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("八位以上的数字仍当主播 uid，点名哪位就查哪位")
    void longNumberIsStreamerUid() {
        // 两位主播都推到本会话：此时「说的是谁」只能由他给的那个号定下来。
        // 若那个号被当成旧页码丢掉，这一步会落到追问「要说哪一位」那条路上
        PushUser other = streamer(OTHER, "另一位主播");
        when(dataSource.getUsers("bilibili")).thenReturn(List.of(streamer(STREAMER, "测试主播"), other));
        when(choice.rank(any())).thenReturn(new BilibiliStreamerChoice.Ranked(
                List.of(streamer(STREAMER, "测试主播"), other), List.of("测试主播", "另一位主播"), 2, 0));
        when(choice.ask(any(), any())).thenReturn("要说哪一位主播？");
        withRanking(3);

        command.execute(context("弹幕", String.valueOf(OTHER)));

        assertAll(
                () -> verify(liveDataService).getLiveUserRanking(anyString(), eq(OTHER), anyString(), anyInt()),
                () -> verify(painter).paintRanking(any(), any(), eq(1), any(), any())
        );
    }

    @Test
    @DisplayName("榜上 37 人应一次列全，页脚写「前 37 名 · 共 37 人」")
    void listsEveryoneInOneImage() {
        withRanking(37);

        command.execute(context("弹幕"));

        ArgumentCaptor<List<UserScore>> rows = captor();
        ArgumentCaptor<String> footnote = ArgumentCaptor.forClass(String.class);
        verify(painter).paintRanking(any(), rows.capture(), eq(1), any(), footnote.capture());
        assertAll(
                () -> assertEquals(37, rows.getValue().size(), "一张图要列全 37 人"),
                () -> assertTrue(footnote.getValue().contains("前 37 名 · 共 37 人"), footnote.getValue()),
                () -> assertFalse(footnote.getValue().contains("页"), "不该再写页码：" + footnote.getValue())
        );
    }

    @Test
    @DisplayName("榜上 80 人只列前 50 名，末尾写「其余 30 名未列出」")
    void notesRemainderBeyondListedTopN() {
        withRanking(80);

        command.execute(context("弹幕"));

        ArgumentCaptor<List<UserScore>> rows = captor();
        ArgumentCaptor<String> footnote = ArgumentCaptor.forClass(String.class);
        verify(painter).paintRanking(any(), rows.capture(), eq(1), any(), footnote.capture());
        assertAll(
                () -> assertEquals(50, rows.getValue().size(), "默认列前 50 名"),
                () -> assertTrue(footnote.getValue().contains("前 50 名 · 共 80 人"), footnote.getValue()),
                () -> assertTrue(footnote.getValue().contains("其余 30 名未列出"), footnote.getValue())
        );
    }

    @Test
    @DisplayName("恰 50 人时全部列出，不写「其余」")
    void noRemainderWhenEveryoneListed() {
        withRanking(50);

        command.execute(context("弹幕"));

        ArgumentCaptor<String> footnote = ArgumentCaptor.forClass(String.class);
        verify(painter).paintRanking(any(), any(), eq(1), any(), footnote.capture());
        assertAll(
                () -> assertFalse(footnote.getValue().contains("其余"), footnote.getValue()),
                () -> assertTrue(footnote.getValue().contains("前 50 名 · 共 50 人"), footnote.getValue())
        );
    }

    @Test
    @DisplayName("三位以内的数字不当主播也不当页码，回同一张全量长图")
    void shortNumberIsNeitherStreamerNorPage() {
        withRanking(37);

        // 「2」留着是老写法（原来指第 2 页）：现在要照认，回的还是那张全量长图，
        // 既不是第 2 页的 10 人，也不该拿去当主播名查
        CommandReply reply = command.execute(context("弹幕", "2"));

        ArgumentCaptor<List<UserScore>> rows = captor();
        verify(painter).paintRanking(any(), rows.capture(), eq(1), any(), any());
        assertAll(
                () -> assertEquals(37, rows.getValue().size(), "「2」不是页码，出的是同一张全量长图"),
                () -> assertFalse(reply.content().contains("没有配置"), "「2」不该当主播：" + reply.content()),
                () -> assertFalse(reply.content().contains("页码"), reply.content())
        );
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
        return contextAs("数据排行榜", args);
    }

    /**
     * 按打出来的那一整句造上下文：拼写本身就是问题的一部分（旧名「总数据排行榜」
     * 与新写法「数据排行榜 总」问的是同一件事，回话要认得两种问法）
     */
    private CommandContext contextAs(String typed, String... args) {
        return new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP, 2000000002L,
                typed, Arrays.asList(args), typed);
    }

    /**
     * 回话里「例如：」那一行——示例是给人照着发的，它自己就得是个能跑通的问法
     */
    private static String exampleOf(String reply) {
        for (String line : reply.split("\n")) {
            if (line.startsWith("例如：")) {
                return line;
            }
        }
        return "";
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
