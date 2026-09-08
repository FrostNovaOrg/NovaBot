#!/usr/bin/env bash
# NovaCore 边界尺：核心与平台壳的分界是否守住
#
# 判据来源：2026-09-03 产品边界定义 —— NovaCore ＝「一个礼貌的、能登录的 B 站直播事件源」，
# 五层归核心（①连接 ②身份 ③解析与事件模型 ④事件输出协议 ⑤采集范围），
# 控制台／登录／推送／聚合／存储归 NovaBot 壳。一句话尺：莓果或 VRDash 用不到的，就不是核心。
#
# 本尺只 grep/find，不 build、不联网。拆仓／拆模块后作 CI 守卫。
# 退码：任一格红 ⇒ 非 0。
#
# —— 格4／格5 为什么要加（2026-09-03 加严）——
# 原来的格2 只按 NovaEventEndpoint／NovaEventStreamConfiguration 两个文件名判「在不在核心模块」。
# 那量的是位置不是边界：把事件流的其余几个类挪回插件、或把核心的配置键写回平台前缀，
# 尺一格都不会红，而边界已经破了。加两格补上这两条缝——
#   格4 从「引用方向」判：核心引用了插件，就是核心依赖了平台，文件放在哪个目录都不作数。
#   格5 从「配置键」判：键名带平台名，等于把平台写进了核心对外的接口面，改名要惊动所有使用者。
# 两格都不写死任何平台名或插件包名，一律从源码现算，理由同格1。
#
# —— 「射程为空即红」与格9–12 为什么要加（2026-09-08 加严）——
# 八格全绿的那一天，其中四格是**哑的**：格1 的射程是一个写死的界面目录，格4 的插件包靠
# 「仓根一级目录名」枚举，格5 与格6 的射程由核心模块名拼出来。目录一改名或往下挪一层，
# 这四格的射程当场变成空集——而空集上的判据恒真，它们照报绿、退码照样是 0。
# 一个「查过了，没有违规」的绿，和一个「我什么都没查到」的绿，在那一行输出上长得一模一样。
# 因此本次给每一处射程加了空集守卫：射程为空 ⇒ 判红并印「射程为空」，宁可红错也不许绿在空集上。
# 同一条道理格5 的源头②（找不到发行模板就红）与格7（两侧件集有一侧为空就红）早已用过，
# 这次是把它补齐到全部射程上。跑法：`NOVACORE_CHECK_ROOT=$(mktemp -d) bash tools/novacore-boundary-check.sh`
# 应当整片判红——那趟是这些守卫自己的阳性对照，绿了就说明守卫没接上。
#
# 模块枚举一律走 `git ls-files '*/pom.xml'`（任意深度、只认在册件），不再按仓根一级目录名。
# 新增四格补的是另外四条缝：⑨同一个 java 包跨模块（撞包，立格时有三个，三刀解完已清零，
# 闭集照旧钉住不许再长）、
# ⑩插件 import 了兄弟插件却没在 pom 里申报那个模块、以及主码直引里层包却没申报 novacore、⑪核心界面目录在且非空（格1 射程的正面读数）、
# ⑫写死了模块目录名的在册件数只减不增（给目录重排立账，改一件销一件）。
# 格13 补模板缝：模板不进 reactor，引用了已改名或不存在的类，编译与测试都看不见；
# 使用者照抄，切面静默不生效。

set -uo pipefail

# —— 允许承载「核心」的模块目录名（拆模块后把新名加进来即可，不必改判据逻辑）——
ALLOWED_CORE_MODULES="core/starbot-core core/novacore"

# 缺省量本仓。NOVACORE_CHECK_ROOT 只为把上面那些「射程为空」的分支跑出来用：
# 指向一棵空树跑一趟，本尺该整片判红；若还有格子报绿，那一格就是绿在空集上。
REPO_ROOT="${NOVACORE_CHECK_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT" || exit 2

CORE_UI="core/starbot-core/src/main/resources/config-ui"
RED=0

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 数一个清单件有几行（空行不计）
#
# 不写 `$(grep -cv … || echo 0)`：空文件时 grep 已经把 "0" 印在标准输出上、退码才是 1，
# 于是 `|| echo 0` 再补一个 0，取回来的是**两行**「0」。拿它去 `[ "$n" -gt 0 ]` 比，
# bash 报 integer expression expected（在标准错误里，没人看）并判假——
# 判假恰好就是「跳过这一格」的那条路。射程为空的守卫要是也踩这个坑，守卫本身就是哑的。
count_lines() {
    cl_n="$(grep -cv '^[[:space:]]*$' "$1" 2>/dev/null)"
    printf '%s' "${cl_n:-0}"
}

# —— 件清单按工作树现算（格8／格9／格12／格13 的闭集共用）——
#
# 这三格原先都走 `git ls-files`，读的是**索引**，不是盘上的树。差别只在搬件的那一笔上现形：
# 刚挪到新位置的件没 `git add` 就不在索引里，挪走的旧件已经不在盘上却还留在索引里——
# 于是尺量到的是**改前的现状**。而执行层不许 git add（改动留树、候并入），
# 于是「把撞包解开」的那一笔在自己的树上永远看不到转绿：尺量不到刚做完的那件事，
# 转绿只能等并入之后由别人代跑一趟。一把在最需要它的那一刻是哑的尺，等于没有。
#
# 改法：`--cached` 与 `--others --exclude-standard` 取并集，再逐条剔掉盘上已不存在的路径。
#   取并集 —— 新件（还没 add）与在册件都算数；`--exclude-standard` 保证 target/、scratch/
#             这类被忽略的目录不会混进来，也就不必再逐层排除构建产物。
#   剔不存在 —— `--cached` 会把 `mv` 走、`rm` 掉而没 `git rm` 的件继续列出来，
#             不剔的话搬走的旧位置会被再数一遍，撞包永远解不开。
# 其余读法（挑哪些路径、怎么截包名、拿什么正则搜）一概不变。
# core.quotepath=false 免得非 ASCII 路径被转义成八进制而对不上。
tree_files() {
    git -c core.quotepath=false ls-files --cached --others --exclude-standard "$@" 2>/dev/null \
        | sort -u \
        | while IFS= read -r tf_path; do
              [ -e "$tf_path" ] && printf '%s\n' "$tf_path"
          done
}

