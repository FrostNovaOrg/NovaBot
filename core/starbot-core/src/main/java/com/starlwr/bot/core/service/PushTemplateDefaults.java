package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.handler.NovaEventHandler;
import com.starlwr.bot.core.model.HandlerOption;
import com.starlwr.bot.core.sender.AtMode;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 改过的默认模板
 *
 * <h2>为什么要有这一层</h2>
 * 出厂默认模板写在各处理器的 {@code getDefaultParams()} 里，那是代码，控制台改不动。
 * 而「改一次默认，所有用默认的通道一起变」正是推送这一页的立身之本：一位主播推去八个群，
 * 想给开播通知加一句话，没有这一层就得点开八个通道各改一遍，而且此后这八份各是各的，
 * 下一次再改又是八遍。
 * <p>
 * 这一层存的是<b>相对出厂默认的覆盖</b>，不是整份参数：只写使用者真的改过的那几个键。
 * 存整份的话，出厂默认此后再动（改错别字、加一个新参数），这些实例一个也跟不上，
 * 而屏幕上它们仍然显示着「默认」。
 *
 * <h2>存在哪</h2>
 * {@code datasource.json} 的同目录下，文件名 {@code template-defaults.json}，
 * 形如 <code>{"处理器全类名": {"message": "…"}}</code>。
 * <p>
 * 不并进 {@code datasource.json}：那份是<b>使用者手写的推送配置</b>，结构是一个主播数组，
 * 塞一个非主播的条目进去会让所有读它的地方（校验、限额统计、数据源加载）都得先认一遍
 * 「这一条不是主播」。也不并进 {@code state.json}：那份存的是程序自己产生的运行状态
 * （订阅名单、被关掉的命令），会被自动保存整份盖掉，而默认模板是人写的东西。
 *
 * <h2>与 {@code supersededDefaults} 的关系</h2>
 * 两者管的是不同的事：那张表回答「使用者存着的这一串是不是某一版出厂默认」，
 * 本类回答「这台机器的默认现在是什么」。判定顺序是先认旧默认（于是那一条跟着走），
 * 再由本类给出跟着走的目标——见 {@code StarBotEventHandlerPushMessageInitializer}。
 */
@Slf4j
@Service
public class PushTemplateDefaults {
    /**
     * 文件名。与推送配置同目录
     */
    static final String FILE_NAME = "template-defaults.json";

    /**
     * 模板里的占位符：从 <code>{</code> 到<b>最近一个</b> <code>}</code>
     * <p>
     * 非贪婪，与 {@code MessagePlaceholders} 及适配器的取法一致。贪婪的话
     * {@code {uname} 正在直播 {title}} 会被读成一个占位符，于是一份好模板被判成写错。
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{.*?}");

    private final StarBotCoreProperties properties;

    /**
     * 处理器全类名 → 覆盖掉的那几个参数
     */
    private JSONObject cache;

    /**
     * 读盘与写盘的锁。读的一侧是推送线程（每次加载配置都会问一遍默认参数），
     * 写的一侧是控制台
     */
    private final Object lock = new Object();

