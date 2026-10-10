package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置页组目录的落点：窄屏上顶栏与目录都吸在视口顶上，标题得让出它们的高度
 * <p>
 * 抓的用户故障：平板、手机或放大页面（959px 以下）时，侧栏变成吸顶的顶部导航，
 * 组目录也吸顶。点左边目录的条目跳过去，滚到位的组标题正好被这两块盖住——
 * 看起来像「点了没反应」。顶栏里的内容会折行，高度不是定数，因此量的是实测高度，
 * 不写死数。第五格量两处各写的一个数：focusGroup 的断点必须与 app.css 的同一处。
 * <p>
 * 吸顶那半是纯样式叠层（目录压到顶栏底下），合进以后由真浏览器量，不在本夹具。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("组目录落点让出吸顶元素")
class SettingsFocusLandingTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-focus-landing-fixture.mjs");

    @Test
    @DisplayName("窄屏落点扣掉顶栏与目录的实测高度，宽屏照旧，断点与样式表同一处")
    void focusGroupLandsBelowStickyBars() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "组目录落点");
    }
}
