package com.starlwr.bot.core.plugin;

import lombok.*;

import java.util.Objects;

/**
 * 依赖信息
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Dependency {
    /**
     * 组名
     */
    private String groupId;

    /**
     * 依赖名
     */
    private String artifactId;

    /**
     * 版本号
     */
    private String version;

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Dependency that)) return false;
        return Objects.equals(groupId, that.groupId) && Objects.equals(artifactId, that.artifactId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId);
    }

    /**
     * 获取依赖唯一标识符
     * @return 依赖唯一标识符
     */
    public String getId() {
        return groupId + ":" + artifactId;
    }
}
