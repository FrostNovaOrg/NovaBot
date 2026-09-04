package com.starlwr.bot.core.config.ui;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 登录页的装配
 * <p>
 * 登录页是一张<b>不取任何外部资源</b>的独立页面：未登录时由安全过滤器直接吐出来，
 * 而此刻除了登录那几条接口之外，控制台的一切（包括 {@code /config/assets} 下的脚本）
 * 都还关着门。所以它的样式与脚本都写在自己里面。
 * <p>
 * 但页面上那几个判定（摆什么、折什么、禁什么、锁定那句话怎么写）不能也抄一份在里面：
 * 抄一份就有两份，而<b>夹具量的会是没人跑的那一份</b>。因此这里在发出页面时把
 * {@code login-model.js} 的内容原样拼进它的 module 脚本，两处从此是同一份字节。
 * <p>
 * 拼不上时<b>抛而不是静默略过</b>：少了那段脚本的登录页照样渲染得出来，只是点什么都不动，
 * 而那与「服务器没起来」在使用者眼里长得一样。
 */
final class ConfigUiLoginPage {
    /**
     * 页面里那一行占位。脚本内容原样顶替它，前后的缩进不作处理——
     * 拼进去的是一整段顶格的代码，JavaScript 不在意缩进
     */
    static final String MODEL_MARKER = "//@@login-model@@";

    private static final String PAGE_RESOURCE = "config-ui/login.html";

    private static final String MODEL_RESOURCE = "config-ui/login-model.js";

    /**
     * 拼好的页面。类路径资源在运行期不会变，因此只拼一次
     */
    private static volatile String composed;

    private ConfigUiLoginPage() {
    }

    /**
     * 取拼好的登录页
     * @return 完整 HTML
     * @throws IOException 读取资源失败时抛出
     */
    static String html() throws IOException {
        String cached = composed;
        if (cached == null) {
            synchronized (ConfigUiLoginPage.class) {
                if (composed == null) {
                    composed = compose(read(PAGE_RESOURCE), read(MODEL_RESOURCE));
                }
                cached = composed;
            }
        }

        return cached;
    }

    /**
     * 把判定那一段拼进页面
     * <p>
     * 单独一支是为了让判据能直接喂两段文本进来，不必先把资源摆进类路径。
     * @param page 页面模板
     * @param model 判定脚本
     * @return 拼好的页面
     */
    static String compose(String page, String model) {
        if (!page.contains(MODEL_MARKER)) {
            throw new IllegalStateException("登录页里找不到判定脚本的占位 " + MODEL_MARKER
                    + "，拼不上它页面上点什么都不会动");
        }

        // 只替第一处：占位出现两次意味着页面上会有两份同名函数，后一份把前一份盖掉，
        // 而这件事在页面上看不出任何异常
        return page.replaceFirst(java.util.regex.Pattern.quote(MODEL_MARKER),
                java.util.regex.Matcher.quoteReplacement(model));
    }

    private static String read(String resource) throws IOException {
        try (InputStream stream = new ClassPathResource(resource).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
