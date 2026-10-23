#!/bin/sh
# 在真浏览器里把控制台离线演示各页打开一遍，见同目录 README.md。
#
# 用法：sh tools/console-browser-check/run.sh [--chrome 浏览器路径] [--out 结果目录]
# 浏览器路径也可由环境变量 NOVA_CHROME 给出。
# 退码：0 全过；1 有失败；2 跑不起来。
set -u

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
chrome=${NOVA_CHROME:-}
out=$ROOT/target/console-browser-check

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
            out=$2
            shift 2
            ;;
        -h|--help)
            sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            cannot_run "不认识的参数：$1（用法见 --help）"
            ;;
    esac
done

command -v node >/dev/null 2>&1 || cannot_run "没有找到 node，需要 node 22 或更高版本"
major=$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null) || major=0
[ "$major" -ge 22 ] 2>/dev/null || cannot_run "node 版本太旧（$(node --version 2>/dev/null)），需要 22 或更高"
[ -n "$chrome" ] || cannot_run "没给浏览器：设环境变量 NOVA_CHROME，或用 --chrome 浏览器路径（下载见 README.md）"
[ -f "$chrome" ] && [ -x "$chrome" ] || cannot_run "浏览器不存在或不能执行：$chrome"
command -v python3 >/dev/null 2>&1 || cannot_run "没有找到 python3，导出演示要用它"
command -v bash >/dev/null 2>&1 || cannot_run "没有找到 bash，导出演示要用它"

case "$out" in
    /*) ;;
    *) out=$(pwd)/$out ;;
esac
# 结果目录在仓库里时，必须落在 git 忽略的地方，跑出来的东西一样都不能被提交。
# 先核再建，免得在仓库里留下一个空目录。
case "$out/" in
    "$ROOT"/*)
        command -v git >/dev/null 2>&1 || cannot_run "结果目录在仓库里，却没有 git 可以核对它是否被忽略"
        git -C "$ROOT" check-ignore -q "$out/" || cannot_run "结果目录没有被 git 忽略：$out"
        ;;
esac
mkdir -p "$out" || cannot_run "建不了结果目录：$out"
out=$(cd "$out" && pwd)
# 只清本脚本写的几样，免得上一次的截图冒充这一次的
rm -f "$out"/*.png "$out/summary.txt" "$out/browser.log" "$out/export.log"

work=$(mktemp -d "${TMPDIR:-/tmp}/nova-console-check.XXXXXX") || cannot_run "建不了临时目录"
trap 'rm -rf "$work"' EXIT
trap 'exit 2' HUP INT TERM

if ! bash "$ROOT/tools/demo-assets/export-console-demo.sh" "$work/demo" >"$out/export.log" 2>&1; then
    cat "$out/export.log" >&2
    cannot_run "导出离线演示失败（输出见上，也存在 $out/export.log）"
fi
mkdir "$work/profile" || cannot_run "建不了浏览器的临时用户目录"

node "$ROOT/tools/console-browser-check/check.mjs" \
    --demo "$work/demo" --chrome "$chrome" --out "$out" --profile "$work/profile"
status=$?

case "$out/" in
    "$ROOT"/*)
        leaked=$(git -C "$ROOT" ls-files --others --exclude-standard -- "$out")
        if [ -n "$leaked" ]; then
            echo "$leaked" >&2
            cannot_run "上面这些结果文件没有被 git 忽略"
        fi
        ;;
esac
exit "$status"
