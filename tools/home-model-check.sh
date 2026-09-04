#!/usr/bin/env bash
# 首页视图模型八档尺：喂八份接口回包，逐档核对首页该长成什么样
#
# 依据：5.1 场景清单第一节的八档与首页原型。
# 八档在真机上凑齐一次的代价极高（要么等故障发生，要么去改线上配置），
# 而首页恰恰是「出事时第一眼看的那一页」——它在故障档下长什么样，正是最该被守住的部分。
# 视图模型因此被切成纯函数（config-ui/home-model.js，不碰 DOM），本尺喂它八份回包对答案。
#
# 顺带把首页那几个前端模块过一遍语法（node --input-type=module --check < 文件）：
# 它们是 ES module，没有构建步骤，语法错要等页面加载时才炸，而那时报的是一句与出错文件无关的「载入失败」。
# node --check 对含 import 的 .js 一律返 0（Node v22 实测），那一格从来没能红过。
#
# 退码：0 全对；1 有档对不上或有模块语法不过；2 环境不具备（没装 node）。

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 2

if ! command -v node > /dev/null 2>&1; then
    echo "未找到 node，本尺跑不了（它量的是浏览器里那份逻辑）" >&2
    exit 2
fi

UI="starbot-core/src/main/resources/config-ui"
RED=0

# —— 语法 ——
# 逐个跑而不是一次传多个文件：一次传一串时后面那些是「查过了」还是「没轮到」分不出来
for f in "$UI"/home-model.js "$UI"/overview.js "$UI"/main.js "$UI"/core.js; do
    if node --input-type=module --check < "$f"; then
        echo "语法 绿 $f"
    else
        echo "语法 红 $f"
        RED=1
    fi
done

# —— 八档 ——
# 退码单独读：写成管道时 $? 读到的是管道末端那个命令的退码，与被测无关
node tools/home-model-check.mjs
if [ $? -ne 0 ]; then
    RED=1
fi

exit "$RED"
