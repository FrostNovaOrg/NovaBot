#!/usr/bin/env bash
#
# 把正在跑的分目录实例换成指定版本。
# 能热交接就热交接（新版本先起到门口等锁，再停旧版本），不能就普通重启。
# 失败把旧版本起回来。回滚就是再调一次，目标写成上一版。
#
# 用法：
#   sudo /usr/local/sbin/novabot-switch-version <目标版本>
#   sudo /usr/local/sbin/novabot-switch-version --plan <目标版本>  只看这次走热交接还是普通重启，不改机器
#
# 安装目录不按脚本自己的位置推。缺省写在下面那一行，装到系统目录时替换它。
#
# 可改位置的环境变量（测试替身用，缺省是本机路径）：
#   NOVABOT_INSTALL_DIR         缺省 /opt/starbot
#   NOVABOT_PROC_LOCKS          缺省 /proc/locks
#   NOVABOT_PROC_MEMINFO        缺省 /proc/meminfo
#   NOVABOT_SYSTEMD_RUN_DIR     缺省 /run/systemd/system
#   NOVABOT_STATE_DIR           缺省 <安装目录>/.novabot-switch
#
# 可改时长的环境变量（缺省就是原来写死的秒数；只认正整数，给错了照缺省并在标准错误说一句）：
#   NOVABOT_STAY_ACTIVE_SECONDS           缺省 10  就绪后稳住的秒数
#   NOVABOT_STAY_ACTIVE_RESTART_SECONDS   缺省 60  没有 handover=1 时普通启动后稳住的秒数
#   NOVABOT_READY_TIMEOUT_SECONDS         缺省 180 等到 ready 的上限秒数
#   NOVABOT_WAITING_TIMEOUT_SECONDS       缺省 120 等到门口（waiting）的上限秒数
#
set -euo pipefail

say() {
    printf '%s\n' "$*"
}

PLAN=no
if [ "${1:-}" = "--plan" ]; then
    PLAN=yes
    shift
fi
if [ "$#" -ne 1 ]; then
    say "用法：sudo /usr/local/sbin/novabot-switch-version [--plan] <目标版本>"
    exit 1
fi

TARGET=$1
if [ -z "$TARGET" ]; then
    say "目标版本号不能是空的。"
    exit 1
fi
case "$TARGET" in
    .*|-*|*..*|*.|*[!A-Za-z0-9._-]*)
        say "目标版本号不能当作目录名。"
        exit 1
        ;;
esac

if [ "$PLAN" = yes ]; then
    say "只看不动：把正在跑的实例换成 ${TARGET} 会怎么走。"
fi

script_dir=$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SCRIPT_PATH="$script_dir/$(basename "${BASH_SOURCE[0]}")"
INSTALL_DIR="${NOVABOT_INSTALL_DIR:-/opt/starbot}"
if [ ! -d "$INSTALL_DIR/releases" ]; then
    say "安装目录里没有 releases。"
    exit 1
fi

PROC_LOCKS="${NOVABOT_PROC_LOCKS:-/proc/locks}"
PROC_MEMINFO="${NOVABOT_PROC_MEMINFO:-/proc/meminfo}"
RUN_DIR="${NOVABOT_SYSTEMD_RUN_DIR:-/run/systemd/system}"
STATE_DIR="${NOVABOT_STATE_DIR:-$INSTALL_DIR/.novabot-switch}"

# 时长只认正整数。没设就用缺省；设了不算正整数就照缺省，并在标准错误说一句。
pick_seconds() {
    local name="$1"
    local raw="$2"
    local fallback="$3"
    if [ -z "$raw" ]; then
        printf '%s' "$fallback"
        return 0
    fi
    case "$raw" in
        *[!0-9]*|0*)
            printf '%s\n' "${name} 只认正整数，${raw} 不算，这次照缺省 ${fallback} 秒。" >&2
            printf '%s' "$fallback"
            return 0
            ;;
    esac
    printf '%s' "$raw"
}

