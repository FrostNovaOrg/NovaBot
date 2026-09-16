#!/usr/bin/env bash
# 产物整包条目尺：打出来的包里，jar 条目有没有源码里已经不存在的东西
#
# 用法：bash tools/artifact-entry-check.sh [产物目录]   （默认 dist/build）
# 退码：0＝产物干净，且对照／自证量得动
#       1＝产物本身红：有源码对不上的条目，或某一格没能量完
#         （核心 jar 不在、读不出核心 jar、读不出 jar、模块找不到、
#          产物里没有 lib/、plugins/ 里一个 jar 都没有）
#         对照与自证的红句此时不作数，诊断仍印全。
#       2＝主扫描是绿的，但对照或自证证明不了这把尺能红
#         （阳性锚取不到，子进程没退 1 或没点名幽灵，
#          白名单臂／资源样例清单空或放行了不该放行的，也走 2）
#
# ── 与 tools/artifact-ui-resource-check.sh 的分工 ────────────────────────
# 那把只量三样：核心 jar 的 BOOT-INF/classes/config-ui（界面核心格）、
# 各插件 jar 的 config-ui-pages（界面插件格）、出厂覆盖件 template-defaults.json，
# 而且只判「多出」不判「缺少」。本尺量其余条目：源码已删、包里还躺着的 class、
# 非界面资源、插件字体。BOOT-INF/classes/config-ui/** 与 config-ui-pages/** 归那一把，
# 本尺跳过不重判。另量 $OUT/lib/*.jar 里带 META-INF/maven/org.frostnova.nova/ 的
# 自家产物（第三方一律跳过）；找不到对应模块红并点名，不许静默跳过。
#
# ── 量法 ────────────────────────────────────────────────────────────────
# 核心 jar：条目 ⊆ 源码 ＋ 白名单。
#   (a) BOOT-INF/classes/org/**.class → 去掉 $内部类 后缀，映射
#       核心模块 src/main/java/**/*.java（模块目录现找，不写死名字）
#   (b) BOOT-INF/classes/** 里非 class 的 → 该模块 src/main/resources/**
#       减去 pom 排除的 application-dev.yml（不得进包）
#   (c) 白名单（少一条第一趟就一片红，人会把名单放宽到尺失去判别力）：
#       org/springframework/boot/loader/、BOOT-INF/lib/、BOOT-INF/classpath.idx、
#       BOOT-INF/layers.idx、META-INF/maven/、根 META-INF/ 的 MANIFEST.MF／
#       build-info.properties／spring-configuration-metadata.json／spring.factories／services/
# 插件 jar 同法：plugin.json、dependency.json、META-INF/spring/*.imports、
# META-INF/maven/、以及与核心相同的根 META-INF/ 白名单；config-ui-pages/ 归界面尺；
# 其余 class → plugins/<模块>/src/main/java（不走白名单）；其余资源（含 fonts/**）→
# plugins/<模块>/src/main/resources，fonts 必须能找到同名件。
# 自家 lib jar：.class 一律对源码，不走白名单；其余资源 →
# src/main/resources；META-INF 白名单与插件根 META-INF 相同（任一臂都不放行 .class）。
#
# 🔴 分母不许用 target/classes——源文件删掉或改名之后，上一次构建留下的那一份
#    照旧躺在那里、照旧进 jar，而包上看不出来（build.sh 顶部「陈旧产物」段）。
#
# 白名单自证：环境变量 ARTIFACT_ENTRY_DROP_PREFIX 设成 (c) 里任一前缀，
# 该桶整片当多出；不设则内置对照会临时拿掉 org/springframework/boot/loader/ 验一次。
# 插件与 lib 两把白名单逐臂探：每臂造一个落在该臂路径下的 .class 探针，调真函数，
# 断它不被放行。臂清单与函数共用，不许另抄。
# 另有一份「必不放行」非 class 资源样例。这份是规格，不从臂清单现算。
# 覆盖：任一臂放到「同目录、同后缀、任意名」，或放到「上一级目录直到根、同后缀」，
# 都至少放行一只样例。插件、lib、核心各逐只探。
# 阴性对照：起一个子进程，对同一个产物目录端到端跑本尺；只在子进程里把幽灵
# 条目种进真条目表（环境变量 ARTIFACT_ENTRY_CHILD，防递归）。断子进程退 1，
# 且每条幽灵都被点名（点名须是「多出条目: <幽灵>」这一形）。核心一种 class；
# 插件与 lib 各一种 class 和一种资源。子进程用正在跑本尺的解释器（$BASH）。
# ARTIFACT_ENTRY_CHILD 在场时往 stderr 说一句本趟不作数，对照整段跳过。
set -uo pipefail

