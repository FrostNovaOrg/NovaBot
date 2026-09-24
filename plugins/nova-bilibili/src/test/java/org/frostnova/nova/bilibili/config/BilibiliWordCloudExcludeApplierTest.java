package org.frostnova.nova.bilibili.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词云屏蔽名单保存后写回正在运行的配置。
 */
@DisplayName("词云屏蔽名单即时生效")
class BilibiliWordCloudExcludeApplierTest {
    @Test
    @DisplayName("保存一份名单后，运行中的配置就是这一份；清空则回到空")
    void saveReplacesTheRunningList() {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        BilibiliWordCloudExcludeApplier applier = new BilibiliWordCloudExcludeApplier(properties);

        applier.appliers().get(BilibiliWordCloudExcludeApplier.KEY)
                .accept("19000000000201\n\n19000000000202");
        assertEquals(List.of("19000000000201", "19000000000202"),
                properties.getLive().getWordCloudExcludeUids());

        applier.appliers().get(BilibiliWordCloudExcludeApplier.KEY).accept("  ");
        assertTrue(properties.getLive().getWordCloudExcludeUids().isEmpty(), "清空后名单还在");
    }
}
