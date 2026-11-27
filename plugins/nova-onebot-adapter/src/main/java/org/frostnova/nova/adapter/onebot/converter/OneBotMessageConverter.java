package org.frostnova.nova.adapter.onebot.converter;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.model.MessagePlaceholders;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.lang.StringUtil;
import org.frostnova.nova.core.util.MessageImageAddressPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 把占位符写法翻译成 OneBot 认得的消息段
 * <ul>
 *     <li>{next}: 消息分条，核心已在造消息时处理掉，转换器见不到它</li>
 *     <li>{face=1}: 表情</li>
 *     <li>{at=all}: @全体成员</li>
 *     <li>{at=123456}: @指定成员</li>
 *     <li>{image_url=https://example.com/image.jpg}: 网络图片</li>
 *     <li>{image_base64=...}: Base64 图片</li>
 * </ul>
 * <p>
 * @、图片与旧路径写法的值<b>空着</b>时整段丢掉；表情 ID <b>解析不了</b>、图片网址
 * 写法不对（不是 HTTP、HTTPS，读不出主机名，带 {@code ..} 段等）时原样留成文本。
 * 图片网址按 {@link MessageImageAddressPolicy} 判：机器人程序会照这个地址去取图，所以指向本机、内网与链路本地的，
 * 以及主机名解析不出的，<b>这张图不发</b>，与值空着一样整段丢掉、也不留成文本（地址连同查询串里的令牌不进群），
 * 消息其余部分照发，日志里记一条不带地址的警告。
 * 本地图片写法不再支持，非空值原样留成文本，请改用 Base64 图片。
 */
@Slf4j
@Component
@NovaComponent
public class OneBotMessageConverter {
    private static final String FACE_PREFIX = "{face=";

    private static final String AT_PREFIX = "{at=";

    private final MessageImageAddressPolicy imageAddresses;

    /**
     * 不放行任何内网网段，按系统解析判图片地址
     */
    public OneBotMessageConverter() {
        this(new MessageImageAddressPolicy());
    }

    /**
     * 插件单独装起来（不带核心）时没有核心的那一件，就按不放行任何内网网段判
     */
    @Autowired
    public OneBotMessageConverter(ObjectProvider<MessageImageAddressPolicy> imageAddresses) {
        this(imageAddresses.getIfAvailable(MessageImageAddressPolicy::new));
    }

    /**
     * @param imageAddresses 图片地址能不能交给机器人程序去取
     */
    public OneBotMessageConverter(MessageImageAddressPolicy imageAddresses) {
        this.imageAddresses = imageAddresses;
    }

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
            addImageUrl(elements, placeholder, valueOf(placeholder, MessagePlaceholders.IMAGE_URL_PREFIX));
        } else if (placeholder.startsWith(MessagePlaceholders.IMAGE_PATH_PREFIX)) {
            String value = valueOf(placeholder, MessagePlaceholders.IMAGE_PATH_PREFIX);
            if (StringUtil.isNotBlank(value)) {
                elements.add(element("text", "text", placeholder));
            }
        } else if (placeholder.startsWith(MessagePlaceholders.IMAGE_BASE64_PREFIX)) {
            addIfPresent(elements, "image", "file", valueOf(placeholder, MessagePlaceholders.IMAGE_BASE64_PREFIX), "base64://");
        } else {
            elements.add(element("text", "text", placeholder));
        }
    }

    /**
     * 网络图片：能交给机器人程序去取的成图片段
     * <p>
     * 指向本机、内网，或主机名解析不出的，这张图不发：不成图片段，也不留成文本——
     * 留成文本的话，内网图床的地址连同查询串里的令牌会原样进群，开播那一刻本机域名解析抖一下，
     * 群里也会多出一行占位符原文。只有写法不对的才原样留成文本，让使用者看见自己写错的那几个字。
     * 警告里不带地址：地址里可能有令牌。
     * <p>
     * 机器人程序自己取图时跟不跟跳转，这里管不到；这里只看写在消息里的这个地址。
     */
    private void addImageUrl(JSONArray elements, String placeholder, String value) {
        if (StringUtil.isBlank(value)) {
            return;
        }
        switch (imageAddresses.check(value)) {
            case ALLOWED -> elements.add(element("image", "file", value));
            case BLOCKED -> log.warn("消息里有一张图片的地址指向本机或内网, 这张图没有交给机器人程序、没有发出, 消息其余部分照发; "
                    + "确实要从内网取图时, 在设置页「推送」组高级里的「消息图片 · 允许的内网网段」"
                    + "（novabot.core.push.image-allowed-networks）写上图床的地址并重启");
            case UNRESOLVED -> log.warn("消息里有一张图片的地址主机名解析不出, 这张图没有交给机器人程序、没有发出, 消息其余部分照发; "
                    + "请检查图片地址和这台机器的域名解析");
            case INVALID -> elements.add(element("text", "text", placeholder));
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
     * @param scheme 值的前缀，Base64 图片使用 base64://；没有前缀时传空串
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
