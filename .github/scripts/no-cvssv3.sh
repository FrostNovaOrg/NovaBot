#!/usr/bin/env bash
#
# 列出**没有 CVSSv3 分数**、因而没有参与阈值判定的漏洞条目。
#
# 为什么要单列：只有 CVSSv2 的旧条目在上一个脚本里会被 select 掉。
# 悄悄掉队等于把「阈值没判到它」说成「它不高危」——这是同一个病的小号版本。
# 数量与 CVE 号必须报出来，让人自己决定要不要点进去看。
#
# 用法：no-cvssv3.sh <报告.json>
set -euo pipefail

report="${1:?用法: no-cvssv3.sh <报告.json>}"

jq -r '
  .dependencies[]? as $d
  | $d.vulnerabilities[]?
  | select(.cvssv3.baseScore == null)
  | [$d.fileName, .name]
  | @tsv
' "$report"
