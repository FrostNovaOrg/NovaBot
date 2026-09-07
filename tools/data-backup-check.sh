#!/usr/bin/env bash
# 直播数据备份脚本五问尺：内容同步、再跑零传输、源删件目标仍在、不像数据目录退 2、
# 发行模板落点与安装路径
#
# 用法：bash tools/data-backup-check.sh
# 退码：0 全绿；1 有红。全程本机目录，不走远程。
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

SCRIPT="$REPO_ROOT/dist/templates/tools/data-backup.sh"
RED=0

backup() {
    if [ ! -f "$SCRIPT" ]; then
        echo "dist/templates/tools/data-backup.sh 不存在" >&2
        return 127
    fi
    bash "$SCRIPT" "$@"
}

WORK="$(mktemp -d "${TMPDIR:-/tmp}/novabot-data-backup-check-XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

SRC="$WORK/src"
DST="$WORK/dst"
mkdir -p "$SRC/details/a/b/c" "$SRC/reports" "$DST"
printf 'session-line-1\n' > "$SRC/sessions.jsonl"
printf '{"ok":true}\n' > "$SRC/details/a/b/c/detail.json"
printf 'PNG\n' > "$SRC/reports/x.png"

# ① 同步后正文逐字同，reports/ 不在目标
out1=""
if out1="$(backup "$SRC" "$DST" 2>&1)"; then
    same=1
    cmp -s "$SRC/sessions.jsonl" "$DST/sessions.jsonl" || same=0
    cmp -s "$SRC/details/a/b/c/detail.json" "$DST/details/a/b/c/detail.json" || same=0
    if [ "$same" -eq 1 ] && [ ! -e "$DST/reports" ]; then
        echo "① 绿：sessions.jsonl 与 detail.json 逐字同，reports/ 未同步"
    else
        echo "① 红：内容对不上或 reports/ 出现在目标"
        echo "$out1" | sed 's/^/    /'
        RED=$((RED + 1))
    fi
else
    echo "① 红：备份未成功（退码 $?）"
    echo "$out1" | sed 's/^/    /'
    RED=$((RED + 1))
fi

# ② 再跑一次：有「备份完成」，且 --stats 传输文件数 0（或极小）
out2=""
if out2="$(backup "$SRC" "$DST" 2>&1)"; then
    xfer="$(printf '%s\n' "$out2" | awk '
        /Number of regular files transferred:/ { print $5; exit }
        /Number of files transferred:/ { print $5; exit }
    ')"
    xfer="${xfer:-99}"
    case "$out2" in
        *备份完成*)
            if [ "$xfer" = "0" ] || [ "$xfer" = "1" ]; then
                echo "② 绿：再跑含「备份完成」，传输文件数 ${xfer}"
            else
                echo "② 红：再跑传输文件数不是 0（读到 ${xfer}）"
                echo "$out2" | sed 's/^/    /'
                RED=$((RED + 1))
            fi
            ;;
        *)
            echo "② 红：再跑输出不含「备份完成」"
            echo "$out2" | sed 's/^/    /'
            RED=$((RED + 1))
            ;;
    esac
else
    echo "② 红：再跑未成功（退码 $?）"
    echo "$out2" | sed 's/^/    /'
    RED=$((RED + 1))
fi

# ③ 源目录删一件后再跑，目标件仍在（备份不删）
rm -f "$SRC/details/a/b/c/detail.json"
out3=""
if out3="$(backup "$SRC" "$DST" 2>&1)"; then
    if [ -f "$DST/details/a/b/c/detail.json" ]; then
        echo "③ 绿：源删 detail.json 后目标件仍在"
    else
        echo "③ 红：源删后目标件也不见了（不该删）"
        echo "$out3" | sed 's/^/    /'
        RED=$((RED + 1))
    fi
else
    echo "③ 红：源删后再跑未成功（退码 $?）"
    echo "$out3" | sed 's/^/    /'
    RED=$((RED + 1))
fi

# ④ 不像数据目录的空目录 → 退 2
EMPTY="$WORK/empty"
mkdir -p "$EMPTY"
out4=""
backup "$EMPTY" "$WORK/out4" >"$WORK/out4.txt" 2>&1
rc4=$?
out4="$(cat "$WORK/out4.txt")"
if [ "$rc4" -eq 2 ]; then
    echo "④ 绿：空目录退 2"
else
    echo "④ 红：空目录应退 2，实际 $rc4"
    echo "$out4" | sed 's/^/    /'
    RED=$((RED + 1))
fi

# ⑤ 三件在 dist/templates 下，且 service ExecStart 以 /opt/starbot/ 开头、User 与 starbot.service 同
TPL="$REPO_ROOT/dist/templates"
SH="$TPL/tools/data-backup.sh"
SVC="$TPL/novabot-backup.service"
TMR="$TPL/novabot-backup.timer"
REF="$TPL/starbot.service"
ok5=1
reason5=""
if [ ! -f "$SH" ] || [ ! -f "$SVC" ] || [ ! -f "$TMR" ]; then
    ok5=0
    reason5="三件不在 dist/templates 下"
fi
if [ "$ok5" -eq 1 ]; then
    exec_line=""
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            ExecStart=*) exec_line="${line#ExecStart=}"; break ;;
        esac
    done < "$SVC"
    case "$exec_line" in
        /opt/starbot/*) ;;
        *)
            ok5=0
            reason5="ExecStart 不以 /opt/starbot/ 开头（读到 ${exec_line}）"
            ;;
    esac
fi
if [ "$ok5" -eq 1 ]; then
    user_svc=""
    user_ref=""
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            User=*) user_svc="${line#User=}"; break ;;
        esac
    done < "$SVC"
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            User=*) user_ref="${line#User=}"; break ;;
        esac
    done < "$REF"
    if [ -z "$user_svc" ] || [ "$user_svc" != "$user_ref" ]; then
        ok5=0
        reason5="User 与 starbot.service 不同（service=${user_svc} ref=${user_ref}）"
    fi
fi
if [ "$ok5" -eq 1 ]; then
    novabot_n=$(grep -c "/opt/novabot" "$TMR" || true)
    starbot_n=$(grep -c "/opt/starbot" "$TMR" || true)
    if [ "$novabot_n" -ne 0 ] || [ "$starbot_n" -lt 1 ]; then
        ok5=0
        reason5="timer 注释仍写 /opt/novabot 或未写 /opt/starbot（novabot=${novabot_n} starbot=${starbot_n}）"
    fi
fi
if [ "$ok5" -eq 1 ]; then
    echo "⑤ 绿：三件在 dist/templates 下，ExecStart 以 /opt/starbot/ 开头，User 与 starbot.service 同，timer 注释现行安装目录"
else
    echo "⑤ 红：${reason5}"
    RED=$((RED + 1))
fi

echo "汇总：绿 $((5 - RED))／5，红 $RED"
[ "$RED" -eq 0 ] && exit 0
exit 1
