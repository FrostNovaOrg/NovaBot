#!/usr/bin/env bash
# NovaCore 边界尺：核心与平台壳的分界是否守住
#
# 判据来源：2026-09-03 产品边界定义 —— NovaCore ＝「一个礼貌的、能登录的 B 站直播事件源」，
# 五层归核心（①连接 ②身份 ③解析与事件模型 ④事件输出协议 ⑤采集范围），
# 控制台／登录／推送／聚合／存储归 NovaBot 壳。一句话尺：莓果或 VRDash 用不到的，就不是核心。
#
# 本尺只 grep/find，不 build、不联网。拆仓／拆模块后作 CI 守卫。
# 退码：格1 或 格2 任一红 ⇒ 非 0；格3 只印数不判红绿（读法未定，见文末注释）。

set -uo pipefail

# —— 允许承载「核心」的模块目录名（拆模块后把新名加进来即可，不必改判据逻辑）——
ALLOWED_CORE_MODULES="starbot-core novacore starbot-novacore"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 2

CORE_UI="starbot-core/src/main/resources/config-ui"
RED=0

# ============================================================
# 格1：core 资源里不许有平台专页
# 现状（拆前）＝红：整份 bilibili.js 在 core 资源里，且 index.html/main.js/push.js 写死 bilibili。
# 拆法：core 只留 tab 注册点，平台页由插件带（笔二）。
# ============================================================
g1_hits=""
g1_n=0

add_hit() {
    # $1 = "file:line" 串（可多行）
    while IFS= read -r line; do
        [ -z "$line" ] && continue
        g1_hits="${g1_hits}${line} "
        g1_n=$((g1_n + 1))
    done <<< "$1"
}

# 1a 平台专页文件本身
if [ -f "$CORE_UI/bilibili.js" ]; then
    add_hit "$CORE_UI/bilibili.js:1"
fi
# 1b index.html 的 tab 与 section
if [ -f "$CORE_UI/index.html" ]; then
    add_hit "$(grep -n 'data-tab="bilibili"\|id="bilibili"' "$CORE_UI/index.html" \
        | sed "s|^\([0-9]*\):.*|$CORE_UI/index.html:\1|")"
fi
# 1c main.js 的 import 与分支
if [ -f "$CORE_UI/main.js" ]; then
    add_hit "$(grep -n "from '\./bilibili\.js'\|tab === 'bilibili'" "$CORE_UI/main.js" \
        | sed "s|^\([0-9]*\):.*|$CORE_UI/main.js:\1|")"
fi
# 1d push.js 写死 platform bilibili（含赋值与比较两形）
if [ -f "$CORE_UI/push.js" ]; then
    add_hit "$(grep -n "platform: *'bilibili'\|platform !== *'bilibili'\|platform === *'bilibili'" "$CORE_UI/push.js" \
        | sed "s|^\([0-9]*\):.*|$CORE_UI/push.js:\1|")"
fi

if [ "$g1_n" -eq 0 ]; then
    echo "格1 绿 命中0 core 资源无平台专页"
else
    echo "格1 红 命中${g1_n} ${g1_hits% }"
    RED=1
fi

# ============================================================
# 格2：§5 事件输出协议（真源）须在核心模块
# 现状（拆前）＝红：端点与装配都在 starbot-bilibili。
# 拆法：NovaEventEndpoint / NovaEventStreamConfiguration 迁核心，协议与路径不变（笔三）。
# ============================================================
g2_files=$(find . -path ./target -prune -o -name 'NovaEventEndpoint.java' -print \
    -o -name 'NovaEventStreamConfiguration.java' -print 2>/dev/null \
    | grep -v '/src/test/' | sed 's|^\./||' | sort)

g2_bad=""
g2_n=0
g2_total=0
if [ -z "$g2_files" ]; then
    echo "格2 红 命中0 未找到 NovaEventEndpoint/NovaEventStreamConfiguration（真源不在树里）"
    RED=1
else
    while IFS= read -r f; do
        [ -z "$f" ] && continue
        g2_total=$((g2_total + 1))
        mod="${f%%/*}"
        ok=0
        for allowed in $ALLOWED_CORE_MODULES; do
            [ "$mod" = "$allowed" ] && ok=1
        done
        if [ "$ok" -eq 0 ]; then
            g2_bad="${g2_bad}${f} "
            g2_n=$((g2_n + 1))
        fi
    done <<< "$g2_files"

    if [ "$g2_n" -eq 0 ]; then
        echo "格2 绿 在核${g2_total}/${g2_total} $(echo "$g2_files" | tr '\n' ' ')"
    else
        echo "格2 红 离核${g2_n}/${g2_total} ${g2_bad% }"
        RED=1
    fi
fi

# ============================================================
# 格3：平台清单 LivePlatform —— 本尺只印数，不判红绿（读法待定）
#
# 读法甲（闭集枚举留核心）：尺＝枚举成员数 与 全树被引用的平台数 并排。
#   绿判据将写成：两数相等 —— 枚举里列的每一个平台都真有实现，没有列而不做的空头。
#   落法：核心保留 LivePlatform，新增平台须同时改枚举与实现。
#
# 读法乙（开放标识/注册表）：尺＝core 内引用 LivePlatform.<具体成员> 的处数，须为 0。
#   绿判据将写成：该处数 == 0 —— 核心只认平台标识串，不认某个具体平台的名字；
#   平台由插件在注册表里自报，核心的枚举退化为字符串或整份退休。
#   落法：核心里所有 LivePlatform.BILIBILI 之类改为按标识串比对。
# ============================================================
LP="starbot-core/src/main/java/com/starlwr/bot/core/enums/LivePlatform.java"
if [ -f "$LP" ]; then
    g3_members=$(grep -cE '^\s+[A-Z][A-Z_]*\("' "$LP")
else
    g3_members=0
fi
g3_used=$(grep -rhoE 'LivePlatform\.[A-Z][A-Z_]*' --include='*.java' \
    */src/main 2>/dev/null | sort -u | wc -l | tr -d ' ')
g3_core_concrete=$(grep -rcE 'LivePlatform\.[A-Z][A-Z_]*' --include='*.java' \
    starbot-core/src/main 2>/dev/null | awk -F: '{s+=$2} END {print s+0}')

echo "格3 印数 读法甲=枚举${g3_members}/被引用${g3_used} 读法乙=core内具体成员${g3_core_concrete}处(须0) $LP"

exit "$RED"
