#!/usr/bin/env bash
# 产物界面资源尺：打出来的包里，界面资源目录有没有来路不明的条目
#
# 用法：tools/artifact-ui-resource-check.sh [产物目录]   （默认 dist/build）
# 退码：0＝产物干净；1＝产物里有清单外条目，或量不动（工具缺失、jar 不在、登记清单取不出）
#
# ── 为什么这把尺与构建链那道 clean 是两把 ────────────────────────────────
# 两者答的不是同一个问题：
#   build.sh 恒带 clean —— 答的是「**构建有没有从空目录开始**」；
#   本尺      —— 答的是「**打出来的这个包里有没有脏东西**」。
#
# 第一个问题答「是」，第二个问题并不因此自动答「是」：产物是从多处拷进 dist/build 的
# （核心 jar、各插件 jar、依赖、模板目录），清的是编译输出，管不着拷进来的那些；
# 何况一条链上任何一处将来改回增量、或有人手动补一个文件进包，clean 那一侧一个字都不会变。
# 只装一道，就是把另一个问题悄悄结掉——而那个问题下次出事时，表现仍然是「构建成功」。
#
# ── 判据 ────────────────────────────────────────────────────────────────
# 核心格：**jar 内条目 ⊆ 源码目录条目**——核心 config-ui 没有登记机制，目录就是真源。
# 插件格：**jar 内条目 ⊆ 该模块的登记清单**（tools/console-page-registry.sh 取的
#         provider 申报＋控制器直取并集）。插件界面件进产物走的是登记不是目录：
#         目录里多放一件不改变出口，按目录判会把那件认成「源码有」——洞正在这里。
# 只判「多出」不判「缺少」：缺少的那种表现是页面 404，一眼看得见；多出的那种表现是
# 请求成功、内容却来自根本没登记过的一份，**看起来完全正常**——那才是要一把尺来量的。
#
# 空集也要报数：一把「子集」尺在被量清单为空时永远是绿的。因此每一格都把两侧条目数印出来，
# 并且：核心那一格若一个界面资源都没有，直接判红（「界面丢光了」不能读成「干净」）；
# 插件那一格若 jar 里有页条目而登记清单为空，直接判红，且不再逐条论多出——
# 对着空清单论多出，论出来的每一条都是空的；先查清单哑没哑，再谈谁多出。
set -uo pipefail

OUT="${1:-dist/build}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1
[ -d "$OUT" ] || OUT="$REPO_ROOT/$OUT"

# 插件页面脚本在插件 jar 里的资源目录，与 ConsolePages.SCRIPT_ROOT 同一个串
PLUGIN_UI_DIR="config-ui-pages"
# 核心界面资源在 Spring Boot 重打包后的位置
CORE_UI_IN_JAR="BOOT-INF/classes/config-ui"
CORE_UI_SRC="core/nova-core/src/main/resources/config-ui"

RED=0
EXTRA_TOTAL=0

# jar 内条目：unzip 与 JDK 的 jar 谁能读出来就用谁。
#
# 🔴 判的是「跑得通」不是「在不在 PATH 上」。macOS 自带一个 /usr/bin/jar 的桩：
#    command -v 找得到它，跑起来却是一句「Unable to locate a Java Runtime」——
#    2026-09-03 在本机实测到的原文。按「在不在」来选，就会选中一个跑不动的，
#    还不去试下一个。所以这里逐个真跑，第一个既退 0 又有输出的才算数。
# 两个都读不出来时判红而不是跳过：一把量不动却报绿的尺，比没有这把尺更糟。
list_jar_entries() {
    local jar="$1" out
    for reader in "unzip -Z1" "jar tf"; do
        if out="$($reader "$jar" 2> /dev/null)" && [ -n "$out" ]; then
            printf '%s\n' "$out"
            return 0
        fi
    done
    return 2
}

# 由 jar 去掉版本号得到的模块名，到源码目录：在 plugins/ 与 core/ 下按目录名现找。
# 不写死任何一个插件的名字。找不到就红并印出 jar 名——拼到一个不存在的目录上，
# 源码侧是空集，子集尺会静默绿。
find_module_dir() {
    local name="$1"
    local d
    for d in plugins/"$name" core/"$name"; do
        if [ -d "$d" ]; then
            printf '%s\n' "$d"
            return 0
        fi
    done
    return 1
}

