package com.starlwr.bot.core.config.ui.napcat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 反向见证：确认识别层认得的那个登录路径，在当前这份 WebUI 里确实还在
 *
 * <h2>它防的是什么</h2>
 * 续登层靠「内层落到 {@code /web_login}」认出会话失效。NapCat 一升级换了路由名，
 * 这个识别就<b>悄悄失效</b>——而失效的表现是「再也不触发」，
 * 它与「从没断过」在界面上、在日志里、在任何计数器上<b>长得一模一样</b>。
 * 所以正向计数（续登了几次）救不了这件事，必须有一条会主动报警的反向判据。
 *
 * <h2>🔴 为什么跑在服务端，不跑在那个包装页里</h2>
 * 两条理由都是实测出来的（裁决 #138 四 ②）：
 * <ol>
 *   <li><b>缓存</b>：NapCat 对外壳与资产都发 {@code Cache-Control: public, max-age=86400}。
 *       在浏览器里 fetch，一份一天前的外壳会指向一份一天前的资产，
 *       于是见证读到的是<b>昨天的路由表</b>——今天改的名字要等 24 小时才看得见。
 *       换到这里就没有这个问题：<b>回环这条路上没有浏览器缓存、没有 CDN、没有反代缓存</b>，
 *       {@code Cache-Control} 是给缓存看的，链路上没有缓存时它不起作用</li>
 *   <li><b>时机</b>：页面里的见证<b>只在有人打开那个页面时才跑</b>，
 *       而「升级后静默失效」恰恰发生在没人打开的那段时间里。
 *       把哨兵放在他要守的那道门后面，等于没放</li>
 * </ol>
 *
 * <h2>为什么必须先取外壳再取资产</h2>
 * 资产文件名带内容哈希（{@code index-BfMm4PRv.js}），NapCat 一重打包就全变。
 * 把资产地址写死等于写下一个下次升级必然失效的常量，
 * <b>而它失效的方式是「取不到」，会被误读成「路由没了」</b>。所以两跳，不能省。
 *
 * <h2>为什么数「处数」不数「行数」</h2>
 * 主包是压缩过的，整个包只有两行。同一个字面量在里面出现 4 处，按行数只数得出 2。
 * <b>行数量的是压缩器的换行习惯，不是代码里有几处引用</b>——
 * 压缩器换一版，行数就变，而处数不变（裁决 #138 四 照记①）。
 *
 * <h2>失败方向</h2>
 * 任何一步不成立都<b>只打一条 WARN</b>，绝不抛、绝不影响凭据签发。
 * 见证是来报信的，不是来把门关上的（边界②：失败回落到现状）。
 */
@Slf4j
public class NapCatRouteWitness {
    /**
     * 识别层认的那个登录路由
     * <p>
     * 它同时是续登层判断「内层落到登录页了」用的那个串。
     * <b>两处必须是同一个值</b>，否则见证盯的是一个没人用的字面量，
     * 于是它永远绿、而识别层照样悄悄失效——那正是这个类要防的东西。
     */
    public static final String ROUTE_LITERAL = "web_login";

    /**
     * 认得出的最少处数
     * <p>
     * 取 1 不取实测的 4：4 是「当前这一版恰好引用了几次」，
     * 它会随 NapCat 重构而变，而<b>把一个会正常变化的数写成阈值，等于安排一次假报警</b>。
     * 要回答的问题只有一个——这个路由还在不在。
     */
    static final int MINIMUM_OCCURRENCES = 1;

    /**
     * 外壳里的入口脚本
     * <p>
     * 只认 {@code <script ... src="...">}。外壳里另有几条 {@code <link rel="modulepreload">}
     * 指向别的分块，<b>那些不是入口</b>，抓进来只会让见证白下载几百 KB。
     */
    private static final Pattern SCRIPT_SRC =
            Pattern.compile("<script[^>]*\\ssrc=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private final RestTemplate restTemplate;

    private final String baseUrl;

    /**
     * 上一次见证的结果
     * <p>
     * 存它只为一件事：让判据能证明<b>见证确实被调用过</b>。
     * 少了这个，「见证根本没人调」与「见证通过了」在外面看长得一模一样——
     * 裁决 #134 的 M3 刚踩过这一族（桶实现得很对，但没人调它）。
     */
    private volatile Verdict lastVerdict;

    public NapCatRouteWitness(String baseUrl, RestTemplate restTemplate) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.restTemplate = restTemplate;
    }