SCRIPT_PATH="${BASH_SOURCE[0]}"
case "$SCRIPT_PATH" in
    /*) ;;
    *) SCRIPT_PATH="$(pwd)/$SCRIPT_PATH" ;;
esac

OUT="${1:-dist/build}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1
[ -d "$OUT" ] || OUT="$REPO_ROOT/$OUT"

DROP_PREFIX="${ARTIFACT_ENTRY_DROP_PREFIX:-}"

RED=0
EXTRA_TOTAL=0
CELL_COUNT=0
CELL_EXTRA=0
PLUGIN_WL_PROVED=0
LIB_WL_PROVED=0
CORE_WL_PROVED=0
CONTROL_RED=0
CONTROL_MSGS=""

# 对照／自证量不动：记下、印出来、接着量。末尾再定退码（产物红→1，否则 2）。
note_control_red() {
    local msg="$1"
    CONTROL_RED=1
    CONTROL_MSGS="${CONTROL_MSGS}${msg}"$'\n'
}

flush_control_notes() {
    local suffix="$1"
    local line=""
    [ -n "$CONTROL_MSGS" ] || return 0
    while IFS= read -r line || [ -n "$line" ]; do
        [ -n "$line" ] || continue
        if [ -n "$suffix" ]; then
            echo "${line}（${suffix}）" >&2
        else
            echo "$line" >&2
        fi
    done <<< "$CONTROL_MSGS"
}

# 不经管道找行。第三参非空＝整行相等（阳性锚）；空＝行内含 needle。
# 点名须喂「多出条目: <幽灵>」，幽灵名之后须紧跟两个空格加「（」，不许接别的字。
haystack_has() {
    local haystack="$1" needle="$2" exact="${3:-}"
    local line="" sep="  （"
    while IFS= read -r line || [ -n "$line" ]; do
        if [ -n "$exact" ]; then
            if [ "$line" = "$needle" ]; then
                return 0
            fi
        else
            case "$line" in
                *"$needle$sep"*) return 0 ;;
            esac
        fi
    done <<< "$haystack"
    return 1
}

# jar 内条目：unzip 与 JDK 的 jar 谁能读出来就用谁。
#
# 🔴 判的是「跑得通」不是「在不在 PATH 上」。macOS 自带一个 /usr/bin/jar 的桩：
#    command -v 找得到它，跑起来却是一句「Unable to locate a Java Runtime」——
#    2026-09-03 在本机实测到的原文。按「在不在」来选，就会选中一个跑不动的，
#    还不去试下一个。所以这里逐个真跑，第一个既退 0 又有输出的才算数。
# 两个都读不出来时判红而不是跳过：一把量不动却报绿的尺，比没有这把尺更糟。
list_jar_entries() {
    local jar="$1" out=""
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
    local d=""
    for d in plugins/"$name" core/"$name"; do
        if [ -d "$d" ]; then
            printf '%s\n' "$d"
            return 0
        fi
    done
    return 1
}

# 打出 NovaBot.jar 的那只核心模块：core/ 下带出厂 application.yml 的目录。
# 不写死目录名——目录一改，写死的路径安静失灵。
# 命中不止一只时红并点名，不许静默取字典序第一只。
find_core_module() {
    local d="" hits="" n=0
    for d in core/*; do
        if [ -f "$d/src/main/resources/application.yml" ]; then
            hits="${hits} ${d}"
            n=$((n + 1))
        fi
    done
    hits="${hits# }"
    if [ "$n" -eq 0 ]; then
        return 1
    fi
    if [ "$n" -gt 1 ]; then
        echo "核心条目 红 core/ 下带 application.yml 的模块不止一只：${hits}" >&2
        return 2
    fi
    printf '%s\n' "$hits"
    return 0
}

# 模块 pom 自己的 artifactId（跳过 parent／dependencies／build／profiles）。
project_artifact_id() {
    awk '
        /<parent>/ { skip=1 }
        /<\/parent>/ { skip=0; next }
        /<dependencies>/ { skip=1 }
        /<\/dependencies>/ { skip=0; next }
        /<dependencyManagement>/ { skip=1 }
        /<\/dependencyManagement>/ { skip=0; next }
        /<build>/ { skip=1 }
        /<\/build>/ { skip=0; next }
        /<profiles>/ { skip=1 }
        /<\/profiles>/ { skip=0; next }
        !skip && /<artifactId>/ {
            sub(/.*<artifactId>/, "")
            sub(/<\/artifactId>.*/, "")
            gsub(/^[ \t]+|[ \t]+$/, "")
            print
            exit
        }
    ' "$1"
}

