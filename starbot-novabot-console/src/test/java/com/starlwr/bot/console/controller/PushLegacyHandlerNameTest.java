package com.starlwr.bot.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 推送页遇到旧全类名的处理器：界面说的与机器人做的必须是同一件事
 * <p>
 * 处理器换过包名之后，使用者 {@code datasource.json} 里那一条写的仍是旧名。后端认得出旧名，
 * 前端按主名严格比就对不上，于是开关显示成「关」而机器人照推、取消勾选删不掉那一条、
 * 旧名下的自定义模板读不到而页面报「默认模板 ✓」——三条<b>都不报错</b>，
 * 界面点一遍看不出任何异常，只有老使用者升级之后才撞得上。
 * <p>
 * 夹具读源码树里那一份，不是构建产物里的副本；toggleNotice 按花括号配平切出整块后真执行。
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("推送页旧全类名")
class PushLegacyHandlerNameTest {
    private static final String FIXTURE =
            "starbot-novabot-console/src/test/resources/frontend/push-legacy-handler-fixture.mjs";

    @Test
    @DisplayName("开关、删除、模板、版式、采纳一律归一到主名；只读不改文件，别名不张开到别类")
    void legacyNamedEntriesAreRecognised() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "推送页旧全类名");
    }
}
