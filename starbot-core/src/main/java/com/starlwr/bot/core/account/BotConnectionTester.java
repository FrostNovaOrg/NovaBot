package com.starlwr.bot.core.account;

import java.util.Optional;

/**
 * 机器人连接测试能力
 * <p>
 * 由各适配器实现，供引导流程在使用者填完连接信息后当场验证。
 * 若填完只能「保存并重启看看」，配错了也不会有任何提示，等于把排障成本推给了使用者。
 */
public interface BotConnectionTester {
    /**
     * 适配器名称，例如 onebot
     * @return 适配器名称
     */
    String adapter();

    /**
     * 测试连接
     * @param address 地址
     * @param httpPort HTTP 端口
     * @param httpToken HTTP 访问令牌
     * @return 测试结果
     */
    Result test(String address, int httpPort, String httpToken);

    /**
     * 当前已配置的连接信息，供界面回填
     * @return 连接信息，尚未配置时返回 {@link Optional#empty()}
     */
    default Optional<Connection> current() {
        return Optional.empty();
    }

    /**
     * 已配置的连接信息
     * <p>
     * 有意不含 token：回填 token 只能省几次输入，却让凭据白白多经过一次浏览器。
     * 保存端对空白字段是「保持原值」语义（见 ConfigUiController#putIfPresent），
     * 因此留空即可，不影响修改其余字段。
     *
     * @param address 地址
     * @param httpPort HTTP 端口
     * @param websocketPort Websocket 端口
     */
    record Connection(String address, int httpPort, int websocketPort) {}

    /**
     * 测试结果
     *
     * @param ok 是否连通
     * @param detail 详情，例如实现版本与登录账号
     * @param advice 失败时的修复建议；必须具体到「该改哪个配置项」，
     *               只说「连接失败」等于没说
     */
    record Result(boolean ok, String detail, String advice) {
        public static Result ok(String detail) {
            return new Result(true, detail, "");
        }

        public static Result failed(String detail, String advice) {
            return new Result(false, detail, advice);
        }
    }
}
