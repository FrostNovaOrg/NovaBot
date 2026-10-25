#!/usr/bin/env bash
# 小结样稿页的语法检查；页面行为由 RecapPreviewTest 的前端夹具验证。
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 2

if ! command -v node > /dev/null 2>&1; then
    echo "未找到 node，小结样稿页语法检查无法运行" >&2
    exit 2
fi

PAGES="plugins/nova-console/src/main/resources/config-ui-pages"
RED=0
SYNTAX_RED=0

# 阴性对照必须识别出错误语法，才能相信下面的检查结果。
if printf 'import {a} from "./x.js";\nconst b = ;;;\n' | node --input-type=module --check > /dev/null 2>&1; then
    echo "阴性对照 红：错误语法没有被识别" >&2
    RED=1
else
    echo "阴性对照 绿（必错的模块确实被判红）"
fi

for f in "$PAGES"/recap-preview.js; do
    if node --input-type=module --check < "$f" > /dev/null 2>&1; then
        echo "语法 绿 $f"
    else
        echo "语法 红 $f"
        node --input-type=module --check < "$f"
        RED=1
        SYNTAX_RED=1
    fi
done

if [ "$SYNTAX_RED" -ne 0 ]; then
    echo "汇总：语法 红（名单见上方「语法 红」行），整尺退码 $RED"
else
    echo "汇总：语法 绿，整尺退码 $RED"
fi
exit "$RED"
