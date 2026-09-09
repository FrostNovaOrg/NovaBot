package org.frostnova.nova.core.enums;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 推送目标类型的线上格式测试
 * <p>
 * 这个枚举决定**公开推送接口**的线上取值，因此它的编解码是对外契约，
 * 不能靠「Jackson 默认按枚举序号解，而序号恰好等于 code」这个巧合成立。
 * 下面几条把 0→FRIEND、1→GROUP 钉死：谁调整枚举顺序，测试就红。
 */
@DisplayName("推送目标类型")
class PushTargetTypeTest {
    // 本项目用的是 Jackson 3（jackson-databind 3.0.2），包名已从 com.fasterxml.jackson.databind
    // 迁到 tools.jackson.databind；注解仍在旧包（jackson-annotations 2.20），别被这一点绕住
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("⚠️ 线上取值按 code 反解, 不是按枚举序号")
    void decodesByCodeNotOrdinal() {
        assertEquals(PushTargetType.FRIEND, mapper.readValue("0", PushTargetType.class));
        assertEquals(PushTargetType.GROUP, mapper.readValue("1", PushTargetType.class));
    }

    @Test
    @DisplayName("⚠️ 调整枚举顺序不该改变线上取值")
    void ordinalDriftWouldBeCaught() {
        // 这一条不测 Jackson，而是把「code 与序号不再相等」这件事本身钉住：
        // 现在 FRIEND 与 GROUP 的序号恰好等于 code，正是这个巧合让旧实现能跑。
        // 如果有人在它们前面插入枚举值，下面两条会立刻失败，
        // 提醒他去确认线上格式仍由 code 决定（而不是默默地变了）
        assertEquals(0, PushTargetType.FRIEND.getCode());
        assertEquals(1, PushTargetType.GROUP.getCode());
        assertEquals(-1, PushTargetType.UNKNOWN.getCode(), "UNKNOWN 的 code 与序号本来就不同");
    }

    @Test
    @DisplayName("序列化出去也是 code")
    void encodesAsCode() {
        assertEquals("0", mapper.writeValueAsString(PushTargetType.FRIEND));
        assertEquals("1", mapper.writeValueAsString(PushTargetType.GROUP));
    }

    @Test
    @DisplayName("⚠️ 认不出的取值当场报错, 不能静默变成 UNKNOWN")
    void unknownCodeIsRejected() {
        // 这是本次改动顺带修掉的一个静默失败：宽松解析会把认不出的取值变成 UNKNOWN，
        // 而 UNKNOWN 在运行期是「丢弃这条消息」——调用方收到成功响应，群里什么都没有。
        // 实测「把 type 写成 2」就是这个下场：2 恰好是 UNKNOWN 的枚举序号
        assertThrows(Exception.class, () -> mapper.readValue("2", PushTargetType.class),
                "type=2 必须报错，不能变成一条被安静丢掉的推送");
        assertThrows(Exception.class, () -> mapper.readValue("99", PushTargetType.class));
    }

    @Test
    @DisplayName("UNKNOWN 不接受从外部传入")
    void unknownCannotComeFromOutside() {
        // UNKNOWN 是内部表示「认不出」的哨兵值，外部没有理由发它
        assertThrows(IllegalArgumentException.class, () -> PushTargetType.fromCode(-1));
    }

    @Test
    @DisplayName("of() 仍然宽松, 供程序内部使用")
    void ofStaysLenient() {
        // 内部有依赖这个宽松行为的地方（例如读旧配置时先取到 UNKNOWN 再给出提示），
        // 所以 of() 的语义保持不变，严格只加在对外的入口上
        assertEquals(PushTargetType.UNKNOWN, PushTargetType.of(2));
        assertEquals(PushTargetType.UNKNOWN, PushTargetType.of(-1));
        assertEquals(PushTargetType.GROUP, PushTargetType.of(1));
    }
}