# —— 在册模块目录现算（格1／格4／格8／格10／格11 共用）——
# 按 pom.xml 找、任意深度：原来那句 `for mod in */` 枚举的是**仓根一级目录名**，
# 模块往 plugins/ 底下一挪，它枚举得 0，而依赖这份枚举的几格全部静默转绿。
# 不用 find 而走上面的 tree_files：构建产物里的 pom 副本不是模块，
# 新建还没入册的模块却是——后者正是 git ls-files 那一版看不见的。
MODULES="$WORK/modules"
tree_files '*/pom.xml' | sed 's|/pom\.xml$||' | sort -u > "$MODULES"
module_n=$(count_lines "$MODULES")

# 模块目录是不是「允许承载核心」的那几个
is_core_module() {
    for icm_allowed in $ALLOWED_CORE_MODULES; do
        [ "$1" = "$icm_allowed" ] && return 0
    done
    return 1
}

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

# —— 平台标识件的位置现算，不写死模块名 ——
# 写死 core/starbot-core/... 的那一版有一个安静的失败形态：核心件搬进新模块的那一刻文件就不在了，
# 而格3 的两个「件内」计数在文件缺席时双双为 0，于是它报绿——报的绿是「这个文件里没有平台申报」，
# 而实情是「这个文件不在这里」。判据落在空集上恒真，看起来和守住了一模一样。
LP=""
for allowed in $ALLOWED_CORE_MODULES; do
    candidate="$allowed/src/main/java/com/starlwr/bot/core/enums/LivePlatform.java"
    [ -f "$candidate" ] && LP="$candidate" && break
done

# —— 插件模块的主码目录，供源头① 与格3 使用（模块清单现算，见上）——
PLUGIN_MAINS=""
while IFS= read -r mod; do
    [ -z "$mod" ] && continue
    [ -d "$mod/src/main" ] || continue
    is_core_module "$mod" && continue
    PLUGIN_MAINS="${PLUGIN_MAINS}${mod}/src/main "
done < "$MODULES"

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

while IFS= read -r mod; do
    [ -z "$mod" ] && continue
    [ -d "$mod/src/main" ] || continue
    is_core_module "$mod" && continue

    while IFS= read -r f; do
        [ -z "$f" ] && continue
        # 连接卡与账号登录的显示名才是平台名（「哔哩哔哩」）。顶级页／首页卡／向导步
        # 的显示名是产品功能名（「主播」），写进平台名清单会把核心界面里所有「主播」
        # 都判成把平台写死——那不是这一格要守的边界。
        if grep -q 'implements ConsolePageProvider' "$f" 2>/dev/null; then
            if grep -qE 'return ConsolePageSlot\.(TOP|HOME_CARD|SETUP_STEP)' "$f" 2>/dev/null; then
                continue
            fi
        fi
        grep -A3 'String displayName()' "$f" 2>/dev/null \
            | grep -oE 'return "[^"]*"' \
            | sed -E 's/^return "(.*)"$/\1/' >> "$TOKENS"
    done <<< "$(grep -rl 'implements ConsolePageProvider\|implements AccountLoginProvider' \
        --include='*.java' "$mod/src/main" 2>/dev/null)"
done < "$MODULES"

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

# —— 射程为空即红（两条射程各一条）——
# ① 被查的那个目录：原来这里只有 `if [ -d ]` 而没有 else，目录一改名整格静静地什么都不查；
# ② 平台名清单：清单是从插件侧现算的，插件模块枚举不到时它是空的，
#    而空清单喂给 grep -f 一处也匹配不上——查了整个目录、拿着一张白名单，报的绿名副其实是空的。
# 谁负责哪一条：本条只问「目录在不在、清单空不空」，目录**在而空**由格11 答（另一形态，另一读数）。
g1_scope=""
[ -d "$CORE_UI" ] || g1_scope="${g1_scope}核心界面目录不在($CORE_UI) "
[ "$g1_tokens" -eq 0 ] && g1_scope="${g1_scope}平台名清单为空(插件模块枚举${module_n}个) "

if [ -z "$g1_scope" ] && [ -d "$CORE_UI" ]; then
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

if [ -n "$g1_scope" ]; then
    echo "格1 红 射程为空 ${g1_scope% }"
    RED=1
elif [ "$g1_n" -eq 0 ]; then
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
        # 不取路径第一段：模块在 core/novacore 时第一段是 core，对不上 ALLOWED 整串。
        ok=0
        for allowed in $ALLOWED_CORE_MODULES; do
            case "$f" in
                "$allowed"/*) ok=1 ;;
            esac
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
if [ -z "$LP" ]; then
    echo "格3 红 核心模块里找不到 LivePlatform.java 受查模块: $ALLOWED_CORE_MODULES"
    RED=1
elif [ "$g3_declared" -eq 0 ] && [ "$g3_named" -eq 0 ] \
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

while IFS= read -r mod; do
    [ -z "$mod" ] && continue
    [ -d "$mod/src/main/java/com/starlwr/bot" ] || continue
    is_core_module "$mod" && continue

    find "$mod/src/main/java/com/starlwr/bot" -mindepth 1 -maxdepth 1 -type d 2>/dev/null \
        | sed 's|.*/||' >> "$G4_PKGS"
done < "$MODULES"

sort -u "$G4_PKGS" -o "$G4_PKGS"
g4_pkg_n=$(count_lines "$G4_PKGS")

g4_hits=""
g4_n=0

# 射程两条，缺一即红（原来是「两条都在才查」，于是缺一条就跳过整格而报绿）：
# 被查的一侧＝核心主码目录，拿来查的一侧＝插件包名清单。
g4_scope=""
[ -z "$CORE_MAINS" ] && g4_scope="${g4_scope}核心主码目录为空(受查模块:$ALLOWED_CORE_MODULES) "
[ "$g4_pkg_n" -eq 0 ] && g4_scope="${g4_scope}插件包清单为空(在册模块${module_n}个) "

