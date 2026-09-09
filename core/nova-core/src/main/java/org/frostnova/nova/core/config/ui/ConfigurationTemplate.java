package org.frostnova.nova.core.config.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把配置面渲染成一份完整的 application.yml
 * <p>
 * <b>为什么要有这个东西。</b>发行包此前带着一份手写的 application.yml。手写件与配置面之间
 * 没有任何东西钉住：代码里加了配置项，那份文件不会跟着长——2026-09-04 现算，
 * 手写件缺了配置面 80 项里的 24 项。而缺项的表现是<b>「设置页上有、配置文件里没有」</b>，
 * 使用者照着文件改，改不到那一项；程序照常启动，什么也不报。
 * <p>
 * 因此不再手写：文件从配置面本身渲染出来，键集就是配置面的键集，加一项少一项都跟着走。
 *
 * <h2>值取自运行中的配置对象，不取自元数据里的默认值</h2>
 * 编译期元数据只记得下写成字面量的默认值。像 {@code allow-ips} 这种在 Java 里初始化成
 * 一份清单的，元数据那一栏是空的——照它写出去就成了 {@code allow-ips:}（空白名单），
 * 而空白名单的语义是<b>全部拒绝</b>。取值这条路见 {@link ConfigurationPropertyFields#values}。
 *
 * <h2>没有值的那几项为什么写成注释</h2>
 * 多数配置项留空是有意义的（口令留空＝不启用）。但「留空即未配」那几项写成空值会让程序
 * <b>根本起不来</b>，那张表原本管的是「界面上清空它＝删掉这一行」——
 * 这里管的是同一件事的另一头：「它本来就没值＝这一行别写成空的」。<b>两处共用一张表</b>，
 * 各写一份的下场是有人往表里加了一项，而生成出来的文件照旧写它一个空值。
 * 核心自有表见 {@link ConfigurationFileService#CORE_BLANK_MEANS_ABSENT}，平台键由插件申报。
 */
final class ConfigurationTemplate {
    /**
     * 缩进单位，与 {@link ConfigurationFileService} 认的一致
     */
    private static final String INDENT = "  ";

    /**
     * 说明折行的宽度，按码点数
     * <p>
     * 说明多为中文，一个字占两列，40 个码点约合 80 列——与源码里的行宽是同一个数量级。
     */
    private static final int WRAP = 40;

    /**
     * 值首位出现即必须加引号的 YAML 指示符，与 {@link ConfigurationFileService} 同源
     */
    private static final String INDICATOR_START = "-?:,[]{}#&*!|>'\"%@`";

    /**
     * 写在文件最前面的话
     * <p>
     * 这份文件是程序自己写出来的，而<b>使用者手改它是正当用法</b>——所以第一句就得说清
     * 「改了不会被覆盖」。不说的话，人不敢改；说反了，人以为白改。
     */
    private static final List<String> HEADER = List.of(
            "NovaBot 配置文件",
            "",
            "本文件由 NovaBot 在第一次保存设置时生成，键与说明取自程序自身的配置面，",
            "取值是各项的默认值。之后它就是你的文件了：手改也好、在控制台里改也好，",
            "程序只改动到的那几行，其余内容逐字节不动，也不会再整份重写。",
            "",
            "留空表示「不配置这一项」，多数项留空即为关闭该功能。",
            "以 # 开头的行是说明，去掉 # 并不会让它生效——那只是把一行说明变成一行配置。");

    private ConfigurationTemplate() {
    }

    /**
     * 渲染一份完整的配置文件
     * @param fields 配置面上的全部配置项，顺序即文件里的顺序
     * @param values 配置项名到当前取值，缺席时回落到元数据里的默认值
     * @return 文件全文，以换行结尾
     */
    static String render(List<ConfigurationMetadataService.ConfigurationField> fields, Map<String, Object> values) {
        return render(fields, values, ConfigurationFileService.CORE_BLANK_MEANS_ABSENT);
    }

    /**
     * 渲染一份完整的配置文件，按给出的「留空即未配」键省略空值
     * @param fields 配置面上的全部配置项
     * @param values 配置项名到当前取值
     * @param blankMeansAbsent 留空即未配的完整路径
     * @return 文件全文，以换行结尾
     */
    static String render(List<ConfigurationMetadataService.ConfigurationField> fields, Map<String, Object> values,
                         Set<String> blankMeansAbsent) {
        StringBuilder out = new StringBuilder();
        for (String line : HEADER) {
            out.append(line.isEmpty() ? "#" : "# " + line).append('\n');
        }
        out.append('\n');

        render(tree(fields), values, 0, out, blankMeansAbsent);
        return out.toString();
    }

    /**
     * 按点分路径把配置项摊成一棵树
     * <p>
     * 不靠「排序之后同一个前缀必然挨着」来分节：那条性质在 {@code network-thread} 与
     * {@code network} 这类名字上要靠字符编码的先后才成立，而那不是谁写下来的约定。
     * @param fields 配置项
     * @return 树，叶子挂着配置项本身
     */
    private static Node tree(List<ConfigurationMetadataService.ConfigurationField> fields) {
        Node root = new Node();

        for (ConfigurationMetadataService.ConfigurationField field : fields) {
            Node current = root;
            for (String segment : field.name().split("\\.")) {
                current = current.children.computeIfAbsent(segment, key -> new Node());
            }
            current.field = field;
        }

        return root;
    }

    private static void render(Node node, Map<String, Object> values, int depth, StringBuilder out,
                               Set<String> blankMeansAbsent) {
        String pad = INDENT.repeat(depth);

        for (Map.Entry<String, Node> entry : node.children.entrySet()) {
            String name = entry.getKey();
            Node child = entry.getValue();

            if (child.field == null) {
                // 中间节点：只是一层缩进，没有值也没有说明
                out.append(pad).append(name).append(":\n");
                render(child, values, depth + 1, out, blankMeansAbsent);
                continue;
            }

            renderLeaf(name, child.field, values, pad, out, blankMeansAbsent);

            // 既是配置项又还有下级的形态目前不存在，但真出现时也得写出来，
            // 否则那一支会连同它的键一起从文件里消失，而文件看起来是完整的
            render(child, values, depth + 1, out, blankMeansAbsent);
        }
    }

    private static void renderLeaf(String name, ConfigurationMetadataService.ConfigurationField field,
                                   Map<String, Object> values, String pad, StringBuilder out,
                                   Set<String> blankMeansAbsent) {
        for (String line : comment(field.description())) {
            out.append(pad).append("# ").append(line).append('\n');
        }

        Object value = values.containsKey(field.name()) ? values.get(field.name()) : field.defaultValue();

        // 没值、且写成空值会让程序起不来的那几项：整行注释掉，并说清为什么
        if (isBlank(value) && blankMeansAbsent.contains(field.name())) {
            out.append(pad).append("# 这一项写成空值会让程序起不来，因此默认整行注释掉；要用就去掉行首的 #\n");
            out.append(pad).append("# ").append(name).append(":\n");
            return;
        }

        if (value instanceof Collection<?> items) {
            renderList(name, items, pad, out, blankMeansAbsent);
            return;
        }

        if (value instanceof Map<?, ?> entries) {
            renderMap(name, entries, pad, out);
            return;
        }

        String scalar = scalar(value);
        out.append(pad).append(name).append(':');
        if (!scalar.isEmpty()) {
            out.append(' ').append(scalar);
        }
        out.append('\n');
    }

    /**
     * 列表：空表写成 {@code []}
     * <p>
     * 写成空白的话，「这一项是个空列表」与「这一项还没填」在文件上长得一样，
     * 而前者是<b>一个已经做出的决定</b>。
     * <p>
     * 元素是对象时按字段写出（键名短横线），读得回来仍是一组字段。
     * 按 {@code toString} 写出去的那一版，读回来是一串谁也解析不回对象的文字。
     */
    private static void renderList(String name, Collection<?> items, String pad, StringBuilder out,
                                   Set<String> blankMeansAbsent) {
        if (items.isEmpty()) {
            out.append(pad).append(name).append(": []\n");
            return;
        }

        StringBuilder body = new StringBuilder();
        for (Object item : items) {
            Map<String, Object> fields = objectFields(item);
            if (fields == null) {
                body.append(pad).append(INDENT).append("- ").append(scalar(item)).append('\n');
                continue;
            }
            renderObjectItem(fields, pad, body, blankMeansAbsent);
        }

        if (body.isEmpty()) {
            out.append(pad).append(name).append(": []\n");
            return;
        }

        out.append(pad).append(name).append(":\n");
        out.append(body);
    }

    /**
     * 把一个对象列表项按字段写成 YAML
     */
    private static void renderObjectItem(Map<String, Object> fields, String pad, StringBuilder out,
                                         Set<String> blankMeansAbsent) {
        boolean first = true;
        String itemPad = pad + INDENT;
        String fieldPad = itemPad + INDENT;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (isBlank(entry.getValue())
                    && ConfigurationFileService.isBlankMeansAbsentField(entry.getKey(), blankMeansAbsent)) {
                continue;
            }
            String rendered = scalar(entry.getValue());
            out.append(first ? itemPad + "- " : fieldPad).append(entry.getKey()).append(':');
            if (!rendered.isEmpty()) {
                out.append(' ').append(rendered);
            }
            out.append('\n');
            first = false;
        }
    }

    /**
     * 对象列表项的字段。认不出结构时返回 null，调用方按标量写
     */
    private static Map<String, Object> objectFields(Object item) {
        if (item instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, value) -> out.put(String.valueOf(key), value));
            return out;
        }

        if (item == null || item instanceof CharSequence || item instanceof Number
                || item instanceof Boolean || item instanceof Enum<?>
                || item instanceof Collection<?> || item.getClass().isArray()) {
            return null;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (Class<?> type = item.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    out.put(ConfigurationPropertyFields.toKebab(field.getName()), field.get(item));
                } catch (RuntimeException | ReflectiveOperationException ignored) {
                    // 读不到就跳过这一项：编一个值写出去，和没写长得不一样，却是假的
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static void renderMap(String name, Map<?, ?> entries, String pad, StringBuilder out) {
        if (entries.isEmpty()) {
            out.append(pad).append(name).append(": {}\n");
            return;
        }

        out.append(pad).append(name).append(":\n");
        entries.forEach((key, value) ->
                out.append(pad).append(INDENT).append(key).append(": ").append(scalar(value)).append('\n'));
    }

    /**
     * 把一个值写成 YAML 标量
     * <p>
     * 该加引号的一律加：一个 {@code 127.0.0.1} 不加引号是对的，而一个恰好写成 {@code yes}
     * 或以 {@code -} 开头的字符串不加引号就变成了另一个类型，<b>而文件看起来毫无异常</b>。
     */
    private static String scalar(Object value) {
        if (isBlank(value)) {
            return "";
        }

        if (!(value instanceof CharSequence)) {
            return String.valueOf(value);
        }

        String text = value.toString();
        if (needsQuotes(text)) {
            return "'" + text.replace("'", "''") + "'";
        }

        return text;
    }

    private static boolean needsQuotes(String text) {
        if (INDICATOR_START.indexOf(text.charAt(0)) >= 0) {
            return true;
        }
        if (!text.strip().equals(text) || text.contains(": ") || text.contains(" #")) {
            return true;
        }

        // 看着像别的类型的字符串：不加引号读回来就不是字符串了
        String lower = text.toLowerCase();
        if (lower.matches("true|false|null|yes|no|on|off|~")) {
            return true;
        }

        return lower.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");
    }

    private static boolean isBlank(Object value) {
        return value == null || (value instanceof CharSequence text && text.toString().isBlank());
    }

    /**
     * 把说明折成若干行注释
     * @param description 说明，可为 null
     * @return 逐行注释文本，没有说明时为空
     */
    private static List<String> comment(String description) {
        List<String> lines = new ArrayList<>();
        if (description == null || description.isBlank()) {
            return lines;
        }

        for (String paragraph : description.strip().split("\\R")) {
            String text = paragraph.strip();
            while (text.length() > WRAP) {
                int cut = breakPoint(text);
                lines.add(text.substring(0, cut).strip());
                text = text.substring(cut).strip();
            }
            if (!text.isEmpty()) {
                lines.add(text);
            }
        }

        return lines;
    }

    /**
     * 找一处断行的位置
     * <p>
     * 优先在标点或空格之后断，找不到就按宽度硬断——中文里没有空格，
     * 只按空格找会得到一整行不折的说明
     */
    private static int breakPoint(String text) {
        for (int i = WRAP; i > WRAP / 2; i--) {
            char c = text.charAt(i - 1);
            if (c == ' ' || "，。；：、）」』】".indexOf(c) >= 0) {
                return i;
            }
        }

        return WRAP;
    }

    /**
     * 配置树的一个节点
     */
    private static final class Node {
        private final Map<String, Node> children = new LinkedHashMap<>();

        private ConfigurationMetadataService.ConfigurationField field;
    }
}
