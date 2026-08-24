package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.painter.BilibiliDataQueryPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.StarBotStateStore;
import com.starlwr.bot.core.service.UserBindingService;
import com.starlwr.bot.core.service.RevenueVisibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 个人数据命令测试
 */
@DisplayName("我的数据命令")
class BilibiliMyDataCommandTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 30003L;

    private static final Long QQ = 2000000002L;

    private static final Long UID = 19659359825927L;

    private static final Long STREAMER = 10001L;

    private LiveDataService liveDataService;

    private UserBindingService bindings;

    private BilibiliDataQueryPainter painter;

    private BilibiliMyLiveDataCommand liveCommand;

    private BilibiliMyTotalDataCommand totalCommand;

    private RevenueVisibilityService revenueVisibility;

    @BeforeEach
    void setUp() {
        revenueVisibility = new RevenueVisibilityService(new StarBotStateStore(new StarBotCoreProperties()));
        // 本类测的是绑定与名次文案。群聊默认不展示金额，不显式放开的话礼物卡片根本不会出现
        revenueVisibility.set(PLATFORM, GROUP, true);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers("bilibili")).thenReturn(List.of(streamer()));

        liveDataService = mock(LiveDataService.class);
        bindings = new UserBindingService(new StarBotStateStore(new StarBotCoreProperties()));
        painter = mock(BilibiliDataQueryPainter.class);
        when(painter.paintCards(any(), any(), any())).thenReturn(Optional.of("QUJD"));

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));

        liveCommand = new BilibiliMyLiveDataCommand(dataSource, liveDataService, painter, bindings, api, revenueVisibility);
        totalCommand = new BilibiliMyTotalDataCommand(dataSource, liveDataService, painter, bindings, api, revenueVisibility);
    }

    @Test
    @DisplayName("未绑定时应引导去绑定，而不是回一堆 0")
    void requiresBinding() {
        CommandReply reply = liveCommand.execute(context());

        assertTrue(reply.content().contains("绑定"));
        verify(painter, never()).paintCards(any(), any(), any());
    }

    @Test
    @DisplayName("没有互动数据时应说明而非出一张空图")
    void repliesWhenNoInteraction() {
        bindings.bind(PLATFORM, "bilibili", QQ, UID);

        CommandReply reply = liveCommand.execute(context());

        assertTrue(reply.content().contains("还没有"));
        verify(painter, never()).paintCards(any(), any(), any());
    }

    @Test
    @DisplayName("只玩过一项时应只出一张卡片，零值项不占位")
    void skipsZeroValuedCards() {
        bindings.bind(PLATFORM, "bilibili", QQ, UID);
        when(liveDataService.getLiveUserMetric(anyString(), eq(STREAMER), eq(BilibiliLiveMetric.DANMU_USERS), eq(UID)))
                .thenReturn(144.0);
        when(liveDataService.getLiveUserRank(anyString(), eq(STREAMER), eq(BilibiliLiveMetric.DANMU_USERS), eq(UID)))
                .thenReturn(3);

        liveCommand.execute(context());

        ArgumentCaptor<List<BilibiliDataQueryPainter.DataCard>> cards = captor();
        verify(painter).paintCards(any(), cards.capture(), any());
        assertEquals(1, cards.getValue().size());
        assertEquals("144 条", cards.getValue().get(0).value());
        assertEquals("弹幕 · 第 3 名", cards.getValue().get(0).label());
    }

    @Test
    @DisplayName("未上榜时卡片不应带名次")
    void omitsRankWhenUnranked() {
        bindings.bind(PLATFORM, "bilibili", QQ, UID);
        when(liveDataService.getLiveUserMetric(anyString(), eq(STREAMER), eq(BilibiliLiveMetric.GIFT_USERS), eq(UID)))
                .thenReturn(52.5);

        liveCommand.execute(context());

        ArgumentCaptor<List<BilibiliDataQueryPainter.DataCard>> cards = captor();
        verify(painter).paintCards(any(), cards.capture(), any());
        assertEquals("¥52.5", cards.getValue().get(0).value());
        assertEquals("礼物", cards.getValue().get(0).label());
    }

    @Test
    @DisplayName("不展示金额的会话应省略金额卡片，保留非金额卡片")
    void omitsMoneyCardsWithoutRevenue() {
        // 查的虽是自己的数据，回复却发在会话里、同群其他人都看得见，因此仍按会话设置处理
        revenueVisibility.set(PLATFORM, GROUP, false);
        bindings.bind(PLATFORM, "bilibili", QQ, UID);
        when(liveDataService.getLiveUserMetric(anyString(), eq(STREAMER), eq(BilibiliLiveMetric.GIFT_USERS), eq(UID)))
                .thenReturn(52.5);
        when(liveDataService.getLiveUserMetric(anyString(), eq(STREAMER), eq(BilibiliLiveMetric.DANMU_USERS), eq(UID)))
                .thenReturn(144.0);

        liveCommand.execute(context());

        ArgumentCaptor<List<BilibiliDataQueryPainter.DataCard>> cards = captor();
        verify(painter).paintCards(any(), cards.capture(), any());
        assertEquals(1, cards.getValue().size(), "礼物是金额，只该剩弹幕这一张");
        assertEquals("144 条", cards.getValue().get(0).value());
    }

    @Test
    @DisplayName("礼物卡片要附口径说明，本场范围不提口径变更")
    void giftCardCarriesTheUnitNote() {
        bindings.bind(PLATFORM, "bilibili", QQ, UID);
        when(liveDataService.getLiveUserMetric(anyString(), eq(STREAMER), eq(BilibiliLiveMetric.GIFT_USERS), eq(UID)))
                .thenReturn(52.5);

        liveCommand.execute(context());

        ArgumentCaptor<String> footnote = ArgumentCaptor.forClass(String.class);
        verify(painter).paintCards(any(), any(), footnote.capture());
        assertEquals(BilibiliLiveMetric.GIFT_RANKING_NOTE, footnote.getValue(),
                "本场数据是变更之后攒的，只有一段口径，不该提变更");
    }

    @Test
    @DisplayName("⚠️ 累计范围要另说一句：这个数里含口径变更前后两段")
    void totalScopeAlsoDeclaresTheUnitChange() {
        bindings.bind(PLATFORM, "bilibili", QQ, UID);
        when(liveDataService.supportsTotalData()).thenReturn(true);
        when(liveDataService.getTotalUserMetric(anyString(), eq(STREAMER), eq(BilibiliLiveMetric.GIFT_USERS), eq(UID)))
                .thenReturn(52.5);

        totalCommand.execute(context());

        ArgumentCaptor<String> footnote = ArgumentCaptor.forClass(String.class);
        verify(painter).paintCards(any(), any(), footnote.capture());
        // 累计数据是逐场累加的，变更那一刻之前按实扣、之后按到手价值——
        // 同一个数里含两段口径，正撞「同名指标必须同口径」那条规矩，只能标注
        assertTrue(footnote.getValue().contains(BilibiliLiveMetric.GIFT_RANKING_NOTE),
                "口径说明本身仍要有，实际: " + footnote.getValue());
        assertTrue(footnote.getValue().contains(BilibiliLiveMetric.GIFT_RANKING_SCOPE_CHANGE_NOTE),
                "累计范围还要说明口径变更过，实际: " + footnote.getValue());
    }

    @Test
    @DisplayName("没有礼物卡片时不要附一句关于礼物口径的话")
    void noGiftCardNoUnitNote() {
        bindings.bind(PLATFORM, "bilibili", QQ, UID);
        when(liveDataService.getLiveUserMetric(anyString(), eq(STREAMER), eq(BilibiliLiveMetric.DANMU_USERS), eq(UID)))
                .thenReturn(144.0);

        liveCommand.execute(context());

        ArgumentCaptor<String> footnote = ArgumentCaptor.forClass(String.class);
        verify(painter).paintCards(any(), any(), footnote.capture());
        assertEquals(null, footnote.getValue(), "只发过弹幕的人不该读到一句礼物口径说明");
    }

    @Test
    @DisplayName("未配置累计存储时总数据应明确告知，而不是展示 0")
    void totalDataNeedsExternalStore() {
        bindings.bind(PLATFORM, "bilibili", QQ, UID);
        when(liveDataService.supportsTotalData()).thenReturn(false);

        CommandReply reply = totalCommand.execute(context());

        assertTrue(reply.content().contains("Redis"), reply.content());
        verify(painter, never()).paintCards(any(), any(), any());
    }

    private CommandContext context() {
        return new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP, QQ, "我的数据", List.of(), "我的数据");
    }

    private PushUser streamer() {
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

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<BilibiliDataQueryPainter.DataCard>> captor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