    @Autowired
    public PushTemplateDefaults(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 这台机器此刻的默认参数：出厂默认盖上改过的那几个键
     * <p>
     * <b>每次调用都返回新实例</b>，与 {@link NovaEventHandler#getDefaultParams()} 同一条约定：
     * 推送消息会把使用者的参数直接写进这个返回值，共用一份的话，一个推送目标的自定义参数
     * 会串到其他目标上。
     * @param handler 处理器
     * @return 默认参数
     */
    public JSONObject paramsOf(@NonNull NovaEventHandler handler) {
        JSONObject params = handler.getDefaultParams();
        if (params == null) {
            params = new JSONObject();
        }

        JSONObject stored = overridesOf(handler);
        for (Map.Entry<String, Object> entry : stored.entrySet()) {
            params.put(entry.getKey(), entry.getValue());
        }
        return params;
    }

    /**
     * 某个处理器改过的那几个键，没改过时为空对象
     * @param className 处理器全类名
     * @return 覆盖参数的拷贝
     */
    public JSONObject overridesOf(@NonNull String className) {
        synchronized (lock) {
            JSONObject stored = load().getJSONObject(className);
            return stored == null ? new JSONObject() : stored.clone();
        }
    }

    /**
     * 某个处理器改过的那几个键：先按它现在的类名读，读不到再按它声明过的旧名回落读一次
     * <p>
     * 这张表的键是处理器全类名，而处理器<b>换过包名</b>之后，使用者机器上那份文件里存的
     * 仍是旧名。按新名读不到就当成没改过的话，屏幕上一切正常，发出去的模板却已经
     * 悄悄回到出厂默认——使用者改了很久的那段话就这么没了，而且没有任何提示。
     * @param handler 处理器
     * @return 覆盖参数的拷贝
     */
    public JSONObject overridesOf(@NonNull NovaEventHandler handler) {
        synchronized (lock) {
            JSONObject data = load();
            JSONObject stored = data.getJSONObject(handler.getClass().getName());
            for (String legacy : handler.legacyClassNames()) {
                if (stored != null) {
                    break;
                }
                if (legacy != null && !legacy.isBlank()) {
                    stored = data.getJSONObject(legacy);
                }
            }
            return stored == null ? new JSONObject() : stored.clone();
        }
    }

    /**
     * 存着的全部覆盖，键为处理器全类名
     * @return 拷贝
     */
    public JSONObject all() {
        synchronized (lock) {
            return load().clone();
        }
    }

    /**
     * 改这个处理器的默认参数
     * <p>
     * 传进来的是<b>整份覆盖</b>而不是增量：某个键不在里面就是「这一项回到出厂默认」，
     * 空对象即整个处理器回到出厂默认。增量语义下「删掉一个覆盖」没有说法，
     * 而那正是「恢复默认」按下去要做的事。
     * <p>
     * 校验不过时<b>一个字也不写</b>并回问题清单：写一半的默认模板会让一批通道
     * 立刻跟着变成半份，而这件事在界面上要等下一次推送才看得出来。
     * @param handler 处理器
     * @param overrides 整份覆盖，可为空对象
     * @return 问题清单，为空即已写盘
     */
    public List<String> save(@NonNull NovaEventHandler handler, JSONObject overrides) {
        JSONObject wanted = overrides == null ? new JSONObject() : overrides;
        List<String> issues = validate(handler, wanted);
        if (!issues.isEmpty()) {
            return issues;
        }

        String className = handler.getClass().getName();
        synchronized (lock) {
            JSONObject data = load();
            // 存过旧名的那一份一并清掉：留着的话，「恢复默认」把新名下那份删干净之后，
            // 读的一侧又会按旧名回落读回来——屏幕上显示已回到默认，发出去的还是那份旧覆盖
            for (String legacy : handler.legacyClassNames()) {
                data.remove(legacy);
            }

            if (wanted.isEmpty()) {
                data.remove(className);
            } else {
                data.put(className, wanted);
            }

            Path path = path();
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, data.toJSONString(), StandardCharsets.UTF_8);
                cache = data;
                log.info("默认模板已更新: {}", className);
            } catch (IOException e) {
                log.error("保存默认模板 {} 失败", path, e);
                // 缓存不推进：写盘没成，内存里就不许显示成已经改了。
                // 两边分叉时屏幕上一切正常，而重启之后改动凭空消失
                cache = null;
                return List.of("写入 " + path + " 失败: " + e.getMessage());
            }
        }
        return List.of();
    }

