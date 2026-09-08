package com.starlwr.bot.adapter.onebot.console;

import com.starlwr.bot.core.config.ui.vocab.ConsoleVocabulary;
import com.starlwr.bot.core.plugin.NovaComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OneBot 适配器供给控制台的人话词
 * <p>
 * 核心界面只写中性兜底；装了本插件之后，连接页、日志入口和导航里该出现的
 * 实现名、平台名、群号／号码叫法从这里来。
 */
@Slf4j
@Component
@NovaComponent
public class OneBotConsoleVocabulary implements ConsoleVocabulary {
    @Override
    public String id() {
        return "onebot";
    }

    @Override
    public Map<String, String> terms() {
        Map<String, String> terms = new LinkedHashMap<>();
        terms.put("bot.platform", "QQ");
        terms.put("bot.impl", "NapCat");
        terms.put("bot.family", "OneBot 实现");
        terms.put("bot.impl.hint", "NapCat、Lagrange 等 OneBot 实现");
        terms.put("bot.target.group", "群号");
        terms.put("bot.target.user", "QQ 号");
        terms.put("bot.targets", "群与好友");
        return Map.copyOf(terms);
    }
}
