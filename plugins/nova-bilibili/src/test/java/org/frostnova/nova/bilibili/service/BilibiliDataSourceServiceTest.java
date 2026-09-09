package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.model.Up;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.datasource.DataSourceService.StreamerWithFans;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 查主播「补全＋粉丝数」往平台打的趟数
 * <p>
 * 昵称、房间号与粉丝数出自主播信息接口（MASTER_INFO_API）的同一份响应。
 * 此前补全一趟、粉丝数再一趟，查一位主播要打两趟；本族钉住一趟这个数——
 * 桩上给粉丝数单留的那趟若又被调，这里的读数会先于任何人看见地翻倍。
 */
@DisplayName("查主播连粉丝数")
class BilibiliDataSourceServiceTest {
    private static final long UID = 10001L;

    private BilibiliApiUtil api;

    /**
     * 往主播信息接口去的趟数：getUpInfoByUid 与 getFansCount 打的都是它，桩上各计一票
     */
    private final AtomicInteger masterInfoTrips = new AtomicInteger();

    @BeforeEach
    void setUp() {
        api = mock(BilibiliApiUtil.class);
    }

    @Test
    @DisplayName("一次「补全＋粉丝数」只向主播信息接口去一趟")
    void completesAndFetchesFansInOneMasterInfoTrip() {
        Up up = new Up(UID, "主播甲", 20002L, "https://example.invalid/face.jpg", 243L);
        when(api.getUpInfoByUid(UID)).thenAnswer(invocation -> {
            masterInfoTrips.incrementAndGet();
            return up;
        });
        when(api.getFansCount(UID)).thenAnswer(invocation -> {
            masterInfoTrips.incrementAndGet();
            return Optional.of(243L);
        });

        PushUser user = new PushUser();
        user.setUid(UID);
        user.setPlatform("bilibili");
        StreamerWithFans found = new BilibiliDataSourceService(api).completeStreamerWithFans(user);

        assertEquals("主播甲", found.user().getUname(), "补全真的发生了，不是一趟都没打");
        assertEquals(243L, found.fans(), "粉丝数随同一趟响应带回");
        assertEquals(1, masterInfoTrips.get(), "昵称、房间号与粉丝数在主播信息接口的同一份响应里，一趟就该全拿到");
    }
}
