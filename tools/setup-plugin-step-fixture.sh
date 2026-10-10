#!/usr/bin/env bash
# 向导插件步夹具：源码契约、步骤表插位、事实两态、放行与跳过的转交
#
# 九问各自 try/catch，末尾汇总红格数。由 build.sh 第 7 步在视图模型名单之后跑
# （--skip-tests 时不跑），也可单独跑。
#
# 退码：0 全绿；1 有问红；2 环境不具备（没装 node）。

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 2

if ! command -v node > /dev/null 2>&1; then
    echo "未找到 node，本尺跑不了（它量的是浏览器里那份逻辑）" >&2
    exit 2
fi

node tools/setup-plugin-step-fixture.mjs
exit $?
