#!/usr/bin/env bash
#
# 把正在跑的分目录实例换成指定版本。
# 能热交接就热交接（新版本先起到门口等锁，再停旧版本），不能就普通重启。
# 失败把旧版本起回来。回滚就是再调一次，目标写成上一版。
#
# 用法：
#   sudo /usr/local/sbin/novabot-switch-version <目标版本>
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
set -euo pipefail

say() {
    printf '%s\n' "$*"
}

if [ "$#" -ne 1 ]; then
    say "用法：sudo /usr/local/sbin/novabot-switch-version <目标版本>"
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

prepare_state_dir() {
    if [ -L "$STATE_DIR" ] || { [ -e "$STATE_DIR" ] && [ ! -d "$STATE_DIR" ]; }; then
        say "状态目录已在，但是符号链接或不是目录，这次什么都没改。"
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

# 等状态件出现 waiting。0 到了；1 进程没了；2 超过 120 秒；3 没经过 waiting 就 passed/ready；4 安全模式。
wait_phase_waiting() {
    local unit="$1"
    local file="$2"
    local deadline now
    deadline=$(( $(date +%s) + 120 ))
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
        wait_phase_ready "$unit" "$file" 180 || wait_rc=$?
        if [ "$wait_rc" -ne 0 ]; then
            remove_dropin "$ver"
            systemctl daemon-reload || true
            return 1
        fi
        if ! stay_active "$unit" 10 "$file"; then
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
    if ! stay_active "$unit" 60 ""; then
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

trap on_interrupt INT TERM HUP

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

# MemAvailable 不少于目标的 MemoryHigh。infinity 或取不到按 1.2G。
memory_state() {
    local high avail
    MEM_KIND=unread
    high=$(systemctl show -p MemoryHigh --value "$(unit_of "$TARGET")" 2>/dev/null || true)
    high=$(printf '%s' "$high" | tr -d '[:space:]')
    if [ -z "$high" ] || [ "$high" = "infinity" ]; then
        high=1.2G
    fi
    if [ ! -r "$PROC_MEMINFO" ]; then
        MEM_KIND=unread
        return 0
    fi
    avail=$(awk '/^MemAvailable:/ { print $2; exit }' "$PROC_MEMINFO" 2>/dev/null || true)
    if [ -z "$avail" ]; then
        MEM_KIND=unread
        return 0
    fi
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

report_running_and_boot() {
    local boot
    boot=""
    if [ -n "$OLD" ] && systemctl is-enabled --quiet "$(unit_of "$OLD")"; then
        boot="${OLD}"
    fi
    if systemctl is-enabled --quiet "$(unit_of "$TARGET")"; then
        if [ -n "$boot" ]; then
            boot="${boot} 和 ${TARGET}"
        else
            boot="${TARGET}"
        fi
    fi
    if [ -z "$boot" ]; then
        say_running "，没有版本设了开机自启"
    else
        say_running "，开机自启在 ${boot}"
    fi
}

switch_autostart() {
    local failed=0
    if ! systemctl daemon-reload; then
        failed=1
        say "重新加载服务配置没有成功。"
    fi
    if [ -n "$OLD" ]; then
        if ! systemctl disable "$(unit_of "$OLD")"; then
            failed=1
            say "请手动执行：systemctl disable $(unit_of "$OLD")"
        fi
    fi
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
    wait_phase_waiting "$unit" "$file" || wait_rc=$?
    case "$wait_rc" in
        0) ;;
        1) fail_out "$TARGET 在等到门口之前就退出了。" ;;
        2) fail_out "等 $TARGET 到门口超过 120 秒。" ;;
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
    wait_phase_ready "$unit" "$file" 180 || wait_rc=$?
    case "$wait_rc" in
        0) ;;
        1) fail_out "$TARGET 在就绪前退出了。" ;;
        2) fail_out "等 $TARGET 就绪超过 180 秒。" ;;
        4) fail_out "$TARGET 进了安全模式。" ;;
        *) fail_out "等 $TARGET 就绪时出了意外。" ;;
    esac
    GAP_END=$(date +%s)
    if ! stay_active "$unit" 10 "$file"; then
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

say "把正在跑的实例换成 ${TARGET}。"

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
    if ! prepare_state_dir; then
        report_running
        exit 1
    fi
fi

if [ "$count" -eq 0 ]; then
    say "现在没有分目录实例在跑，普通启动 ${TARGET}。"
    if ordinary_start "$TARGET"; then
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
reasons=""
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

if [ -n "$reasons" ]; then
    do_ordinary "$reasons"
fi
do_hot
