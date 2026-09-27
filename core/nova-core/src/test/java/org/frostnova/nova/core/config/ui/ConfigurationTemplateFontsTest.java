package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首次写出的配置文件里的成图字体表
 * <p>
 * 用户故障：新装实例的配置文件把整张默认字体表写死了，以后默认表改了也用不上。
 * <p>
 * 生产上写出文件时，配置对象已经走过启动装配（{@link NovaCoreProperties#init()}），
 * 这里照那条路先装配、再按运行中的值渲染；只拿刚 new 出来的对象渲染，量不到这一步。
 */
@DisplayName("首次写出的配置文件不带默认字体表")
class ConfigurationTemplateFontsTest {
    @Test
    @DisplayName("没配过字体时，写出的成图字体是空表")
    void firstWriteLeavesFontsEmpty() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.init();
        assertTrue(properties.getPaint().fontChain().contains("内置"), "锚: 装配后挑字的表该有默认表, 否则下面量不出区别");

        String yaml = ConfigurationTemplate.render(new ConfigurationMetadataService().getFields(),
                ConfigurationPropertyFields.values(List.of(properties)));

        assertEquals(List.of(), fonts(yaml), "配置文件只该写使用者配的字体, 默认表留给程序每次启动现接");
    }

    @Test
    @DisplayName("配过的字体照样写出")
    void configuredFontsAreWritten() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPaint().getFonts().add("我的字体");
        properties.init();

        String yaml = ConfigurationTemplate.render(new ConfigurationMetadataService().getFields(),
                ConfigurationPropertyFields.values(List.of(properties)));

        assertEquals(List.of("我的字体"), fonts(yaml));
    }

    @SuppressWarnings("unchecked")
    private static Object fonts(String yaml) {
        Map<String, Object> root = new Yaml().load(yaml);
        Map<String, Object> core = (Map<String, Object>) ((Map<String, Object>) root.get("novabot")).get("core");
        return ((Map<String, Object>) core.get("paint")).get("fonts");
    }
}
