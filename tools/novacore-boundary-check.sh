#!/usr/bin/env bash
# NovaCore 边界尺：核心与平台壳的分界是否守住
#
# 判据来源：2026-09-03 产品边界定义 —— NovaCore ＝「一个礼貌的、能登录的 B 站直播事件源」，
# 五层归核心（①连接 ②身份 ③解析与事件模型 ④事件输出协议 ⑤采集范围），
# 控制台／登录／推送／聚合／存储归 NovaBot 壳。一句话尺：莓果或 VRDash 用不到的，就不是核心。
#
# 本尺只 grep/find，不 build、不联网。拆仓／拆模块后作 CI 守卫。
# 退码：三格任一红 ⇒ 非 0。

set -uo pipefail

# —— 允许承载「核心」的模块目录名（拆模块后把新名加进来即可，不必改判据逻辑）——
ALLOWED_CORE_MODULES="starbot-core novacore starbot-novacore"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 2

CORE_UI="starbot-core/src/main/resources/config-ui"
RED=0

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ============================================================
# 格1：core 的界面目录里不许出现任何一个直播平台的名字
#
# 平台名清单当场从源码里算出来，本脚本不写死任何一个平台名 —— 写死一个，
# 这把尺守的就只是那一个平台，下一个平台写死进核心界面照样是绿的。两处源头：
#   ① enums/LivePlatform.java 的枚举成员名与其名称字符串（含小写、去下划线、连字符各变体）
#   ② 各插件自己申报给控制台的显示名（ConsolePageProvider / AccountLoginProvider 的 displayName）
#      —— 界面上「登录哔哩哔哩」这类中文写死，只有从这一处才认得出来
#
# 射程是 config-ui/ 整个目录，文件名与文件内容都查，而不是点名几个文件几种写法：
# 把平台字样挪到同目录的另一个文件、换一种拼法，尺就该照样逮到，否则它量的是位置不是边界。
#
# 匹配方式分两档：不足 4 字符的短标识（如 CC、YY）按整词匹配，其余按子串匹配。
# 两个字母的子串会命中 account、success 这类词，那种判据只会被当成噪音关掉。
# ============================================================

# —— 例外表：每条写明「为什么它不算把平台写死进核心」，没有理由的例外一律不许加 ——
#    形如 "文件名 正则"，命中行的文件名与内容同时匹配才豁免。
#    现无例外：核心界面里一处平台字样都不该有。
G1_EXEMPT=()

TOKENS="$WORK/tokens"
: > "$TOKENS"

LP="starbot-core/src/main/java/com/starlwr/bot/core/enums/LivePlatform.java"
if [ -f "$LP" ]; then
    while IFS= read -r line; do
        member="$(printf '%s' "$line" | sed -E 's/^[[:space:]]*([A-Z][A-Z_]*)\(.*/\1/')"
        value="$(printf '%s' "$line" | sed -E 's/^[^"]*"([^"]*)".*/\1/')"
        lower="$(printf '%s' "$member" | tr 'A-Z' 'a-z')"
        printf '%s\n%s\n%s\n%s\n' "$value" "$lower" "${lower//_/}" "${lower//_/-}" >> "$TOKENS"
    done <<< "$(grep -E '^[[:space:]]+[A-Z][A-Z_]*\("' "$LP")"
fi

for mod in */; do
    mod="${mod%/}"
    [ -d "$mod/src/main" ] || continue
    skip=0
    for allowed in $ALLOWED_CORE_MODULES; do
        [ "$mod" = "$allowed" ] && skip=1
    done
    [ "$skip" -eq 1 ] && continue

    while IFS= read -r f; do
        [ -z "$f" ] && continue
        grep -A3 'String displayName()' "$f" 2>/dev/null \
            | grep -oE 'return "[^"]*"' \
            | sed -E 's/^return "(.*)"$/\1/' >> "$TOKENS"
    done <<< "$(grep -rl 'implements ConsolePageProvider\|implements AccountLoginProvider' \
        --include='*.java' "$mod/src/main" 2>/dev/null)"
done

grep -v '^[[:space:]]*$' "$TOKENS" | sort -u > "$WORK/all"
grep -E '^[ -~]+$' "$WORK/all" | awk 'length($0) >= 4' > "$WORK/long"
grep -E '^[ -~]+$' "$WORK/all" | awk 'length($0) <  4' > "$WORK/short"
grep -vE '^[ -~]+$' "$WORK/all" > "$WORK/wide"
g1_tokens=$(wc -l < "$WORK/all" | tr -d ' ')

