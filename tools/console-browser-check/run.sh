#!/bin/sh
# 在真浏览器里把控制台离线演示各页打开一遍，见同目录 README.md。
#
# 用法：sh tools/console-browser-check/run.sh [--chrome 浏览器路径] [--out 结果目录]
# 浏览器路径也可由环境变量 NOVA_CHROME 给出。
# 不给 --out 时每次在系统临时目录下新建一个结果目录，跑完在末尾印出目录在哪。
# 退码：0 全过；1 有失败；2 跑不起来。
set -u

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
chrome=${NOVA_CHROME:-}
out=
out_given=no

cannot_run() {
    echo "跑不起来：$*" >&2
    exit 2
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --chrome)
            [ "$#" -ge 2 ] || cannot_run "--chrome 后面要跟浏览器路径"
            chrome=$2
            shift 2
            ;;
        --out)
            [ "$#" -ge 2 ] || cannot_run "--out 后面要跟结果目录"
            [ -n "$2" ] || cannot_run "--out 后面要跟结果目录"
            out=$2
            out_given=yes
            shift 2
            ;;
        -h|--help)
            sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            cannot_run "不认识的参数：$1（用法见 --help）"
            ;;
    esac
done

# 目录里只要有一个东西就算不空，隐藏文件也算。
dir_is_empty() {
    for entry in "$1"/* "$1"/.[!.]* "$1"/..?*; do
        if [ -e "$entry" ] || [ -L "$entry" ]; then
            return 1
        fi
    done
    return 0
}

# 后面会进入结果目录，相对路径要先换成绝对路径。
here=$(pwd)
case "$chrome" in
    ''|/*) ;;
    *) chrome=$here/$chrome ;;
esac
tmp=${TMPDIR:-/tmp}
case "$tmp" in
    /*) ;;
    *) tmp=$here/$tmp ;;
esac

# 先核结果目录：这一步在启动浏览器、写任何结果文件之前做完。
# 本工具不删结果目录里的任何东西。给了 --out 时，目录不存在就建它，是空目录就照用；
# 别的（里面已经有文件，隐藏文件也算；或者那条路不是目录）一律拒跑，说清是哪个目录、为什么、
# 怎么办（自己清空，或换一个目录）。
# 先进入目录、再核进去的这一个：核过之后这条路就算被换成别处，写入也还落在核过的目录里，
# 因为之后一律用相对路径，不再按 --out 的路径重新找。
if [ "$out_given" = yes ]; then
    if [ -e "$out" ] || [ -L "$out" ]; then
        [ -d "$out" ] || cannot_run "--out 指向的不是目录：${out}。它一点没动。请用 --out 换一个目录"
    else
        mkdir -p "$out" || cannot_run "建不了结果目录：${out}"
    fi
    cd -P "$out" || cannot_run "进不了结果目录：${out}"
    dir_is_empty . || cannot_run "结果目录里已经有文件（隐藏文件也算），本工具不删也不动里面的东西：${out}。请自己把这个目录清空，或者用 --out 换一个别的目录"
fi

command -v node >/dev/null 2>&1 || cannot_run "没有找到 node，需要 node 22 或更高版本"
major=$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null) || major=0
[ "$major" -ge 22 ] 2>/dev/null || cannot_run "node 版本太旧（$(node --version 2>/dev/null)），需要 22 或更高"
[ -n "$chrome" ] || cannot_run "没给浏览器：设环境变量 NOVA_CHROME，或用 --chrome 浏览器路径（下载见 README.md）"
[ -f "$chrome" ] && [ -x "$chrome" ] || cannot_run "浏览器不存在或不能执行：$chrome"
command -v python3 >/dev/null 2>&1 || cannot_run "没有找到 python3，导出演示要用它"
command -v bash >/dev/null 2>&1 || cannot_run "没有找到 bash，导出演示要用它"

if [ "$out_given" = no ]; then
    out=$(mktemp -d "${tmp}/nova-console-browser-check.XXXXXX") || cannot_run "建不了结果目录"
    cd -P "$out" || cannot_run "进不了结果目录：${out}"
fi
# 只用来给人看是哪个目录；写入都用相对路径。用外部的 pwd 向系统问当前目录：
# 有的 sh 内置 pwd 记的是 cd 那一刻的路径，目录被挪走后会指错地方。
out=$(env pwd -P)

work=$(mktemp -d "${tmp}/nova-console-check.XXXXXX") || cannot_run "建不了临时目录"
# work 是本工具自己建的临时目录（导出的演示副本、浏览器的临时用户目录），跑完只清这一个。
trap 'rm -rf "${work}"' EXIT
trap 'exit 2' HUP INT TERM

# 结果文件只新建、不覆盖：同名的已经在那里，重定向就失败，那个文件不动。
set -C
command exec 3>export.log || cannot_run "结果目录里已经有 export.log，没有覆盖它：${out}/export.log"
if ! bash "$ROOT/tools/demo-assets/export-console-demo.sh" "${work}/demo" >&3 2>&1; then
    exec 3>&-
    cat export.log >&2
    cannot_run "导出离线演示失败（输出见上，也存在 ${out}/export.log）"
fi
exec 3>&-
mkdir "${work}/profile" || cannot_run "建不了浏览器的临时用户目录"

node "$ROOT/tools/console-browser-check/check.mjs" \
    --demo "${work}/demo" --chrome "$chrome" --out . --profile "${work}/profile"
status=$?
echo "结果目录：$(env pwd -P)"
exit "$status"