STAY_ACTIVE_SECONDS=$(pick_seconds NOVABOT_STAY_ACTIVE_SECONDS "${NOVABOT_STAY_ACTIVE_SECONDS:-}" 10)
STAY_ACTIVE_RESTART_SECONDS=$(pick_seconds NOVABOT_STAY_ACTIVE_RESTART_SECONDS "${NOVABOT_STAY_ACTIVE_RESTART_SECONDS:-}" 60)
READY_TIMEOUT_SECONDS=$(pick_seconds NOVABOT_READY_TIMEOUT_SECONDS "${NOVABOT_READY_TIMEOUT_SECONDS:-}" 180)
WAITING_TIMEOUT_SECONDS=$(pick_seconds NOVABOT_WAITING_TIMEOUT_SECONDS "${NOVABOT_WAITING_TIMEOUT_SECONDS:-}" 120)

# 更早的单元名。拆开写，模板正文里不出现「名字加点」那种旧键字面。
EARLIER_UNIT=starbot
OLD=""
OLD_STOP_ISSUED=0
TARGET_START_ISSUED=0
TARGET_READY=0
SWITCH_DONE=0
SERVICE_USER=root
PHASE_NOW=""
LOCK_KIND=missing
MEM_KIND=unread
ACTIVE_VERSIONS=""
ENABLED_VERSIONS=""
NORMAL_LIST=""
RESTART_LIST=""
GAP_START=0
GAP_END=0

if [ ! -d "$INSTALL_DIR/releases/$TARGET" ]; then
    say "安装目录里没有版本 ${TARGET}。"
    exit 1
fi

unit_of() {
    printf 'novabot@%s' "$1"
}

state_file_of() {
    printf '%s/%s.state' "$STATE_DIR" "$1"
}

dropin_dir_of() {
    printf '%s/novabot@%s.service.d' "$RUN_DIR" "$1"
}

read_phase() {
    local f="$1"
    local line
    PHASE_NOW=""
    if [ -L "$f" ]; then
        return 0
    fi
    [ -f "$f" ] || return 0
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            phase=*)
                PHASE_NOW=${line#phase=}
                PHASE_NOW=${PHASE_NOW%$'\r'}
                return 0
                ;;
        esac
    done < "$f"
}

has_handover() {
    local f="$INSTALL_DIR/releases/$1/BUILD-INFO"
    [ -f "$f" ] || return 1
    grep -q '^handover=1$' "$f"
}

lookup_user() {
    local user
    user=$(systemctl show -p User --value "$(unit_of "$TARGET")" 2>/dev/null || true)
    user=$(printf '%s' "$user" | tr -d '[:space:]')
    if [ -z "$user" ]; then
        user=root
    fi
    SERVICE_USER=$user
}

state_dir_ok() {
    if [ -L "$STATE_DIR" ] || { [ -e "$STATE_DIR" ] && [ ! -d "$STATE_DIR" ]; }; then
        say "状态目录已在，但是符号链接或不是目录，这次什么都没改。"
        return 1
    fi
    return 0
}

prepare_state_dir() {
    if ! state_dir_ok; then
        return 1
    fi
    mkdir -p "$STATE_DIR"
    if ! chown -h "$SERVICE_USER:$SERVICE_USER" "$STATE_DIR"; then
        if ! chown -h "$SERVICE_USER" "$STATE_DIR"; then
            say "状态目录没有交给用户 ${SERVICE_USER}。"
            return 1
        fi
    fi
    return 0
}

write_dropin() {
    local ver="$1"
    local standby="$2"
    local dir file state
    dir=$(dropin_dir_of "$ver")
    if ! mkdir -p "$dir"; then
        return 1
    fi
    file="$dir/novabot-switch.conf"
    state=$(state_file_of "$ver")
    if [ "$standby" = yes ]; then
        if ! cat > "$file" <<EOF
[Service]
Environment="NOVABOT_STANDBY=true"
Environment="NOVABOT_STATE_FILE=$state"
EOF
        then
            return 1
        fi
    else
        if ! cat > "$file" <<EOF
[Service]
Environment="NOVABOT_STATE_FILE=$state"
EOF
        then
            return 1
        fi
    fi
    rm -f "$state"
}

remove_dropin() {
    local ver="$1"
    local dir
    [ -n "$ver" ] || return 0
    dir=$(dropin_dir_of "$ver")
    rm -f "$dir/novabot-switch.conf" || true
    rmdir "$dir" 2>/dev/null || true
}

