#!/usr/bin/env bash
# 产物整包条目尺：打出来的包里，jar 条目有没有源码里已经不存在的东西
#
# 用法：bash tools/artifact-entry-check.sh [产物目录]   （默认 dist/build）
# 退码：0＝产物干净；1＝有源码对不上的条目，或量不动（工具缺失、jar 不在）；
#       2＝阴性对照／白名单自证没红，这一格量不动
#
# ── 与 tools/artifact-ui-resource-check.sh 的分工 ────────────────────────
# 那把只量三样：核心 jar 的 BOOT-INF/classes/config-ui（界面核心格）、
# 各插件 jar 的 config-ui-pages（界面插件格）、出厂覆盖件 template-defaults.json，
# 而且只判「多出」不判「缺少」。本尺量其余条目：源码已删、包里还躺着的 class、
# 非界面资源、插件字体。BOOT-INF/classes/config-ui/** 与 config-ui-pages/** 归那一把，
# 本尺跳过不重判。
#
# ── 判据 ────────────────────────────────────────────────────────────────
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
# 其余 class → plugins/<模块>/src/main/java；其余资源（含 fonts/**）→
# plugins/<模块>/src/main/resources，fonts 必须能找到同名件。
#
# 🔴 分母不许用 target/classes——源文件删掉或改名之后，上一次构建留下的那一份
#    照旧躺在那里、照旧进 jar，而包上看不出来（build.sh 顶部「陈旧产物」段）。
#
# 白名单自证：环境变量 ARTIFACT_ENTRY_DROP_PREFIX 设成 (c) 里任一前缀，
# 该桶整片当多出；不设则内置对照会临时拿掉 org/springframework/boot/loader/ 验一次。
set -uo pipefail

OUT="${1:-dist/build}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1
[ -d "$OUT" ] || OUT="$REPO_ROOT/$OUT"

DROP_PREFIX="${ARTIFACT_ENTRY_DROP_PREFIX:-}"

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

