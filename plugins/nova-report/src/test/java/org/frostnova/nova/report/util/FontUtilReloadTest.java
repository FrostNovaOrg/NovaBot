package org.frostnova.nova.report.util;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplier;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 设置页上改了图片字体，不重启，下一张图就按新字体画
 * <p>
 * 抓的用户故障：字体表启动时装好一次就不再看配置，保存之后页面说已生效，图上还是旧字体，
 * 要重启才换得过来。
 */
@DisplayName("图片字体保存即生效")
class FontUtilReloadTest {
    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    @DisplayName("换了字体表：下一张图起按新表挑字；新表里装不上的那一项照旧跳过，由后面的补")
    void savedFontsApplyToTheNextImage() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.init();
        FontUtil fonts = new FontUtil(new DefaultResourceLoader(), properties);
        fonts.init();
        String symbols = fonts.parseFont("内置符号").orElseThrow().getName();
        String before = fonts.primaryFont().getName();
        assertNotEquals(symbols, before, "起步时打头的不是「内置符号」，否则下面量不出区别");

        RuntimeConfigurationApplier applier = new RuntimeConfigurationApplier(properties,
                new DefaultListableBeanFactory().getBeanProvider(RuntimeConfigurationApplierContributor.class),
                TimelineWriter.NONE);
        List<String> restart = applier.applyAndTrack(Map.of("novabot.core.paint.fonts", "绝无此字体\n内置符号"));

        assertEquals(List.of(), restart, "图片字体不该再要重启");
        assertEquals(symbols, fonts.primaryFont().getName(), "新表打头的那一项当场成了基线字体");
        assertEquals(symbols, fonts.findFontForCharacter('A').getName(), "挑字先问新表里的字体");
        assertEquals(before, fonts.findFontForCharacter('中').getName(), "新表补不上的字由默认表接着补");
    }
}