# 等状态件出现 waiting。0 到了；1 进程没了；2 超过 WAITING_TIMEOUT_SECONDS 秒；3 没经过 waiting 就 passed/ready；4 安全模式。
wait_phase_waiting() {
    local unit="$1"
    local file="$2"
    local limit="$3"
    local deadline now
    deadline=$(( $(date +%s) + limit ))
    while true; do
        now=$(date +%s)
        read_phase "$file"
        case "$PHASE_NOW" in
            waiting) return 0 ;;
            passed|ready) return 3 ;;
            safe-mode) return 4 ;;
        esac
        if ! systemctl is-active --quiet "$unit"; then
            return 1
        fi
        if [ "$now" -ge "$deadline" ]; then
            return 2
        fi
        sleep 1
    done
}

# 等状态件到 ready。0 到了；1 进程没了；2 超时；4 安全模式。
wait_phase_ready() {
    local unit="$1"
    local file="$2"
    local limit="$3"
    local deadline now
    deadline=$(( $(date +%s) + limit ))
    while true; do
        now=$(date +%s)
        read_phase "$file"
        case "$PHASE_NOW" in
            ready) return 0 ;;
            safe-mode) return 4 ;;
        esac
        if ! systemctl is-active --quiet "$unit"; then
            return 1
        fi
        if [ "$now" -ge "$deadline" ]; then
            return 2
        fi
        sleep 1
    done
}

# 其后若干秒一直 active。状态件若进了安全模式也算没稳住。
stay_active() {
    local unit="$1"
    local seconds="$2"
    local file="$3"
    local begin now
    begin=$(date +%s)
    while true; do
        if ! systemctl is-active --quiet "$unit"; then
            return 1
        fi
        if [ -n "$file" ]; then
            read_phase "$file"
            if [ "$PHASE_NOW" = "safe-mode" ]; then
                return 1
            fi
        fi
        now=$(date +%s)
        if [ $((now - begin)) -ge "$seconds" ]; then
            return 0
        fi
        sleep 1
    done
}

ordinary_start() {
    local ver="$1"
    local unit file wait_rc
    unit=$(unit_of "$ver")
    file=$(state_file_of "$ver")
    say "普通启动 ${ver}。"
    if has_handover "$ver"; then
        write_dropin "$ver" no
        systemctl daemon-reload
        if [ "$ver" = "$TARGET" ]; then
            TARGET_START_ISSUED=1
        fi
        if ! systemctl start "$unit"; then
            say "启动 $ver 失败。"
            remove_dropin "$ver"
            systemctl daemon-reload || true
            return 1
        fi
        wait_rc=0
        wait_phase_ready "$unit" "$file" "$READY_TIMEOUT_SECONDS" || wait_rc=$?
        if [ "$wait_rc" -ne 0 ]; then
            remove_dropin "$ver"
            systemctl daemon-reload || true
            return 1
        fi
        if ! stay_active "$unit" "$STAY_ACTIVE_SECONDS" "$file"; then
            remove_dropin "$ver"
            systemctl daemon-reload || true
            return 1
        fi
        remove_dropin "$ver"
        systemctl daemon-reload
        return 0
    fi
    if [ "$ver" = "$TARGET" ]; then
        TARGET_START_ISSUED=1
    fi
    if ! systemctl start "$unit"; then
        say "启动 $ver 失败。"
        return 1
    fi
    if ! stay_active "$unit" "$STAY_ACTIVE_RESTART_SECONDS" ""; then
        return 1
    fi
    return 0
}

display_name() {
    local name="$1"
    case "$name" in
        novabot|starbot)
            printf '旧服务 %s' "$name"
            ;;
        *)
            if [ -n "$OLD" ] && [ "$name" = "$OLD" ]; then
                printf '旧版 %s' "$name"
            else
                printf '%s' "$name"
            fi
            ;;
    esac
}

add_running() {
    local kind="$1"
    local label="$2"
    if [ "$kind" = "active" ]; then
        if [ -n "$NORMAL_LIST" ]; then
            NORMAL_LIST="${NORMAL_LIST}、${label}"
        else
            NORMAL_LIST=$label
        fi
    elif [ "$kind" = "activating" ]; then
        if [ -n "$RESTART_LIST" ]; then
            RESTART_LIST="${RESTART_LIST}、${label}"
        else
            RESTART_LIST=$label
        fi
    fi
}

