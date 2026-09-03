#!/usr/bin/env bash
# NovaCore 边界尺：核心与平台壳的分界是否守住
#
# 判据来源：2026-09-03 产品边界定义 —— NovaCore ＝「一个礼貌的、能登录的 B 站直播事件源」，
# 五层归核心（①连接 ②身份 ③解析与事件模型 ④事件输出协议 ⑤采集范围），
# 控制台／登录／推送／聚合／存储归 NovaBot 壳。一句话尺：莓果或 VRDash 用不到的，就不是核心。
#
# 本尺只 grep/find，不 build、不联网。拆仓／拆模块后作 CI 守卫。
# 退码：五格任一红 ⇒ 非 0。
#
# —— 格4／格5 为什么要加（2026-09-03 加严）——
# 原来的格2 只按 NovaEventEndpoint／NovaEventStreamConfiguration 两个文件名判「在不在核心模块」。
# 那量的是位置不是边界：把事件流的其余几个类挪回插件、或把核心的配置键写回平台前缀，
# 尺一格都不会红，而边界已经破了。加两格补上这两条缝——
#   格4 从「引用方向」判：核心引用了插件，就是核心依赖了平台，文件放在哪个目录都不作数。
#   格5 从「配置键」判：键名带平台名，等于把平台写进了核心对外的接口面，改名要惊动所有使用者。
# 两格都不写死任何平台名或插件包名，一律从源码现算，理由同格1。

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
#   ① 各插件模块登记平台的地方：LivePlatform.of("<标识串>"[, "<显示名>"]) 里的字面量，
#      以及承载它的常量名（含小写、去下划线、连字符各变体）
#      —— 口径改自插件侧是 2026-09-03 平台清单改开放标识（乙形）的必然结果：核心里已经
#      没有平台清单可读，认得哪几个平台完全取决于这台实例装了哪些插件，尺也就只能从那一侧算。
#      代价照记：从此清单里只有<b>真有插件的</b>平台，没人实现的平台名写进核心界面尺不会红——
#      而那种名字本来也不该由核心来背书，它该在开插件的那一笔里连同实现一起进来。
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

# —— 插件模块的主码目录，供源头① 与格3 使用 ——
PLUGIN_MAINS=""
for mod in */; do
    mod="${mod%/}"
    [ -d "$mod/src/main" ] || continue
    skip=0
    for allowed in $ALLOWED_CORE_MODULES; do
        [ "$mod" = "$allowed" ] && skip=1
    done
    [ "$skip" -eq 1 ] && continue
    PLUGIN_MAINS="${PLUGIN_MAINS}${mod}/src/main "
done

