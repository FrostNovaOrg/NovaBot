package org.frostnova.nova.core.converter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置文件里的颜色值转换
 *
 * <h2>为什么会有这组判据</h2>
 * 这个转换器接的是使用者手写的 yml（{@code novabot.core.paint.*} 那一族颜色）。
 * 它认得几种互不相同的写法——英文名、逗号分隔的 RGB、带井号与不带井号的十六进制——
 * 每一种都是有人已经写在自己配置里的，认不出来的后果是<b>启动直接失败</b>。
 * <p>
 * 量的是「现在认什么、不认什么」，包括几处容易被当成 bug 顺手改掉的边角
 * （见「容易看错的写法」一节）。
 */
@DisplayName("配置颜色值转换")
class ColorConverterTest {
    private ColorConverter converter;

    @BeforeEach
    void setUp() {
        converter = new ColorConverter();
    }

    @Nested
    @DisplayName("认得的写法")
    class Accepted {
        @Test
        @DisplayName("英文颜色名，大小写与首尾空格都容忍")
        void namedColors() {
            assertEquals(Color.RED, converter.convert("RED"));
            assertEquals(Color.RED, converter.convert("red"));
            assertEquals(Color.BLUE, converter.convert("  blue  "));
            assertEquals(Color.LIGHT_GRAY, converter.convert("light_gray"), "带下划线的常量名按大写找得到");
        }

        @Test
        @DisplayName("逗号分隔的 RGB，逗号两边可以有空格")
        void commaSeparatedRgb() {
            assertEquals(new Color(255, 0, 0), converter.convert("255,0,0"));
            assertEquals(new Color(18, 52, 86), converter.convert("18, 52 , 86"));
            assertEquals(new Color(0, 0, 0), converter.convert("0,0,0"));
        }

        @Test
        @DisplayName("六位十六进制，带井号与不带井号都认")
        void hexWithAndWithoutHash() {
            assertEquals(new Color(0xFF, 0x00, 0x00), converter.convert("#FF0000"));
            assertEquals(new Color(0xFF, 0x00, 0x00), converter.convert("ff0000"));
            assertEquals(new Color(0x12, 0x34, 0x56), converter.convert("#123456"));
        }

        @Test
        @DisplayName("留空表示不设置，应返回空而不是黑色")
        void blankMeansUnset() {
            assertNull(converter.convert(""));
            assertNull(converter.convert("   "));
        }
    }

    @Nested
    @DisplayName("不认的写法要当场报错")
    class Rejected {
        @Test
        @DisplayName("不成形的值要报错，且报错里带上原值")
        void rejectsGarbageAndNamesTheValue() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> converter.convert("不是颜色"));
            assertTrue(e.getMessage().contains("不是颜色"), "报错要指出是哪个值不对: " + e.getMessage());
        }

        @Test
        @DisplayName("分量越界的 RGB 要报错，而不是截到 255")
        void rejectsOutOfRangeRgb() {
            assertThrows(IllegalArgumentException.class, () -> converter.convert("256,0,0"));
            assertThrows(IllegalArgumentException.class, () -> converter.convert("999,999,999"));
        }

        @Test
        @DisplayName("分量不足三个的要报错")
        void rejectsIncompleteRgb() {
            assertThrows(IllegalArgumentException.class, () -> converter.convert("255,0"));
            assertThrows(IllegalArgumentException.class, () -> converter.convert("255,0,0,0"));
        }

        @Test
        @DisplayName("十六进制里有非法字符要报错")
        void rejectsMalformedHex() {
            assertThrows(IllegalArgumentException.class, () -> converter.convert("#GGGGGG"));
            assertThrows(IllegalArgumentException.class, () -> converter.convert("gg0000"));
        }

        /**
         * {@code Color} 从 {@code Transparency} 继承了几个 int 常量，按名字找得到但不是颜色。
         * 现码靠强制转换失败落到最后那句报错——结果是对的，别在改写时让它们蒙混过关。
         */
        @Test
        @DisplayName("同名的非颜色常量不许蒙混过去")
        void rejectsNonColorConstants() {
            assertThrows(IllegalArgumentException.class, () -> converter.convert("TRANSLUCENT"));
            assertThrows(IllegalArgumentException.class, () -> converter.convert("opaque"));
        }

        @Test
        @DisplayName("null 不是「留空」，直接抛空指针")
        void nullThrows() {
            assertThrows(NullPointerException.class, () -> converter.convert(null),
                    "留空走的是空串那条路; null 进来是调用方的错, 现码不兜");
        }
    }

    @Nested
    @DisplayName("容易看错的写法")
    class Traps {
        /**
         * 三位十六进制（CSS 里的 {@code #F00}）<b>不会</b>被当成红色。
         * {@code Color.decode} 把它按整数 0xF00 解，得到的是一个几乎全黑的绿。
         * 使用者写了不会报错，只会发现颜色不对——钉住它是为了别在改写时以为「原来就支持」。
         */
        @Test
        @DisplayName("三位十六进制不报错，但也不是你想要的那个颜色")
        void threeDigitHexIsNotCssShorthand() {
            Color color = converter.convert("#F00");

            assertEquals(new Color(0x00, 0x0F, 0x00), color,
                    "现码按整数 0xF00 解, 不是 CSS 那种 #F00 → #FF0000 的简写");
        }

        /**
         * 驼峰写法的常量名（{@code lightGray}）取不到：现码是整串转大写去找字段，
         * 而字段名叫 {@code LIGHT_GRAY}。
         */
        @Test
        @DisplayName("驼峰写法的颜色名取不到")
        void camelCaseNameIsNotFound() {
            assertThrows(IllegalArgumentException.class, () -> converter.convert("lightGray"));
            assertEquals(Color.LIGHT_GRAY, converter.convert("LIGHT_GRAY"), "带下划线的写法才认");
        }
    }

    /**
     * 🔴 这个注解掉了不会有任何报错：yml 里的颜色值会以「不能把 String 转成 Color」
     * 的形态在启动时炸开，而看起来像是配置写错了。
     */
    @Test
    @DisplayName("必须登记为配置绑定用的转换器")
    void registeredAsConfigurationPropertiesBinding() {
        assertNotNull(ColorConverter.class.getAnnotation(ConfigurationPropertiesBinding.class),
                "少了 @ConfigurationPropertiesBinding, 这个转换器不会参与配置绑定");
    }
}