# 按 pom 的 <artifactId> 现找 core/*、plugins/*。命中 0 只或不止一只都失败。
find_module_by_artifact_id() {
    local aid="$1"
    local d="" got="" hits="" n=0
    for d in core/* plugins/*; do
        [ -f "$d/pom.xml" ] || continue
        got="$(project_artifact_id "$d/pom.xml")"
        if [ "$got" = "$aid" ]; then
            hits="${hits} ${d}"
            n=$((n + 1))
        fi
    done
    hits="${hits# }"
    if [ "$n" -eq 0 ]; then
        return 1
    fi
    if [ "$n" -gt 1 ]; then
        echo "lib自家产物 红 artifactId=${aid} 命中不止一只模块：${hits}" >&2
        return 2
    fi
    printf '%s\n' "$hits"
    return 0
}

# 从条目表取 META-INF/maven/org.frostnova.nova/<artifactId>/…；空＝第三方。
own_artifact_id_from_entries() {
    local entries="$1"
    local ids="" line="" n=0 aid=""
    ids="$(printf '%s\n' "$entries" | sed -n 's|^META-INF/maven/org.frostnova.nova/\([^/][^/]*\)/.*|\1|p' | sort -u)"
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        n=$((n + 1))
        aid="$line"
    done <<EOF
$ids
EOF
    if [ "$n" -eq 0 ]; then
        return 1
    fi
    if [ "$n" -gt 1 ]; then
        echo "lib自家产物 红 一只 jar 里出现多个 org.frostnova.nova artifactId：${ids}" >&2
        return 2
    fi
    printf '%s\n' "$aid"
    return 0
}

core_whitelisted() {
    local e="$1" drop="${2:-}"
    case "$e" in
        org/springframework/boot/loader/*)
            [ "$drop" = "org/springframework/boot/loader/" ] && return 1
            return 0 ;;
        BOOT-INF/lib/*)
            [ "$drop" = "BOOT-INF/lib/" ] && return 1
            return 0 ;;
        BOOT-INF/classpath.idx)
            [ "$drop" = "BOOT-INF/classpath.idx" ] && return 1
            return 0 ;;
        BOOT-INF/layers.idx)
            [ "$drop" = "BOOT-INF/layers.idx" ] && return 1
            return 0 ;;
        META-INF/maven/*)
            [ "$drop" = "META-INF/maven/" ] && return 1
            return 0 ;;
        META-INF/MANIFEST.MF)
            [ "$drop" = "META-INF/MANIFEST.MF" ] && return 1
            return 0 ;;
        META-INF/build-info.properties)
            [ "$drop" = "META-INF/build-info.properties" ] && return 1
            return 0 ;;
        META-INF/spring-configuration-metadata.json)
            [ "$drop" = "META-INF/spring-configuration-metadata.json" ] && return 1
            return 0 ;;
        META-INF/spring.factories)
            [ "$drop" = "META-INF/spring.factories" ] && return 1
            return 0 ;;
        META-INF/services/*)
            [ "$drop" = "META-INF/services/" ] && return 1
            return 0 ;;
    esac
    return 1
}

# 插件／lib 白名单臂。函数与逐臂自证共用这一份，不许另手抄。
PLUGIN_WL_ARMS=(
    "plugin.json"
    "dependency.json"
    "META-INF/spring/*.imports"
    "META-INF/maven/*"
    "META-INF/MANIFEST.MF"
    "META-INF/build-info.properties"
    "META-INF/spring-configuration-metadata.json"
    "META-INF/spring.factories"
    "META-INF/services/*"
)
LIB_WL_ARMS=(
    "META-INF/maven/*"
    "META-INF/MANIFEST.MF"
    "META-INF/build-info.properties"
    "META-INF/spring-configuration-metadata.json"
    "META-INF/spring.factories"
    "META-INF/services/*"
)

# 子进程种进真条目表的幽灵。只在 ARTIFACT_ENTRY_CHILD 下认。
GHOST_CORE_CLASS="org/frostnova/nova/core/config/ArtifactEntryCheckSentinel.class"
GHOST_PLUGIN_CLASS="org/frostnova/nova/artifact/GhostNeverExistedPlugin.class"
GHOST_PLUGIN_RES="ghost-never-existed-plugin.txt"
GHOST_LIB_CLASS="org/frostnova/nova/artifact/GhostNeverExistedLib.class"
GHOST_LIB_RES="ghost-never-existed-lib.txt"

# 必不放行的非 class 资源样例。写的是永远不该放行什么，不是臂清单的抄件。
# 同目录同后缀任意名、以及上一级直到根同后缀，各至少一只能被放行。
NEVER_ALLOW_RES=(
    "ghost-never-existed.txt"
    "ghost-never-existed.json"
    "ghost-never-existed.MF"
    "ghost-never-existed.properties"
    "ghost-never-existed.factories"
    "ghost-never-existed.imports"
    "META-INF/ghost-never-existed.txt"
    "META-INF/ghost-never-existed.json"
    "META-INF/ghost-never-existed.MF"
    "META-INF/ghost-never-existed.properties"
    "META-INF/ghost-never-existed.factories"
    "META-INF/ghost-never-existed.imports"
    "META-INF/spring/ghost-never-existed.txt"
    "BOOT-INF/ghost-never-existed.txt"
    "BOOT-INF/ghost-never-existed.idx"
    "ghost-never-existed.idx"
)

arm_matches() {
    local e="$1" arm="$2"
    case "$e" in
        $arm) return 0 ;;
    esac
    return 1
}

# drop 键与旧 case 臂上的字面一致。plugin.json 等无 drop 的臂回空。
arm_drop_key() {
    local arm="$1"
    case "$arm" in
        plugin.json|dependency.json)
            return 0
            ;;
        META-INF/spring/\*.imports)
            return 0
            ;;
        *\*)
            printf '%s\n' "${arm%\*}"
            ;;
        *)
            printf '%s\n' "$arm"
            ;;
    esac
}

# 探针落在该臂路径下：glob 臂把第一个 * 起换成 GhostNeverExisted.class；
# 带目录的精确臂换成同目录下的 GhostNeverExisted.class；否则根上那一件。
class_probe_for_arm() {
    local arm="$1" prefix="" probe=""
    case "$arm" in
        *\**)
            set -f
            local IFS='*'
            set -- $arm
            set +f
            prefix=$1
            probe="${prefix}GhostNeverExisted.class"
            ;;
        */*)
            probe="${arm%/*}/GhostNeverExisted.class"
            ;;
        *)
            probe="GhostNeverExisted.class"
            ;;
    esac
    printf '%s\n' "$probe"
}