# 一格：某个 jar 的某个资源目录 ⊆ 某一份清单
#   $1 jar 路径   $2 jar 内目录前缀（不带末尾斜杠）   $3 清单件（每行一个件名）
#   $4 这一格叫什么   $5 清单的叫法（源码／登记）——读数与多出报文里用它
check_one() {
    local jar="$1" prefix="$2" list="$3" label="$4" word="$5"
    local entries rel extra=0 count=0 list_count=0

    if [ ! -f "$jar" ]; then
        echo "  $label 红 产物里没有这个 jar: $jar"
        RED=1
        return
    fi

    if ! entries="$(list_jar_entries "$jar")"; then
        echo "  $label 红 读不出 jar 条目（unzip 与 jar 都没能列出它）: $jar"
        RED=1
        return
    fi

    if [ -s "$list" ]; then
        list_count="$(wc -l < "$list" | tr -d ' ')"
    fi

    while IFS= read -r entry; do
        case "$entry" in
            "$prefix"/*) ;;
            *) continue ;;
        esac
        case "$entry" in
            */) continue ;;          # 目录条目本身不算文件
        esac
        rel="${entry#"$prefix"/}"
        [ -n "$rel" ] || continue
        count=$((count + 1))
        if [ "$list_count" -gt 0 ] && ! grep -qxF "$rel" "$list"; then
            echo "  $label 多出条目: $prefix/$rel  （不在${word}清单）"
            extra=$((extra + 1))
            EXTRA_TOTAL=$((EXTRA_TOTAL + 1))
        fi
    done <<< "$entries"

    if [ "$count" -gt 0 ] && [ "$list_count" -eq 0 ]; then
        echo "  $label 红 ${word}清单为空 —— jar 里有${count}项，清单里一个名字都没有 $(basename "$jar")"
        RED=1
    elif [ "$extra" -gt 0 ]; then
        echo "  $label 红 jar内${count}项 ${word}${list_count}项 多出${extra}项 $(basename "$jar")"
        RED=1
    elif [ "$count" -eq 0 ] && [ "$label" = "核心界面" ]; then
        echo "  $label 红 jar内0项 —— 核心界面资源一个都没打进包，这不是干净是丢了 $(basename "$jar")"
        RED=1
    else
        echo "  $label 绿 jar内${count}项 ${word}${list_count}项 多出0项 $(basename "$jar")"
    fi
}

echo "产物界面资源尺：$OUT"

# 登记清单（插件格的真源）：一处取来全场共用。取不出来直接红——
# 清单哑了还往下跑，带页条目的插件格会在空清单上报「清单为空」，没页的却照绿。
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
REG="$WORK/pages-reg.tsv"
if ! bash "$REPO_ROOT/tools/console-page-registry.sh" > "$REG" 2>/dev/null; then
    echo "  登记清单 红 取不出来（tools/console-page-registry.sh 退非 0）——插件格的真源哑了，不给绿"
    exit 1
fi

# 出厂默认模板的覆盖件：按需生成，与 datasource.json 同目录。
# 包里带一份等于替使用者做了一半的事——而它一旦存在就归使用者所有，
# 程序不会再去动它。「还没改过默认模板」与「改成了空覆盖」在一个 {} 上长得一样，
# 只有前者是真的。手放一份进产物必须红，不能靠「构建的人记得别拷」。
if [ -e "$OUT/template-defaults.json" ]; then
    echo "  出厂覆盖件 红 产物里有 template-defaults.json —— 这份是第一次改默认模板时自己写的，不该随包"
    RED=1
else
    echo "  出厂覆盖件 绿 产物里没有 template-defaults.json"
fi

# 核心格照旧按源码目录判：核心 config-ui 没有登记机制，目录即真源，
# 清单就是那份目录的文件名列表（相对核心资源目录）。
core_list="$WORK/core-ui.list"
: > "$core_list"
if [ -d "$CORE_UI_SRC" ]; then
    find "$CORE_UI_SRC" -type f | sed "s|^$CORE_UI_SRC/||" | sort > "$core_list"
fi
check_one "$OUT/NovaBot.jar" "$CORE_UI_IN_JAR" "$core_list" "核心界面" 源码

# 插件那一侧按目录里实际有哪些 jar 来量，不写死任何一个插件的名字：
# 写死一个，这把尺守的就只是那一个插件，下一个插件带着脏条目进包照样是绿的。
# 模块名由 jar 文件名去掉版本号得出；该模块的登记清单按模块目录从登记件里现取。
shopt -s nullglob
plugin_jars=("$OUT"/plugins/*.jar)
shopt -u nullglob
if [ "${#plugin_jars[@]}" -eq 0 ]; then
    echo "  插件页面 红 $OUT/plugins/ 里一个 jar 都没有"
    RED=1
else
    for jar in "${plugin_jars[@]}"; do
        base="$(basename "$jar" .jar)"
        module="$(echo "$base" | sed -E 's/-[0-9][^-]*(-SNAPSHOT)?$//')"
        src_mod=""
        if ! src_mod="$(find_module_dir "$module")"; then
            echo "  插件页面[$module] 红 源码树找不到模块目录 $(basename "$jar")"
            RED=1
            continue
        fi
        mod_list="$WORK/pages-$module.list"
        awk -F'\t' -v m="$src_mod" '$1==m{print $2}' "$REG" > "$mod_list"
        check_one "$jar" "$PLUGIN_UI_DIR" "$mod_list" "插件页面[$module]" 登记
    done
fi

if [ "$RED" -ne 0 ]; then
    echo
    if [ "$EXTRA_TOTAL" -gt 0 ]; then
        echo "产物里出现了 ${EXTRA_TOTAL} 个清单里没有的界面资源条目（核心＝源码目录清单，插件＝登记清单）。" >&2
        echo "多半是上一次构建留在 target/ 里的旧文件——源文件删掉或改名后 Maven 不会删它，" >&2
        echo "它照旧进包，而界面资源出口「核心里有就用核心的」会让这种旧文件静默压过插件那一份。" >&2
        echo "先 mvn clean 再重建；若清理后仍在，那它就不是残留，去查是谁把它拷进产物的。" >&2
    else
        echo "这把尺没能把产物量完（上面每一格红都写明了卡在哪里），因此不给绿。" >&2
    fi
    exit 1
fi

exit 0
