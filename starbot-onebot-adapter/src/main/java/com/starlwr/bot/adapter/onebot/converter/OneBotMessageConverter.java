package com.starlwr.bot.adapter.onebot.converter;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OneBot 消息转换器
 * <ul>
 *     <li>{next}: 消息分条，已由 StarBot 内部处理，无需手动处理</li>
 *     <li>{face=1}: 表情</li>
 *     <li>{at=all}: @全体成员</li>
 *     <li>{at=123456}: @指定成员</li>
 *     <li>{image_url=https://example.com/image.jpg}: 网络图片</li>
 *     <li>{image_path=/opt/image.jpg}: 本地图片</li>
 *     <li>{image_base64=...}: Base64 图片</li>
 * </ul>
 */
@Slf4j
@Component
@StarBotComponent
public class OneBotMessageConverter {
    /**
     * 将可包含占位符的原始消息转换为 OneBot 可识别的格式
     * @param content 可包含占位符的消息内容
     * @return 转换后的 JSON 数组
     */
    public JSONArray convert(String content) {
        JSONArray elements = new JSONArray();

        int braceStart = content.indexOf("{");

        while (!content.isEmpty()) {
            if (braceStart == -1) {
                elements.add(createTextElement(content));
                content = "";
            } else if (braceStart != 0) {
                elements.add(createTextElement(content.substring(0, braceStart)));
                content = content.substring(braceStart);
            } else {
                int braceEnd = content.indexOf("}");

                if (braceEnd == -1) {
                    elements.add(createTextElement(content));
                    content = "";
                } else {
                    String placeholder = content.substring(0, braceEnd + 1);

                    if (placeholder.startsWith("{face=")) {
                        try {
                            int faceId = Integer.parseInt(placeholder.substring(6, placeholder.length() - 1));
                            elements.add(createFaceElement(faceId));
                        } catch (NumberFormatException e) {
                            log.error("表情 ID 格式错误: {}", placeholder, e);
                            elements.add(createTextElement(placeholder));
                        }
                    } else if (placeholder.startsWith("{at=")) {
                        String target = placeholder.substring(4, placeholder.length() - 1);
                        if (StringUtil.isNotBlank(target)) {
                            elements.add(createAtElement(target));
                        }
                    } else if (placeholder.startsWith("{image_url=")) {
                        String url = placeholder.substring(11, placeholder.length() - 1);
                        if (StringUtil.isNotBlank(url)) {
                            elements.add(createUrlImageElement(url));
                        }
                    } else if (placeholder.startsWith("{image_path=")) {
                        String path = placeholder.substring(12, placeholder.length() - 1);
                        if (StringUtil.isNotBlank(path)) {
                            elements.add(createPathImageElement(path));
                        }
                    } else if (placeholder.startsWith("{image_base64=")) {
                        String base64 = placeholder.substring(14, placeholder.length() - 1);
                        if (StringUtil.isNotBlank(base64)) {
                            elements.add(createBase64ImageElement(base64));
                        }
                    } else {
                        elements.add(createTextElement(placeholder));
                    }

                    content = content.substring(braceEnd + 1);
                }
            }

            braceStart = content.indexOf("{");
        }

        return elements;
    }

    /**
     * 创建文本元素
     * @param text 文本内容
     * @return 文本元素
     */
    private JSONObject createTextElement(String text) {
        JSONObject element = new JSONObject();
        element.put("type", "text");
        JSONObject data = new JSONObject();
        data.put("text", text);
        element.put("data", data);
        return element;
    }

    /**
     * 创建表情元素
     * @param faceId 表情 ID
     * @return 表情元素
     */
    private JSONObject createFaceElement(int faceId) {
        JSONObject element = new JSONObject();
        element.put("type", "face");
        JSONObject data = new JSONObject();
        data.put("id", faceId);
        element.put("data", data);
        return element;
    }

    /**
     * 创建 @ 元素
     * @param target @ 目标
     * @return @ 元素
     */
    private JSONObject createAtElement(String target) {
        JSONObject element = new JSONObject();
        element.put("type", "at");
        JSONObject data = new JSONObject();
        data.put("qq", target);
        element.put("data", data);
        return element;
    }

    /**
     * 创建网络图片元素
     * @param url 图片 URL
     * @return 网络图片元素
     */
    private JSONObject createUrlImageElement(String url) {
        JSONObject element = new JSONObject();
        element.put("type", "image");
        JSONObject data = new JSONObject();
        data.put("file", url);
        element.put("data", data);
        return element;
    }

    /**
     * 创建本地图片元素
     * @param path 图片路径
     * @return 本地图片元素
     */
    private JSONObject createPathImageElement(String path) {
        JSONObject element = new JSONObject();
        element.put("type", "image");
        JSONObject data = new JSONObject();
        data.put("file", "file://" + path);
        element.put("data", data);
        return element;
    }

    /**
     * 创建 Base64 图片元素
     * @param base64 Base64 字符串
     * @return Base64 图片元素
     */
    private JSONObject createBase64ImageElement(String base64) {
        JSONObject element = new JSONObject();
        element.put("type", "image");
        JSONObject data = new JSONObject();
        data.put("file", "base64://" + base64);
        element.put("data", data);
        return element;
    }
}