whitelist_by_arms() {
    local e="$1" drop="$2"
    shift 2
    local arm="" key=""
    case "$e" in
        *.class) return 1 ;;
    esac
    for arm in "$@"; do
        if arm_matches "$e" "$arm"; then
            key="$(arm_drop_key "$arm")"
            if [ -n "$key" ] && [ "$drop" = "$key" ]; then
                return 1
            fi
            return 0
        fi
    done
    return 1
}

plugin_whitelisted() {
    if [ "${#PLUGIN_WL_ARMS[@]}" -eq 0 ]; then
        return 1
    fi
    whitelist_by_arms "$1" "${2:-}" "${PLUGIN_WL_ARMS[@]}"
}

lib_whitelisted() {
    if [ "${#LIB_WL_ARMS[@]}" -eq 0 ]; then
        return 1
    fi
    whitelist_by_arms "$1" "${2:-}" "${LIB_WL_ARMS[@]}"
}

# 只在子进程里把幽灵种进调用方的 entries。bash 动态作用域，不另声明 local。
plant_child_ghosts() {
    local kind="$1"
    [ -n "${ARTIFACT_ENTRY_CHILD:-}" ] || return 0
    case "$kind" in
        core)
            entries="${entries}
BOOT-INF/classes/${GHOST_CORE_CLASS}"
            ;;
        plugin)
            entries="${entries}
${GHOST_PLUGIN_CLASS}
${GHOST_PLUGIN_RES}"
            ;;
        lib)
            entries="${entries}
${GHOST_LIB_CLASS}
${GHOST_LIB_RES}"
            ;;
    esac
}

prove_never_allow_resources() {
    local kind="$1"
    local sample="" n=0 failed=0
    if [ "${#NEVER_ALLOW_RES[@]}" -eq 0 ]; then
        note_control_red "白名单自证 红：资源样例清单是空的，这一格量不动"
        return
    fi
    for sample in "${NEVER_ALLOW_RES[@]}"; do
        n=$((n + 1))
        if [ "$kind" = plugin ]; then
            if plugin_whitelisted "$sample"; then
                note_control_red "白名单自证 红：插件白名单放行了资源样例 ${sample}，这一格量不动"
                failed=1
            fi
        elif [ "$kind" = lib ]; then
            if lib_whitelisted "$sample"; then
                note_control_red "白名单自证 红：lib白名单放行了资源样例 ${sample}，这一格量不动"
                failed=1
            fi
        else
            if core_whitelisted "$sample"; then
                note_control_red "白名单自证 红：核心白名单放行了资源样例 ${sample}，这一格量不动"
                failed=1
            fi
        fi
    done
    if [ "$n" -eq 0 ]; then
        note_control_red "白名单自证 红：资源样例清单是空的，这一格量不动"
        return
    fi
    if [ "$failed" -ne 0 ]; then
        return
    fi
    if [ "$kind" = plugin ]; then
        echo "插件白名单自证 绿（资源样例 ${n} 只皆不放行）"
    elif [ "$kind" = lib ]; then
        echo "lib白名单自证 绿（资源样例 ${n} 只皆不放行）"
    else
        echo "核心白名单自证 绿（资源样例 ${n} 只皆不放行）"
    fi
}

