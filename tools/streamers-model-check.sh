#!/usr/bin/env bash
# 主播页那两个前端模块的语法尺
#
# 这一页的行为判据不在这里——地址栏往返、状态四档、折线几何、人气峰三态、缺口分列
# 由 StreamersModelTest 拉起 streamers-model-fixture.mjs 逐格量，接在整盘里跑。
# 本尺补的是那一份量不到的另一半：**渲染那一份（streamers.js）的语法**。
# 它不碰得到夹具（满篇 DOM），而它是 main.js 直接 import 的——一个语法错会让
# 整个控制台加载不出来，屏幕上只剩一句与出错文件无关的「载入失败」。
#
# 🔴 用 `node --input-type=module --check < 文件` 而不是 `node --check 文件`：
#    后者对含 import 的 .js 一律返 0（Node v22 实测），也就是说那一格从来没能红过——
#    一把量不动却报绿的判据，比没有这把判据更糟。本尺自带阴性对照：
#    把一段必定语法错的模块喂进去，它必须红；不红就说明这一格又量不动了，整尺判红。
#
# 别处已经在量的不在这里重量：main.js、overview.js、core.js、home-model.js
# 由 home-model-check.sh 一把过（每份 js 恰一把尺，重复量不添判力）。
#
# 退码：0 全过；1 有模块语法不过，或阴性对照不红；2 环境不具备（没装 node）。

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
# 放在语法检查之前：这几行要是恒绿，下面那一串「语法 绿」一个字也不作数
if printf 'import {a} from "./x.js";\nconst b = ;;;\n' | node --input-type=module --check > /dev/null 2>&1; then
    echo "阴性对照 红：一段必定语法错的模块被判成了过，这一格量不动" >&2
    RED=1
else
    echo "阴性对照 绿（必错的模块确实被判红）"
fi

# —— 语法 ——
# 逐个跑而不是一次传多个：一次传一串时，后面那些是「查过了」还是「没轮到」分不出来
for f in "$UI"/streamers-model.js "$UI"/streamers.js; do
    if node --input-type=module --check < "$f" > /dev/null 2>&1; then
        echo "语法 绿 $f"
    else
        echo "语法 红 $f"
        node --input-type=module --check < "$f"
        RED=1
    fi
done

exit "$RED"
