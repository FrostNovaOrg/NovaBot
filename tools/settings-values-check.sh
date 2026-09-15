#!/usr/bin/env bash
# 设置页开关值跟随 store.values：改值后重画，checkbox 与旁注跟着变
#
# 设置页 DOM 只在建的那一刻读一次 valuesOf。本尺切 valuesOf + buildControl 真执行，
# 钉开关那一格在 store.values 改完再画时 checked 与「已启用／已关闭」跟着走，
# 以及未保存草稿优先于已保存值。
#
# 退码：0 全对；1 有格对不上；2 环境不具备（没装 node）。

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 2

if ! command -v node > /dev/null 2>&1; then
    echo "未找到 node，本尺跑不了（它量的是浏览器里那份逻辑）" >&2
    exit 2
fi

node tools/settings-values-check.mjs
if [ $? -ne 0 ]; then
    exit 1
fi
exit 0
