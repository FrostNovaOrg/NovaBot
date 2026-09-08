package com.starlwr.bot.adapter.onebot.security;

import com.starlwr.bot.core.lang.SecureToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 推送接口 Token 存储，维护「接口路径 -> 期望 Token」的映射
 * <p>
 * 未显式配置 Token 的推送平台会在启动时自动生成一个高强度随机 Token。由于 StarBot 核心
 * 是通过本机回环地址调用自身推送接口的，自动生成的 Token 会同步注册给核心，对默认部署完全透明；
 * 而外部调用方若要接入，则必须在配置文件中显式设置 Token，从而杜绝「零配置即裸奔」的情况。
 * <p>
 * 令牌的生成、恒定时间比对与强度判定统一由 {@link SecureToken} 提供。
 */
@Slf4j
public class PushApiTokenStore {
    /**
     * Token 最小长度，低于此长度视为弱 Token
     */
    public static final int MIN_TOKEN_LENGTH = SecureToken.MIN_LENGTH;

    /**
     * 路径匹配口径。按 Spring 的默认选项构造，与路由侧
     * {@link org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping}
     * 在未被定制时的解析同源：框架把请求按「解码后的路径、去掉分号后的矩阵参数」去匹配路由，
     * 鉴权侧若另拿原始 URI 做字符串精确查表，两边口径就会分叉——
     * {@code /onebot/send;jsessionid=abc} 在查表眼里是没见过的路径（放行），
     * 在框架眼里却正是登记过的那条（照常路由），门禁就这样被一个分号绕过。
     * 用框架自己的解析与匹配来认路，两侧口径同源；框架哪天改了折叠或解码的规矩，两侧一起变。
     * 「同源」只承诺默认配置：路由侧若换用了自定义的解析配置，本解析器必须随之同改，
     * 否则两边各认各的路。
     */
    private static final PathPatternParser PATH_PARSER = new PathPatternParser();

    /**
     * 接口路径 -> 期望 Token
     */
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    /**
     * 接口路径 -> 该登记路径的匹配模式（框架口径）
     * <p>
     * 按登记先后保序：{@link #resolve} 取最具体的做法本就不依赖迭代顺序，
     * 但一旦有实现退化成「取首个命中」，答案就完全由迭代顺序决定——哈希表的迭代顺序
     * 随 JDK 与键名漂，那种退化测不测得出来全凭运气。保序之后迭代顺序固定，
     * 「先登宽者再登具体者」的登记顺序下取首个命中必答错，退化必被测试逮住。
     * <p>
     * 登记发生在连接建立时（运行期也会来），迭代按 {@link java.util.Collections#synchronizedMap} 的规约用同步块护住。
     */
    private final Map<String, PathPattern> patterns = Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * 注册某个推送接口的 Token
     * @param path 接口完整路径，例如 /onebot/send
     * @param token 期望的 Token
     */
    public void register(String path, String token) {
        tokens.put(path, token);
        patterns.put(path, PATH_PARSER.parse(path));
    }

    /**
     * 判断某个路径是否为受保护的推送接口
     * <p>
     * 只答「是否落在已登记的推送接口上」这一个布尔：按框架口径解析不了的路径（非法的百分号
     * 编码等）答 false，不把异常甩给只想要一个布尔的调用方。真门禁（安全过滤器）对解析失败
     * 是默认拒绝，不走这里。
     * @param path 接口路径
     * @return 是否受保护
     */
    public boolean isProtected(String path) {
        try {
            return resolve(path, "") != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 按框架匹配口径认路：这个请求路径会落到的登记路径
     * <p>
     * 互含的登记模式同时命中时取最具体的那一条——与路由侧在多个候选中按特异性排序取第一
     * 的口径同源；否则命中哪条会随登记顺序漂，令牌与限流跟着记到不同的账上。
     * @param requestUri 请求原始 URI（未解码，含 contextPath）
     * @param contextPath 应用 contextPath，空串表示没有
     * @return 命中的登记路径；不属于任何推送接口时为 null
     * @throws IllegalArgumentException 路径按框架口径解析不了（contextPath 对不上、非法的百分号编码等）
     */
    public String resolve(String requestUri, String contextPath) {
        PathContainer withinApplication = RequestPath.parse(requestUri, contextPath).pathWithinApplication();

        String bestPath = null;
        PathPattern bestPattern = null;
        synchronized (patterns) {
            for (Map.Entry<String, PathPattern> entry : patterns.entrySet()) {
                if (!entry.getValue().matches(withinApplication)) {
                    continue;
                }
                if (bestPattern == null || entry.getValue().compareTo(bestPattern) < 0) {
                    bestPath = entry.getKey();
                    bestPattern = entry.getValue();
                }
            }
        }

        return bestPath;
    }

    /**
     * 校验请求携带的 Token 是否与接口期望的 Token 一致
     * @param path 接口路径
     * @param presented 请求携带的 Token，可为 null
     * @return 是否校验通过
     */
    public boolean verify(String path, String presented) {
        return SecureToken.verify(tokens.get(path), presented);
    }

    /**
     * 生成一个高强度随机 Token
     * @return 生成的 Token
     */
    public static String generate() {
        return SecureToken.generate();
    }

    /**
     * 判断 Token 是否过弱
     * @param token 待判断的 Token
     * @return 是否过弱
     */
    public static boolean isWeak(String token) {
        return SecureToken.isWeak(token);
    }

    /**
     * 生成 Token 指纹，用于在日志中标识 Token 而不泄露其本身
     * @param token Token
     * @return 指纹字符串
     */
    public static String fingerprint(String token) {
        return SecureToken.fingerprint(token);
    }
}
