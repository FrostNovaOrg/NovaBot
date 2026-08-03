package com.starlwr.bot.bilibili.model;

import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Optional;

/**
 * 动态
 * <p>
 * 动态接口返回的结构随类型差异极大，且字段会随版本变动，因此仅提取稳定的少量字段作为强类型属性，
 * 其余内容保留原始 JSON，由绘制逻辑按需取用。
 */
@Getter
@Setter
@NoArgsConstructor
public class Dynamic {
    /**
     * 动态 ID
     */
    private String id;

    /**
     * 动态类型，例如 DYNAMIC_TYPE_AV、DYNAMIC_TYPE_DRAW、DYNAMIC_TYPE_FORWARD
     */
    private String type;

    /**
     * 动态是否可见
     */
    private Boolean visible;

    /**
     * 动态基础信息
     */
    private JSONObject basic;

    /**
     * 动态各模块内容
     */
    private JSONObject modules;

    /**
     * 被转发的原动态，仅转发动态存在
     */
    private Dynamic origin;

    /**
     * 判断是否为转发动态
     * @return 是否为转发动态
     */
    public boolean isForward() {
        return "DYNAMIC_TYPE_FORWARD".equals(type);
    }

    /**
     * 获取动态发布者的 uid
     * @return 发布者 uid
     */
    public Optional<Long> getAuthorUid() {
        return author().map(author -> author.getLong("mid"));
    }

    /**
     * 获取动态发布者昵称
     * @return 发布者昵称
     */
    public Optional<String> getAuthorName() {
        return author().map(author -> author.getString("name"));
    }

    /**
     * 获取动态发布者头像地址
     * @return 发布者头像地址
     */
    public Optional<String> getAuthorFace() {
        return author().map(author -> author.getString("face"));
    }

    /**
     * 获取动态发布时间
     * @return 发布时间
     */
    public Optional<Instant> getPublishTime() {
        return author()
                .map(author -> author.getLong("pub_ts"))
                .map(Instant::ofEpochSecond);
    }

    /**
     * 获取动态跳转地址
     * @return 跳转地址
     */
    public String getUrl() {
        return "https://t.bilibili.com/" + id;
    }

    /**
     * 取出作者模块
     * @return 作者模块
     */
    private Optional<JSONObject> author() {
        return Optional.ofNullable(modules).map(m -> m.getJSONObject("module_author"));
    }
}