collect_running_labels() {
    local line unit ver kind label st
    NORMAL_LIST=""
    RESTART_LIST=""
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            *" loaded activating "*) kind=activating ;;
            *" loaded active "*) kind=active ;;
            *) continue ;;
        esac
        unit=$(printf '%s\n' "$line" | awk '{
            for (i = 1; i <= NF; i++) if ($i ~ /^novabot@/) { print $i; exit }
        }')
        unit=${unit%.service}
        case "$unit" in
            novabot@*)
                ver=${unit#novabot@}
                label=$(display_name "$ver")
                add_running "$kind" "$label"
                ;;
        esac
    done <<EOF
$(systemctl list-units 'novabot@*.service' --state=active,activating --no-legend --no-pager --plain 2>/dev/null || true)
EOF
    for ver in novabot "$EARLIER_UNIT"; do
        st=$(systemctl is-active "${ver}.service" 2>/dev/null || true)
        st=$(printf '%s' "$st" | tr -d '[:space:]')
        label=$(display_name "$ver")
        add_running "$st" "$label"
    done
}

say_running() {
    local boot="$1"
    local lead
    collect_running_labels
    lead=" "
    case "$NORMAL_LIST" in
        旧*) lead="" ;;
    esac
    if [ -n "$NORMAL_LIST" ] && [ -n "$RESTART_LIST" ]; then
        say "机器上跑的是${lead}${NORMAL_LIST}${boot}；${RESTART_LIST} 正在反复重启。"
    elif [ -n "$NORMAL_LIST" ]; then
        say "机器上跑的是${lead}${NORMAL_LIST}${boot}。"
    elif [ -n "$RESTART_LIST" ]; then
        say "机器上没有版本在正常跑${boot}；${RESTART_LIST} 正在反复重启。"
    else
        say "机器上没有版本在跑${boot}。"
    fi
}

report_running() {
    say_running ""
}

fail_out() {
    say "$1"
    say "看日志：journalctl -u $(unit_of "$TARGET") -n 200 --no-pager"
    if [ "$TARGET_START_ISSUED" = 1 ]; then
        systemctl stop "$(unit_of "$TARGET")" || true
    fi
    remove_dropin "$TARGET"
    systemctl daemon-reload || true
    if [ "$OLD_STOP_ISSUED" = 1 ] && [ -n "$OLD" ]; then
        say "把旧版 $OLD 起回来。"
        if ! ordinary_start "$OLD"; then
            systemctl stop "$(unit_of "$OLD")" || true
            remove_dropin "$OLD"
            systemctl daemon-reload || true
            say "旧版 $OLD 没能起来。"
        fi
    fi
    report_running
    exit 1
}

on_interrupt() {
    trap '' INT TERM HUP
    say "换版被打断，正在收拾。"
    if [ "$SWITCH_DONE" = 1 ]; then
        exit 0
    fi
    if [ "$TARGET_READY" = 1 ]; then
        remove_dropin "$TARGET"
        if switch_autostart; then
            say "已换到 ${TARGET}。"
            exit 0
        fi
        exit 1
    fi
    if [ "$TARGET_START_ISSUED" = 1 ]; then
        systemctl stop "$(unit_of "$TARGET")" || true
    fi
    remove_dropin "$TARGET"
    systemctl daemon-reload || true
    if [ -n "$OLD" ] && [ "$OLD_STOP_ISSUED" = 1 ]; then
        ordinary_start "$OLD" || true
    fi
    report_running
    exit 130
}

on_plan_interrupt() {
    trap '' INT TERM HUP
    exit 130
}

if [ "$PLAN" = yes ]; then
    trap on_plan_interrupt INT TERM HUP
else
    trap on_interrupt INT TERM HUP
fi

