#!/usr/bin/env bash
# 连接页视图模型十二档尺：喂十二份接口回包，逐档核对三张卡该长成什么样
#
# 依据：5.1 连接页三卡的设计与页面原型。
# 九档是「登录态 × 机器人态」的全组合，另三档补显隐与外部面板卡的空态——
# 这十二种在真机上凑齐一次的代价极高：掉登录要等凭据过期，机器人掉线要去把 OneBot 实现停掉，
# 未配置那一档只在刚装好的机器上出现一次。视图模型因此被切成纯函数
# （config-ui/links-model.js，不碰 DOM），本尺喂它十二份回包对答案。
#
# 顺带把连接页那几个前端模块过一遍语法（node --input-type=module --check < 文件）：
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
for f in "$UI"/links-model.js "$UI"/links.js "$UI"/tokens.js "$UI"/bot.js "$UI"/main.js; do
    if node --input-type=module --check < "$f"; then
        echo "语法 绿 $f"
    else
        echo "语法 红 $f"
        RED=1
    fi
done

# —— 十二档 ——
# 退码单独读：写成管道时 $? 读到的是管道末端那个命令的退码，与被测无关
node tools/links-model-check.mjs
if [ $? -ne 0 ]; then
    RED=1
fi

exit "$RED"
