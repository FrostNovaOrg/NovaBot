#!/usr/bin/env bash
# 产物界面资源尺：打出来的包里，界面资源目录有没有源码里不存在的条目
#
# 用法：tools/artifact-ui-resource-check.sh [产物目录]   （默认 dist/build）
# 退码：0＝产物干净；1＝产物里有源码里没有的条目，或量不动（工具缺失、jar 不在）
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
# 对每个 jar 的界面资源目录：**jar 内条目 ⊆ 源码目录条目**。
# 只判「多出」不判「缺少」：缺少的那种表现是页面 404，一眼看得见；多出的那种表现是
# 请求成功、内容却来自源码里根本不存在的一份，**看起来完全正常**——那才是要一把尺来量的。
#
# 空集也要报数：一把「子集」尺在被量目录为空时永远是绿的。因此每一格都把两侧条目数印出来，
# 并且核心那一格若一个界面资源都没有，直接判红——不写这一条，「界面丢光了」会读成「干净」。
set -uo pipefail

OUT="${1:-dist/build}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1
[ -d "$OUT" ] || OUT="$REPO_ROOT/$OUT"

# 插件页面脚本在插件 jar 里的资源目录，与 ConsolePages.SCRIPT_ROOT 同一个串
PLUGIN_UI_DIR="config-ui-pages"
# 核心界面资源在 Spring Boot 重打包后的位置
CORE_UI_IN_JAR="BOOT-INF/classes/config-ui"
CORE_UI_SRC="starbot-core/src/main/resources/config-ui"

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

# 一格：某个 jar 的某个资源目录 ⊆ 某个源码目录
#   $1 jar 路径   $2 jar 内目录前缀（不带末尾斜杠）   $3 源码目录   $4 这一格叫什么
check_one() {
    local jar="$1" prefix="$2" src="$3" label="$4"
    local entries rel extra=0 count=0 src_count=0

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

    if [ -d "$src" ]; then
        src_count="$(find "$src" -type f | wc -l | tr -d ' ')"
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
        if [ ! -f "$src/$rel" ]; then
            echo "  $label 多出条目: $prefix/$rel  （源码 $src/ 下没有这个文件）"
            extra=$((extra + 1))
            EXTRA_TOTAL=$((EXTRA_TOTAL + 1))
        fi
    done <<< "$entries"

    if [ "$extra" -gt 0 ]; then
        echo "  $label 红 jar内${count}项 源码${src_count}项 多出${extra}项 $(basename "$jar")"
        RED=1
    elif [ "$count" -eq 0 ] && [ "$label" = "核心界面" ]; then
        echo "  $label 红 jar内0项 —— 核心界面资源一个都没打进包，这不是干净是丢了 $(basename "$jar")"
        RED=1
    else
        echo "  $label 绿 jar内${count}项 源码${src_count}项 多出0项 $(basename "$jar")"
    fi
}

echo "产物界面资源尺：$OUT"

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

check_one "$OUT/StarBotCore.jar" "$CORE_UI_IN_JAR" "$CORE_UI_SRC" "核心界面"

# 插件那一侧按目录里实际有哪些 jar 来量，不写死任何一个插件的名字：
# 写死一个，这把尺守的就只是那一个插件，下一个插件带着脏条目进包照样是绿的。
# 模块名由 jar 文件名去掉版本号得出，源码目录即该模块的 config-ui-pages/。
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
        check_one "$jar" "$PLUGIN_UI_DIR" "$module/src/main/resources/$PLUGIN_UI_DIR" "插件页面[$module]"
    done
fi

if [ "$RED" -ne 0 ]; then
    echo
    if [ "$EXTRA_TOTAL" -gt 0 ]; then
        echo "产物里出现了 ${EXTRA_TOTAL} 个源码树里没有的界面资源条目。" >&2
        echo "多半是上一次构建留在 target/ 里的旧文件——源文件删掉或改名后 Maven 不会删它，" >&2
        echo "它照旧进包，而界面资源出口「核心里有就用核心的」会让这种旧文件静默压过插件那一份。" >&2
        echo "先 mvn clean 再重建；若清理后仍在，那它就不是残留，去查是谁把它拷进产物的。" >&2
    else
        echo "这把尺没能把产物量完（上面每一格红都写明了卡在哪里），因此不给绿。" >&2
    fi
    exit 1
fi

exit 0
