package com.starlwr.maven.plugin.model;

import lombok.*;

/**
 * 依赖信息
 */
@Getter
@Setter
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
    public String toString() {
        return groupId + ":" + artifactId + "-v" + version;
    }
}
