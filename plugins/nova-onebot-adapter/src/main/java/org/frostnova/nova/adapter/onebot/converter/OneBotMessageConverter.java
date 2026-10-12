package org.frostnova.nova.adapter.onebot.converter;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.model.MessagePlaceholders;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.lang.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 把占位符写法翻译成 OneBot 认得的消息段
 * <ul>
 *     <li>{next}: 消息分条，核心已在造消息时处理掉，转换器见不到它</li>
 *     <li>{face=1}: 表情</li>
 *     <li>{at=all}: @全体成员</li>
 *     <li>{at=123456}: @指定成员</li>
 *     <li>{image_url=https://example.com/image.jpg}: 网络图片</li>
 *     <li>{image_path=/opt/image.jpg}: 本地图片</li>
 *     <li>{image_base64=...}: Base64 图片</li>
 * </ul>
 * <p>
 * @ 与三种图片的值<b>空着</b>时整段丢掉；表情 ID <b>解析不了</b>、图片网址或路径
 * 不符合要求时原样留成文本。图片网址只接受 HTTP 与 HTTPS，路径须为本机绝对图片路径且不含 {@code ..}。
 */
@Slf4j
@Component
@NovaComponent
public class OneBotMessageConverter {
    private static final String FACE_PREFIX = "{face=";

    private static final String AT_PREFIX = "{at=";

    /**
     * 将可包含占位符的原始消息转换为 OneBot 可识别的格式
     * <p>
     * 占位符取的是「从 <code>{</code> 到<b>最近一个</b> <code>}</code>」，与
     * {@link MessagePlaceholders} 的非贪婪口径一致；认不出的写法一律原样留成文本，
     * 让使用者在群里看见自己写错的那几个字，而不是安静地少一段内容。
     * @param content 可包含占位符的消息内容
     * @return 转换后的 JSON 数组
     */
    public JSONArray convert(String content) {
        JSONArray elements = new JSONArray();

        int cursor = 0;
        while (cursor < content.length()) {
            int braceStart = content.indexOf('{', cursor);
            if (braceStart == -1) {
                elements.add(element("text", "text", content.substring(cursor)));
                break;
            }

            if (braceStart > cursor) {
                elements.add(element("text", "text", content.substring(cursor, braceStart)));
            }

            int braceEnd = content.indexOf('}', braceStart);
            if (braceEnd == -1) {
                // 左花括号没有配对的右花括号，从它到结尾都不可能是占位符
                elements.add(element("text", "text", content.substring(braceStart)));
                break;
            }

            addPlaceholder(elements, content.substring(braceStart, braceEnd + 1));
            cursor = braceEnd + 1;
        }

        return elements;
    }

    /**
     * 把一个完整的占位符翻译成一个消息段，认不出或值为空时另作处理
     * @param elements 转换结果，翻译得出的消息段追加在这里
     * @param placeholder 含首尾花括号的整个占位符
     */
    private void addPlaceholder(JSONArray elements, String placeholder) {
        if (placeholder.startsWith(FACE_PREFIX)) {
            String faceId = valueOf(placeholder, FACE_PREFIX);
            try {
                elements.add(element("face", "id", Integer.parseInt(faceId)));
            } catch (NumberFormatException e) {
                log.error("表情 ID 格式错误: {}", placeholder, e);
                elements.add(element("text", "text", placeholder));
            }
        } else if (placeholder.startsWith(AT_PREFIX)) {
            addIfPresent(elements, "at", "qq", valueOf(placeholder, AT_PREFIX), "");
        } else if (placeholder.startsWith(MessagePlaceholders.IMAGE_URL_PREFIX)) {
            String value = valueOf(placeholder, MessagePlaceholders.IMAGE_URL_PREFIX);
            addImage(elements, placeholder, value, "", isHttpUrl(value));
        } else if (placeholder.startsWith(MessagePlaceholders.IMAGE_PATH_PREFIX)) {
            String value = valueOf(placeholder, MessagePlaceholders.IMAGE_PATH_PREFIX);
            addImage(elements, placeholder, value, "file://", isImagePath(value));
        } else if (placeholder.startsWith(MessagePlaceholders.IMAGE_BASE64_PREFIX)) {
            addIfPresent(elements, "image", "file", valueOf(placeholder, MessagePlaceholders.IMAGE_BASE64_PREFIX), "base64://");
        } else {
            elements.add(element("text", "text", placeholder));
        }
    }

    private void addImage(JSONArray elements, String placeholder, String value, String scheme, boolean valid) {
        if (StringUtil.isBlank(value)) {
            return;
        }
        if (valid) {
            addIfPresent(elements, "image", "file", value, scheme);
        } else {
            elements.add(element("text", "text", placeholder));
        }
    }

    private boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && StringUtil.isNotBlank(uri.getRawAuthority());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isImagePath(String value) {
        if (value.contains("..") || !value.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpg|jpeg|gif|webp|bmp)")) {
            return false;
        }
        // 文件由机器人端读取，只接受本机 Unix 路径或带盘符的 Windows 路径。
        boolean unixAbsolute = value.startsWith("/") && !value.startsWith("//");
        boolean windowsAbsolute = value.matches("^[A-Za-z]:[\\\\/].*");
        if (!unixAbsolute && !windowsAbsolute) {
            return false;
        }
        try {
            Path.of(value);
            return true;
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /**
     * 取出占位符等号后面那一段
     * <p>
     * 长度从前缀常量现算，不写死偏移量：偏移量与前缀改一处漏一处时，
     * 截出来的仍是一个像模像样的字符串，错处要到消息发进群里才看得见
     */
    private String valueOf(String placeholder, String prefix) {
        return placeholder.substring(prefix.length(), placeholder.length() - 1);
    }

    /**
     * 值不为空时才追加这一段
     * <p>
     * 空值整段丢掉而不是留成文本：使用者的模板里留了个没填的位置，
     * 把 <code>{at=}</code> 原样发进群里只会让人以为机器人坏了
     * @param scheme 值的前缀，OneBot 靠它分辨本地文件与 Base64；没有前缀时传空串
     */
    private void addIfPresent(JSONArray elements, String type, String dataKey, String value, String scheme) {
        if (StringUtil.isNotBlank(value)) {
            elements.add(element(type, dataKey, scheme + value));
        }
    }

    /**
     * 造一个消息段
     * <p>
     * 五种段的形状是同一个：一个 {@code type} 加一个只有一项的 {@code data}。
     * 各写一遍的那一版里，改动要在五处之间逐个对照才知道漏没漏
     * @param type 段类型
     * @param dataKey {@code data} 里那一项的键名，各类型不同
     * @param dataValue 那一项的值
     * @return 消息段
     */
    private JSONObject element(String type, String dataKey, Object dataValue) {
        JSONObject data = new JSONObject();
        data.put(dataKey, dataValue);

        JSONObject element = new JSONObject();
        element.put("type", type);
        element.put("data", data);
        return element;
    }
}
