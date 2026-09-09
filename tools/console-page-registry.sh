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
# 模块枚举照边界尺（tools/novacore-boundary-check.sh）的在册 pom.xml 法：
# git 索引 ∪ 未跟踪未忽略件、任意深度、剔盘上已不存在的路径，不按仓根一级目录名。
# 核心模块也照扫——它今天一件也登记不出（见上，SCRIPT_ROOT 空名不算），
# 不为它单带一份名单，名单一重复就开始漂。
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 2

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 在册模块目录（每行一个，如 plugins/nova-console）
git -c core.quotepath=false ls-files --cached --others --exclude-standard '*/pom.xml' 2>/dev/null \
    | sort -u \
    | while IFS= read -r pom; do
          [ -e "$pom" ] && printf '%s\n' "${pom%/pom.xml}"
      done > "$WORK/modules"

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

sort -u "$REG"
