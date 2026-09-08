package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 配置项元数据测试
 * <p>
 * 界面上的说明文字直接取自 Javadoc，其中的 HTML 标签与 {@code @link}、{@code @code}
 * 等内联标记若不清理，会原样出现在使用者眼前——真机上确实出现过
 * 「{@code @link com.starlwr.bot.core.enums.PushTargetType}」这样的字面文本。
 */
@DisplayName("配置项说明清理")
class ConfigurationMetadataServiceTest {
    private ConfigurationMetadataService service;

    @BeforeEach
    void setUp() {
        service = new ConfigurationMetadataService();
    }

    @Test
    @DisplayName("应移除 HTML 标签")
    void stripsHtmlTags() {
        assertEquals("是否启用告警 关闭后只写日志",
                service.cleanDescription("是否启用告警 <p>关闭后只写日志"));
    }

    @Test
    @DisplayName("{@code} 应只保留内容")
    void unwrapsCodeTag() {
        assertEquals("应填 GROUP(1) 或 FRIEND(0)",
                service.cleanDescription("应填 {@code GROUP(1)} 或 {@code FRIEND(0)}"));
    }

    @Test
    @DisplayName("{@link 全限定类名} 应只保留简单类名")
    void simplifiesLinkedClassName() {
        assertEquals("取值必须与 PushTargetType 的 code 一致",
                service.cleanDescription("取值必须与 {@link com.starlwr.bot.core.enums.PushTargetType} 的 code 一致"));
    }

    @Test
    @DisplayName("{@link 目标 说明文字} 应只保留说明文字")
    void keepsLinkLabel() {
        assertEquals("见推送目标类型",
                service.cleanDescription("见{@link com.starlwr.bot.core.enums.PushTargetType 推送目标类型}"));
    }

    @Test
    @DisplayName("带成员的链接应保留类名与成员")
    void keepsMemberReference() {
        assertEquals("参见 Sender#token",
                service.cleanDescription("参见 {@link com.starlwr.bot.core.model.Sender#token}"));
    }

    @Test
    @DisplayName("清理后不应残留任何 Javadoc 标记")
    void leavesNoJavadocMarkup() {
        String cleaned = service.cleanDescription(
                "{@code A} 与 {@link com.foo.Bar} 及 {@linkplain com.foo.Baz 别名}");

        assertFalse(cleaned.contains("{@"), "不应残留 Javadoc 标记，实际: " + cleaned);
        assertFalse(cleaned.contains("com.foo"), "不应残留包路径，实际: " + cleaned);
    }

    @Test
    @DisplayName("普通文本与小写点号串不应被误改")
    void keepsPlainText() {
        assertEquals("改完自动重载 datasource.json，不用重启",
                service.cleanDescription("改完自动重载 datasource.json，不用重启"));
    }

    @Test
    @DisplayName("空值应原样返回")
    void handlesNull() {
        assertEquals(null, service.cleanDescription(null));
    }

    // ============ 控件类型推断 ============

    @Test
    @DisplayName("映射类型应标记为 complex 而非普通文本框")
    void mapIsComplexWidget() {
        // 落进 string 分支时界面会给出一个普通文本框，填了既不生效也不报错
        assertEquals("complex", field("java.util.Map<java.lang.String,java.lang.String>").widget());
    }

    @Test
    @DisplayName("基础类型应推断出对应控件")
    void primitiveWidgets() {
        assertEquals("boolean", field("java.lang.Boolean").widget());
        assertEquals("integer", field("java.lang.Integer").widget());
        assertEquals("integer", field("java.lang.Long").widget());
        assertEquals("number", field("java.lang.Double").widget());
        assertEquals("string", field("java.lang.String").widget());
    }

    @Test
    @DisplayName("列表按元素类型区分为 list 与 complex")
    void listWidgets() {
        assertEquals("list", field("java.util.List<java.lang.String>").widget());
        assertEquals("complex", field("java.util.List<com.starlwr.bot.core.model.Sender>").widget());
    }

    /**
     * 构造一个仅关心类型的配置项
     */
    private ConfigurationMetadataService.ConfigurationField field(String type) {
        return new ConfigurationMetadataService.ConfigurationField("x", type, "", null);
    }
}
