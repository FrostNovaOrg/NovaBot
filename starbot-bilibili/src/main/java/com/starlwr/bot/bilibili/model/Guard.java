package com.starlwr.bot.bilibili.model;

import com.starlwr.bot.bilibili.enums.GuardType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 大航海信息
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Guard {
    /**
     * 大航海类型
     */
    private GuardType guardType;

    /**
     * 大航海图标地址
     */
    private String icon;

    public Guard(GuardType guardType) {
        this.guardType = guardType;
    }

    public Guard(Integer guardType) {
        this(GuardType.of(guardType == null ? -1 : guardType));
    }

    public Guard(Integer guardType, String icon) {
        this(GuardType.of(guardType == null ? -1 : guardType), icon);
    }
}
