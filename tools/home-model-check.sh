#!/usr/bin/env bash
# 首页视图模型尺：喂九份接口回包，逐档核对首页该长成什么样
#
# 依据：5.1 场景清单第一节的八档与首页原型；第九档（有新版）随新版提示功能补入。
# 八档在真机上凑齐一次的代价极高（要么等故障发生，要么去改线上配置），
# 而首页恰恰是「出事时第一眼看的那一页」——它在故障档下长什么样，正是最该被守住的部分。
# 视图模型因此被切成纯函数（config-ui/home-model.js，不碰 DOM），本尺喂它九份回包对答案。
#
# 顺带把首页与装配底座的前端模块过一遍语法（node --input-type=module --check < 文件）：
# 它们是 ES module，没有构建步骤，语法错要等页面加载时才炸，而那时报的是一句与出错文件无关的「载入失败」。
# main/core/store 三件跨页底座没有同名尺，就近归这把量；config-ui 下每份 js 恰由一把尺过语法，
# 别在两把尺里重量同一份——重复不添判力，只添两边清单不同步的空当。
# 走 stdin 加 --input-type=module 之后坏语法会红（Node 22 实测）；直接 `node --check 文件`
# 对含 import 的 .js 仍一律返 0，那种写法本尺不用。
#
# 🔴 本尺自带阴性对照：把一段必定语法错的模块喂进同一道检查，它必须红；
#    不红就说明这一格又量不动了（整把尺恒绿），整尺判红。
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
# 放在语法检查之前：这几行要是恒绿，下面那一串「语法 绿」一个字也不作数
if printf 'import {a} from "./x.js";\nconst b = ;;;\n' | node --input-type=module --check > /dev/null 2>&1; then
    echo "阴性对照 红：一段必定语法错的模块被判成了过，这一格量不动" >&2
    RED=1
else
    echo "阴性对照 绿（必错的模块确实被判红）"
fi

# —— 语法 ——
# 逐个跑而不是一次传多个文件：node --check 只报第一个出错的，
# 一次传一串时后面那些是「查过了」还是「没轮到」分不出来。
# 必须走 stdin 加 --input-type=module：node --check 对 .js 文件按 CommonJS 解析，
# 撞上 import/export 会静默放过，整把尺对这些模块文件恒绿——实测 Node 22，
# 同一段坏语法 .mjs 红、.js 绿。stdin 形态强制按模块解析，尺才作数
for f in "$UI"/home-model.js "$UI"/overview.js "$UI"/main.js "$UI"/core.js \
         "$UI"/store.js; do
    if node --input-type=module --check < "$f"; then
        echo "语法 绿 $f"
    else
        echo "语法 红 $f"
        RED=1
        SYNTAX_RED=1
    fi
done

# —— 九档 ——
# 退码单独读：写成管道时 $? 读到的是管道末端那个命令的退码，与被测无关
node tools/home-model-check.mjs
if [ $? -ne 0 ]; then
    RED=1
fi

# —— 末行汇总 ——
# 档尺（.mjs）的末句只数它自己的格，语法红盖不进去：语法红而档全对时，
# 整把尺的最后一句会是「跑了 N 格，全绿」，读起来像全绿。末行由本尺自己收
if [ "$SYNTAX_RED" -ne 0 ]; then
    echo "汇总：语法 红（名单见上方「语法 红」行），整尺退码 $RED"
else
    echo "汇总：语法 绿，整尺退码 $RED"
fi
exit "$RED"
