package org.frostnova.nova.report.handler;

import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.report.painter.BilibiliLiveReportPainter;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.HandlerOption;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下播报告推送处理器测试
 */
@DisplayName("下播报告推送处理器")
class BilibiliLiveReportPushHandlerTest {
    private BilibiliLiveReportPainter painter;

    private NovaMessageSender sender;

    private BilibiliLiveReportPushHandler handler;

    private RevenueVisibilityService revenueVisibility;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        // 落点全指到临时目录：状态存储会写盘，跑一趟测试不该在仓里留下文件
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());

        revenueVisibility = new RevenueVisibilityService(new NovaStateStore(properties));
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));
        painter = mock(BilibiliLiveReportPainter.class);
        sender = mock(NovaMessageSender.class);

        handler = new BilibiliLiveReportPushHandler(api, sender, painter, revenueVisibility);
    }

    @Test
    @DisplayName("下播报告昵称与文字退路里的占位符只显示文字")
    void sourcePlaceholdersStayText() {
        when(painter.paint(anyString(), any(), any())).thenReturn(Optional.empty());
        when(painter.textReport(anyString(), any(), any())).thenReturn("舰长{at=123}{image_path=/opt/a.png}{next}");
        PushMessage message = pushMessage();
        message.getParamsJsonObject().put("message", "{uname}|{report}");
        handler.handle(new BilibiliLiveOffEvent(new LiveStreamerInfo(10001L, "{at=all}{report}", 20002L)), message);
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(captor.capture());
        String content = captor.getValue().getContent();
        var segments = new org.frostnova.nova.adapter.onebot.converter.OneBotMessageConverter().convert(content);
        assertFalse(segments.isEmpty());
        for (int i = 0; i < segments.size(); i++) {
            assertEquals("text", segments.getJSONObject(i).getString("type"), "外来文字只能显示为文字: " + content);
        }
        assertEquals("｛at=all｝｛report｝|舰长｛at=123｝｛image_path=/opt/a.png｝｛next｝", content);
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
        PushMessage visibleMessage = pushMessage();
        visibleMessage.getTarget().setRevenueVisible(true);
        handler.handle(event(), visibleMessage);
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

    // 故障：下播仍提醒全体，或去掉旧提醒后多发一条空消息。
    @ParameterizedTest
    @CsvSource({
            "all,{at=123 }{next}正文", "all_or_subscribers,前{at=abc}正文后",
            "all,{at=123}{next}正文{at=456}", "all_or_subscribers,前{at=123}正文{at=456}后",
            "all,正文", "all_or_subscribers,正文",
            "all,{at=all}{next}正文", "all_or_subscribers,{at=all}{next}正文",
            "all,前{at}正文{at=all}后", "all_or_subscribers,前{at}正文{at=all}后",
            "all,{at}{next} {at=all} {next}正文{next}{at}",
            "all_or_subscribers,{at}{next} {at=all} {next}正文{next}{at}"
    })
    @DisplayName("下播旧提醒与空段不发送")
    void ignoresLegacyMentions(String mode, String template) {
        when(painter.paint(anyString(), any(), any())).thenReturn(Optional.of("QUJD"));
        PushMessage message = pushMessage();
        message.getParamsJsonObject().put("at_mode", mode);
        message.getParamsJsonObject().put("message", template);
        handler.handle(event(), message);
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(captor.capture());
        assertEquals(template.contains("前") ? "前正文后" : "正文", captor.getValue().getContent(), "下播不提醒且不发空段");
        assertFalse(handler.placeholders().contains("{at=all}"));
        assertFalse(handler.placeholderLabels().containsKey("{at=all}"));
        assertEquals(mode, message.getParamsJsonObject().getString("at_mode"), "存值不变");
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
