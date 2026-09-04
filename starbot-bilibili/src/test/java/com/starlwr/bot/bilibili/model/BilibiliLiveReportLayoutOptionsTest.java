package com.starlwr.bot.bilibili.model;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.model.HandlerOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 版式项一览「无漏项」
 *
 * <h2>这把尺量什么</h2>
 * {@link BilibiliLiveReportOptions#layoutOptions()} 那张表要被三方读：推送处理器的默认参数、
 * 控制台的渲染、以及版式枚举接口。表一旦与真正生效的字段对不上，
 * 🔴 <b>一个漏在表外的版式项，和一个不存在的版式项，在界面上长得一样</b>——
 * 它照样生效，只是没人配得到它；而多出来的一项则是「勾了不算数」。
 * <p>
 * <b>两个方向都量</b>，只量一个方向的话，另一个方向的漏读成没有：
 * <ul>
 *   <li><b>表 → 生效</b>：表里每一项都拿一个非默认值喂进 {@link BilibiliLiveReportOptions#of}，
 *       断言对应字段真的变了。这一条同时钉住了 key 的拼写——键名打错时字段不会动。</li>
 *   <li><b>生效 → 表</b>：反射列出类里全部实例字段，逐个换算成 snake_case，
 *       断言与表里的键集合相等。新加一个字段而忘了进表，这一条当场红。</li>
 * </ul>
 *
 * <h2>它量不到什么</h2>
 * 「生效 → 表」那一向<b>假定字段名与键名按 camelCase↔snake_case 对应</b>。
 * 哪天有人给字段配一个不同形的键，这一条会误红——那时该改的是这条判据的换算法，
 * 而不是把那一项从分母里摘出去。
 * <p>
 * 金额可见性不在表里，它不来自推送参数（见 {@link BilibiliLiveReportOptions} 的字段注释），
 * 因此在「生效 → 表」这一向里<b>具名排除</b>；具名而不是按规则排除，是为了
 * 让下一个新增的「也不来自参数」的字段先红一次，由人来判它该不该排除。
 */
@DisplayName("版式项一览无漏项")
class BilibiliLiveReportLayoutOptionsTest {

    /**
     * 不来自推送参数、故不进版式表的字段
     */
    private static final Set<String> NOT_FROM_PARAMS = Set.of("showRevenue");

    @Test
    @DisplayName("🔴 先证这把尺量得到东西：表非空，且每项都填齐了")
    void tableIsPopulated() {
        List<HandlerOption> options = BilibiliLiveReportOptions.layoutOptions();

        assertFalse(options.isEmpty(), "版式表是空的，下面两个方向都会白白通过");

        for (HandlerOption option : options) {
            assertNotNull(option.key(), "版式项缺 key");
            assertFalse(option.key().isBlank(), "版式项的 key 是空白");
            assertFalse(option.label().isBlank(), "版式项 " + option.key() + " 没有人话名");
            assertNotNull(option.type(), "版式项 " + option.key() + " 没有类型");
            assertNotNull(option.defaultValue(), "版式项 " + option.key() + " 没有默认值");

            if (option.type() == HandlerOption.Type.INTEGER) {
                assertNotNull(option.min(), "数字项 " + option.key() + " 没有下限");
                assertNotNull(option.max(), "数字项 " + option.key() + " 没有上限");
                assertTrue(option.min() < option.max(), "数字项 " + option.key() + " 的取值范围是空的");
            }
        }

        System.out.println("版式项 " + options.size() + " 项："
                + options.stream().map(HandlerOption::key).collect(Collectors.joining("、")));
    }

    @Test
    @DisplayName("🔴 键不许重名：重名那一项会被界面上的另一项悄悄盖掉")
    void keysAreUnique() {
        List<String> keys = BilibiliLiveReportOptions.layoutOptions().stream().map(HandlerOption::key).toList();

        assertEquals(keys.size(), new LinkedHashSet<>(keys).size(), "版式表里有重名的键: " + keys);
    }

    @Test
    @DisplayName("🔴 表 → 生效：表里每一项喂非默认值都真的改得动对应字段")
    void everyListedOptionIsActuallyRead() {
        for (HandlerOption option : BilibiliLiveReportOptions.layoutOptions()) {
            Object other = otherThanDefault(option);

            JSONObject params = new JSONObject();
            params.put(option.key(), other);
            BilibiliLiveReportOptions parsed = BilibiliLiveReportOptions.of(params, true);

            String field = camelCase(option.key());
            Object actual = read(parsed, field);

            assertEquals(other, actual, "版式项 " + option.key() + " 喂了 " + other
                    + " 却没改动字段 " + field + "（读到 " + actual + "）——"
                    + "要么 of() 没读这个键，要么键名与字段名对不上");
        }
    }

    @Test
    @DisplayName("🔴 生效 → 表：类里每个来自参数的字段都在表里")
    void everyParamFieldIsListed() {
        Set<String> listed = new TreeSet<>(BilibiliLiveReportOptions.layoutOptions().stream()
                .map(HandlerOption::key).toList());

        Set<String> fromFields = new TreeSet<>();
        for (Field field : BilibiliLiveReportOptions.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            if (NOT_FROM_PARAMS.contains(field.getName())) {
                continue;
            }
            fromFields.add(snakeCase(field.getName()));
        }

        // 先证分母不是空的：类里一个实例字段都反射不到时，下面那条相等会在两个空集上成立
        assertFalse(fromFields.isEmpty(), "一个来自参数的字段都没反射到，这一格什么都没量到");

        assertEquals(fromFields, listed, "版式表与类字段对不上"
                + "；表里多出来的: " + minus(listed, fromFields)
                + "；表里漏掉的: " + minus(fromFields, listed));

        System.out.println("类字段 " + fromFields.size() + " 个（排除 " + NOT_FROM_PARAMS + "）"
                + "，表里 " + listed.size() + " 项，差集两向皆空");
    }

    /**
     * 取一个与默认值不同、且仍在合法区间内的值
     */
    private static Object otherThanDefault(HandlerOption option) {
        if (option.type() == HandlerOption.Type.BOOLEAN) {
            return !((Boolean) option.defaultValue());
        }

        int defaultValue = (Integer) option.defaultValue();
        // 取两端里与默认值不同的那一端：区间非空已由上一格钉住，故必有一端可取
        return defaultValue == option.max() ? option.min() : option.max();
    }

    private static Object read(BilibiliLiveReportOptions options, String fieldName) {
        try {
            Field field = BilibiliLiveReportOptions.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(options);
        } catch (NoSuchFieldException e) {
            return fail("版式项对应的字段 " + fieldName + " 不存在");
        } catch (IllegalAccessException e) {
            return fail("读不到字段 " + fieldName + ": " + e.getMessage());
        }
    }

    private static String camelCase(String snake) {
        StringBuilder text = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
                continue;
            }
            text.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return text.toString();
    }

    private static String snakeCase(String camel) {
        StringBuilder text = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                text.append('_').append(Character.toLowerCase(c));
            } else {
                text.append(c);
            }
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static Set<String> minus(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }
}
