package org.frostnova.nova.report.command;

import org.frostnova.nova.bilibili.command.BilibiliStreamerChoice;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.service.ReportPaletteSettings;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.report.painter.BilibiliLiveReportPainter;
import org.frostnova.nova.report.painter.ReportPalette;
import org.frostnova.nova.report.painter.ReportPalettes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 群里命令回的图按这个群所在通道的配色画
 * <p>
 * 用户故障：这个群选了夜幕，推送是夜幕，群里打「直播报告」回的却是另一套。
 */
@DisplayName("命令回图的配色")
class CommandReportPaletteTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 30003L;

    private static final Long STREAMER = 10001L;

    @TempDir
    Path dir;

    private PushTemplateDefaults defaults;

    private ReportPalettes palettes;

    private LiveDataService live;

    private BilibiliLiveReportPainter report;

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        defaults = new PushTemplateDefaults(properties);
        palettes = new ReportPalettes(new ReportPaletteSettings(defaults));

        live = mock(LiveDataService.class);
        when(live.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.of(true));
        report = mock(BilibiliLiveReportPainter.class);
        when(report.paint(anyString(), any(), any(), any())).thenReturn(Optional.of("QUJD"));
    }

    @Test
    @DisplayName("这个群选了夜幕，直播报告按夜幕画")
    void reportUsesThisChannelsPalette() {
        assertSame(ReportPalette.NIGHT, reportPalette("night"));
    }

    @Test
    @DisplayName("这个群没选、默认模板没改：按粉蓝画；默认模板改成暖阳：按暖阳画")
    void reportFollowsTheDefaultTemplateWhenUnchosen() {
        assertSame(ReportPalette.PINK_BLUE, reportPalette(null));

        assertTrue(new ReportPaletteSettings(defaults).saveDefault("warm").isEmpty());
        setUp();
        assertSame(ReportPalette.WARM, reportPalette(null));
    }

    private ReportPalette reportPalette(String channelPalette) {
        AbstractDataSource source = mock(AbstractDataSource.class);
        when(source.getUsers("bilibili")).thenReturn(List.of(streamer(channelPalette)));
        RevenueVisibilityService revenue = new RevenueVisibilityService(new NovaStateStore(new NovaCoreProperties()));

        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        BilibiliLiveReportCommand liveReport = new BilibiliLiveReportCommand(source,
                new BilibiliStreamerChoice(live, new LiveSessionArchive(properties)), live, report, revenue);
        liveReport.setPalettes(palettes);
        liveReport.execute(context("直播报告"));

        ArgumentCaptor<ReportPalette> live = ArgumentCaptor.forClass(ReportPalette.class);
        verify(report).paint(anyString(), any(), any(), live.capture());
        return live.getValue();
    }

    private static CommandContext context(String command, String... args) {
        return new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP, 2000000002L,
                command, List.of(args), args.length == 0 ? command : command + " " + String.join(" ", args));
    }

    private static PushUser streamer(String palette) {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.GROUP);
        target.setNum(GROUP);
        target.setEnabled(true);
        target.setRevenueVisible(true);
        target.setReportPalette(palette);
        target.setMessages(new ArrayList<>());

        PushUser user = new PushUser();
        user.setUid(STREAMER);
        user.setUname("示例主播");
        user.setPlatform("bilibili");
        user.setEnabled(true);
        user.setTargets(List.of(target));
        target.setUser(user);
        return user;
    }
}
