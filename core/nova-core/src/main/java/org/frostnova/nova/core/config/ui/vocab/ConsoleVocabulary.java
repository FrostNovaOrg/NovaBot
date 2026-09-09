package org.frostnova.nova.core.config.ui.vocab;

import java.util.Map;

/**
 * 控制台人话词注册点
 * <p>
 * 各平台插件实现本接口并注册为 Bean，即可把「这个平台叫什么、机器人实现叫什么、
 * 群和好友在那边怎么称呼」供给控制台。核心只保管这张词表，<b>不认识任何一个具体平台</b>：
 * 界面上的中性兜底写在核心里，带名字的那一版由插件在运行时填回来。
 * <p>
 * 存在的意义是把「装了哪个平台」从文案里挪走。此前连接页、导航、日志入口把一个具体
 * 平台的名字写死在核心的界面文件里，于是没装那个插件的实例也满屏都是那个名字；
 * 想加第二个平台时，又得回头改核心的界面。
 */
public interface ConsoleVocabulary {
    /**
     * 供方标识，只含 ASCII
     * <p>
     * 给日志用，不进词表、不进界面。
     * @return 供方标识
     */
    String id();

    /**
     * 这一份人话词
     * <p>
     * 键必须落在 {@link ConsoleVocabularies#KEYS} 里，闭集外的键会被丢掉。
     * 值为空或只含空白视同没供这一项。
     * @return 键到人话的映射
     */
    Map<String, String> terms();
}
