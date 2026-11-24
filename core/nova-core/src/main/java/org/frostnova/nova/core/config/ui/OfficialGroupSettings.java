package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.ChannelSettings;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.SessionKey;
import org.frostnova.nova.core.service.SessionQuietHoursService;
import org.frostnova.nova.core.service.SessionQuietHoursService.Mode;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/** 计算新通道要保存的设置，并用同一份结果说明切换后会怎样生效。 */
final class OfficialGroupSettings {
    private static final PushTargetType GROUP = PushTargetType.GROUP;
    private static final Set<String> ALWAYS_ENABLED = Set.of("菜单", "启用命令", "禁用命令");
    private static final String ASSISTANT_COMMAND = "问";
    private final NovaStateStore state;
    private final RevenueVisibilityService revenue;
    private final SessionQuietHoursService quiet;

    OfficialGroupSettings(NovaStateStore state, RevenueVisibilityService revenue, SessionQuietHoursService quiet) {
        this.state = state;
        this.revenue = revenue;
        this.quiet = quiet;
    }

    JSONObject resolve(PushTarget source, String official, long officialNum) {
        JSONObject target = new JSONObject();

        boolean oldVisible = revenue.isVisible(source);
        Boolean officialVisible = revenue.explicit(official, GROUP, officialNum);
        boolean officialHides = Boolean.FALSE.equals(officialVisible);
        boolean visible = oldVisible && !officialHides;
        if (officialHides) target.put("revenueVisible", false);
        String amountResult = "金额可见：" + (officialHides ? "按官方群现有设置" : "")
                + (visible ? "显示金额" : "隐藏金额")
                + (!oldVisible && Boolean.TRUE.equals(officialVisible) ? "（沿用原通道的较严设置）" : "")
                + difference(visible != oldVisible);

        var configuredQuiet = source.getQuietHours();
        QuietHours oldQuiet = configuredQuiet == null ? QuietHours.FOLLOW
                : QuietHours.of(Mode.of(configuredQuiet.getMode()), configuredQuiet.getStart(), configuredQuiet.getEnd());
        QuietHours officialQuiet = sessionQuiet(official, officialNum);
        boolean officialQuietSet = officialQuiet.mode() != Mode.FOLLOW || explicitFollow(official, officialNum);
        QuietHours appliedQuiet = officialQuietSet ? officialQuiet : oldQuiet;
        boolean quietChanged = !appliedQuiet.equals(oldQuiet);
        if (quietChanged) target.put("quietHours", appliedQuiet.json());
        String quietResult = "静音时段：" + (officialQuietSet ? "按官方群现有设置 " : "")
                + appliedQuiet.description() + (quietChanged ? "，原来" + oldQuiet.description() : "") + "。";

        JSONObject oldSessionCommands = sessionCommands(source.getPlatform(), source.getNum());
        JSONObject officialSessionCommands = sessionCommands(official, officialNum);
        List<String> disabled = ChannelSettings.COMMAND_KEYS.stream()
                .filter(key -> ChannelSettings.disabled(source, key)).sorted().toList();
        String commandResult = "通道命令开关：" + commandDescription(disabled) + "。";
        List<String> oldGroupCommands = groupCommands(oldSessionCommands);
        List<String> appliedGroupCommands = officialSessionCommands == null ? oldGroupCommands : groupCommands(officialSessionCommands);
        String groupCommandResult = "群命令开关：" + (officialSessionCommands == null ? "" : "按官方群现有设置，")
                + commandDescription(appliedGroupCommands) + difference(!appliedGroupCommands.equals(oldGroupCommands));

        boolean permitsAssistant = Boolean.TRUE.equals(source.getAssistantEnabled());
        boolean oldAssistant = permitsAssistant && !oldGroupCommands.contains(ASSISTANT_COMMAND);
        boolean groupBlocksAssistant = appliedGroupCommands.contains(ASSISTANT_COMMAND);
        if (permitsAssistant && !oldAssistant && !groupBlocksAssistant) {
            target.put("assistantEnabled", false);
            permitsAssistant = false;
        }
        boolean assistant = permitsAssistant && !groupBlocksAssistant;
        String assistantResult = "问数开关："
                + (groupBlocksAssistant && officialSessionCommands != null ? "按官方群现有设置" : "")
                + (assistant ? "开启" : "关闭") + difference(assistant != oldAssistant);

        return JSONObject.of("targetSettings", target, "results",
                List.of(amountResult, quietResult, commandResult, groupCommandResult, assistantResult));
    }

    JSONObject groupCommandsToCopy(PushTarget source) {
        JSONObject commands = sessionCommands(source.getPlatform(), source.getNum());
        if (commands == null) return null;
        JSONObject copy = new JSONObject(commands);
        copy.keySet().removeIf(key -> !isGroupCommand(key));
        return copy.isEmpty() ? null : copy;
    }

    private JSONObject sessionCommands(String platform, long num) {
        String key = SessionKey.require(platform, GROUP, num);
        return state.read(CommandSettingsService.NAMESPACE, key, data -> data.getJSONObject(key)).orElse(null);
    }

    private static List<String> groupCommands(JSONObject commands) {
        return commands == null ? List.of() : commands.keySet().stream()
                .filter(OfficialGroupSettings::isGroupCommand).sorted().toList();
    }

    private static boolean isGroupCommand(String key) {
        return !ChannelSettings.isKnownCommandKey(key) && !ALWAYS_ENABLED.contains(key);
    }

    private QuietHours sessionQuiet(String platform, long num) {
        var setting = quiet.get(platform, GROUP, num);
        return QuietHours.of(setting.mode(), setting.start(), setting.end());
    }

    private boolean explicitFollow(String platform, long num) {
        String key = SessionKey.require(platform, GROUP, num);
        return state.read(SessionQuietHoursService.NAMESPACE, key, data -> data.get(key))
                .filter(JSONObject.class::isInstance).map(JSONObject.class::cast)
                .map(data -> "follow".equalsIgnoreCase(data.getString("mode"))).orElse(false);
    }

    private static String commandDescription(List<String> disabled) {
        return disabled.isEmpty() ? "未关闭命令" : "已关闭「" + String.join("」「", disabled) + "」";
    }

    private static String difference(boolean changed) {
        return changed ? "，和原来不同。" : "。";
    }

    private record QuietHours(Mode mode, String start, String end) {
        static final QuietHours FOLLOW = new QuietHours(Mode.FOLLOW, null, null);
        static final QuietHours OFF = new QuietHours(Mode.OFF, null, null);

        static QuietHours of(Mode mode, String start, String end) {
            if (mode == Mode.FOLLOW) return FOLLOW;
            if (mode == Mode.OFF || start == null || end == null) return OFF;
            try {
                LocalTime from = LocalTime.parse(start.trim());
                LocalTime to = LocalTime.parse(end.trim());
                return from.equals(to) ? OFF : new QuietHours(Mode.CUSTOM, from.toString(), to.toString());
            } catch (DateTimeParseException error) {
                return OFF;
            }
        }

        JSONObject json() {
            return mode == Mode.CUSTOM ? JSONObject.of("mode", mode.key(), "start", start, "end", end)
                    : JSONObject.of("mode", mode.key());
        }

        String description() {
            return switch (mode) {
                case FOLLOW -> "跟随全局";
                case OFF -> "不静音";
                case CUSTOM -> start + "–" + end;
            };
        }
    }
}