if [ -z "$g4_scope" ]; then
    sed -E 's|^|com\\.starlwr\\.bot\\.|; s|$|([^A-Za-z0-9_]\|$)|' "$G4_PKGS" > "$WORK/g4re"
    while IFS= read -r hit; do
        [ -z "$hit" ] && continue
        g4_hits="${g4_hits}${hit%%:*}:$(printf '%s' "$hit" | cut -d: -f2) "
        g4_n=$((g4_n + 1))
    done <<< "$(grep -rnE -f "$WORK/g4re" --include='*.java' $CORE_MAINS 2>/dev/null | sort -u)"
fi

if [ -n "$g4_scope" ]; then
    echo "格4 红 射程为空 ${g4_scope% }"
    RED=1
elif [ "$g4_n" -eq 0 ]; then
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
#   ② 发行模板 dist/templates/application.example.yml 里核心那一节的键（starbot.core.* 全部路径）
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
# 🔴 找不到核心主码就红，理由与下面源头② 的「找不到模板就红」一字不差：
#    CORE_MAINS 是由核心模块名拼出来的，模块一改名它就是空串，源头① 整段没量到，
#    而这一格只凭源头② 的模板键照报绿——受查键数少一半，那一行输出上看不出来。
if [ -z "$CORE_MAINS" ]; then
    g5_hits="${g5_hits}$(printf '%s' "$ALLOWED_CORE_MODULES" | tr ' ' ','):0(找不到核心主码目录，源头①整段没量到) "
    g5_n=$((g5_n + 1))
else
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
# 🔴 名字改过一次（5.1：发行包不再带 application.yml，改带 application.example.yml），
#    所以这里不写死一个名字，现找。而且**找不到就红**：原来那句 `if [ -f ]` 在文件改名的
#    那一刻会静静跳过整个源头②，受查键数少一半而这一格照报绿——
#    一个「模板里没有平台名」的绿，和一个「我没找到模板」的绿，在那一行输出上长得一样。
TEMPLATE_YML=""
for candidate in dist/templates/application.example.yml dist/templates/application.yml; do
    [ -f "$candidate" ] && TEMPLATE_YML="$candidate" && break
done
if [ -z "$TEMPLATE_YML" ]; then
    g5_hits="${g5_hits}dist/templates/:0(找不到发行配置模板，源头②整段没量到) "
    g5_n=$((g5_n + 1))
fi
if [ -n "$TEMPLATE_YML" ]; then
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
#   核心件 ＝ 事件源纯库。整目录收的六个包 ＋ 逐件点名的若干件
#            （平台标识与事件枚举、异常型）。
#   壳侧件 ＝ 核心模块主码里除此之外的**全部**件。用「补集」而不是再列一张壳的清单，
#            是因为这两种写法只有一处不同、而那一处正是要害：漏列一件壳，
#            列清单的写法静默放过，补集的写法当场判红。新件默认落在壳这一侧，
#            要进核心得往上面那张表里加一行并写明理由——这正是该有的方向。
#
# 逐件点名的那几件为什么算核心，一句话各记一条：
#   （*Properties 五件、ConfigEffect、CoreConfigurationSections 原先逐件点名在 config/ 下，
#     现已整体改到新包 properties——那五件是配置纯 POJO、零框架注解，事件源自己要读的那几节；
#     ConfigEffect 是生效时机的标注，只用 java.lang.annotation，必须与那几节同侧，
#     放在壳侧就等于核心反过来引用运行壳，本格与格7 会当场红；CoreConfigurationSections 是
#     那几节的元数据出处，默认值取自字段初始值、说明取自 Javadoc，两者只存在于源码里，
#     因此这份声明必须与那几个类同模块，放到壳侧生成出来的就是一列空值，它不参与绑定。
#     properties 进了下面的「整目录收」，表上不再点名：留着旧路径的八行是空条目，
#     路径一改谁也匹配不到，而表照旧看着是满的——同下面 EventStreamTokenService 那几行的处理）
#   （EventStreamTokenService 与 DataSourceService / DataSourceServiceConfig 原先在这张表上，
#     现已并入 protocol／datasource 两个包——那两个包在上面的「整目录收」里，
#     再在表上点一次是空条目：路径下次一改它谁也匹配不到，而表照旧看着是满的）
#   enums/LivePlatform / LiveEndReason / PushTargetType —— 平台标识与事件模型枚举；
#                          PushTargetType 是 PushTarget 的字段类型，随推送模型一起走，
#                          它留在壳侧的话 PushTarget 根本编不过
#   exception/DataSourceException —— 采集范围加载失败的异常型
#   （SecureToken / MathUtil / StringUtil / CollectionUtil 原先逐件点名在 util/ 下，
#     现已整体改到 lang 包——四个纯函数工具，不碰框架、不碰界面，各自都有核心侧的
#     调用方（金额换算、推送参数判空、配置重载时的集合比对）。lang 进了下面的
#     「整目录收」，表上不再点名：留着旧路径的四行是空条目，路径一改谁也匹配不到，
#     而表照旧看着是满的——同 EventStreamTokenService 那几行的处理）
# ============================================================

# —— 核心件：整目录收 ——
# 这六个包只长在核心模块里（壳侧没有同名包），因此整目录收。
# 两侧同名的包眼下一个也不剩了：config 是最后一个，核心侧那八件已改到 properties——
# 逐件点名那张表原本就是为「两侧同名包」准备的，同名包没了，表也就只剩枚举与异常型那几件。
G6_CORE_DIRS="protocol event datasource model lang properties"

# —— 核心件：逐件点名（路径相对 com/starlwr/bot/core/）——
G6_CORE_FILES="enums/LivePlatform.java
enums/LiveEndReason.java
enums/PushTargetType.java
exception/DataSourceException.java"

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
g6_total=$(count_lines "$G6_ALL")

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
g6_core_n=$(count_lines "$G6_CORE")

G6_SHELL="$WORK/g6shell"
: > "$G6_SHELL"
[ "$g6_core_n" -gt 0 ] && grep -vxFf "$G6_CORE" "$G6_ALL" > "$G6_SHELL"
sed 's|.*/||; s|\.java$||' "$G6_SHELL" | sort -u > "$WORK/g6names"
g6_shell_n=$(count_lines "$WORK/g6names")

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

