#!/usr/bin/env bash
# 模板编辑器视图模型尺：语法 + 至少三档对照
#
# 一整串模板与一张张卡之间的换算、调色板上摆哪些块，全是纯函数
# （config-ui/template-model.js，不碰 DOM）。本尺喂它几份模板对答案，
# 并顺带把这一页那几个前端模块过一遍语法。
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

UI="starbot-core/src/main/resources/config-ui"
RED=0

# —— 阴性对照：这一格自己得先证明它分得出红绿 ——
if printf 'import {a} from "./x.js";\nconst b = ;;;\n' | node --input-type=module --check > /dev/null 2>&1; then
    echo "阴性对照 红：一段必定语法错的模块被判成了过，这一格量不动" >&2
    RED=1
else
    echo "阴性对照 绿（必错的模块确实被判红）"
fi

# —— 语法 ——
for f in "$UI"/template-model.js "$UI"/template.js; do
    if node --input-type=module --check < "$f" > /dev/null 2>&1; then
        echo "语法 绿 $f"
    else
        echo "语法 红 $f"
        node --input-type=module --check < "$f"
        RED=1
    fi
done

# —— 各档 ——
node tools/template-model-check.mjs
if [ $? -ne 0 ]; then
    RED=1
fi

exit "$RED"