scan() {
    # $1 = 目标（目录或单行文本时用 - 从标准输入读）
    [ -s "$WORK/long" ]  && grep -rinF  -f "$WORK/long"  "$1" 2>/dev/null
    [ -s "$WORK/short" ] && grep -rinwF -f "$WORK/short" "$1" 2>/dev/null
    [ -s "$WORK/wide" ]  && grep -rinF  -f "$WORK/wide"  "$1" 2>/dev/null
    return 0
}

g1_hits=""
g1_n=0

if [ -d "$CORE_UI" ]; then
    # 文件名本身带平台名的（整份平台专页放在核心资源里就是这一形）
    while IFS= read -r f; do
        [ -z "$f" ] && continue
        base="$(basename "$f")"
        named=0
        [ -s "$WORK/long" ] && printf '%s\n' "$base" | grep -qiF -f "$WORK/long" && named=1
        [ -s "$WORK/short" ] && printf '%s\n' "$base" | grep -qiwF -f "$WORK/short" && named=1
        [ -s "$WORK/wide" ] && printf '%s\n' "$base" | grep -qF -f "$WORK/wide" && named=1
        if [ "$named" -eq 1 ]; then
            g1_hits="${g1_hits}${f}:0(文件名) "
            g1_n=$((g1_n + 1))
        fi
    done <<< "$(find "$CORE_UI" -type f | sort)"

    # 文件内容里出现平台名的（同一行被多类标识命中只算一处）
    scan "$CORE_UI" | sort -u | awk -F: '!seen[$1":"$2]++' > "$WORK/hits"
    while IFS= read -r hit; do
        [ -z "$hit" ] && continue
        file="${hit%%:*}"
        rest="${hit#*:}"
        lineno="${rest%%:*}"
        text="${rest#*:}"

        exempt=0
        for rule in ${G1_EXEMPT[@]+"${G1_EXEMPT[@]}"}; do
            rule_file="${rule%% *}"
            rule_re="${rule#* }"
            if [[ "$file" == *"$rule_file"* ]] && printf '%s' "$text" | grep -qE "$rule_re"; then
                exempt=1
                break
            fi
        done
        [ "$exempt" -eq 1 ] && continue

        g1_hits="${g1_hits}${file}:${lineno} "
        g1_n=$((g1_n + 1))
    done < "$WORK/hits"
fi

if [ "$g1_n" -eq 0 ]; then
    echo "格1 绿 命中0 核心界面无平台字样 平台名${g1_tokens}个(现算)"
else
    echo "格1 红 命中${g1_n} ${g1_hits% } 平台名${g1_tokens}个(现算)"
    RED=1
fi

# ============================================================
# 格2：§5 事件输出协议（真源）须在核心模块
# 现状（拆前）＝红：端点与装配都在 starbot-bilibili。
# 拆法：NovaEventEndpoint / NovaEventStreamConfiguration 迁核心，协议与路径不变。
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
# 格3：平台清单 LivePlatform —— 读法已定为「开放标识／注册表」
#
# 绿判据：core 内引用 LivePlatform.<具体成员> 的处数为 0 ——
# 核心只认平台标识串，不认某一个具体平台的名字；平台由插件在注册表里自报。
# 另一读法（闭集枚举留核心，绿判据＝枚举成员数与被引用平台数相等）不采：
# 那要为枚举里列而不做的那几个平台背书，而它们一个实现也没有。
# 两数照旧印出来，只是不再作判据 —— 它是「空头平台有几个」的现读数。
# ============================================================
if [ -f "$LP" ]; then
    g3_members=$(grep -cE '^\s+[A-Z][A-Z_]*\("' "$LP")
else
    g3_members=0
fi
g3_used=$(grep -rhoE 'LivePlatform\.[A-Z][A-Z_]*' --include='*.java' \
    */src/main 2>/dev/null | sort -u | wc -l | tr -d ' ')
g3_core_concrete=$(grep -rcE 'LivePlatform\.[A-Z][A-Z_]*' --include='*.java' \
    starbot-core/src/main 2>/dev/null | awk -F: '{s+=$2} END {print s+0}')

if [ "$g3_core_concrete" -eq 0 ]; then
    echo "格3 绿 core内具体成员0处 枚举${g3_members}/被引用${g3_used} $LP"
else
    echo "格3 红 core内具体成员${g3_core_concrete}处(须0) 枚举${g3_members}/被引用${g3_used} $LP"
    RED=1
fi

exit "$RED"