    /**
     * 这份覆盖能不能写
     * <p>
     * 三条都是奔着「配了却发不出去」那一类去的：这一层改的是<b>所有用默认的通道</b>，
     * 一个写错的占位符会同时出现在每一个群里，而现象只是消息里多了一串花括号。
     * @param handler 处理器
     * @param overrides 整份覆盖
     * @return 问题清单
     */
    private List<String> validate(NovaEventHandler handler, JSONObject overrides) {
        List<String> issues = new ArrayList<>();
        Set<String> allowed = writableKeys(handler);

        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            String key = entry.getKey();
            if (!allowed.contains(key)) {
                issues.add("这个处理器没有名为 " + key + " 的参数，可改的是: " + String.join("、", allowed));
                continue;
            }

            Object value = entry.getValue();
            if (AtMode.PARAM_KEY.equals(key)) {
                if (!(value instanceof String text) || AtMode.parse(text) == null) {
                    issues.add(AtMode.PARAM_KEY + " 只能是 " + AtMode.SUBSCRIBERS.key() + "、"
                            + AtMode.ALL.key() + "、" + AtMode.ALL_OR_SUBSCRIBERS.key());
                }
                continue;
            }

            if ("message".equals(key)) {
                issues.addAll(validateMessage(handler, value));
            }
        }

        return issues;
    }

    /**
     * 模板本身能不能用
     * @param handler 处理器
     * @param value 模板
     * @return 问题清单
     */
    private List<String> validateMessage(NovaEventHandler handler, Object value) {
        List<String> issues = new ArrayList<>();
        if (!(value instanceof String text)) {
            issues.add("message 必须是一段文字");
            return issues;
        }
        if (text.isBlank()) {
            // 空模板发不出任何东西，而界面上它与「配好了」长得一样。
            // 整类通知不想推，是在通道的「推什么」里把它关掉，不是把模板清空
            issues.add("模板不能为空。整类通知不想推的话，到通道的「推什么」里把它关掉");
            return issues;
        }

        Set<String> known = new LinkedHashSet<>(handler.placeholders());
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String found = matcher.group();
            if (!known.contains(found)) {
                issues.add("认不出的占位符 " + found + "，可用的是: " + String.join(" ", known));
            }
        }
        return issues;
    }

    /**
     * 这个处理器允许改的键：出厂默认里有的、自报的可配置项，加上 @ 谁那一档
     * <p>
     * {@code at_mode} 刻意不在出厂默认里（见 {@link AtMode}），因此得单列一条，
     * 否则默认模板上那个三选一存不下来。
     * @param handler 处理器
     * @return 可改的键
     */
    private Set<String> writableKeys(NovaEventHandler handler) {
        Set<String> keys = new LinkedHashSet<>();
        JSONObject factory = handler.getDefaultParams();
        if (factory != null) {
            keys.addAll(factory.keySet());
        }
        for (HandlerOption option : handler.options()) {
            keys.add(option.key());
        }
        keys.add(AtMode.PARAM_KEY);
        return keys;
    }

    /**
     * 读盘，读过一次就记在内存里
     * <p>
     * 文件损坏时当作没有覆盖，而不是让程序起不来：默认模板回到出厂值是可接受的降级，
     * 推送整个停摆不是。
     * @return 缓存本体，调用方必须持锁
     */
    private JSONObject load() {
        if (cache != null) {
            return cache;
        }

        Path path = path();
        try {
            cache = JSONObject.parseObject(Files.readString(path, StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            cache = new JSONObject();
        } catch (Exception e) {
            log.error("读取默认模板 {} 异常, 本次按出厂默认办", path, e);
            cache = new JSONObject();
        }

        if (cache == null) {
            cache = new JSONObject();
        }
        return cache;
    }

    /**
     * 文件位置：推送配置的同目录
     * @return 路径
     */
    Path path() {
        Path datasource = Path.of(properties.getDatasource().getJsonPath());
        Path parent = datasource.getParent();
        return parent == null ? Path.of(FILE_NAME) : parent.resolve(FILE_NAME);
    }
}
