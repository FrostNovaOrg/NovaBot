package com.starlwr.bot.bilibili.handler;

import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent;
import com.starlwr.bot.bilibili.painter.BilibiliLiveReportPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.HandlerOption;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportOptions;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveReportArchive;
import com.starlwr.bot.core.service.RevenueVisibilityService;
import com.starlwr.bot.core.service.StarBotStateStore;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下播报告推送处理器测试
 */
@DisplayName("下播报告推送处理器")
class BilibiliLiveReportPushHandlerTest {
    private BilibiliLiveReportPainter painter;

    private StarBotMessageSender sender;

    private BilibiliLiveReportPushHandler handler;

    private RevenueVisibilityService revenueVisibility;

    private LiveReportArchive reports;

    @TempDir
    Path dir;

    /** 本场开播时刻，留档以它为键 */
    private static final long START_TIME = 1757000000000L;

    @BeforeEach
    void setUp() {
        // 落点全指到临时目录：状态存储与报告留档都会写盘，跑一趟测试不该在仓里留下文件
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());

        revenueVisibility = new RevenueVisibilityService(new StarBotStateStore(properties));
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));
        painter = mock(BilibiliLiveReportPainter.class);
        sender = mock(StarBotMessageSender.class);
        reports = new LiveReportArchive(properties);

        LiveDataService liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStartTime(anyString(), anyLong())).thenReturn(Optional.of(START_TIME));

        handler = new BilibiliLiveReportPushHandler(api, sender, painter, revenueVisibility, reports, liveDataService);
    }

    /**
     * 换一份「问不出开播时刻」的处理器
     * <p>
     * 那种场次<b>本来也不会被归档</b>（下播事件监听器同样以开播时刻为前提），
     * 控制台上不存在这一行，留下的图永远没人点得到
     */
    private BilibiliLiveReportPushHandler handlerWithoutStartTime() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        LiveDataService liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStartTime(anyString(), anyLong())).thenReturn(Optional.empty());
        return new BilibiliLiveReportPushHandler(api, sender, painter, revenueVisibility, reports, liveDataService);
    }

    @Test
    @DisplayName("绘制成功时应推送报告图片")
    void pushesReportImage() {
        when(painter.paint(anyString(), any(), any())).thenReturn(Optional.of("QUJD"));

        handler.handle(event(), pushMessage());

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(captor.capture());
        assertEquals("{image_base64=QUJD}", captor.getValue().getContent());
    }

    @Test
    @DisplayName("绘制失败时应改发文字版，而不是什么都不发")
    void fallsBackToTextWhenPaintFails() {
        // 此前这里断言的是「整条跳过」：占位符被替换成空串、消息成空白、发送环节跳过。
        // 那个行为让主播看到的是「这场没有报告」，而真相是「报告画不出来」——
        // Phase 1.5 第 2 项就是改掉它，所以这条测试的期望跟着改了
        when(painter.paint(anyString(), any(), any())).thenReturn(Optional.empty());
        when(painter.textReport(anyString(), any(), any())).thenReturn("测试主播 本场直播数据\n弹幕 106 条");

        handler.handle(event(), pushMessage());

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(captor.capture());
        assertTrue(captor.getValue().getContent().contains("弹幕 106 条"), "应把文字版报告发出去");
    }

    @Test
    @DisplayName("降级用的金额可见性与图片版同一份，不因降级放宽")
    void textFallbackKeepsRevenueVisibility() {
        when(painter.paint(anyString(), any(), any())).thenReturn(Optional.empty());
        when(painter.textReport(anyString(), any(), any())).thenReturn("文字版");

        handler.handle(event(), pushMessage());

        ArgumentCaptor<BilibiliLiveReportOptions> options = ArgumentCaptor.forClass(BilibiliLiveReportOptions.class);
        verify(painter).textReport(anyString(), any(), options.capture());
        assertFalse(options.getValue().isShowRevenue(), "群聊降级后同样不该带金额");
    }

    @Test
    @DisplayName("界面上的可选项应与实际生效的默认值一一对应")
    void optionsMatchDefaultParams() {
        // 两处若各写一份，改了一处忘了另一处，界面上勾的与实际生效的就会对不上，
        // 而这种不一致不会有任何报错——只会让人以为「配了没用」
        List<HandlerOption> options = handler.options();
        assertFalse(options.isEmpty(), "下播报告应声明可配置的版式选项");

        for (HandlerOption option : options) {
            assertTrue(handler.getDefaultParams().containsKey(option.key()),
                    "选项 " + option.key() + " 未出现在默认参数中");
            assertEquals(option.defaultValue(), handler.getDefaultParams().get(option.key()),
                    "选项 " + option.key() + " 的默认值与默认参数不一致");
        }
    }

    @Test
    @DisplayName("排行榜类选项的取值区间应与报告版式的夹取区间一致")
    void rankingOptionBoundsMatchPainter() {
        // 界面允许填 21 而绘制时夹到 20，等于界面在骗人
        handler.options().stream()
                .filter(option -> option.type() == HandlerOption.Type.INTEGER)
                .forEach(option -> {
                    assertEquals(0, option.min(), option.key() + " 的下限应为 0");
                    if ("guard_list_limit".equals(option.key())) {
                        assertEquals(1000, option.max(), option.key() + " 的上限应与版式夹取区间一致");
                        return;
                    }
                    assertEquals(20, option.max(), option.key() + " 的上限应与 BilibiliLiveReportOptions 一致");
                });
    }

    @Test
    @DisplayName("金额可见性取自推送目标所在的会话，而非推送参数")
    void revenueFollowsTargetSession() {
        when(painter.paint(anyString(), any(), any())).thenReturn(Optional.of("QUJD"));
        ArgumentCaptor<BilibiliLiveReportOptions> options = ArgumentCaptor.forClass(BilibiliLiveReportOptions.class);

        // 群聊默认不展示金额
        handler.handle(event(), pushMessage());
        verify(painter).paint(anyString(), any(), options.capture());
        assertFalse(options.getValue().isShowRevenue(), "群聊默认不该带金额");

        // 同一份推送参数，改会话设置后应当跟着变
        revenueVisibility.set("qq-onebot", 30003L, true);
        handler.handle(event(), pushMessage());
        verify(painter, times(2)).paint(anyString(), any(), options.capture());
        assertTrue(options.getValue().isShowRevenue(), "放开后应带金额");
    }

    @Test
    @DisplayName("私聊默认展示金额：主播看自己的报告不该缺数")
    void revenueVisibleInFriendChat() {
        when(painter.paint(anyString(), any(), any())).thenReturn(Optional.of("QUJD"));

        PushMessage message = pushMessage();
        message.getTarget().setType(PushTargetType.FRIEND);
        message.getTarget().setNum(2000000002L);

        handler.handle(event(), message);

        ArgumentCaptor<BilibiliLiveReportOptions> options = ArgumentCaptor.forClass(BilibiliLiveReportOptions.class);
        verify(painter).paint(anyString(), any(), options.capture());
        assertTrue(options.getValue().isShowRevenue());
    }

    @Test
    @DisplayName("出图即留一份到本地：文件名带平台·uid·开播时刻，内容与推出去的那张同字节")
    void archivesTheReportItJustPushed() {
        byte[] png = "假装这是一张 PNG".getBytes(StandardCharsets.UTF_8);
        when(painter.paint(anyString(), any(), any()))
                .thenReturn(Optional.of(Base64.getEncoder().encodeToString(png)));

        handler.handle(event(), pushMessage());

        Path file = dir.resolve("reports").resolve("bilibili-10001-" + START_TIME + ".png");
        assertTrue(Files.exists(file), "下播出了报告图，控制台的场次表就该点得开它");
        assertArrayEquals(png, readAll(file), "留下的必须是推出去的那一张，不是另画一张");
        assertTrue(reports.has("bilibili", 10001L, START_TIME));
    }

    @Test
    @DisplayName("没记到开播时刻就不留档：那种场次连归档都没有，图留下也没人点得到")
    void skipsArchiveWithoutStartTime() {
        when(painter.paint(anyString(), any(), any())).thenReturn(Optional.of("QUJD"));

        handlerWithoutStartTime().handle(event(), pushMessage());

        assertFalse(Files.exists(dir.resolve("reports")), "问不出开播时刻时不该凭空造一个键");
    }

    @Test
    @DisplayName("画不出来改发文字版时不留档：那一场就是没有图")
    void skipsArchiveWhenPaintFails() {
        when(painter.paint(anyString(), any(), any())).thenReturn(Optional.empty());
        when(painter.textReport(anyString(), any(), any())).thenReturn("文字版");

        handler.handle(event(), pushMessage());

        assertFalse(reports.has("bilibili", 10001L, START_TIME),
                "留一张空图会让人以为是图坏了, 而真相是这一场压根没出图");
    }

    @Test
    @DisplayName("同一场推给多个通道只留一份，且金额可见的那份优先")
    void keepsTheCopyThatShowsRevenue() {
        byte[] hidden = "群聊版·没有金额".getBytes(StandardCharsets.UTF_8);
        byte[] visible = "私聊版·带金额".getBytes(StandardCharsets.UTF_8);
        when(painter.paint(anyString(), any(), any()))
                .thenReturn(Optional.of(Base64.getEncoder().encodeToString(hidden)))
                .thenReturn(Optional.of(Base64.getEncoder().encodeToString(visible)));

        // 先推群（默认不带金额），再推私聊（默认带金额）
        handler.handle(event(), pushMessage());
        PushMessage friend = pushMessage();
        friend.getTarget().setType(PushTargetType.FRIEND);
        friend.getTarget().setNum(2000000002L);
        handler.handle(event(), friend);

        Path file = dir.resolve("reports").resolve("bilibili-10001-" + START_TIME + ".png");
        assertArrayEquals(visible, readAll(file),
                "留档是给这台机器的主人看的, 他本就看得到金额, 不该留一份比自己权限还少的报告");
        assertEquals(1, Objects.requireNonNull(dir.resolve("reports").toFile().listFiles()).length,
                "一场只该留一份, 推给几个通道就存几张会让磁盘按通道数翻倍");
    }

    @Test
    @DisplayName("金额可见的那份留下之后，后来的不可见版本不许把它盖掉")
    void visibleCopyIsNotOverwrittenByHiddenOne() {
        byte[] visible = "私聊版·带金额".getBytes(StandardCharsets.UTF_8);
        byte[] hidden = "群聊版·没有金额".getBytes(StandardCharsets.UTF_8);
        when(painter.paint(anyString(), any(), any()))
                .thenReturn(Optional.of(Base64.getEncoder().encodeToString(visible)))
                .thenReturn(Optional.of(Base64.getEncoder().encodeToString(hidden)));

        PushMessage friend = pushMessage();
        friend.getTarget().setType(PushTargetType.FRIEND);
        friend.getTarget().setNum(2000000002L);
        handler.handle(event(), friend);
        handler.handle(event(), pushMessage());

        assertArrayEquals(visible, readAll(dir.resolve("reports").resolve("bilibili-10001-" + START_TIME + ".png")),
                "顺序反过来结论应当不变, 否则留下哪一份取决于通道在配置里的先后");
    }

    private byte[] readAll(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (java.io.IOException e) {
            throw new AssertionError("读不到留档: " + path, e);
        }
    }

    private BilibiliLiveOffEvent event() {
        return new BilibiliLiveOffEvent(new LiveStreamerInfo(10001L, "主播甲", 20002L));
    }

    private PushMessage pushMessage() {
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(30003L);

        PushMessage message = new PushMessage();
        message.setTarget(target);
        message.setParamsJsonObject(handler.getDefaultParams());
        return message;
    }
}
