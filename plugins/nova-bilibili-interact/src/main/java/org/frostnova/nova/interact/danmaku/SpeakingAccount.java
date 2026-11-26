package org.frostnova.nova.interact.danmaku;

import com.alibaba.fastjson2.JSONObject;

import java.util.Map;
import java.util.Optional;

/**
 * 发弹幕时用的那个账号
 * <p>
 * 两种：单独的发言账号，或监听直播间用的那个账号。这一层只答「能不能用」与「替我发一个请求」，
 * 凭据始终留在各自的接口工具里：这里不读、不存、不复制。
 */
interface SpeakingAccount {
    /**
     * 是哪一种
     * @return 选项
     */
    DanmakuAccountChoice choice();

    /**
     * 此刻为什么用不了
     * @return 拒绝的分类与说法，能用时为空
     */
    Optional<Refusal> refusal();

    /**
     * 账号 uid，用不了时为 null
     * @return uid
     */
    Long uid();

    /**
     * 限速、暂停与字数上限缓存按它分账：换了号就是另一本账
     * @return 账号标识，用不了时为 null
     */
    default String key() {
        Long uid = uid();
        return uid == null ? null : choice().value() + ":" + uid;
    }

    /**
     * 以这个账号的登录态请求一个读接口，地址里带好查询参数
     * @param url 接口地址
     * @return 回包里的 data
     */
    JSONObject get(String url);

    /**
     * 以这个账号的登录态发一次表单 POST：只发一次，回包整份交回
     * @param url 接口地址
     * @param form 表单参数，跨站校验口令由接口工具自己填
     * @return 完整回包
     */
    JSONObject postOnce(String url, Map<String, Object> form);

    /**
     * 用不了的理由
     * @param failure 分类
     * @param message 说给使用者的话
     */
    record Refusal(DanmakuFailure failure, String message) {
    }
}
