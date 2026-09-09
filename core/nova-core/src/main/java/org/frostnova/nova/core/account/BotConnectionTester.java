package org.frostnova.nova.core.account;

import java.util.Map;
import java.util.Optional;

/**
 * 机器人连接能力
 * <p>
 * 由各适配器实现，供引导流程在使用者填完连接信息后当场验证，并在存下来之后当场接上。
 * 若填完只能「保存并重启看看」，配错了也不会有任何提示，等于把排障成本推给了使用者。
 */
public interface BotConnectionTester {
    /**
     * 适配器名称，由适配器自报
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
     * 运行期接上这份连接信息
     * <p>
     * 🔴 <b>这一步不做的话，引导流程走不完。</b>连接信息在进程启动时读一次，此后不再回头看：
     * 一台全新的机器上存下连接之后，「挑推送目标」「发一条试试」这两步面对的仍是一个
     * 什么都没连上的进程——取回来的名单是空的、消息发不出去，而屏幕上没有任何东西解释为什么。
     * 只能让人先去重启一次，而<b>「存好、重启、回来接着走」这句话本身就是一处缺陷</b>。
     * <p>
     * 空白的字段（地址、两个 Token）与非正数的端口一律<b>保持原值</b>，与保存端一致：
     * 界面有意不回填 Token，留空的语义是「这一项不改」，而不是「把它清掉」。
     * <p>
     * 反复调用是正常用法（使用者会连按保存），因此必须幂等：接上之后再接一次，
     * 结果仍是<b>一条</b>连接，旧的那条连同它的重连与检测任务一并回收。
     * @param address 地址，留空表示不改
     * @param httpPort HTTP 端口，非正数表示不改
     * @param websocketPort Websocket 端口，非正数表示不改
     * @param httpToken HTTP 访问令牌，留空表示不改
     * @param websocketToken Websocket 访问令牌，留空表示不改
     * @return 接上的结果
     */
    Applied apply(String address, int httpPort, int websocketPort, String httpToken, String websocketToken);

    /**
     * 运行期接上连接的结果
     * <p>
     * <b>{@code configuration} 由适配器给，落盘由核心做。</b>这条连接在配置文件里是一个列表元素，
     * 元素内部有哪几个键、叫什么名字，只有适配器自己知道；而「写哪个文件、写之前备份、
     * 不碰其余各行」是配置文件那一侧的规矩。两边各管各的，核心因此不必去猜适配器的键名，
     * 适配器也不必自己去改文件。
     * <p>
     * 一台还没有任何机器人的机器上，这份映射是<b>整条元素</b>（含平台名与接口路径）——
     * 只写地址与端口的话，文件里会落下一条没有名字的连接，而它在下次启动时直接让程序起不来。
     *
     * @param live 这条连接现在是不是已经接上了；false 表示仍要重启一次才生效
     * @param detail 一句话说明，界面直接显示给使用者
     * @param configuration 配置文件里这条连接该有的全部字段，键为列表元素内部的键，顺序即写入顺序
     */
    record Applied(boolean live, String detail, Map<String, String> configuration) {}

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