# —— 射程为空即红 ——
# 两条都是「什么都没量到」而不是「量过了没问题」：件集空了，比对整段跳过；
# 核心件集空了（上面那张点名表一件都没对上，路径变了就是这一形），补集＝全部件，
# 于是「核心件引用壳侧件」这句话没有主语，一处也命不中。
# 壳侧件为 0 不判红：那是核心模块里只剩核心件的终局形态，读数在上面那行看得见。
g6_scope=""
[ "$g6_total" -eq 0 ] && g6_scope="${g6_scope}核心模块主码为空(受查模块:$ALLOWED_CORE_MODULES) "
[ "$g6_total" -gt 0 ] && [ "$g6_core_n" -eq 0 ] && g6_scope="${g6_scope}核心件集为空(点名表与整目录都没对上任何件) "

if [ -n "$g6_scope" ]; then
    echo "格6 红 射程为空 ${g6_scope% } $g6_read"
    RED=1
elif [ "$g6_n" -eq 0 ]; then
    echo "格6 绿 命中0 核心件不引用壳侧件 $g6_read"
else
    echo "格6 红 命中${g6_n} ${g6_hits% } $g6_read"
    RED=1
fi

# ============================================================
# 格7：核心模块的构建描述里不得依赖运行壳模块
#
# 格6 是这一格的前奏，两格问的不是同一件事：格6 问「核心件有没有在源码里引用壳侧件」，
# 靠 grep 类名判；本格问「构建配置上核心还能不能够得着壳」。前者绿而后者红是常态——
# 源码一处不引用，pom 里却仍写着对壳的依赖，那么下一个人往核心里写一句 new 壳类()
# 照样编得过，边界只剩下一把每天要有人跑的 grep 在守。本格一绿，那句 new 当场编译失败：
# 判据从「有人记得跑尺」变成「编译器不答应」。这是模块化这一步真正买到的东西。
#
# 模块名不写死，两侧都从格6 已经算好的两份清单现算：
#   核心模块 ＝ 核心件所在的模块目录（格6 的 G6_CORE）
#   壳模块   ＝ 壳侧件所在的模块目录（格6 的 G6_SHELL）
# 写死 "novacore 的 pom 里不许有 starbot-core" 只守得住这一次改名之前的形态：
# 模块一改名，那一格就什么都不查了，而它照样报绿——同一个坑格3 刚踩过一次。
#
# 两侧落在同一个模块时判红并写明「尚未拆分」：那时核心与壳同在一个 jar 里，
# 「pom 不依赖壳」这句话恒真，而恒真的判据守不住任何东西。
#
# 依赖按 artifactId 判，不按目录名：目录叫什么与 Maven 解析用的坐标是两件事。
# 比对前先去掉 XML 注释——pom 里写一句「本模块不依赖运行壳」是该写的话，
# 让它把自己判红，结局必然是把那句注释删掉（理由同格3 的 strip_comments）。
# ============================================================

# 去掉 XML 注释后的正文
strip_xml_comments() {
    awk '
    {
        line = $0
        while (1) {
            if (inc) {
                i = index(line, "-->")
                if (i == 0) { line = ""; break }
                line = substr(line, i + 3); inc = 0
            } else {
                i = index(line, "<!--")
                if (i == 0) break
                pre = substr(line, 1, i - 1); rest = substr(line, i + 4)
                j = index(rest, "-->")
                if (j == 0) { line = pre; inc = 1; break }
                line = pre substr(rest, j + 3)
            }
        }
        print line
    }' "$1"
}

# 取一个模块自身的 artifactId
#
# 先把 <parent> 那一段整块去掉再取第一处：模块 pom 里最先出现的 artifactId 是父工程的，
# 直接 head -1 取到的是 starbot-parent，于是本格拿父工程的坐标去核心 pom 里找——
# 找得到（每个模块都声明父工程），判红，而红的理由与要守的那件事毫无关系。
module_artifact() {
    strip_xml_comments "$1/pom.xml" 2>/dev/null \
        | awk '/<parent>/{skip=1} /<\/parent>/{skip=0; next} !skip' \
        | grep -oE '<artifactId>[^<]+</artifactId>' \
        | head -1 \
        | awk -F'[<>]' '{print $3}'
}

# 一份件清单落在哪几个模块目录里
# 按 /src/main/java/ 截，不取路径第一段：模块挪进 plugins/ 之后第一段是 "plugins"，
# 于是本格拿 plugins/pom.xml 去找依赖——找不到，判红，而红的理由与要守的那件事无关。
modules_of() {
    sed -E 's|/src/main/java/.*$||' "$1" 2>/dev/null | sort -u | grep -v '^[[:space:]]*$'
}

g7_core_mods="$(modules_of "$G6_CORE" | tr '\n' ' ')"
g7_shell_mods="$(modules_of "$G6_SHELL" | tr '\n' ' ')"
g7_hits=""
g7_n=0
g7_read="核心模块[${g7_core_mods% }] 壳模块[${g7_shell_mods% }]"

if [ -z "${g7_core_mods// /}" ] || [ -z "${g7_shell_mods// /}" ]; then
    echo "格7 红 两侧件集有一侧为空，算不出模块归属 $g7_read"
    RED=1
else
    g7_same=0
    for cm in $g7_core_mods; do
        for sm in $g7_shell_mods; do
            [ "$cm" = "$sm" ] && g7_same=1
        done
    done

    if [ "$g7_same" -eq 1 ]; then
        echo "格7 红 核心件与壳侧件仍同处一个模块，核心尚未拆成独立模块 $g7_read"
        RED=1
    else
        for cm in $g7_core_mods; do
            if [ ! -f "$cm/pom.xml" ]; then
                g7_hits="${g7_hits}${cm}/pom.xml(缺件) "
                g7_n=$((g7_n + 1))
                continue
            fi
            strip_xml_comments "$cm/pom.xml" > "$WORK/g7pom"
            for sm in $g7_shell_mods; do
                sm_artifact="$(module_artifact "$sm")"
                [ -z "$sm_artifact" ] && continue
                hit=$(grep -c "<artifactId>${sm_artifact}</artifactId>" "$WORK/g7pom")
                if [ "$hit" -gt 0 ]; then
                    g7_hits="${g7_hits}${cm}/pom.xml(->${sm_artifact}x${hit}) "
                    g7_n=$((g7_n + hit))
                fi
            done
        done

        if [ "$g7_n" -eq 0 ]; then
            echo "格7 绿 命中0 核心模块不依赖运行壳 $g7_read"
        else
            echo "格7 红 命中${g7_n} ${g7_hits% } $g7_read"
            RED=1
        fi
    fi