# —— 源头①：各插件模块登记平台的地方 ——
g1_registrations=0
if [ -n "$PLUGIN_MAINS" ]; then
    while IFS= read -r call; do
        [ -z "$call" ] && continue
        g1_registrations=$((g1_registrations + 1))
        printf '%s\n' "$call" | grep -oE '"[^"]*"' | sed -E 's/^"(.*)"$/\1/' >> "$TOKENS"
    done <<< "$(grep -rhoE 'LivePlatform\.of\("[^"]*"([[:space:]]*,[[:space:]]*"[^"]*")?[[:space:]]*\)' \
        --include='*.java' $PLUGIN_MAINS 2>/dev/null | sort -u)"

    while IFS= read -r member; do
        [ -z "$member" ] && continue
        lower="$(printf '%s' "$member" | tr 'A-Z' 'a-z')"
        printf '%s\n%s\n%s\n' "$lower" "${lower//_/}" "${lower//_/-}" >> "$TOKENS"
    done <<< "$(grep -rhoE 'LivePlatform[[:space:]]+[A-Z][A-Z_0-9]*[[:space:]]*=' \
        --include='*.java' $PLUGIN_MAINS 2>/dev/null \
        | sed -E 's/.*[[:space:]]([A-Z][A-Z_0-9]*)[[:space:]]*=$/\1/' | sort -u)"
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

# 单串是否带平台名。分档规则与 scan 一致：短标识整词、其余子串
# （格5 逐条判配置键时用；不能拿 scan 去查，那会连「文件路径里的平台名」也一并命中）
platform_hit() {
    if [ -s "$WORK/long" ] && printf '%s\n' "$1" | grep -qiF -f "$WORK/long"; then
        return 0
    fi
    if [ -s "$WORK/short" ] && printf '%s\n' "$1" | grep -qiwF -f "$WORK/short"; then
        return 0
    fi
    if [ -s "$WORK/wide" ] && printf '%s\n' "$1" | grep -qF -f "$WORK/wide"; then
        return 0
    fi
    return 1
}

# 去掉 Java 注释后的正文（格3 用）
#
# 判据只看代码不看注释：类上写一段「插件这样登记平台」的用法示例，正是该写的话——
# 让示例把自己的判据判红，那把尺的下场必然是把示例删掉，而不是把边界守住。
# 同一条道理在格5 源头② 已经用过一次（只看键不看注释）。
# 已知边界：串里含 // 的行会被从那里截断，判据因此只会漏不会误报——
# 想靠这一点把登记藏进核心，得先在同一行写一个带 // 的字符串，那已不是笔误而是有意为之。
strip_comments() {
    awk '
    {
        line = $0
        while (1) {
            if (inc) {
                i = index(line, "*/")
                if (i == 0) { line = ""; break }
                line = substr(line, i + 2); inc = 0
            } else {
                i = index(line, "/*")
                if (i == 0) break
                pre = substr(line, 1, i - 1); rest = substr(line, i + 2)
                j = index(rest, "*/")
                if (j == 0) { line = pre; inc = 1; break }
                line = pre substr(rest, j + 2)
            }
        }
        sub(/\/\/.*$/, "", line)
        print line
    }' "$1"
}

# 核心主码正文里命中某个模式的处数（注释不计）
core_code_hits() {
    hits_re="$1"
    hits_total=0
    while IFS= read -r hits_f; do
        [ -z "$hits_f" ] && continue
        hits_n=$(strip_comments "$hits_f" | grep -cE "$hits_re")
        hits_total=$((hits_total + hits_n))
    done <<< "$(grep -rlE "$hits_re" --include='*.java' $CORE_MAINS 2>/dev/null)"
    echo "$hits_total"
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
    echo "格1 绿 命中0 核心界面无平台字样 平台名${g1_tokens}个(现算自插件侧登记${g1_registrations}处)"
else
    echo "格1 红 命中${g1_n} ${g1_hits% } 平台名${g1_tokens}个(现算自插件侧登记${g1_registrations}处)"
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

# —— 核心模块的主码目录，供格3／格4／格5 使用 ——
# 从 ALLOWED_CORE_MODULES 现算而不写死 starbot-core：模块一改名，写死的那几格就静默地什么都不查了
CORE_MAINS=""
for allowed in $ALLOWED_CORE_MODULES; do
    [ -d "$allowed/src/main" ] && CORE_MAINS="${CORE_MAINS}${allowed}/src/main "
done

# ============================================================
# 格3：平台标识 LivePlatform —— 读法已定为「开放标识」（乙形，2026-09-03 裁）
#
# 枚举列平台名的老形态已退场。它有两处不成立：一是那份清单要为列而不做的那几个平台背书，
# 而它们一个实现也没有；二是——更要紧的——它把「世上有哪些直播平台」写死进了核心，
# 于是核心永远认识每一个平台的名字，拆出去给别的产品用时那份名单还得跟着走。
#
# 乙形＝平台由各插件在自己那一侧登记，核心只认标识串并原样透传。三条一起判，全绿才绿：
#   ① LivePlatform 件内不许有平台申报：既不许列枚举成员，也不许挂平台常量，
#      并且件内不许出现任何一个现算出来的平台名 —— 后者是兜底，
#      防的是换一种写法（数组、Map、静态块）把同一份名单塞回同一个文件。
#   ② 核心主码里引用 LivePlatform.<具体成员> 的处数为 0 —— 核心不认识任何一个具体平台。
#   ③ 平台登记 LivePlatform.of("…") 只出现在插件模块，核心主码里 0 处 ——
#      光有 ① ② 拦不住核心在别的文件里就地登记一个平台，那等于把平台名换个地方写回核心。
#      射程只到 src/main 不含 src/test：测试造场景时按串取一个平台是数据不是依赖，理由同格4。
#
# ① 的两个数与 ③ 一并印出来，红时看得出是哪一条不成立。
# ============================================================
g3_declared=0
g3_named=0
if [ -f "$LP" ]; then
    strip_comments "$LP" > "$WORK/lp_code"
    g3_declared=$(grep -cE '^[[:space:]]*[A-Z][A-Z_0-9]*\("|static[[:space:]]+final[[:space:]]+LivePlatform[[:space:]]+[A-Z]' "$WORK/lp_code")
    g3_named=$(scan "$WORK/lp_code" | sort -u | awk -F: '!seen[$1":"$2]++' | wc -l | tr -d ' ')
fi
g3_core_concrete=$(core_code_hits 'LivePlatform\.[A-Z][A-Z_]*')
g3_core_register=$(core_code_hits 'LivePlatform\.of[[:space:]]*\(')

g3_read="件内申报${g3_declared}处 件内平台名${g3_named}处 核心内具体成员${g3_core_concrete}处 核心内登记${g3_core_register}处 插件侧登记${g1_registrations}处(现算)"
if [ "$g3_declared" -eq 0 ] && [ "$g3_named" -eq 0 ] \
    && [ "$g3_core_concrete" -eq 0 ] && [ "$g3_core_register" -eq 0 ]; then
    echo "格3 绿 $g3_read $LP"
else
    echo "格3 红 四数须全0 $g3_read $LP"
    RED=1
fi

# ============================================================
# 格4：核心不得引用插件包
#
# 格2 问的是「事件流那两个类在哪个模块」，这一格问的是「核心有没有反过来依赖插件」——
# 后者才是拆仓时真正拦路的那件事：核心一旦 import 了插件的类，它就编译不了，
# 也就不可能单独发布给莓果或 VRDash 用。文件放在哪个目录跟这件事无关。
#
# 插件包名当场从模块目录算出来，不写死：本仓实际的插件根包是 com.starlwr.bot.bilibili 与
# com.starlwr.bot.adapter（OneBot 适配器的模块目录叫 starbot-onebot-adapter，包却在 adapter 下）——
# 写死一份名单，只要有一处对不上，这一格就是个永远绿的摆设。
#
# 射程只到核心的 src/main，不含 src/test：
#   测试要造场景，写一个插件类的**全限定名字符串**是正常的（现有一处：
#   ConfigurationValidatorTest 里那个用来喂给校验器的处理器类名字面）。那是数据不是依赖，
#   它既不参与核心的编译，也不会跟着核心发布出去。把测试算进来，这一格第一天就得开例外，
#   而开在测试上的例外会顺手把真正的依赖也放过去。
# 判据是「出现即命中」而非只查 import 行：全限定名直接写在代码里同样是依赖，
# 反射按字符串加载更是——那种依赖编译期看不见，运行期才炸。
# ============================================================

G4_PKGS="$WORK/g4pkgs"
: > "$G4_PKGS"

for mod in */; do
    mod="${mod%/}"
    [ -d "$mod/src/main/java/com/starlwr/bot" ] || continue
    skip=0
    for allowed in $ALLOWED_CORE_MODULES; do
        [ "$mod" = "$allowed" ] && skip=1
    done
    [ "$skip" -eq 1 ] && continue

    find "$mod/src/main/java/com/starlwr/bot" -mindepth 1 -maxdepth 1 -type d 2>/dev/null \
        | sed 's|.*/||' >> "$G4_PKGS"
done

sort -u "$G4_PKGS" -o "$G4_PKGS"
g4_pkg_n=$(grep -cv '^[[:space:]]*$' "$G4_PKGS" 2>/dev/null || echo 0)

g4_hits=""
g4_n=0

if [ -n "$CORE_MAINS" ] && [ "$g4_pkg_n" -gt 0 ]; then
    sed -E 's|^|com\\.starlwr\\.bot\\.|; s|$|([^A-Za-z0-9_]\|$)|' "$G4_PKGS" > "$WORK/g4re"
    while IFS= read -r hit; do
        [ -z "$hit" ] && continue
        g4_hits="${g4_hits}${hit%%:*}:$(printf '%s' "$hit" | cut -d: -f2) "
        g4_n=$((g4_n + 1))
    done <<< "$(grep -rnE -f "$WORK/g4re" --include='*.java' $CORE_MAINS 2>/dev/null | sort -u)"
fi

if [ "$g4_n" -eq 0 ]; then
    echo "格4 绿 命中0 核心不引用插件包 插件包${g4_pkg_n}个(现算): $(tr '\n' ',' < "$G4_PKGS" | sed 's/,$//')"
else
    echo "格4 红 命中${g4_n} ${g4_hits% } 插件包${g4_pkg_n}个(现算)"
    RED=1
fi

# ============================================================
# 格5：核心的配置键不得带平台名
#
# 配置键是核心对使用者的接口面。键里带平台名，等于核心公开承认自己长在某一个平台上：
# 拆出去给别的产品用时改不动（改一次所有既有部署的配置都失效），不改又处处是那个平台的名字。
# 这一格与格1 是同一条边界的两面——格1 管界面上看得见的字，这一格管配置文件里写下的键。
#
# 两处源头，都在核心一侧：
#   ① 核心主码里作为配置前缀出现的字面量：@ConfigurationProperties(prefix = "…")，
#      以及被它引用的 …PREFIX… 常量（值以 starbot. 开头的那些）
#   ② 发行模板 dist/templates/application.yml 里核心那一节的键（starbot.core.* 全部路径）
# 平台名清单与格1 共用，从 LivePlatform 现算。
#
# —— 例外表：每条写明理由与到期条件，没有到期条件的例外一律不许加 ——
#    命中行的文件名与内容同时匹配才豁免。
G5_EXEMPT=(
    # 旧键兼容：事件输出的配置键曾在平台插件一侧，真源迁入核心后键名改了，但既有部署的
    # application.yml 里写的还是旧键。这个字面量是**读侧**认旧键用的（绑定时先按它绑一趟，
    # 再让现行键逐项压过去），不是核心在用平台前缀对外提供配置——核心自己声明的前缀是
    # @ConfigurationProperties 上那个，不带平台名。
    # 引用它的是 NovaEventStreamConfiguration，字面量本身落在 EventStreamProperties 上。
    # 到期条件：旧键弃用移除的那一版——那一版删掉 LEGACY_PREFIX 常量，本条同时删除。
    "EventStreamProperties.java LEGACY_PREFIX"
)
# ============================================================

g5_hits=""
g5_n=0
g5_keys=0

# —— 源头①：核心主码里的配置前缀字面量 ——
if [ -n "$CORE_MAINS" ]; then
    while IFS= read -r hit; do
        [ -z "$hit" ] && continue
        file="${hit%%:*}"
        rest="${hit#*:}"
        lineno="${rest%%:*}"
        text="${rest#*:}"

        # 取这一行里的字符串字面量作为待判的键
        key="$(printf '%s' "$text" | sed -E 's/^[^"]*"([^"]*)".*/\1/')"
        [ -z "$key" ] && continue
        g5_keys=$((g5_keys + 1))

        exempt=0
        for rule in ${G5_EXEMPT[@]+"${G5_EXEMPT[@]}"}; do
            rule_file="${rule%% *}"
            rule_re="${rule#* }"
            if [[ "$file" == *"$rule_file"* ]] && printf '%s' "$text" | grep -qE "$rule_re"; then
                exempt=1
                break
            fi
        done
        [ "$exempt" -eq 1 ] && continue

        if platform_hit "$key"; then
            g5_hits="${g5_hits}${file}:${lineno}(${key}) "
            g5_n=$((g5_n + 1))
        fi
    done <<< "$(grep -rnE '@ConfigurationProperties\(.*prefix[[:space:]]*=[[:space:]]*"|(static[[:space:]]+final[[:space:]]+String[[:space:]]+[A-Z_]*PREFIX[A-Z_]*[[:space:]]*=[[:space:]]*"starbot\.)' \
        --include='*.java' $CORE_MAINS 2>/dev/null | sort -u)"
fi

# —— 源头②：发行模板里核心那一节的键 ——
# 只看键，不看注释：注释里提到旧位置是在教人怎么迁，正是该写的话
TEMPLATE_YML="dist/templates/application.yml"
if [ -f "$TEMPLATE_YML" ]; then
    while IFS= read -r entry; do
        [ -z "$entry" ] && continue
        lineno="${entry%%:*}"
        key="${entry#*:}"
        g5_keys=$((g5_keys + 1))

        if platform_hit "$key"; then
            g5_hits="${g5_hits}${TEMPLATE_YML}:${lineno}(${key}) "
            g5_n=$((g5_n + 1))
        fi
    done <<< "$(awk '
        {
            line = $0
            sub(/[[:space:]]*#.*$/, "", line)                 # 去掉注释
            if (line ~ /^[[:space:]]*$/) next
            if (line ~ /^[[:space:]]*-/) next                 # 列表项不是键路径的一级
            if (line !~ /^[[:space:]]*[A-Za-z_][A-Za-z0-9_.-]*[[:space:]]*:/) next

            indent = match(line, /[^ ]/) - 1
            key = line
            sub(/^[[:space:]]*/, "", key)
            sub(/[[:space:]]*:.*$/, "", key)

            depth = int(indent / 2)
            path[depth] = key
            for (i = depth + 1; i <= maxdepth; i++) delete path[i]
            if (depth > maxdepth) maxdepth = depth

            full = path[0]
            for (i = 1; i <= depth; i++) full = full "." path[i]

            if (full ~ /^starbot\.core(\.|$)/) print NR ":" full
        }
    ' "$TEMPLATE_YML")"
fi

if [ "$g5_n" -eq 0 ]; then
    echo "格5 绿 命中0 核心配置键无平台名 受查键${g5_keys}个 例外${#G5_EXEMPT[@]}条"
else
    echo "格5 红 命中${g5_n} ${g5_hits% } 受查键${g5_keys}个 例外${#G5_EXEMPT[@]}条"
    RED=1
fi

# ============================================================
# 格6：核心件不得引用壳侧件
#
# 这一格是编译期守卫的前奏。核心拆成独立模块之后，它的 pom 不再依赖运行壳，
# 那一刻起核心件里每一处对壳侧件的引用都是编译错误。拆的那天不该是
# 「先编译失败、再回头逐个查」，而该是「这把尺早已 0 命中，改完直接编得过」。
#
# 与格4 的分工：格4 管「核心 → 插件模块」，跨模块，编译期本就看得见；
# 本格管「核心 → 同一个模块内的壳侧件」，同一个 jar 里，编译期一点异常都没有，
# 只有真拆开那天才炸。也正因为同模块，**同包不写 import 的引用一样算**——
# 只查 import 行的尺，会把 config 包内、model 包内那一批不写 import 的引用整批放过，
# 而那种引用拆的时候一样编不过。判据因此按简单类名在正文里找，与格4 同法。
#
# 注释不算（与格3 同法，走 strip_comments）：类注释里写一句「这个事件由某某记住」
# 是该写的话，让它把判据判红，结局必然是把注释删掉而不是把边界守住。
#
# 两侧集合都写在下面、可当场比对，不从别处读表：
#   核心件 ＝ 事件源纯库。整目录收的四个包 ＋ 逐件点名的若干件（配置纯 POJO、
#            事件流令牌、数据源服务接口与注解、平台标识与事件枚举、异常型、
#            以及它们用到的四个纯函数工具类）。
#   壳侧件 ＝ 核心模块主码里除此之外的**全部**件。用「补集」而不是再列一张壳的清单，
#            是因为这两种写法只有一处不同、而那一处正是要害：漏列一件壳，
#            列清单的写法静默放过，补集的写法当场判红。新件默认落在壳这一侧，
#            要进核心得往上面那张表里加一行并写明理由——这正是该有的方向。
#
# 逐件点名的那几件为什么算核心，一句话各记一条：
#   config/*Properties       —— 配置纯 POJO，零框架注解，事件源自己要读的那几节
#   service/EventStreamTokenService —— 事件输出协议的只读令牌，属第④层
#   service/DataSourceService / DataSourceServiceConfig —— 采集范围的接口面与实现注解
#   enums/LivePlatform / LiveEndReason / PushTargetType —— 平台标识与事件模型枚举；
#                          PushTargetType 是 PushTarget 的字段类型，随推送模型一起走，
#                          它留在壳侧的话 PushTarget 根本编不过
#   exception/DataSourceException —— 采集范围加载失败的异常型
#   util/SecureToken / MathUtil / StringUtil / CollectionUtil —— 四个纯函数工具，
#                          不碰框架、不碰界面，且各自都有核心侧的调用方（金额换算、
#                          推送参数判空、配置重载时的集合比对）
# ============================================================

# —— 核心件：整目录收 ——
G6_CORE_DIRS="protocol event datasource model"

# —— 核心件：逐件点名（路径相对 com/starlwr/bot/core/）——
G6_CORE_FILES="config/NetworkProperties.java
config/NetworkThreadProperties.java
config/LogProperties.java
config/LiveProperties.java
config/DatasourceProperties.java
config/EventStreamProperties.java
service/EventStreamTokenService.java
service/DataSourceService.java
service/DataSourceServiceConfig.java
enums/LivePlatform.java
enums/LiveEndReason.java
enums/PushTargetType.java
exception/DataSourceException.java
util/SecureToken.java
util/MathUtil.java
util/StringUtil.java
util/CollectionUtil.java"

# —— 例外表：每条写「核心件基名 被引件基名 到期条件」，三段以空格分隔 ——
#    **到期条件是必填的**：没有到期条件的例外只会长住，一年后没人记得它当初豁免的是什么。
#    现无例外。
G6_EXEMPT=()

G6_ALL="$WORK/g6all"
: > "$G6_ALL"
for allowed in $ALLOWED_CORE_MODULES; do
    [ -d "$allowed/src/main/java" ] && find "$allowed/src/main/java" -name '*.java' -type f >> "$G6_ALL"
done
sort -u "$G6_ALL" -o "$G6_ALL"
g6_total=$(grep -cv '^[[:space:]]*$' "$G6_ALL" 2>/dev/null || echo 0)

G6_CORE="$WORK/g6core"
: > "$G6_CORE"
if [ "$g6_total" -gt 0 ]; then
    for d in $G6_CORE_DIRS; do
        grep -F "/com/starlwr/bot/core/$d/" "$G6_ALL" >> "$G6_CORE" 2>/dev/null
    done
    while IFS= read -r rel; do
        [ -z "$rel" ] && continue
        grep -F "/com/starlwr/bot/core/$rel" "$G6_ALL" >> "$G6_CORE" 2>/dev/null
    done <<< "$G6_CORE_FILES"
fi
sort -u "$G6_CORE" -o "$G6_CORE"
g6_core_n=$(grep -cv '^[[:space:]]*$' "$G6_CORE" 2>/dev/null || echo 0)

G6_SHELL="$WORK/g6shell"
: > "$G6_SHELL"
[ "$g6_core_n" -gt 0 ] && grep -vxFf "$G6_CORE" "$G6_ALL" > "$G6_SHELL"
sed 's|.*/||; s|\.java$||' "$G6_SHELL" | sort -u > "$WORK/g6names"
g6_shell_n=$(grep -cv '^[[:space:]]*$' "$WORK/g6names" 2>/dev/null || echo 0)

g6_hits=""
g6_n=0
if [ "$g6_shell_n" -gt 0 ] && [ "$g6_core_n" -gt 0 ]; then
    sed -E 's|^|(^\|[^A-Za-z0-9_])|; s|$|([^A-Za-z0-9_]\|$)|' "$WORK/g6names" > "$WORK/g6re"
    while IFS= read -r f; do
        [ -z "$f" ] && continue
        strip_comments "$f" > "$WORK/g6body"
        grep -qE -f "$WORK/g6re" "$WORK/g6body" || continue
        core_base="$(basename "$f" .java)"
        while IFS= read -r n; do
            [ -z "$n" ] && continue
            g6_line=$(grep -nE "(^|[^A-Za-z0-9_])${n}([^A-Za-z0-9_]|\$)" "$WORK/g6body" 2>/dev/null | head -1 | cut -d: -f1)
            [ -z "$g6_line" ] && continue

            exempt=0
            for rule in ${G6_EXEMPT[@]+"${G6_EXEMPT[@]}"}; do
                set -- $rule
                if [ "$1" = "$core_base" ] && [ "$2" = "$n" ]; then
                    exempt=1
                    break
                fi
            done
            [ "$exempt" -eq 1 ] && continue

            g6_hits="${g6_hits}${f#*/src/main/java/com/starlwr/bot/core/}:${g6_line}(->${n}) "
            g6_n=$((g6_n + 1))
        done < "$WORK/g6names"
    done < "$G6_CORE"
fi

g6_read="核心件${g6_core_n}/${g6_total} 壳侧件${g6_shell_n} 例外${#G6_EXEMPT[@]}条"
if [ "$g6_n" -eq 0 ]; then
    echo "格6 绿 命中0 核心件不引用壳侧件 $g6_read"
else
    echo "格6 红 命中${g6_n} ${g6_hits% } $g6_read"
    RED=1
fi

exit "$RED"
