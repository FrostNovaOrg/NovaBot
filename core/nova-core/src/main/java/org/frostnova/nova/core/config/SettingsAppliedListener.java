package org.frostnova.nova.core.config;

import java.util.Map;
import java.util.Set;

/**
 * 设置页保存之后要跟着动一下的运行中组件
 * <p>
 * 把新值写回配置对象，只对「用到时现读」的读取方管用。定时任务的间隔、建好的发件器、
 * 日志输出的门槛，都是启动时按当时的值排好、建好的，配置对象改了它们也看不见。
 * 这些组件登记成本接口：保存那一步把新值写回之后，叫它们按新值重排、重建。
 * <p>
 * 收到的取值都已在写配置文件之前校验过，读不懂的那一批根本走不到这里。
 * 这里抛出的异常只说明这一次没能落到运行中的程序上，保存那一步会把这几项记成待重启，
 * <b>异常消息里不许带取值</b>：其中可能有发件密码。
 */
public interface SettingsAppliedListener {
    /**
     * 关心哪几项
     * @return 配置项名
     */
    Set<String> keys();

    /**
     * 这几项刚写回
     * @param changes 本次真正改了的、本组件关心的那几项：配置项名到保存下来的取值，
     *                {@code null} 表示删掉了这一项、回到默认
     */
    void applied(Map<String, String> changes);
}
