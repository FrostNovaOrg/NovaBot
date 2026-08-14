#!/usr/bin/env bash
#
# 从 dependency-check 的 JSON 报告里挑出分数达到阈值的漏洞条目。
#
# 为什么不再解析 CSV：上一版用 `awk -F',' '$NF+0 >= 7'` 取最后一列当分数，
# 而 dependency-check 的 CSV 最后一列是 **Notes**（几乎总是空串），分数在
# CVSSv3_BaseScore 那一列。于是条件恒不成立，job summary 永远打印
# 「✅ 未发现 CVSS ≥ 7 的条目」——**配置在撒谎，而且撒的是让人安心的那种谎**。
# 就算列序猜对了也不行：`-F','` 不处理引号内的逗号，而描述字段几乎必然含逗号。
#
# 字段路径取自上游报告模板（core/src/main/resources/templates/jsonReport.vsl）：
#   dependencies[].fileName
#   dependencies[].vulnerabilities[].name
#   dependencies[].vulnerabilities[].cvssv3.baseScore
#
# 用法：high-cvss.sh <报告.json> [阈值，默认 7]
# 输出：每行 `<依赖文件>\t<CVE>\t<分数>`；无命中时无输出、退出码仍为 0
set -euo pipefail

report="${1:?用法: high-cvss.sh <报告.json> [阈值]}"
threshold="${2:-7}"

jq -r --argjson t "$threshold" '
  .dependencies[]? as $d
  | $d.vulnerabilities[]?
  | select(.cvssv3.baseScore != null and .cvssv3.baseScore >= $t)
  | [$d.fileName, .name, (.cvssv3.baseScore | tostring)]
  | @tsv
' "$report"
