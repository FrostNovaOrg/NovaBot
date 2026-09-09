#!/usr/bin/env bash
# 控制台页登记清单：哪个模块把哪些界面件登记进了产物
#
# 用法：tools/console-page-registry.sh
# 输出：stdout 每行「模块目录<TAB>件名」（件名不带 config-ui-pages/ 前缀）；诊断走 stderr
# 退码：0＝清单已印（清单可以为空：一个模块什么都没登记，它在这里就没有行）
#
# ── 为什么需要这一件 ────────────────────────────────────────────────────
# 插件界面件进产物的真源是**登记**，不是目录：ConsolePageProvider 的 script()/assets()
# 申报页与附属脚本，核心出口只端登记过的名字；不走 provider 的那一路（控制器直取）
# 则以 "config-ui-pages/<名>" 字面量点名。目录里多放一件、少放一件都不改变出口——
# 可有些尺原先按目录判「哪些件该在产物里」，目录里多放一件，jar 里那份就被判「源码有」。
# 本件把真源集中在一处，供那些尺共用（产物界面资源尺的插件格、连接页尺的页目录）。
#
# ── 清单怎么取（两源取并集，都只在 M/src/main/java 下） ────────────────
#   (a) 含 implements ConsolePageProvider 的件里，**代码行**上的 "<名>.js" 字面量。
#       // 或 * 开头的注释行不算：javadoc 里举一个文件名的例子正是该写的话，
#       让示例把清单撑大，结局必然是把示例删掉（同边界尺只看正文不看不例的道理）。
#   (b) 任何件里的 "config-ui-pages/<名>" 字面量——控制器直取的那一路。
#       名为空的不算（核心的 SCRIPT_ROOT 恰是 "config-ui-pages/"，它不是登记）。
#
# ── 清单哑了怎么知道 ────────────────────────────────────────────────────
# 本件一声不吭也可能印出空清单（正则失灵、树被搬空）。因此不在这里做守卫，
# 而让消费它的尺各自带「射程为空」守卫：产物界面资源尺把「jar 有页条目而该模块
# 清单为空」判红，连接页尺把「bilibili.js 未登记」判红。一把哑了的清单在它下游
# 当场变红，而不是两边一起绿在空集上。
#
# 模块枚举两路（输出同形：相对仓根的模块目录；任意深度、剔盘上已不存在的路径）：
#   git 工作树（git rev-parse --is-inside-work-tree 为 true）——git 索引 ∪ 未跟踪未忽略件；
#   否则（导出的干净源码树／任何无 .git 的树）——find pom.xml，剔 target／node_modules／dist／scratch／.git。
# git 路径出错不再吞：git 退非 0 时脚本退 2 并把原因印到 stderr（取不出须红）。
# 核心模块也照扫——它今天一件也登记不出（见上，SCRIPT_ROOT 空名不算），
# 不为它单带一份名单，名单一重复就开始漂。
#
# 同一页名被两个及以上模块登记须红，退 1，stderr 写
# 「重复登记：<页> ← <模块A>,<模块B>」。消费方若 head -n 1 取先到者，
# 双登记会把页目录解到错误模块且不响；本尺在清单出口拦住。
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 2

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 在册模块目录（每行一个，如 plugins/nova-console）
list_module_dirs() {
    local inside git_rc
    inside="$(git rev-parse --is-inside-work-tree 2>/dev/null || true)"
    if [ "$inside" = "true" ]; then
        git -c core.quotepath=false ls-files --cached --others --exclude-standard '*/pom.xml' \
            > "$WORK/poms.raw" 2>"$WORK/poms.err"
        git_rc=$?
        if [ "$git_rc" -ne 0 ]; then
            echo "列模块 红 git ls-files 取不出（退 ${git_rc}）：$(cat "$WORK/poms.err")" >&2
            exit 2
        fi
        sort -u "$WORK/poms.raw" | while IFS= read -r pom; do
            [ -e "$pom" ] && printf '%s\n' "${pom%/pom.xml}"
        done
    else
        find "$REPO_ROOT" -name pom.xml \
            -not -path '*/target/*' \
            -not -path '*/node_modules/*' \
            -not -path '*/dist/*' \
            -not -path '*/scratch/*' \
            -not -path '*/.git/*' \
            | sort -u \
            | while IFS= read -r abs; do
                  pom="${abs#"$REPO_ROOT"/}"
                  [ -e "$pom" ] && printf '%s\n' "${pom%/pom.xml}"
              done
    fi
}

list_module_dirs > "$WORK/modules"

REG="$WORK/reg"
: > "$REG"

while IFS= read -r mod; do
    [ -z "$mod" ] && continue
    main="$mod/src/main/java"
    [ -d "$main" ] || continue

    # (a) provider 申报：代码行上的 .js 字面量（注释行不算）
    while IFS= read -r f; do
        [ -z "$f" ] && continue
        grep -vE '^[[:space:]]*(//|\*)' "$f" 2>/dev/null \
            | grep -oE '"[^"]*\.js"' \
            | tr -d '"' \
            | awk -v m="$mod" '{print m "\t" $0}' >> "$REG"
    done <<< "$(grep -rl 'implements ConsolePageProvider' --include='*.java' "$main" 2>/dev/null)"

    # (b) 控制器直取：config-ui-pages/<名> 字面量（空名不算）
    grep -rhoE '"config-ui-pages/[^"]+"' --include='*.java' "$main" 2>/dev/null \
        | tr -d '"' \
        | sed -E 's|^config-ui-pages/||' \
        | awk -v m="$mod" '{print m "\t" $0}' >> "$REG"
done < "$WORK/modules"

sort -u "$REG" > "$WORK/reg.uniq"

# 同一页名 ↔ 多个模块：红。sort -u 只去「模块+页」整行重复，跨模块同页仍是两行。
: > "$WORK/reg.dup"
awk -F'\t' '
NF >= 2 && $2 != "" {
    p = $2
    m = $1
    if (!(p in first)) {
        first[p] = m
        mods[p] = m
        n[p] = 1
        next
    }
    if (index("," mods[p] ",", "," m ",") == 0) {
        mods[p] = mods[p] "," m
        n[p]++
    }
}
END {
    for (p in n) {
        if (n[p] > 1) print p "\t" mods[p]
    }
}' "$WORK/reg.uniq" > "$WORK/reg.dup"

if [ -s "$WORK/reg.dup" ]; then
    while IFS=$'\t' read -r page mods; do
        echo "重复登记：${page} ← ${mods}" >&2
    done < "$WORK/reg.dup"
    exit 1
fi

cat "$WORK/reg.uniq"