fi

# ============================================================
# 格8：report 包在场，且 bilibili 主码零引用它
#
# 两问都要成立才绿：
#   ① 源码里有 com.starlwr.bot.report 这个包（从各模块 src/main 现算）
#   ② starbot-bilibili 的 src/main 零处出现 com.starlwr.bot.report
#
# 只问 ② 的尺在包还不存在时恒真（零引用），会把「还没拆」报成绿。
# 所以 ① 是门槛：现码 report 包不在场，先红于这一问。
# ============================================================

g8_report_dirs=""
g8_pkg_n=0
while IFS= read -r mod; do
    [ -z "$mod" ] && continue
    [ -d "$mod/src/main/java/com/starlwr/bot/report" ] || continue
    g8_report_dirs="${g8_report_dirs}${mod}/src/main/java/com/starlwr/bot/report "
    g8_pkg_n=$((g8_pkg_n + 1))
done < "$MODULES"

# ② 那一侧的射程：bilibili 插件的主码在哪个模块，按**包名**现找，不写死目录名。
# 原来这里写死 plugins/starbot-bilibili/src/main，模块一改名 `if [ -d ]` 不成立，
# ② 整问跳过、g8_refs 恒为 0，而这一格只要 ① 成立就报绿——报的是「没引用」，
# 实情是「没查」。找不到就红，与 ① 同格待遇。
G8_BILI_MAIN=""
while IFS= read -r mod; do
    [ -z "$mod" ] && continue
    [ -d "$mod/src/main/java/com/starlwr/bot/bilibili" ] || continue
    G8_BILI_MAIN="${G8_BILI_MAIN}${mod}/src/main "
done < "$MODULES"

g8_refs=0
g8_hits=""
if [ -n "$G8_BILI_MAIN" ]; then
    while IFS= read -r hit; do
        [ -z "$hit" ] && continue
        g8_refs=$((g8_refs + 1))
        g8_hits="${g8_hits}${hit%%:*}:$(printf '%s' "$hit" | cut -d: -f2) "
    done <<< "$(grep -rn 'com\.starlwr\.bot\.report' --include='*.java' $G8_BILI_MAIN 2>/dev/null | sort -u)"
fi

if [ -z "$G8_BILI_MAIN" ]; then
    echo "格8 红 射程为空 找不到 com.starlwr.bot.bilibili 的主码模块(在册模块${module_n}个)，②整问没量到 report包在场${g8_pkg_n}（①）"
    RED=1
elif [ "$g8_pkg_n" -eq 0 ]; then
    echo "格8 红 report 包不在场（①） bilibili引用${g8_refs}处（②）"
    RED=1
elif [ "$g8_refs" -ne 0 ]; then
    echo "格8 红 report 包在场${g8_pkg_n} 但 bilibili 主码引用${g8_refs}处（②） ${g8_hits% }"
    RED=1
else
    echo "格8 绿 report 包在场${g8_pkg_n}(${g8_report_dirs% }) bilibili 主码零引用(${G8_BILI_MAIN% })"
fi

# ============================================================
# 格9：同一个 java 包不得跨模块（撞包）
#
# 两个模块往同一个包根里写件，Java 允许，Maven 也不拦，而它有三处代价：
# 一是拆仓那天这个包得整个跟着走，走不了就得先改包名；二是模块边界在源码上看不见——
# 打开 com.starlwr.bot.core.config 那个目录，看不出里面一半的件属于另一个模块；
# 三是分割包在模块化（JPMS）下直接不合法。
#
# 判据是**闭集**：撞包的现状写在下面这行声明里，多一个少一个都判红。
#   多一个 ⇒ 新长出来的撞包，当场逮住（这正是这一格要防的事）；
#   少一个 ⇒ 有一笔把某个包解开了，那一笔顺手把它从声明里划掉——
#            闭集不许「解开了但没人改声明」，否则这行声明会慢慢变成一张过期的名单。
# 「不许有撞包」的写法今天就是红的，红着的判据没人看，一个月后跟没有一样；
# 闭集在同样的现状上是绿的，而它一样拦得住新增。
#
# 受查面为空（一个核心包也数不出来）判红：那不是「没有撞包」，是没量到。
# ============================================================

# —— 现状声明：这几个包同时落在两个以上模块里。解开一个，从这里划掉一个 ——
#    **眼下是空集**：三个分割包都解开了，一个撞包也不剩。
#    service 已解开：novacore 侧那三件并进了 datasource／protocol 两个既有包。
#    util 已解开：novacore 侧那四个纯函数工具整体改到了新包 core.lang。
#    config 已解开：novacore 侧那八件（五节配置 POJO ＋ ConfigEffect ＋ CoreConfigurationSections）
#    整体改到了新包 core.properties；core.config／core.util／core.service 三个包
#    现在都只剩 starbot-core 一个模块在写。
#    空集上这一格照样拦得住新增：再长出一个撞包，实况就多一个而声明仍是空的，当场判红。
#    它与「射程为空」是两回事——后者由上面的受查包数单独守，那是「没量到」，不是「没撞包」。
KNOWN_SPLIT_PACKAGES=""

G9_PAIRS="$WORK/g9pairs"
# 模块×包 的全对：按 /src/main/java/com/starlwr/bot/core/ 截，模块目录在哪一层都算得对
# 件清单走 tree_files（按工作树现算，理由见其注释）：解撞包那一笔正是「把件挪到新包、还没入册」，
# 只读索引的话它量到的是搬之前的包集——这一格恰好是最不该拿旧现状说话的那一格。
tree_files '*/src/main/java/com/starlwr/bot/core/*' \
    | sed -nE 's|^(.*)/src/main/java/com/starlwr/bot/core/([^/]+)/.*$|\1 \2|p' \
    | sort -u > "$G9_PAIRS"
