#!/usr/bin/env bash
# 首页「今日」卡那两个前端模块的语法 + 判定各档
#
# 这张卡（三个数与推送总开关）住在控制台插件里。行为判据由 today-model-check.mjs 逐格量：
# 三个数格该写什么、「—」与 0 分不分得开、不限额那一档画不画分母、明细怎么排怎么截、
# 群名前面加不加平台前缀。它们全是纯函数，在真机上凑齐一次的代价极高——
# 要点出「不限额不画分母」得先去线上把配额上限改成 0。
#
# 顺带把这一卡的渲染那一份（today.js）过一遍语法：它碰不到夹具（满篇 DOM），
# 而它是控制台按注册清单装上来的——一个语法错会让这张卡载入失败，
# 屏幕上只剩一句与出错文件无关的报错。
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

PAGES="plugins/starbot-novabot-console/src/main/resources/config-ui-pages"
RED=0
SYNTAX_RED=0

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
for f in "$PAGES"/today-model.js "$PAGES"/today.js; do
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
# 退码单独读：写成管道时 $? 读到的是管道末端那个命令的退码，与被测无关
node tools/today-model-check.mjs
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