# class 条目 → 源码 java（去掉 $内部类 后缀）。只在 src/main/java 下找。
java_for_class() {
    local java_root="$1" rel="$2"
    local stem="${rel%.class}"
    stem="${stem%%\$*}"
    local src="$java_root/${stem}.java"
    if [ -f "$src" ]; then
        printf '%s\n' "$src"
        return 0
    fi
    return 1
}

count_source() {
    local java_root="$1" res_root="$2" skip_ui="$3" exclude_dev="$4"
    local n=0
    if [ -d "$java_root" ]; then
        n=$((n + $(find "$java_root" -type f -name '*.java' | wc -l | tr -d ' ')))
    fi
    if [ -d "$res_root" ]; then
        while IFS= read -r f; do
            [ -n "$f" ] || continue
            local rel="${f#"$res_root"/}"
            if [ -n "$skip_ui" ]; then
                case "$rel" in
                    "$skip_ui"/*|"$skip_ui") continue ;;
                esac
            fi
            if [ -n "$exclude_dev" ] && [ "$rel" = "$exclude_dev" ]; then
                continue
            fi
            n=$((n + 1))
        done <<EOF
$(find "$res_root" -type f)
EOF
    fi
    printf '%s\n' "$n"
}

CORE_MOD=""
core_rc=0
CORE_MOD="$(find_core_module)" || core_rc=$?
if [ "$core_rc" -eq 2 ]; then
    exit 1
fi
if [ "$core_rc" -ne 0 ] || [ -z "$CORE_MOD" ]; then
    echo "核心条目 红 源码树找不到带 application.yml 的核心模块" >&2
    exit 1
fi
CORE_JAVA="$REPO_ROOT/$CORE_MOD/src/main/java"
CORE_RES="$REPO_ROOT/$CORE_MOD/src/main/resources"

# 多出 → 红。print_cell 用这一句。对照改走子进程端到端，不再只断本函数。
extra_is_red() {
    [ "$1" -gt 0 ]
}

print_cell() {
    local label="$1" jar="$2" count="$3" src_count="$4" extra="$5"
    if extra_is_red "$extra"; then
        echo "  $label 红 jar内${count}项 源码${src_count}项 多出${extra}项 $(basename "$jar")"
        RED=1
    else
        echo "  $label 绿 jar内${count}项 源码${src_count}项 多出0项 $(basename "$jar")"
    fi
}

mark_extra() {
    local cell_label="$1"
    local cell_entry="$2"
    local why="$3"
    local silent="$4"
    CELL_EXTRA=$((CELL_EXTRA + 1))
    if [ -z "$silent" ]; then
        echo "  ${cell_label} 多出条目: ${cell_entry}  （${why}）"
        EXTRA_TOTAL=$((EXTRA_TOTAL + 1))
    fi
}

# 列条目 → 白名单 → 比源码 → 计数。quiet 非空时只改 CELL_*，不印、不累 EXTRA_TOTAL。
scan_core_entries() {
    local entries="$1"
    local java_root="$2"
    local res_root="$3"
    local drop="${4:-}"
    local quiet="${5:-}"
    local entry="" rel=""
    CELL_COUNT=0
    CELL_EXTRA=0
    while IFS= read -r entry; do
        case "$entry" in
            */) continue ;;
        esac
        case "$entry" in
            BOOT-INF/classes/config-ui/*) continue ;;
        esac
        if core_whitelisted "$entry" "$drop"; then
            continue
        fi
        CELL_COUNT=$((CELL_COUNT + 1))
        case "$entry" in
            BOOT-INF/classes/*.class)
                rel="${entry#BOOT-INF/classes/}"
                case "$rel" in
                    org/*)
                        if java_for_class "$java_root" "$rel" >/dev/null; then
                            continue
                        fi
                        ;;
                esac
                mark_extra "核心条目" "$entry" "不在源码" "$quiet"
                continue
                ;;
            BOOT-INF/classes/*)
                rel="${entry#BOOT-INF/classes/}"
                if [ "$rel" = "application-dev.yml" ]; then
                    mark_extra "核心条目" "$entry" "pom 排除，不得进包" "$quiet"
                    continue
                fi
                if [ -f "$res_root/$rel" ]; then
                    continue
                fi
                mark_extra "核心条目" "$entry" "不在源码" "$quiet"
                continue
                ;;
        esac
        mark_extra "核心条目" "$entry" "不在源码" "$quiet"
    done <<< "$entries"
}

scan_plain_entries() {
    local kind="$1"
    local entries="$2"
    local java_root="$3"
    local res_root="$4"
    local drop="${5:-}"
    local quiet="${6:-}"
    local label="$7"
    local skip_ui=""
    local entry=""
    CELL_COUNT=0
    CELL_EXTRA=0
    if [ "$kind" = plugin ]; then
        skip_ui="config-ui-pages"
    fi
    while IFS= read -r entry; do
        case "$entry" in
            */) continue ;;
        esac
        if [ -n "$skip_ui" ]; then
            case "$entry" in
                "$skip_ui"/*) continue ;;
            esac
        fi
        case "$entry" in
            *.class)
                CELL_COUNT=$((CELL_COUNT + 1))
                if java_for_class "$java_root" "$entry" >/dev/null; then
                    continue
                fi
                mark_extra "$label" "$entry" "不在源码" "$quiet"
                continue
                ;;
        esac
        if [ "$kind" = plugin ]; then
            if plugin_whitelisted "$entry" "$drop"; then
                continue
            fi
        else
            if lib_whitelisted "$entry" "$drop"; then
                continue
            fi
        fi
        CELL_COUNT=$((CELL_COUNT + 1))
        if [ -f "$res_root/$entry" ]; then
            continue
        fi
        mark_extra "$label" "$entry" "不在源码" "$quiet"
    done <<< "$entries"
}

prove_arms_reject_class() {
    local kind="$1"
    local arm="" probe="" n=0 failed=0
    if [ "$kind" = plugin ]; then
        if [ "${#PLUGIN_WL_ARMS[@]}" -eq 0 ]; then
            note_control_red "白名单自证 红：plugin 白名单臂清单是空的，这一格量不动"
            return
        fi
        for arm in "${PLUGIN_WL_ARMS[@]}"; do
            n=$((n + 1))
            probe="$(class_probe_for_arm "$arm")"
            if plugin_whitelisted "$probe"; then
                note_control_red "白名单自证 红：插件臂 ${arm} 放行了 .class 探针 ${probe}，这一格量不动"
                failed=1
            fi
        done
    else
        if [ "${#LIB_WL_ARMS[@]}" -eq 0 ]; then
            note_control_red "白名单自证 红：lib 白名单臂清单是空的，这一格量不动"
            return
        fi
        for arm in "${LIB_WL_ARMS[@]}"; do
            n=$((n + 1))
            probe="$(class_probe_for_arm "$arm")"
            if lib_whitelisted "$probe"; then
                note_control_red "白名单自证 红：lib臂 ${arm} 放行了 .class 探针 ${probe}，这一格量不动"
                failed=1
            fi
        done
    fi
    if [ "$n" -eq 0 ]; then
        note_control_red "白名单自证 红：${kind} 白名单臂清单是空的，这一格量不动"
        return
    fi
    if [ "$failed" -ne 0 ]; then
        return
    fi
    if [ "$kind" = plugin ]; then
        echo "插件白名单自证 绿（${n} 臂逐臂探 .class 皆不放行）"
    else
        echo "lib白名单自证 绿（${n} 臂逐臂探 .class 皆不放行）"
    fi
}

check_core() {
    local jar="$1"
    local label="核心条目"
    local entries="" src_count=0
    local java_root="$CORE_JAVA"
    local res_root="$CORE_RES"

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
    plant_child_ghosts core

    src_count="$(count_source "$java_root" "$res_root" "config-ui" "application-dev.yml")"

    # 白名单自证（内置）：临时拿掉 loader 前缀，该桶必须整片红；加回则这些不算多出。
    if [ -z "$DROP_PREFIX" ]; then
        local drop_n=0 still_allowed=0
        local entry=""
        while IFS= read -r entry; do
            case "$entry" in
                */) continue ;;
                org/springframework/boot/loader/*)
                    drop_n=$((drop_n + 1))
                    if core_whitelisted "$entry" "org/springframework/boot/loader/"; then
                        still_allowed=$((still_allowed + 1))
                    fi
                    ;;
            esac
        done <<< "$entries"
        if [ "$drop_n" -eq 0 ]; then
            note_control_red "白名单自证 红：核心 jar 没有 org/springframework/boot/loader/ 条目，这一格量不动"
        elif [ "$still_allowed" -ne 0 ]; then
            note_control_red "白名单自证 红：去掉 org/springframework/boot/loader/ 后该桶未整片红（${drop_n} 项里仍放行 ${still_allowed}），这一格量不动"
        else
            echo "白名单自证 绿（去掉 org/springframework/boot/loader/ 后该桶 ${drop_n} 项皆多出；加回不记这些多出）"
        fi
    fi

    if [ -z "$DROP_PREFIX" ] && [ "$CORE_WL_PROVED" -eq 0 ]; then
        prove_never_allow_resources core
        CORE_WL_PROVED=1
    fi

    scan_core_entries "$entries" "$java_root" "$res_root" "$DROP_PREFIX" ""
    print_cell "$label" "$jar" "$CELL_COUNT" "$src_count" "$CELL_EXTRA"
}

check_plugin() {
    local jar="$1" module="$2"
    local label="插件条目[$module]"
    local entries="" src_count=0 src_mod="" java_root="" res_root=""

    if [ ! -f "$jar" ]; then
        echo "  $label 红 产物里没有这个 jar: $jar"
        RED=1
        return
    fi
    if ! src_mod="$(find_module_dir "$module")"; then
        echo "  $label 红 源码树找不到模块目录 $(basename "$jar")"
        RED=1
        return
    fi
    java_root="$REPO_ROOT/$src_mod/src/main/java"
    res_root="$REPO_ROOT/$src_mod/src/main/resources"

    if ! entries="$(list_jar_entries "$jar")"; then
        echo "  $label 红 读不出 jar 条目（unzip 与 jar 都没能列出它）: $jar"
        RED=1
        return
    fi
    plant_child_ghosts plugin

    src_count="$(count_source "$java_root" "$res_root" "config-ui-pages" "")"

    if [ -z "$DROP_PREFIX" ] && [ "$PLUGIN_WL_PROVED" -eq 0 ]; then
        prove_arms_reject_class plugin
        prove_never_allow_resources plugin
        PLUGIN_WL_PROVED=1
    fi

    scan_plain_entries "plugin" "$entries" "$java_root" "$res_root" "$DROP_PREFIX" "" "$label"
    print_cell "$label" "$jar" "$CELL_COUNT" "$src_count" "$CELL_EXTRA"
}

check_lib_own() {
    local jar="" entries="" aid="" src_mod="" java_root="" res_root="" src_count="" found_own=0
    local lib_jars=() rc=""

    if [ -z "$DROP_PREFIX" ] && [ "$LIB_WL_PROVED" -eq 0 ]; then
        prove_arms_reject_class lib
        prove_never_allow_resources lib
        LIB_WL_PROVED=1
    fi

    if [ ! -d "$OUT/lib" ]; then
        echo "  lib自家产物 红 产物里没有 $OUT/lib/"
        RED=1
        return
    fi

    shopt -s nullglob
    lib_jars=("$OUT"/lib/*.jar)
    shopt -u nullglob
    if [ "${#lib_jars[@]}" -eq 0 ]; then
        echo "  lib自家产物 红 $OUT/lib/ 里一个 jar 都没有"
        RED=1
        return
    fi

    for jar in "${lib_jars[@]}"; do
        if ! entries="$(list_jar_entries "$jar")"; then
            echo "  lib自家产物 红 读不出 jar 条目（unzip 与 jar 都没能列出它）: $jar"
            RED=1
            continue
        fi
        rc=0
        aid="$(own_artifact_id_from_entries "$entries")" || rc=$?
        if [ "$rc" -eq 2 ]; then
            RED=1
            continue
        fi
        if [ "$rc" -ne 0 ] || [ -z "$aid" ]; then
            continue
        fi
        found_own=1
        rc=0
        src_mod="$(find_module_by_artifact_id "$aid")" || rc=$?
        if [ "$rc" -ne 0 ] || [ -z "$src_mod" ]; then
            echo "  lib自家产物 红 找不到 artifactId=${aid} 对应模块 $(basename "$jar")"
            RED=1
            continue
        fi
        java_root="$REPO_ROOT/$src_mod/src/main/java"
        res_root="$REPO_ROOT/$src_mod/src/main/resources"
        src_count="$(count_source "$java_root" "$res_root" "" "")"
        plant_child_ghosts lib
        scan_plain_entries "lib" "$entries" "$java_root" "$res_root" "$DROP_PREFIX" "" "lib自家产物[$aid]"
        print_cell "lib自家产物[$aid]" "$jar" "$CELL_COUNT" "$src_count" "$CELL_EXTRA"
    done

    if [ "$found_own" -eq 0 ]; then
        echo "  lib自家产物 红 $OUT/lib/ 里没有 META-INF/maven/org.frostnova.nova/ 的自家 jar"
        RED=1
    fi
}

# 阴性对照起子进程端到端跑本尺；阳性锚从源码树现取，不钉生产类名。
# 量不动只记下，不当场退；末尾再按产物红优先定退码。
run_entry_controls() {
    local jar="$1"
    local entries="" child_out="" child_rc="" g="" missing=""
    local pos_rel="" pos_java=""
    local core_entry="BOOT-INF/classes/${GHOST_CORE_CLASS}"
    local can_child=1

    if [ -n "${ARTIFACT_ENTRY_CHILD:-}" ]; then
        echo "子进程模式：不跑对照，幽灵种进主扫描，本趟结论不作数" >&2
        return 0
    fi

    if [ ! -f "$SCRIPT_PATH" ]; then
        note_control_red "阴性对照 红：找不到本尺脚本 ${SCRIPT_PATH}，对照没法起子进程"
        can_child=0
    fi
    if [ ! -f "$jar" ]; then
        note_control_red "阴性对照 红：产物里没有核心 jar，对照没法种进真条目"
        can_child=0
    fi
    if [ "$can_child" -eq 1 ] && ! entries="$(list_jar_entries "$jar")"; then
        note_control_red "阴性对照 红：读不出核心 jar 条目，对照没法种进真条目"
        can_child=0
    fi

    if java_for_class "$CORE_JAVA" "$GHOST_CORE_CLASS" >/dev/null; then
        note_control_red "阴性对照 红：合成条目在源码里有对应 java，对照失效"
        can_child=0
    fi

    while IFS= read -r pos_java; do
        [ -n "$pos_java" ] || continue
        pos_rel="${pos_java#"$CORE_JAVA"/}"
        pos_rel="${pos_rel%.java}.class"
        break
    done <<EOF
$(find "$CORE_JAVA" -type f -name '*.java' | sort)
EOF
    if [ -z "$pos_rel" ]; then
        note_control_red "阳性锚 红：核心模块 src/main/java 下一件 .java 都没有"
    elif ! java_for_class "$CORE_JAVA" "$pos_rel" >/dev/null; then
        note_control_red "阳性锚 红：从源码现取的 ${pos_rel} 映射回 class 对不上"
    elif [ -n "$entries" ] && ! haystack_has "$entries" "BOOT-INF/classes/${pos_rel}" exact; then
        note_control_red "阳性锚 红：源码现取的 ${pos_rel} 不在核心 jar 条目里"
    fi

    if [ -z "$DROP_PREFIX" ]; then
        if [ "$CORE_WL_PROVED" -eq 0 ]; then
            prove_never_allow_resources core
            CORE_WL_PROVED=1
        fi
        if [ "$PLUGIN_WL_PROVED" -eq 0 ]; then
            prove_arms_reject_class plugin
            prove_never_allow_resources plugin
            PLUGIN_WL_PROVED=1
        fi
        if [ "$LIB_WL_PROVED" -eq 0 ]; then
            prove_arms_reject_class lib
            prove_never_allow_resources lib
            LIB_WL_PROVED=1
        fi
    fi

    if [ "$can_child" -ne 1 ]; then
        return 0
    fi

    child_out="$(ARTIFACT_ENTRY_CHILD=1 "$BASH" "$SCRIPT_PATH" "$OUT" 2>&1)"
    child_rc=$?
    if [ "$child_rc" -ne 1 ]; then
        note_control_red "阴性对照 红：子进程有多出条目却退 ${child_rc}（须退 1），这一格量不动"
    fi
    for g in "$core_entry" "$GHOST_PLUGIN_CLASS" "$GHOST_PLUGIN_RES" "$GHOST_LIB_CLASS" "$GHOST_LIB_RES"; do
        if ! haystack_has "$child_out" "多出条目: ${g}"; then
            missing="${missing} ${g}"
        fi
    done
    if [ -n "$missing" ]; then
        note_control_red "阴性对照 红：子进程未点名幽灵${missing}，这一格量不动"
    elif [ "$child_rc" -eq 1 ]; then
        echo "阴性对照 绿（子进程退 ${child_rc}；点名 ${core_entry} ${GHOST_PLUGIN_CLASS} ${GHOST_PLUGIN_RES} ${GHOST_LIB_CLASS} ${GHOST_LIB_RES}）"
    fi
}

echo "产物整包条目尺：$OUT"

CORE_JAR="$OUT/NovaBot.jar"
run_entry_controls "$CORE_JAR"
check_core "$CORE_JAR"

shopt -s nullglob
plugin_jars=("$OUT"/plugins/*.jar)
shopt -u nullglob
if [ "${#plugin_jars[@]}" -eq 0 ]; then
    echo "  插件条目 红 $OUT/plugins/ 里一个 jar 都没有"
    RED=1
else
    for jar in "${plugin_jars[@]}"; do
        base="$(basename "$jar" .jar)"
        module="$(echo "$base" | sed -E 's/-[0-9][^-]*(-SNAPSHOT)?$//')"
        check_plugin "$jar" "$module"
    done
fi

check_lib_own

if [ "$RED" -ne 0 ]; then
    echo
    if [ "$EXTRA_TOTAL" -gt 0 ]; then
        echo "产物里出现了 ${EXTRA_TOTAL} 个源码里没有的 jar 条目（核心＝运行模块 src/main，插件＝该模块 src/main；白名单是启动器／依赖／清单；lib 自家产物按 artifactId 对模块 src/main）。" >&2
        echo "多半是上一次构建留在 target/ 里的旧文件——源文件删掉或改名后 Maven 不会删它，" >&2
        echo "它照旧进包。先 mvn clean 再重建；若清理后仍在，那它就不是残留，去查是谁把它拷进产物的。" >&2
    else
        echo "这把尺没能把产物量完（上面每一格红都写明了卡在哪里），因此不给绿。" >&2
    fi
    flush_control_notes "产物本身已红，此句不作数"
    exit 1
fi

if [ "$CONTROL_RED" -ne 0 ]; then
    flush_control_notes ""
    exit 2
fi

exit 0
