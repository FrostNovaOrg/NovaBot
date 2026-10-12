package org.frostnova.nova.core.config.ui;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 自带手册的只读出口
 * <p>
 * 控制台的「使用说明」打开的是<b>随包走的那一份手册</b>：与本程序同一版、不需要外网，
 * 登录后才能看。产物由构建链生成（{@code manual/build.py}）并打进 jar 的
 * {@code config-ui-manual/} 下，这里只是把它原样发出去，没有任何渲染。
 * <p>
 * 出口必须挂在 {@code /config} 下：会话 Cookie 的 Path 与安全过滤器都只认这一段，
 * 挪到别的命名空间下，登录了也打不开。
 * <p>
 * <b>包里没带手册时退到 GitHub Pages 上同一件</b>（302）。只跑 mvn、不经 build.sh 的
 * 开发构建不带手册产物，此时点「使用说明」不该得到一个 404——使用者分不清
 * 「这一版没带手册」与「手册坏了」，而两者有一个共同的退路：站上那份一直在。
 *
 * <h2>件名必须白名单认</h2>
 * 与 {@code /config/assets} 同一条规矩：请求路径直接映射到类路径资源，放任 {@code ../}
 * 进来等于把整个 jar 开放出去。只认「字母数字、下划线、连字符 + 一个扩展名」，
 * 扩展名再按所在目录各认一张表——顶层的 html／css／js 与 {@code images/} 下的图。
 * 名字认不过一律 404，<b>不</b>退到 Pages：退路只给「包里没有这一件」，
 * 不给「想读包外的件」。
 */
@Slf4j
@RestController
@RequestMapping(ConfigUiManualController.PATH)
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiManualController {
    /**
     * 手册出口路径，挂在控制台根路径下
     */
    public static final String PATH = ConfigUiController.BASE_PATH + "/manual";

    /**
     * 手册产物在类路径上的目录。名字刻意不含 {@code config-ui/}：产物校验那把尺按
     * 「jar 内 config-ui 条目 ⊆ 源码目录」判，混进去会被当成来路不明的界面资源
     */
    static final String RESOURCE_ROOT = "config-ui-manual/";

    /**
     * 包里没带手册时的退路：Pages 上同一份手册。只与白名单认过的件名拼，不拼别的
     */
    static final String PAGES_SITE = "https://frostnovaorg.github.io/NovaBot/";

    /**
     * 允许的件名，与 {@code /config/assets} 的那一条同一副写法：
     * 不含点号序列，{@code ../} 根本匹配不上
     */
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9]+");

    /**
     * 顶层件的扩展名到内容类型，未列出的扩展名一律不提供
     */
    private static final Map<String, MediaType> PAGE_TYPES = Map.of(
            "html", MediaType.valueOf("text/html;charset=UTF-8"),
            "css", MediaType.valueOf("text/css;charset=UTF-8"),
            "js", MediaType.valueOf("text/javascript;charset=UTF-8"));

    /**
     * images/ 下图片的扩展名到内容类型，同样未列出的一律不提供
     */
    private static final Map<String, MediaType> IMAGE_TYPES = Map.of(
            "png", MediaType.IMAGE_PNG,
            "svg", MediaType.valueOf("image/svg+xml;charset=UTF-8"),
            "jpg", MediaType.IMAGE_JPEG,
            "jpeg", MediaType.IMAGE_JPEG,
            "gif", MediaType.IMAGE_GIF,
            "webp", MediaType.valueOf("image/webp"));

    /**
     * 从哪个类加载器取手册产物。测试换成只认夹具那几件的那一个，
     * 免得本机恰好跑过 manual/build.py 时主类路径上躺着真手册，红绿跟着环境走
     */
    private final ClassLoader classLoader;

    public ConfigUiManualController() {
        this(ConfigUiManualController.class.getClassLoader());
    }

    ConfigUiManualController(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * 手册顶层的页与脚本
     * @param name 件名
     * @return 件内容；包里没有时 302 到 Pages 上同名件；名字认不过时 404
     */
    @GetMapping("/{name}")
    public ResponseEntity<byte[]> page(@PathVariable String name) {
        return serve("", name, PAGE_TYPES);
    }

    /**
     * 手册引用的图片。单独一条映射而不是一层通配：退到 Pages 时目标路径
     * 得知道件在哪个子目录，混在一条里就说不出来了
     * @param name 件名
     * @return 图片内容；包里没有时 302 到 Pages 上同名件；名字认不过时 404
     */
    @GetMapping("/images/{name}")
    public ResponseEntity<byte[]> image(@PathVariable String name) {
        return serve("images/", name, IMAGE_TYPES);
    }

    /**
     * 发一件手册产物
     * <p>
     * 响应头与 {@code /config/assets} 同一套：内容类型按扩展名、不缓存——
     * 手册跟着版本走，升级后必须立刻换新，缓存住的那份会说旧版的事。
     * 退到 Pages 的那一趟也不缓存：开发构建点过一次之后再把手册打进包，
     * 缓住的重定向会让人继续看站上那份，而看不出包里已经有了。
     */
    private ResponseEntity<byte[]> serve(String directory, String name, Map<String, MediaType> types) {
        if (!FILE_NAME.matcher(name).matches()) {
            log.warn("手册出口拒绝了非法的件名: {}", name);
            return ResponseEntity.notFound().build();
        }

        MediaType type = types.get(name.substring(name.lastIndexOf('.') + 1));
        if (type == null) {
            return ResponseEntity.notFound().build();
        }

        String resource = RESOURCE_ROOT + directory + name;
        try (InputStream stream = classLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(PAGES_SITE + directory + name))
                        .cacheControl(CacheControl.noCache())
                        .build();
            }
            return ResponseEntity.ok()
                    .contentType(type)
                    .cacheControl(CacheControl.noCache())
                    .body(stream.readAllBytes());
        } catch (IOException e) {
            log.error("读取手册件 {} 失败", resource, e);
            return ResponseEntity.notFound().build();
        }
    }
}
