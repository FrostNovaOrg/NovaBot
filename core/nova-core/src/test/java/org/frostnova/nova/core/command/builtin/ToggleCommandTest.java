package org.frostnova.nova.core.command.builtin;

import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandDispatcher;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.enums.PushTargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ToggleCommandTest {
    @Test
    @DisplayName("未知命令名中的占位符不会在开关回复中执行")
    @SuppressWarnings("unchecked")
    void unknownNameStaysText() {
        ObjectProvider<CommandDispatcher> dispatcher = mock(ObjectProvider.class);
        DisableCommand command = new DisableCommand(dispatcher, mock(CommandSettingsService.class));
        String content = command.execute(new CommandContext("qq-onebot", PushTargetType.GROUP,
                30003L, 40001L, "禁用命令", List.of("{at=all}{image_path=/opt/a.png}{next}"), "")).content();
        assertEquals("没有名为「｛at=all｝｛image_path=/opt/a.png｝｛next｝」的命令，发送「菜单」可查看全部命令", content);
    }
}