    /**
     * 见证的结论
     */
    public enum Verdict {
        /** 主包里找得到路由字面量 */
        RECOGNISED,
        /** 主包取到了，但里面没有那个字面量——识别层多半已经失效 */
        ROUTE_GONE,
        /** 外壳取不到 */
        SHELL_UNREACHABLE,
        /** 外壳取到了，但抽不出入口脚本 */
        NO_ENTRY_SCRIPT,
        /** 入口脚本取不到 */
        ASSET_UNREACHABLE
    }

    /**
     * 上一次见证的结论，从未跑过时为 null
     */
    public Verdict lastVerdict() {
        return lastVerdict;
    }

    /**
     * 跑一次见证
     * <p>
     * 不抛任何异常。调用方不需要、也不应该根据它的结果改变自己的行为。
     * @return 这一次的结论
     */
    public Verdict witness() {
        // 🔴 这里<b>不</b>再兜一层 try/catch。原先有一层，但 {@link #fetch} 已经把网络那一族
        //    异常全吞了，于是那层 catch 没有任何路径到得了它——破坏跑当场证实：
        //    把它改成「抛出去」，35 条判据一条都没红。
        //    <b>一段到不了的守卫不是保险，是一句没人验过的承诺。</b>
        //    真正需要守的是调用方（签发凭据的那条路）不被见证带下水，
        //    所以守卫只留在 {@code NapCatCredentialService#mint} 那一处，且那一处有判据够得着
        Verdict verdict = run();
        lastVerdict = verdict;
        report(verdict);
        return verdict;
    }

    private Verdict run() {
        String shell = fetch(baseUrl + "/webui/");
        if (shell == null) {
            return Verdict.SHELL_UNREACHABLE;
        }

        List<String> scripts = entryScripts(shell);
        if (scripts.isEmpty()) {
            return Verdict.NO_ENTRY_SCRIPT;
        }

        boolean fetchedAny = false;
        for (String src : scripts) {
            String asset = fetch(absolute(src));
            if (asset == null) {
                continue;
            }
            fetchedAny = true;
            if (occurrences(asset, ROUTE_LITERAL) >= MINIMUM_OCCURRENCES) {
                return Verdict.RECOGNISED;
            }
        }

        // 一个入口脚本都没取下来，与「取下来了但里面没有」是两回事：
        // 前者该去查网络，后者该去查 NapCat 升级了没有
        return fetchedAny ? Verdict.ROUTE_GONE : Verdict.ASSET_UNREACHABLE;
    }

    private void report(Verdict verdict) {
        switch (verdict) {
            case RECOGNISED -> log.debug("NapCat 路由见证通过, 识别层认得的登录路径仍在");
            case ROUTE_GONE -> log.warn("NapCat 的前端主包里已找不到登录路由 \"{}\", "
                    + "会话失效自动续登多半已经失效（NapCat 升级换过路由名？）, "
                    + "在 NovaBot 侧核对 NapCatRouteWitness.ROUTE_LITERAL 与续登层用的那个值", ROUTE_LITERAL);
            case SHELL_UNREACHABLE -> log.warn("NapCat 路由见证未能取到 WebUI 外壳, 地址: {}/webui/", baseUrl);
            case NO_ENTRY_SCRIPT -> log.warn("NapCat 的 WebUI 外壳里抽不出入口脚本, "
                    + "见证无法进行（它的打包方式变了？）");
            case ASSET_UNREACHABLE -> log.warn("NapCat 路由见证取不到入口脚本, 见证无法进行");
        }
    }

    /**
     * 从外壳里抽出入口脚本的地址
     */
    static List<String> entryScripts(String shell) {
        List<String> out = new ArrayList<>();
        Matcher matcher = SCRIPT_SRC.matcher(shell);
        while (matcher.find()) {
            String src = matcher.group(1).strip();
            // 内联脚本没有 src；data: 与外站地址不是我们要读的东西
            if (!src.isEmpty() && !src.startsWith("data:")) {
                out.add(src);
            }
        }
        return out;
    }

    /**
     * 字面量在文本里出现了几处（不重叠）
     */
    static int occurrences(String text, String literal) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(literal, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + literal.length();
        }
    }

    private String absolute(String src) {
        if (src.startsWith("http://") || src.startsWith("https://")) {
            return src;
        }
        return baseUrl + (src.startsWith("/") ? src : "/" + src);
    }

    private String fetch(String url) {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                return null;
            }
            return response.getBody();
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
