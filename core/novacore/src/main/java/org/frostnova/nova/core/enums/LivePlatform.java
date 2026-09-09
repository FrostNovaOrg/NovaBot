package org.frostnova.nova.core.enums;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直播平台标识
 * <p>
 * <b>这里不列平台。</b> 平台由各直播平台插件在自己那一侧登记，核心只认标识串并原样透传，
 * 因此核心不必认识任何一个具体平台——接一个新平台不用改核心一个字，把某个平台的插件摘掉
 * 也不会在核心里留下一个认不出实现的名字。
 * <p>
 * <b>标识串（{@link #id()}）就是落到配置文件、数据库与对外接口上的那个值</b>，
 * 一经登记不要再改：改一次，既有部署里按旧串存下的数据就认不回来了。
 * 本类不对标识串做去空格、转小写一类的规整，登记时写的是什么，存出去的就是什么。
 * <p>
 * 显示名（{@link #displayName()}）只用于给人看，登记方不给时取标识串本身。
 * <p>
 * 用法：插件在自己的常量类里登记一次，此后各处引用那个常量
 * <pre>{@code
 * public final class ExamplePlatform {
 *     public static final LivePlatform EXAMPLE = LivePlatform.of("example", "示例直播");
 * }
 * }</pre>
 * 拿到未登记的标识串（例如从旧配置或别处的数据里读回来的）时，{@link #of(String)}
 * 会照原串建一个实例返回而<b>不抛异常</b>：认不得的平台也不该让既有数据读不出来。
 */
public final class LivePlatform {
    /**
     * 标识串到实例的登记表。同一标识串全程只有一个实例，等值判断因此既可按引用也可按标识串
     */
    private static final ConcurrentHashMap<String, LivePlatform> REGISTRY = new ConcurrentHashMap<>();

    /**
     * 标识串，落到配置、数据库与对外接口上的值
     */
    private final String id;

    /**
     * 显示名，只给人看。登记方未给时与标识串相同
     */
    private volatile String displayName;

    private LivePlatform(String id) {
        this.id = id;
        this.displayName = id;
    }

    /**
     * 按标识串取得平台，未登记过的照原串建一个
     * @param id 标识串
     * @return 平台标识
     */
    public static LivePlatform of(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("直播平台标识串不能为空");
        }
        return REGISTRY.computeIfAbsent(id, LivePlatform::new);
    }

    /**
     * 登记平台并给出显示名
     * <p>
     * 显示名以最后一次登记为准：标识串可能先被别处按串取用过（例如读回既有数据早于插件装载），
     * 那时还没人知道它该显示成什么，此处补上。标识串本身不会因此改变。
     * @param id 标识串
     * @param displayName 显示名，为空时取标识串本身
     * @return 平台标识
     */
    public static LivePlatform of(String id, String displayName) {
        LivePlatform platform = of(id);
        if (displayName != null && !displayName.isBlank()) {
            platform.displayName = displayName;
        }
        return platform;
    }

    /**
     * 标识串
     * @return 标识串
     */
    public String id() {
        return id;
    }

    /**
     * 显示名
     * @return 显示名
     */
    public String displayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LivePlatform other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