# 打出 NovaBot.jar 的那只核心模块：core/ 下带出厂 application.yml 的目录。
# 不写死目录名——目录一改，写死的路径安静失灵。
find_core_module() {
    local d
    for d in core/*; do
        if [ -f "$d/src/main/resources/application.yml" ]; then
            printf '%s\n' "$d"
            return 0
        fi
    done
    return 1
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

plugin_whitelisted() {
    local e="$1"
    case "$e" in
        plugin.json|dependency.json) return 0 ;;
        META-INF/spring/*.imports) return 0 ;;
        META-INF/maven/*) return 0 ;;
        META-INF/MANIFEST.MF) return 0 ;;
        META-INF/build-info.properties) return 0 ;;
        META-INF/spring-configuration-metadata.json) return 0 ;;
        META-INF/spring.factories) return 0 ;;
        META-INF/services/*) return 0 ;;
    esac
    return 1
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
if ! CORE_MOD="$(find_core_module)"; then
    echo "核心条目 红 源码树找不到带 application.yml 的核心模块" >&2
    exit 1
fi
CORE_JAVA="$REPO_ROOT/$CORE_MOD/src/main/java"
CORE_RES="$REPO_ROOT/$CORE_MOD/src/main/resources"

# 一条核心 class 是否对得上源码。阴性对照走这条，不读产物。
core_class_in_source() {
    local rel="$1"
    java_for_class "$CORE_JAVA" "$rel" >/dev/null
}

print_cell() {
    local label="$1" jar="$2" count="$3" src_count="$4" extra="$5"
    if [ "$extra" -gt 0 ]; then
        echo "  $label 红 jar内${count}项 源码${src_count}项 多出${extra}项 $(basename "$jar")"
        RED=1
    else
        echo "  $label 绿 jar内${count}项 源码${src_count}项 多出0项 $(basename "$jar")"
    fi
}

check_core() {
    local jar="$1"
    local label="核心条目"
    local entries count=0 extra=0 src_count=0
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

    src_count="$(count_source "$java_root" "$res_root" "config-ui" "application-dev.yml")"

    # 白名单自证（内置）：临时拿掉 loader 前缀，该桶必须整片红；加回则这些不算多出。
    if [ -z "$DROP_PREFIX" ]; then
        local drop_n=0 still_allowed=0
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
            echo "白名单自证 红：核心 jar 没有 org/springframework/boot/loader/ 条目，这一格量不动" >&2
            exit 2
        fi
        if [ "$still_allowed" -ne 0 ]; then
            echo "白名单自证 红：去掉 org/springframework/boot/loader/ 后该桶未整片红（${drop_n} 项里仍放行 ${still_allowed}），这一格量不动" >&2
            exit 2
        fi
        echo "白名单自证 绿（去掉 org/springframework/boot/loader/ 后该桶 ${drop_n} 项皆多出；加回不记这些多出）"
    fi

    while IFS= read -r entry; do
        case "$entry" in
            */) continue ;;
        esac
        case "$entry" in
            BOOT-INF/classes/config-ui/*) continue ;;
        esac
        if core_whitelisted "$entry" "$DROP_PREFIX"; then
            continue
        fi
        count=$((count + 1))
        rel=""
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
                echo "  $label 多出条目: $entry  （不在源码）"
                extra=$((extra + 1))
                EXTRA_TOTAL=$((EXTRA_TOTAL + 1))
                continue
                ;;
            BOOT-INF/classes/*)
                rel="${entry#BOOT-INF/classes/}"
                if [ "$rel" = "application-dev.yml" ]; then
                    echo "  $label 多出条目: $entry  （pom 排除，不得进包）"
                    extra=$((extra + 1))
                    EXTRA_TOTAL=$((EXTRA_TOTAL + 1))
                    continue
                fi
                if [ -f "$res_root/$rel" ]; then
                    continue
                fi
                echo "  $label 多出条目: $entry  （不在源码）"
                extra=$((extra + 1))
                EXTRA_TOTAL=$((EXTRA_TOTAL + 1))
                continue
                ;;
        esac
        echo "  $label 多出条目: $entry  （不在源码）"
        extra=$((extra + 1))
        EXTRA_TOTAL=$((EXTRA_TOTAL + 1))
    done <<< "$entries"

    print_cell "$label" "$jar" "$count" "$src_count" "$extra"
}

check_plugin() {
    local jar="$1" module="$2"
    local label="插件条目[$module]"
    local entries count=0 extra=0 src_count=0 src_mod java_root res_root

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

    src_count="$(count_source "$java_root" "$res_root" "config-ui-pages" "")"

    while IFS= read -r entry; do
        case "$entry" in
            */) continue ;;
        esac
        case "$entry" in
            config-ui-pages/*) continue ;;
        esac
        if plugin_whitelisted "$entry"; then
            continue
        fi
        count=$((count + 1))
        case "$entry" in
            *.class)
                if java_for_class "$java_root" "$entry" >/dev/null; then
                    continue
                fi
                echo "  $label 多出条目: $entry  （不在源码）"
                extra=$((extra + 1))
                EXTRA_TOTAL=$((EXTRA_TOTAL + 1))
                continue
                ;;
        esac
        if [ -f "$res_root/$entry" ]; then
            continue
        fi
        echo "  $label 多出条目: $entry  （不在源码）"
        extra=$((extra + 1))
        EXTRA_TOTAL=$((EXTRA_TOTAL + 1))
    done <<< "$entries"

    print_cell "$label" "$jar" "$count" "$src_count" "$extra"
}

# —— built-in sample that must be red ——
NEG_REL="org/frostnova/nova/core/config/ArtifactEntryCheckSentinel.class"
POS_REL="org/frostnova/nova/core/config/DataLocationGuard.class"
if core_class_in_source "$NEG_REL"; then
    echo "阴性对照 红：合成条目在源码里有对应 java，对照失效" >&2
    exit 2
fi
if ! core_class_in_source "$POS_REL"; then
    echo "阴性对照 红：已知在源码里的条目被判成了对不上，这一格量不动" >&2
    exit 2
fi
# 喂比法：合成条目必红；拿掉（换成源码里有的那条）即绿。
if core_class_in_source "$NEG_REL"; then
    echo "阴性对照 红：一段必红的合成条目被判成了过，这一格量不动" >&2
    exit 2
fi
echo "阴性对照 绿（必红的合成条目确实被判红；拿掉即绿）"

echo "产物整包条目尺：$OUT"

CORE_JAR="$OUT/NovaBot.jar"
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

if [ "$RED" -ne 0 ]; then
    echo
    if [ "$EXTRA_TOTAL" -gt 0 ]; then
        echo "产物里出现了 ${EXTRA_TOTAL} 个源码里没有的 jar 条目（核心＝运行模块 src/main，插件＝该模块 src/main；白名单是启动器／依赖／清单）。" >&2
        echo "多半是上一次构建留在 target/ 里的旧文件——源文件删掉或改名后 Maven 不会删它，" >&2
        echo "它照旧进包。先 mvn clean 再重建；若清理后仍在，那它就不是残留，去查是谁把它拷进产物的。" >&2
    else
        echo "这把尺没能把产物量完（上面每一格红都写明了卡在哪里），因此不给绿。" >&2
    fi
    exit 1
fi

exit 0
