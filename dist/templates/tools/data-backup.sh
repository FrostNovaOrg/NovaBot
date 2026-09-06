#!/usr/bin/env bash
# 把直播数据目录同步到另一处。目标可以是本机目录，或 user@host:/path。
# 用法：data-backup.sh <数据目录> <目标>
# 只增不删：目标上已有的文件不会被去掉。不传输 reports/ 与 *.tmp。
set -euo pipefail

if [ "$#" -ne 2 ]; then
    echo "用法: data-backup.sh <数据目录> <目标>" >&2
    exit 1
fi

src=$1
dst=$2

if [ ! -d "$src" ] || { [ ! -f "$src/sessions.jsonl" ] && [ ! -d "$src/details" ]; }; then
    echo "不像数据目录" >&2
    exit 2
fi

args=(-a --partial --human-readable --stats --exclude=reports --exclude='*.tmp')
case "$dst" in
    *:*) args+=(-e "ssh -o BatchMode=yes -o ConnectTimeout=15") ;;
esac

out=$(rsync "${args[@]}" "$src/" "$dst/" 2>&1) || {
    printf '%s\n' "$out" >&2
    exit 1
}
printf '%s\n' "$out"

bytes=$(printf '%s\n' "$out" | awk '
    /Total transferred file size:/ {
        gsub(/,/, "")
        for (i = 1; i <= NF; i++) {
            if ($i ~ /^[0-9]/) { print $i; exit }
        }
    }
')
bytes=${bytes:-0}

echo "备份完成 $(date '+%Y-%m-%d %H:%M:%S') 传输 ${bytes} 字节"
