package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 通行密钥列表：画进传入的那只容器
 * <p>
 * 设置页那张卡是游离节点，容器与登记按钮由调用方传进 loadPasskeys。源码级正则只认
 * 「声明形」的按 id 取，赋值形或换 document.querySelector 的全局取一概看不见；
 * 夹具在真 DOM 形状的游离容器上真执行切段，量的是行为不是写法。
 * 夹具读源码树里那一份，不是构建产物里的副本。切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("通行密钥列表画进传入容器")
class PasskeysListTest {

    @Test
    @DisplayName("列表画进传入 box；空列表提示在；删除后重画进接线当次的容器")
    void listPaintsIntoPassedBoxAndDeleteRepaintsIt() throws IOException, InterruptedException {
        FrontendFixture.run(FrontendFixture.fixture("passkeys-list-fixture.mjs"), "通行密钥列表画进传入容器");
    }
}