g9_pkg_total=$(awk '{print $2}' "$G9_PAIRS" | sort -u | grep -cv '^[[:space:]]*$')
g9_actual="$(awk '{print $2}' "$G9_PAIRS" | sort | uniq -d | tr '\n' ' ')"
g9_actual="${g9_actual% }"
g9_declared="$(printf '%s\n' $KNOWN_SPLIT_PACKAGES | sort | tr '\n' ' ')"
g9_declared="${g9_declared% }"
g9_pairs_n=$(count_lines "$G9_PAIRS")
g9_read="撞包实况[${g9_actual}] 声明[${g9_declared}] 受查包${g9_pkg_total}个 模块×包${g9_pairs_n}对"

if [ "$g9_pkg_total" -eq 0 ]; then
    echo "格9 红 射程为空 数不出任何 com.starlwr.bot.core 包(在册模块${module_n}个) $g9_read"
    RED=1
elif [ "$g9_actual" = "$g9_declared" ]; then
    echo "格9 绿 撞包与声明一致 $g9_read"
else
    echo "格9 红 撞包与声明不符（多一个＝新长出来的，少一个＝解开了没划掉声明） $g9_read"
    RED=1
fi

# ============================================================
# 格10：插件 import 了兄弟插件，本模块 pom 里就得申报那个模块；
#       主码直接 import 里层 novacore 包的，pom 里就得申报 novacore
#
# 兄弟插件之间的引用本身不违规（报告插件画的是哔哩哔哩的直播报告，它必须认得那些模型）。
# 违规的是**引用了却不申报**：靠别人的传递依赖编得过，那个中间人一改依赖，这个模块当场编不了；
# 拆仓时更看不出该带谁走。这一格问的就是「pom 上写没写」，不是「能不能引用」。
#
# 归属按**件的落点**现算，不按包名前缀：napcat 扩展自己的包就是 com.starlwr.bot.adapter.onebot.extension.napcat，
# 与 onebot 适配器同一个包根——按前缀分，它 import 自己的兄弟和 import 自己长得一模一样。
# 把 import 的全限定名折成源码路径、去在册件里找它落在哪个模块，才分得开。
# 通配 import（…​.*）折成目录来找；内部类（…Outer.Inner）折不出件时逐级退一段再找。
#
# 射程两块：①兄弟插件 import 须在 pom 申报那个模块（原判据）；②插件主码直接 import
# 里层包（包名按 core/novacore/src/main/java/com/starlwr/bot/core/ 第一级目录现算，不写死）
# 须在 pom 申报 novacore——今天靠 starbot-core 传递带进来，拆仓那天里层单独出包就断。
# ② 的受查面＝根 pom <module> 列的、starbot-core 与 novacore 之外，外加 templates/*/pom.xml
# （模板插件同口径，不豁免；processor 仍不在 reactor 里）。
# 壳侧包（starbot-core 下那些）仍不在本格之内（格7 守核心不反向依赖壳）。
# ============================================================

# 件清单走 tree_files（按工作树现算，理由见其注释）：本格的归属全靠这份清单查落点，
# 只读索引的话，刚挪到新包、还没 add 的件查不着——查不着不判违规，记进「本仓外」。
# 于是搬件的那一笔会把自己搬出去的件记成「不归本仓管」，而那个数正是本格失灵最先现形的地方。
# 变量不再叫 index：它列的是工作树，不是 git 索引。
G10_TREE="$WORK/g10tree"
tree_files '*/src/main/java/*.java' > "$G10_TREE"

# 一个 java 全限定名（或通配包名）落在哪个模块目录里；找不到印空串
owner_module_of() {
    omo_fqn="$1"
    omo_try=""
    case "$omo_fqn" in
        *'.*')
            # 通配 import：折成目录，取该目录下任意一件的模块
            omo_try="/src/main/java/$(printf '%s' "${omo_fqn%.*}" | tr '.' '/')/"
            grep -F "$omo_try" "$G10_TREE" | head -1 | sed -E 's|/src/main/java/.*$||'
            return 0
            ;;
    esac
    # 整名、去掉一段、再去掉一段：够覆盖 Outer.Inner 与 Outer.Inner.Deeper
    for omo_drop in 0 1 2; do
        omo_path="/src/main/java/$(printf '%s' "$omo_fqn" | tr '.' '/').java"
        grep -F "$omo_path" "$G10_TREE" | head -1 | sed -E 's|/src/main/java/.*$||'
        grep -qF "$omo_path" "$G10_TREE" && return 0
        omo_fqn="${omo_fqn%.*}"
        case "$omo_fqn" in *.*) ;; *) break ;; esac
    done
    return 0
}

# 一个模块 pom 里 <dependency> 段申报了哪些 artifactId
# 只取 <dependency> 里的：<parent> 的坐标、<build><plugins> 里的构建插件坐标都不是依赖申报，
# 拿它们凑数的话，一个模块只要恰好用了同名的构建插件就能免检。
# （dependencyManagement 段里的 <dependency> 会一并算进来；本仓模块 pom 均无该段，根 pom 才有。）
# 先去 XML 注释：pom 里写一句「本模块不依赖某某」是该写的话，让它把自己判绿同样是错的。
declared_artifacts_of() {
    strip_xml_comments "$1/pom.xml" 2>/dev/null \
        | awk '/<dependency>/{inside=1} /<\/dependency>/{inside=0} inside' \
        | grep -oE '<artifactId>[^<]+</artifactId>' \
        | awk -F'[<>]' '{print $3}' | sort -u
}

g10_hits=""
g10_n=0
g10_pairs=""
g10_mods=0
g10_imports=0
g10_unknown=0
g10_inner_pkg_n=0
g10_inner_mods=0
g10_inner_declared=0

