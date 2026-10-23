package org.frostnova.nova.report.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.service.ReportPaletteSettings;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.report.painter.BilibiliLiveReportPreviewPainter;
import org.frostnova.nova.report.painter.ReportPalette;
import org.frostnova.nova.report.painter.ReportPalettes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 控制台里的报告预览按通道的配色出，默认模板里的那一项存得进、取得出
 */
@DisplayName("报告图配色：预览与默认模板")
class ReportPaletteEndpointsTest {
    @TempDir
    Path dir;

    private BilibiliLiveReportPreviewPainter preview;

    private PushTarget channel;

    private PushTemplateDefaults defaults;

    private BilibiliReportLayoutController controller;

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        defaults = new PushTemplateDefaults(properties);

        preview = mock(BilibiliLiveReportPreviewPainter.class);
        when(preview.render(any(), any())).thenReturn(Optional.of(new byte[]{1}));
        controller = new BilibiliReportLayoutController(preview,
                new RevenueVisibilityService(new NovaStateStore(properties)));
        ReportPaletteSettings settings = new ReportPaletteSettings(defaults);
        controller.setPalettes(settings, new ReportPalettes(settings));

        channel = new PushTarget();
        channel.setPlatform("qq-onebot");
        channel.setType(PushTargetType.GROUP);
        channel.setNum(30003L);
        PushUser user = new PushUser();
        user.setTargets(List.of(channel));
        AbstractDataSource source = mock(AbstractDataSource.class);
        when(source.getUser("bilibili", 10001L)).thenReturn(Optional.of(user));
        ReflectionTestUtils.setField(controller, "dataSource", source);
    }

    private static JSONObject channelBody() {
        JSONObject body = new JSONObject();
        body.put("platform", "qq-onebot");
        body.put("type", 1);
        body.put("num", 30003L);
        body.put("streamerPlatform", "bilibili");
        body.put("streamerUid", 10001L);
        return body;
    }

    // 用户故障：通道里选了夜幕，预览还是白底
    @Test
    @DisplayName("通道选了夜幕：这个通道的预览按夜幕画")
    void previewUsesTheChannelsPalette() {
        channel.setReportPalette("night");
        controller.preview(channelBody());
        verify(preview).render(any(), same(ReportPalette.NIGHT));
    }

    @Test
    @DisplayName("草稿里刚选的配色先于存着的：预览跟着草稿当场变")
    void draftPaletteWinsForThisImage() {
        channel.setReportPalette("night");
        JSONObject body = channelBody();
        body.put("reportPalette", "warm");
        controller.preview(body);
        verify(preview).render(any(), same(ReportPalette.WARM));
    }

    // 用户故障：预览里改回「跟随默认」，看到的还是通道原来那套
    @Test
    @DisplayName("草稿里清掉了配色（空值、空串、只有空格）：按默认模板预览，不落到通道存着的那套")
    void clearedDraftFollowsTheDefaultTemplate() {
        assertTrue(defaults.saveReportPalette("warm").isEmpty());
        channel.setReportPalette("night");
        List<JSONObject> drafts = new java.util.ArrayList<>();
        for (String raw : List.of("null", "\"\"", "\" \"")) {
            // 与界面发来的请求同一种来路：从 JSON 文本解析
            JSONObject body = JSONObject.parseObject("{\"reportPalette\":" + raw + "}");
            body.putAll(channelBody());
            drafts.add(body);
        }
        for (JSONObject body : drafts) {
            controller.preview(body);
        }
        var used = org.mockito.ArgumentCaptor.forClass(ReportPalette.class);
        verify(preview, org.mockito.Mockito.times(3)).render(any(), used.capture());
        List<String> ids = used.getAllValues().stream().map(palette -> ReportPalette.PRESETS.stream()
                .filter(preset -> preset.palette() == palette).findFirst().orElseThrow().id()).toList();
        System.out.println("DRAFT_RESET " + ids);
        assertEquals(List.of("warm", "warm", "warm"), ids, "依次为空值、空串、只有空格");
    }

    @Test
    @DisplayName("通道没选、没带通道：都按默认模板画，出厂是粉蓝")
    void previewFollowsTheDefaultTemplate() {
        controller.preview(channelBody());
        controller.preview(null);
        verify(preview, org.mockito.Mockito.times(2)).render(any(), same(ReportPalette.PINK_BLUE));
    }

    @Test
    @DisplayName("配色清单：四套按展示顺序，标出此刻生效的、存着的与出厂的")
    void listsPresetsAndTheDefault() {
        JSONObject listed = controller.palettes();
        JSONArray presets = listed.getJSONArray("presets");
        assertEquals(List.of("classic", "pink-blue", "warm", "night"),
                presets.stream().map(item -> ((JSONObject) item).getString("id")).toList());
        assertEquals(List.of("经典", "粉蓝", "暖阳", "夜幕"),
                presets.stream().map(item -> ((JSONObject) item).getString("name")).toList());
        assertEquals("pink-blue", listed.getString("default"));
        assertNull(listed.get("stored"));
        assertEquals("pink-blue", listed.getString("factory"));
    }

    @Test
    @DisplayName("改默认模板的配色：存的是 id，没另选的通道随之变；空 id 回到出厂")
    void savesTheDefaultAsAnId() throws Exception {
        assertTrue(controller.saveDefaultPalette(JSONObject.of("id", "night")).getBody().getBooleanValue("success"));
        String file = Files.readString(dir.resolve("template-defaults.json"));
        assertTrue(file.contains("\"reportPalette\":\"night\""), file);
        assertEquals("night", controller.palettes().getString("default"));
        assertEquals("night", controller.palettes().getString("stored"));

        controller.preview(channelBody());
        verify(preview).render(any(), same(ReportPalette.NIGHT));

        assertTrue(controller.saveDefaultPalette(new JSONObject()).getBody().getBooleanValue("success"));
        assertEquals("pink-blue", controller.palettes().getString("default"));
        assertNull(controller.palettes().get("stored"));
    }

    @Test
    @DisplayName("不认识的 id 不存：一个字也不写")
    void rejectsUnknownId() {
        var response = controller.saveDefaultPalette(JSONObject.of("id", "sakura"));
        assertEquals(400, response.getStatusCode().value());
        assertFalse(response.getBody().getBooleanValue("success"));
        assertTrue(response.getBody().getString("message").contains("sakura"));
        assertFalse(Files.exists(dir.resolve("template-defaults.json")));
    }
}