# /proc/locks 第 6 栏（主设备:次设备:inode）的最后一段对上 novabot.lock 的 inode。不用 flock。
lock_state() {
    local lock="$INSTALL_DIR/novabot.lock"
    local inode field ino line
    LOCK_KIND=missing
    if [ ! -e "$lock" ]; then
        LOCK_KIND=missing
        return 0
    fi
    if [ ! -r "$PROC_LOCKS" ]; then
        LOCK_KIND=unread
        return 0
    fi
    inode=$(ls -di "$lock" | awk '{print $1}')
    if [ -z "$inode" ]; then
        LOCK_KIND=unread
        return 0
    fi
    while IFS= read -r line || [ -n "$line" ]; do
        field=$(printf '%s\n' "$line" | awk '{print $6}')
        ino=${field##*:}
        if [ -n "$ino" ] && [ "$ino" = "$inode" ]; then
            LOCK_KIND=held
            return 0
        fi
    done < "$PROC_LOCKS"
    LOCK_KIND=missing
    return 0
}

# 内存上限已是 1.2G、900M 这样时照原样印；否则按字节折成同样的形。
format_high() {
    LC_ALL=C awk -v high="$1" 'BEGIN {
        if (high ~ /^[0-9]+(\.[0-9]+)?[KMGT]$/) {
            printf "%s", high
            exit
        }
        n = high + 0
        u = high
        gsub(/[0-9.]/, "", u)
        if (u == "G" || u == "g") bytes = n * 1024 * 1024 * 1024
        else if (u == "M" || u == "m") bytes = n * 1024 * 1024
        else if (u == "K" || u == "k") bytes = n * 1024
        else if (u == "T" || u == "t") bytes = n * 1024 * 1024 * 1024 * 1024
        else bytes = n + 0
        gb = 1024 * 1024 * 1024
        mb = 1024 * 1024
        if (bytes >= gb) {
            s = sprintf("%.1f", bytes / gb)
            sub(/\.0$/, "", s)
            printf "%sG", s
        } else if (bytes >= mb) {
            printf "%dM", int(bytes / mb + 0.5)
        } else {
            printf "%dK", int(bytes / 1024 + 0.5)
        }
    }'
}

# MemAvailable 的单位是 KiB。
format_kib() {
    LC_ALL=C awk -v kb="$1" 'BEGIN {
        bytes = (kb + 0) * 1024
        gb = 1024 * 1024 * 1024
        mb = 1024 * 1024
        if (bytes >= gb) {
            s = sprintf("%.1f", bytes / gb)
            sub(/\.0$/, "", s)
            printf "%sG", s
        } else if (bytes >= mb) {
            printf "%dM", int(bytes / mb + 0.5)
        } else {
            printf "%dK", int(bytes / 1024 + 0.5)
        }
    }'
}

# MemAvailable 不少于目标的 MemoryHigh。infinity 或取不到按 1.2G。
memory_state() {
    local high avail
    MEM_KIND=unread
    AVAIL_TEXT=读不到
    HIGH_TEXT=1.2G
    high=$(systemctl show -p MemoryHigh --value "$(unit_of "$TARGET")" 2>/dev/null || true)
    high=$(printf '%s' "$high" | tr -d '[:space:]')
    if [ -z "$high" ] || [ "$high" = "infinity" ]; then
        high=1.2G
    fi
    HIGH_TEXT=$(format_high "$high")
    if [ ! -r "$PROC_MEMINFO" ]; then
        MEM_KIND=unread
        return 0
    fi
    avail=$(awk '/^MemAvailable:/ { print $2; exit }' "$PROC_MEMINFO" 2>/dev/null || true)
    if [ -z "$avail" ]; then
        MEM_KIND=unread
        return 0
    fi
    AVAIL_TEXT=$(format_kib "$avail")
    if awk -v kb="$avail" -v high="$high" 'BEGIN {
        if (high == "" || high == "infinity") need = 1.2 * 1024 * 1024 * 1024
        else {
            n = high + 0
            u = high
            gsub(/[0-9.]/, "", u)
            if (u == "G" || u == "g") need = n * 1024 * 1024 * 1024
            else if (u == "M" || u == "m") need = n * 1024 * 1024
            else if (u == "K" || u == "k") need = n * 1024
            else if (u == "T" || u == "t") need = n * 1024 * 1024 * 1024 * 1024
            else need = n + 0
        }
        if ((kb + 0) * 1024 >= need) exit 0
        exit 1
    }'; then
        MEM_KIND=enough
    else
        MEM_KIND=short
    fi
    return 0
}