while IFS= read -r mod; do
    [ -z "$mod" ] && continue
    [ -d "$mod/src/main" ] || continue
    is_core_module "$mod" && continue
    g10_mods=$((g10_mods + 1))

    declared_artifacts_of "$mod" > "$WORK/g10deps"

    while IFS= read -r fqn; do
        [ -z "$fqn" ] && continue
        owner="$(owner_module_of "$fqn")"
        # 归属认不出来的不默认成「自己的」也不默认成「违规」，但要**报出个数**：
        # com.starlwr 这个组名不只本仓在用（上游 StarBot 的产物也是它），落在本仓外的 import 不归本格管；
        # 而一个悄悄增长的「认不出」数，正是本格失灵最先看得见的样子。
        if [ -z "$owner" ]; then
            g10_unknown=$((g10_unknown + 1))
            continue
        fi
        [ "$owner" = "$mod" ] && continue             # 自己引自己
        is_core_module "$owner" && continue           # 核心侧，见上面射程那一段
        g10_imports=$((g10_imports + 1))

        owner_artifact="$(module_artifact "$owner")"
        if [ -z "$owner_artifact" ]; then
            g10_hits="${g10_hits}${mod}->${owner}(取不到 artifactId) "
            g10_n=$((g10_n + 1))
            continue
        fi
        if grep -qxF "$owner_artifact" "$WORK/g10deps"; then
            case " $g10_pairs " in
                *" ${mod}->${owner_artifact} "*) ;;
                *) g10_pairs="${g10_pairs}${mod}->${owner_artifact} " ;;
            esac
        else
            g10_hits="${g10_hits}${mod}/pom.xml(未申报 ${owner_artifact}，为 ${fqn}) "
            g10_n=$((g10_n + 1))
        fi
    done <<< "$(grep -rhoE '^import[[:space:]]+(static[[:space:]]+)?com\.starlwr\.[A-Za-z0-9_.]*(\*)?' \
        --include='*.java' "$mod/src/main" 2>/dev/null \
        | sed -E 's/^import[[:space:]]+(static[[:space:]]+)?//' | sort -u)"
done < "$MODULES"

# 里层包名按工作树现算，不写死。受查模块按根 pom <module> 现算。
G10_INNER="$WORK/g10inner"
tree_files 'core/novacore/src/main/java/com/starlwr/bot/core/*' \
    | sed -nE 's|^core/novacore/src/main/java/com/starlwr/bot/core/([^/]+)/.*$|\1|p' \
    | sort -u > "$G10_INNER"
g10_inner_pkg_n=$(count_lines "$G10_INNER")

G10_PLUGIN_MODS="$WORK/g10plugins"
strip_xml_comments pom.xml \
    | awk '/<modules>/{inside=1} /<\/modules>/{inside=0} inside' \
    | grep -oE '<module>[^<]+</module>' \
    | awk -F'[<>]' '{print $3}' > "$WORK/g10rootmod"
: > "$G10_PLUGIN_MODS"
while IFS= read -r g10pm; do
    [ -z "$g10pm" ] && continue
    is_core_module "$g10pm" && continue
    printf '%s\n' "$g10pm" >> "$G10_PLUGIN_MODS"
done < "$WORK/g10rootmod"
for g10tpom in templates/*/pom.xml; do
    [ -f "$g10tpom" ] || continue
    printf '%s\n' "$(dirname "$g10tpom")" >> "$G10_PLUGIN_MODS"
done

if [ "$g10_inner_pkg_n" -gt 0 ]; then
    g10_inner_re="$(tr '\n' '|' < "$G10_INNER")"
    g10_inner_re="${g10_inner_re%|}"
    while IFS= read -r mod; do
        [ -z "$mod" ] && continue
        [ -d "$mod/src/main/java" ] || continue
        g10_ikey="$(printf '%s' "$mod" | tr '/' '_')"
        grep -rlE "^import[[:space:]]+(static[[:space:]]+)?com\\.starlwr\\.bot\\.core\\.(${g10_inner_re})\\." \
            --include='*.java' "$mod/src/main/java" > "$WORK/g10if_${g10_ikey}" 2>/dev/null || true
        g10_inner_files=$(count_lines "$WORK/g10if_${g10_ikey}")
        [ "$g10_inner_files" -eq 0 ] && continue
        g10_inner_mods=$((g10_inner_mods + 1))
        declared_artifacts_of "$mod" > "$WORK/g10deps_inner"
        if grep -qxF "novacore" "$WORK/g10deps_inner"; then
            g10_inner_declared=$((g10_inner_declared + 1))
        else
            g10_hits="${g10_hits}${mod}/pom.xml(未申报 novacore，里层直引 ${g10_inner_files}件) "
            g10_n=$((g10_n + 1))
        fi
    done < "$G10_PLUGIN_MODS"
fi

g10_read="受查插件模块${g10_mods}个 兄弟 import ${g10_imports}处(去重后的全限定名，非行数) 本仓外${g10_unknown}处 已申报[${g10_pairs% }] 里层直引 ${g10_inner_mods} 模块／已申报 ${g10_inner_declared}"
if [ "$g10_mods" -eq 0 ]; then
    echo "格10 红 射程为空 一个插件模块也枚举不到(在册模块${module_n}个)"
    RED=1
elif [ "$g10_inner_pkg_n" -eq 0 ]; then
    echo "格10 红 射程为空 里层包名枚举不到(core/novacore/src/main/java/com/starlwr/bot/core 第一级目录 0 个) $g10_read"
    RED=1
elif [ "$g10_n" -eq 0 ]; then
    echo "格10 绿 命中0 兄弟插件引用都在 pom 里申报过 $g10_read"
else
    echo "格10 红 命中${g10_n} ${g10_hits% } $g10_read"
    RED=1
fi

# ============================================================
# 格11：核心界面目录在、且里面有件
#
# 与格1 的分工写在格1 那一段里：格1 问「目录在不在、平台名清单空不空」，
# 本格问「目录里还有没有件」。分开两格是因为它们红的成因不同、修法也不同——
# 目录不在是**搬走了**（格1 里的路径要跟着改），目录在而空是**掏空了**
# （界面件搬去了别处，而格1 的扫描射程还落在这个空壳上，它照样报绿）。
# 数的是这个目录下的**全部件**，与格1 扫的是同一片：格1 用 find 递归扫，本格就用 find 递归数，
# 换成只数在册件的话，两格量的population 不同，本格的绿就担保不了格1 的射程。
# ============================================================
g11_files=0
[ -d "$CORE_UI" ] && g11_files=$(find "$CORE_UI" -type f 2>/dev/null | wc -l | tr -d ' ')

if [ ! -d "$CORE_UI" ]; then
    echo "格11 红 核心界面目录不在 ${CORE_UI}"
    RED=1
elif [ "$g11_files" -eq 0 ]; then
    echo "格11 红 核心界面目录在而无件 ${CORE_UI}"
    RED=1
else
    echo "格11 绿 核心界面目录在且有件${g11_files} ${CORE_UI}"
fi

# ============================================================
# 格12：写死了模块目录名的在册件数只减不增
#
# 目录重排真正的工作量在这里：这些件里写着 core/starbot-core/ 这样的**路径**，
# 目录一改它们全部失灵——而其中大半（脚本、判据、配置）失灵的方式是安静的。
# 一次改不完，那就立个账：现值封在下面这个上限里，新写一处就红。
# 只减不增——改一件、把上限调低一，账才会往下走；上限只许由「改完一件」的那一笔调。
#
# 排除 *.md：文档里写模块路径是在教人怎么跑命令，跟着改是文档的事，不是这一格要拦的东西。
# 数的是**件数**不是处数：一件里写十处，改的时候是一件事。
# ============================================================

# 现值上限（2026-09-08 实测 77；正则带 core/／plugins/ 前缀后现算，只认目录名）。改掉一件就把它调低一，绝不许调高。
HARDCODED_MODULE_PATH_CAP=77

G12_RE='core/starbot-core/|core/novacore/|plugins/starbot-bilibili/|plugins/starbot-novabot-console/|plugins/starbot-onebot-adapter|plugins/starbot-report/'
G12_LIST="$WORK/g12"
: > "$G12_LIST"
# 件清单走 tree_files 再自己 grep，不走 git grep：git grep 只搜在册件，
# 于是这一格看不见「新写的一件」——而新写一件正是它要拦的那件事（上限只减不增）。
tree_files -- ':!*.md' > "$WORK/g12files"
if [ -s "$WORK/g12files" ]; then
    tr '\n' '\0' < "$WORK/g12files" \
        | xargs -0 grep -l -E "$G12_RE" 2>/dev/null | sort -u > "$G12_LIST"
fi
g12_n=$(count_lines "$G12_LIST")

if [ "$g12_n" -eq 0 ]; then
    # 一件都数不出来，多半是模块名整套换过了（或不在 git 树里跑），不是「改完了」
    echo "格12 红 射程为空 数不出任何写死模块目录名的在册件(在册模块${module_n}个) 上限${HARDCODED_MODULE_PATH_CAP}"
    RED=1
elif [ "$g12_n" -le "$HARDCODED_MODULE_PATH_CAP" ]; then
    echo "格12 绿 写死模块目录名的在册件${g12_n} ≤ 上限${HARDCODED_MODULE_PATH_CAP}"
else
    echo "格12 红 写死模块目录名的在册件${g12_n} > 上限${HARDCODED_MODULE_PATH_CAP}（新写了 $((g12_n - HARDCODED_MODULE_PATH_CAP)) 件）"
    RED=1
fi

# ============================================================
# 格13：模板引用闭集——templates 里写的仓内类名须在仓内有源文件
#
# 模板不进 reactor，写错一个类名编译与测试都看不见。使用者照抄起插件，
# 切面静默不挂上（pointcut 对不上任何方法）。本格问的就是「模板引用的
# com.starlwr 类，仓内是不是真有这一件」。
#
# 取两类名字：
#   ① import com.starlwr.…（非通配；static 末段若不是类型则退一层再找）
#   ② @Pointcut / execution( 串里的全限定类名
# 每个名字折成 */src/main/java/<点换斜杠>.java，在工作树件清单里找；
# 找不到即红并印类名。
#
# 件清单走 tree_files（工作树现算，理由见其注释）——刚改名还没 add 的新类
# 要认得出，刚删还留在索引里的旧类不能继续当「在」。
# 射程为空：templates 下没有 *.java，或抽不出任何类名（正则哑了）⇒ 红。
# ============================================================

