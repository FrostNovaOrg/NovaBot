package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.Test;

class MembersFrontendTest {
    @Test
    void membersAndPersonalMenu() throws Exception {
        FrontendFixture.runFromModule("src/test/resources/frontend/settings-members-fixture.mjs", "成员与个人菜单");
    }
}