collect_active() {
    local line unit ver
    ACTIVE_VERSIONS=""
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            *" loaded active "*|*" loaded activating "*) ;;
            *) continue ;;
        esac
        unit=$(printf '%s\n' "$line" | awk '{
            for (i = 1; i <= NF; i++) if ($i ~ /^novabot@/) { print $i; exit }
        }')
        unit=${unit%.service}
        case "$unit" in
            novabot@*)
                ver=${unit#novabot@}
                ACTIVE_VERSIONS="$ACTIVE_VERSIONS $ver"
                ;;
        esac
    done <<EOF
$(systemctl list-units 'novabot@*.service' --state=active,activating --no-legend --no-pager --plain 2>/dev/null || true)
EOF
}

legacy_name() {
    if systemctl is-active --quiet novabot.service; then
        printf '%s\n' novabot
        return 0
    fi
    if systemctl is-active --quiet "${EARLIER_UNIT}.service"; then
        printf '%s\n' "$EARLIER_UNIT"
        return 0
    fi
    return 1
}

# list-unit-files 列不出 novabot@版本 这种实例，所以逐个问。版本目录里每个名字问一次，
# 再看开机自启链接里挂着的实例。同一个名字只算一次。
collect_enabled() {
    local dir name unit inst
    ENABLED_VERSIONS=""
    if [ -d "$INSTALL_DIR/releases" ]; then
        for dir in "$INSTALL_DIR"/releases/*; do
            [ -d "$dir" ] || continue
            name=$(basename "$dir")
            note_enabled "$name"
        done
    fi
    for link in /etc/systemd/system/*.wants/novabot@*.service; do
        [ -e "$link" ] || continue
        unit=$(basename "$link")
        inst=${unit#novabot@}
        inst=${inst%.service}
        note_enabled "$inst"
    done
}

note_enabled() {
    local name="$1"
    [ -n "$name" ] || return 0
    case " $ENABLED_VERSIONS " in
        *" $name "*) return 0 ;;
    esac
    if systemctl is-enabled --quiet "$(unit_of "$name")"; then
        ENABLED_VERSIONS="${ENABLED_VERSIONS} ${name}"
    fi
}

# 一个就照写。两个用「和」连。三个以上，前面用顿号，最后一个前面用「和」。
join_enabled() {
    local n=0 name i text last
    local names=()
    for name in $ENABLED_VERSIONS; do
        names+=("$name")
        n=$((n + 1))
    done
    if [ "$n" -eq 0 ]; then
        return 0
    fi
    if [ "$n" -eq 1 ]; then
        printf '%s' "${names[0]}"
        return 0
    fi
    if [ "$n" -eq 2 ]; then
        printf '%s 和 %s' "${names[0]}" "${names[1]}"
        return 0
    fi
    text=${names[0]}
    i=1
    last=$((n - 1))
    while [ "$i" -lt "$last" ]; do
        text="${text}、${names[$i]}"
        i=$((i + 1))
    done
    printf '%s 和 %s' "$text" "${names[$last]}"
}

report_running_and_boot() {
    local listed
    collect_enabled
    listed=$(join_enabled)
    if [ -z "$listed" ]; then
        say_running "，没有版本设了开机自启"
    else
        say_running "，开机自启在 ${listed}"
    fi
}

switch_autostart() {
    local failed=0 name listed
    if ! systemctl daemon-reload; then
        failed=1
        say "重新加载服务配置没有成功。"
    fi
    collect_enabled
    listed=$ENABLED_VERSIONS
    for name in $listed; do
        if [ "$name" = "$TARGET" ]; then
            continue
        fi
        if ! systemctl disable "$(unit_of "$name")"; then
            failed=1
            say "请手动执行：systemctl disable $(unit_of "$name")"
        fi
    done
    if ! systemctl enable "$(unit_of "$TARGET")"; then
        failed=1
        say "请手动执行：systemctl enable $(unit_of "$TARGET")"
    fi
    if [ "$failed" = 1 ]; then
        report_running_and_boot
        return 1
    fi
    return 0
}

do_hot() {
    local unit file wait_rc gap
    unit=$(unit_of "$TARGET")
    file=$(state_file_of "$TARGET")
    say "三样都成立，走热交接：先起 ${TARGET}，等到门口再停 ${OLD}。"
    if ! write_dropin "$TARGET" yes; then
        say "写覆盖设置失败。"
        remove_dropin "$TARGET"
        systemctl daemon-reload || true
        report_running
        exit 1
    fi
    if ! systemctl daemon-reload; then
        say "重新加载服务配置没有成功。"
        remove_dropin "$TARGET"
        systemctl daemon-reload || true
        report_running
        exit 1
    fi
    say "启动 ${TARGET}，等它到门口。"
    TARGET_START_ISSUED=1
    if ! systemctl start "$unit"; then
        fail_out "启动 $TARGET 失败。"
    fi
    wait_rc=0
    wait_phase_waiting "$unit" "$file" "$WAITING_TIMEOUT_SECONDS" || wait_rc=$?
    case "$wait_rc" in
        0) ;;
        1) fail_out "$TARGET 在等到门口之前就退出了。" ;;
        2) fail_out "等 $TARGET 到门口超过 ${WAITING_TIMEOUT_SECONDS} 秒。" ;;
        3) fail_out "$TARGET 没经过等待就往下走了，锁其实空着。" ;;
        4) fail_out "$TARGET 在门口之前进了安全模式。" ;;
        *) fail_out "等 $TARGET 到门口时出了意外。" ;;
    esac
    say "${TARGET} 已在门口等锁，停掉 ${OLD}。"
    GAP_START=$(date +%s)
    OLD_STOP_ISSUED=1
    if ! systemctl stop "$(unit_of "$OLD")"; then
        fail_out "停掉 $OLD 失败。"
    fi
    wait_rc=0
    wait_phase_ready "$unit" "$file" "$READY_TIMEOUT_SECONDS" || wait_rc=$?
    case "$wait_rc" in
        0) ;;
        1) fail_out "$TARGET 在就绪前退出了。" ;;
        2) fail_out "等 $TARGET 就绪超过 ${READY_TIMEOUT_SECONDS} 秒。" ;;
        4) fail_out "$TARGET 进了安全模式。" ;;
        *) fail_out "等 $TARGET 就绪时出了意外。" ;;
    esac
    GAP_END=$(date +%s)
    if ! stay_active "$unit" "$STAY_ACTIVE_SECONDS" "$file"; then
        fail_out "$TARGET 就绪后没有稳住。"
    fi
    TARGET_READY=1
    remove_dropin "$TARGET"
    if ! switch_autostart; then
        exit 1
    fi
    SWITCH_DONE=1
    gap=$((GAP_END - GAP_START))
    if [ "$gap" -lt 0 ]; then
        gap=0
    fi
    say "已换到 ${TARGET}，中间断了约 ${gap} 秒（起点到 ready）"
    say "换回上一版：sudo $SCRIPT_PATH $OLD"
    exit 0
}

do_ordinary() {
    say "不走热交接：${1}改走普通重启。"
    say "先停 ${OLD}，再起 ${TARGET}。"
    OLD_STOP_ISSUED=1
    if ! systemctl stop "$(unit_of "$OLD")"; then
        say "停掉 $OLD 失败。"
        report_running
        exit 1
    fi
    if ordinary_start "$TARGET"; then
        TARGET_READY=1
        if ! switch_autostart; then
            exit 1
        fi
        SWITCH_DONE=1
        say "已换到 ${TARGET}。"
        say "换回上一版：sudo $SCRIPT_PATH $OLD"
        exit 0
    fi
    say "$TARGET 没能起来。"
    say "看日志：journalctl -u $(unit_of "$TARGET") -n 200 --no-pager"
    systemctl stop "$(unit_of "$TARGET")" || true
    remove_dropin "$TARGET"
    systemctl daemon-reload || true
    say "把旧版 $OLD 起回来。"
    if ! ordinary_start "$OLD"; then
        systemctl stop "$(unit_of "$OLD")" || true
        remove_dropin "$OLD"
        systemctl daemon-reload || true
        say "旧版 $OLD 没能起来。"
    fi
    report_running
    exit 1
}

collect_handover_reasons() {
    local reasons=""
    if ! has_handover "$TARGET"; then
        reasons="${reasons}没有 handover=1。"
    fi
    lock_state
    case "$LOCK_KIND" in
        held) ;;
        unread) reasons="${reasons}读不到锁。" ;;
        *) reasons="${reasons}旧版没拿着锁。" ;;
    esac
    memory_state
    case "$MEM_KIND" in
        enough) ;;
        short) reasons="${reasons}内存不够。" ;;
        *) reasons="${reasons}读不到可用内存。" ;;
    esac
    HANDOVER_REASONS=$reasons
}

show_plan() {
    local verdict
    if [ "$count" -eq 0 ]; then
        say "现在没有分目录实例在跑，这次会普通启动 ${TARGET}。"
        return 0
    fi
    OLD=$ONLY
    collect_handover_reasons
    if has_handover "$TARGET"; then
        say "目标版本带 handover=1：是"
    else
        say "目标版本带 handover=1：否"
    fi
    case "$LOCK_KIND" in
        held) say "旧版拿着单实例锁：是" ;;
        unread) say "旧版拿着单实例锁：读不到" ;;
        *) say "旧版拿着单实例锁：否" ;;
    esac
    case "$MEM_KIND" in
        enough) verdict="是" ;;
        short) verdict="否" ;;
        *) verdict="读不到" ;;
    esac
    say "可用内存不少于目标的内存上限（可用 ${AVAIL_TEXT}，上限 ${HIGH_TEXT}）：${verdict}"
    if [ -z "$HANDOVER_REASONS" ]; then
        say "这次会走热交接：先起 ${TARGET}，等它到门口再停 ${OLD}。"
    else
        say "这次会走普通重启：${HANDOVER_REASONS}先停 ${OLD}，再起 ${TARGET}。"
    fi
}

if [ "$PLAN" != yes ]; then
    say "把正在跑的实例换成 ${TARGET}。"
fi

collect_active
count=0
ONLY=""
for ver in $ACTIVE_VERSIONS; do
    count=$((count + 1))
    ONLY=$ver
done

if [ "$count" -gt 1 ]; then
    say "有不止一份 novabot@ 在跑，这次什么都没改。"
    report_running
    exit 1
fi

if [ "$count" -eq 1 ] && [ "$ONLY" = "$TARGET" ]; then
    running_state=$(systemctl is-active "$(unit_of "$TARGET")" 2>/dev/null || true)
    running_state=$(printf '%s' "$running_state" | tr -d '[:space:]')
    if [ "$running_state" = "activating" ]; then
        say "${TARGET} 正在反复重启，这次什么都没改。"
        say "看日志：journalctl -u $(unit_of "$TARGET") -n 200 --no-pager"
        report_running
        exit 1
    fi
    say "$TARGET 已经在跑，不用换。"
    exit 0
fi

legacy=$(legacy_name || true)
if [ -n "$legacy" ]; then
    say "旧服务 ${legacy}.service 正在运行。老用户第一次换到分目录，请先手动停掉旧服务，再起新版本。"
    report_running
    exit 1
fi

lookup_user

if [ "$count" -eq 1 ]; then
    OLD=$ONLY
fi

if has_handover "$TARGET" || { [ "$count" -eq 1 ] && has_handover "$ONLY"; }; then
    if [ "$PLAN" = yes ]; then
        if ! state_dir_ok; then
            report_running
            exit 1
        fi
    elif ! prepare_state_dir; then
        report_running
        exit 1
    fi
fi

if [ "$PLAN" = yes ]; then
    show_plan
    exit 0
fi

if [ "$count" -eq 0 ]; then
    say "现在没有分目录实例在跑，普通启动 ${TARGET}。"
    if ordinary_start "$TARGET"; then
        TARGET_READY=1
        if ! switch_autostart; then
            exit 1
        fi
        SWITCH_DONE=1
        say "已换到 ${TARGET}。"
        exit 0
    fi
    say "$TARGET 没能起来。"
    systemctl stop "$(unit_of "$TARGET")" || true
    remove_dropin "$TARGET"
    systemctl daemon-reload || true
    report_running
    exit 1
fi

OLD=$ONLY
collect_handover_reasons
if [ -n "$HANDOVER_REASONS" ]; then
    do_ordinary "$HANDOVER_REASONS"
fi
do_hot