G13_JAVA="$WORK/g13java"
tree_files 'templates/' | grep '\.java$' > "$G13_JAVA" || true
g13_java_n=$(count_lines "$G13_JAVA")

G13_TREE="$WORK/g13tree"
tree_files '*/src/main/java/*.java' > "$G13_TREE"

G13_NAMES="$WORK/g13names"
: > "$G13_NAMES"

# 包段小写开头、类名大写开头：挡住 execution(* Fqcn.method(..)) 把方法名吞进类名
g13_class_re='com\.starlwr(\.[a-z][A-Za-z0-9_]*)+\.[A-Z][A-Za-z0-9_]*'

if [ -s "$G13_JAVA" ]; then
    while IFS= read -r g13f; do
        [ -z "$g13f" ] && continue
        [ -f "$g13f" ] || continue
        grep -E '^import[[:space:]]+(static[[:space:]]+)?com\.starlwr\.' "$g13f" 2>/dev/null \
            | grep -oE "$g13_class_re" >> "$G13_NAMES" || true
        grep -E '@Pointcut|execution\(' "$g13f" 2>/dev/null \
            | grep -oE "$g13_class_re" >> "$G13_NAMES" || true
    done < "$G13_JAVA"
fi
sort -u "$G13_NAMES" -o "$G13_NAMES"
g13_name_n=$(count_lines "$G13_NAMES")

g13_hits=""
g13_n=0
while IFS= read -r g13fqn; do
    [ -z "$g13fqn" ] && continue
    g13path="$(printf '%s' "$g13fqn" | tr '.' '/').java"
    if grep -qF "/src/main/java/${g13path}" "$G13_TREE"; then
        continue
    fi
    g13parent="${g13fqn%.*}"
    g13ppath="$(printf '%s' "$g13parent" | tr '.' '/').java"
    if grep -qF "/src/main/java/${g13ppath}" "$G13_TREE"; then
        continue
    fi
    g13_hits="${g13_hits}${g13fqn} "
    g13_n=$((g13_n + 1))
done < "$G13_NAMES"

g13_read="模板 java ${g13_java_n}件 引用类名${g13_name_n}个"

if [ "$g13_java_n" -eq 0 ]; then
    echo "格13 红 射程为空 templates 下没有 java 件 $g13_read"
    RED=1
elif [ "$g13_name_n" -eq 0 ]; then
    echo "格13 红 射程为空 抽不出任何 com.starlwr 类名 $g13_read"
    RED=1
elif [ "$g13_n" -eq 0 ]; then
    echo "格13 绿 命中0 模板引用的仓内类名都在 $g13_read"
else
    echo "格13 红 命中${g13_n} ${g13_hits% } $g13_read"
    RED=1
fi

exit "$RED"
