#!/usr/bin/env bash
# 告警页「邮件配好了没有」尺：语法 + 至少三档对照
#
# 设置页药丸和初始设置完成页那一行，邮件这一路都只认收件与 SMTP 主机这两栏。
# 判定只留这一份（config-ui/alert-model.js，不碰 DOM）。本尺喂它空串、只填一半、
# 首尾空白对答案，并过一遍语法。
#
# 🔴 用 `node --input-type=module --check < 文件` 而不是 `node --check 文件`：
#    后者对含 import 的 .js 一律返 0（Node v22 实测），也就是说那一格从来没能红过——
#    一把量不动却报绿的判据，比没有这把判据更糟。本尺自带阴性对照：
#    把一段必定语法错的模块喂进去，它必须红；不红就说明这一格又量不动了，整尺判红。
#
# 退码：0 全对；1 有档对不上、有模块语法不过、或阴性对照不红；2 环境不具备（没装 node）。

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 2

if ! command -v node > /dev/null 2>&1; then
    echo "未找到 node，本尺跑不了（它量的是浏览器里那份逻辑）" >&2
    exit 2
fi

UI="core/starbot-core/src/main/resources/config-ui"
RED=0
SYNTAX_RED=0

# —— 阴性对照：这一格自己得先证明它分得出红绿 ——
if printf 'import {a} from "./x.js";\nconst b = ;;;\n' | node --input-type=module --check > /dev/null 2>&1; then
    echo "阴性对照 红：一段必定语法错的模块被判成了过，这一格量不动" >&2
    RED=1
else
    echo "阴性对照 绿（必错的模块确实被判红）"
fi

# —— 语法 ——
for f in "$UI"/alert-model.js; do
    if node --input-type=module --check < "$f" > /dev/null 2>&1; then
        echo "语法 绿 $f"
    else
        echo "语法 红 $f"
        node --input-type=module --check < "$f"
        RED=1
        SYNTAX_RED=1
    fi
done

# —— 各档 ——
node tools/alert-model-check.mjs
if [ $? -ne 0 ]; then
    RED=1
fi

# —— 末行汇总 ——
# 档尺（.mjs）的末句只数它自己的格，语法红盖不进去：语法红而档全对时，
# 整把尺的最后一句会是「跑了 N 格，红 0 格」，读起来像全绿。末行由本尺自己收
if [ "$SYNTAX_RED" -ne 0 ]; then
    echo "汇总：语法 红（名单见上方「语法 红」行），整尺退码 $RED"
else
    echo "汇总：语法 绿，整尺退码 $RED"
fi
exit "$RED"
