#!/usr/bin/env bash
# 在当前工作树中统一版本号，并收集尚未发布的更新日志。
set -euo pipefail
export LANG=en_US.UTF-8 LC_ALL=C
source "$(dirname "${BASH_SOURCE[0]}")/version.sh"

fail(){ printf '停止：%s\n' "$1" >&2; exit 1; }
say(){ printf '%s\n' "$1"; }
stop(){ fail "$2"; }
usage(){ echo 'Usage: set-version.sh VER [--date YYYY-MM-DD] [--allow-empty]'; }
VER=""; RELEASE_DATE=$(date +%F); ALLOW_EMPTY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --help) usage; exit 0;;
    --date)
      [ $# -ge 2 ] || fail '--date 缺少 YYYY-MM-DD'
      RELEASE_DATE="$2"; shift;;
    --allow-empty) ALLOW_EMPTY=1;;
    -*) fail "不认识参数 $1";;
    *) [ -z "$VER" ] || fail "多余的版本号 $1"; VER="$1";;
  esac
  shift
done
release_name_is_version "$VER" || fail '版本号须三段纯数字或 -beta.N（N 为正整数），如 6.0.1 或 6.0.1-beta.1'
[[ "$RELEASE_DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || fail '日期须为 YYYY-MM-DD'
awk -v value="$RELEASE_DATE" 'BEGIN {
  split(value, d, "-"); y=d[1]+0; m=d[2]+0; day=d[3]+0
  days=31
  if (m==4 || m==6 || m==9 || m==11) days=30
  if (m==2) days=28+(y%4==0 && (y%100!=0 || y%400==0))
  exit !(y>0 && m>=1 && m<=12 && day>=1 && day<=days)
}' || fail '日期不存在，请使用有效的 YYYY-MM-DD'
ROOT=$(git rev-parse --show-toplevel) || fail '当前目录不在 Git 工作树中'
cd "$ROOT"
[ -f CHANGELOG.md ] || fail '找不到 CHANGELOG.md'
if awk -v v="$VER" '/^## \[/ {h=$0; sub(/^## \[/, "", h); sub(/\].*$/, "", h); if (h==v) found=1} END {exit !found}' CHANGELOG.md; then
  fail "CHANGELOG.md 已有 ## [${VER}]，请勿重复合并"
fi
CURV=$(awk '/<parent>/{p=1} /<\/parent>/{p=0; next} !p && /<version>/{sub(/.*<version>/, ""); sub(/<\/version>.*/, ""); print; exit}' pom.xml)
release_name_is_version "$CURV" || fail "根 pom 版本号格式不合：${CURV}"
[ "$CURV" != "$VER" ] || fail "版本号 ${VER} 与现版本相同"
[ -z "$(git status --porcelain --untracked-files=all)" ] || fail '工作树不净，请先提交或清理改动'

fragments=()
if [ -d changelog.d ]; then
  while IFS= read -r -d '' fragment; do
    [ "$fragment" != changelog.d/README.md ] || continue
    category="${fragment#changelog.d/}"; category="${category%%/*}"
    case "$category" in
      added|changed|fixed|security) ;;
      *) fail "碎片目录不认识：${fragment}（只认 added、changed、fixed、security）";;
    esac
    [[ "$fragment" =~ ^changelog\.d/(added|changed|fixed|security)/[a-z]+(-[a-z]+)*\.md$ ]] || fail "碎片路径须为 changelog.d/<类>/<英文小写加连字符>.md：${fragment}"
    [ -f "$fragment" ] && [ ! -L "$fragment" ] || fail "碎片须为普通文件：${fragment}"
    LC_ALL=C grep -q '[^[:space:]]' "$fragment" || fail "碎片为空：${fragment}"
    fragments+=("$fragment")
  done < <(find changelog.d \( -type f -o -type l \) -print0)
fi
[ "${#fragments[@]}" -gt 0 ] || [ "$ALLOW_EMPTY" = 1 ] || fail 'changelog.d/ 没有碎片；确无变更时可给 --allow-empty'

WORK=$(mktemp -d "${TMPDIR:-/tmp}/novabot-version.XXXXXX") || fail '建不了临时目录'
trap 'rm -rf "$WORK"' EXIT
{
  printf '## [%s] - %s\n\n' "$VER" "$RELEASE_DATE"
  for category in added changed fixed security; do
    case "$category" in
      added) title=新增;; changed) title=变更;; fixed) title=修复;; security) title=安全;;
    esac
    first=1
    # 通配符按文件名排序，只读取上面已经检查过的碎片。
    for fragment in "changelog.d/$category/"*.md; do
      [ -f "$fragment" ] || continue
      if [ "$first" = 1 ]; then printf '### %s\n\n' "$title"; first=0; fi
      printf -- '- '
      cat "$fragment"
      [ -z "$(tail -c 1 "$fragment")" ] || printf '\n'
    done
    [ "$first" = 1 ] || printf '\n'
  done
  [ "${#fragments[@]}" -gt 0 ] || printf '本版无使用者可见的变更。\n\n'
} > "$WORK/section.md"
awk -v section="$WORK/section.md" '
  /^## \[/ && !inserted {while ((getline line < section) > 0) print line; close(section); inserted=1}
  {print}
  END {if (!inserted) {while ((getline line < section) > 0) print line; close(section)}}
' CHANGELOG.md > "$WORK/CHANGELOG.md"
# 与发布预检使用同一取法，先核对节头，再核对正文非空。
awk -v v="$VER" '/^## \[/ {h=$0; sub(/^## \[/, "", h); sub(/\].*$/, "", h); if (h==v) found=1} END {exit !found}' "$WORK/CHANGELOG.md" || fail '合并后找不到版本节'
awk -v v="$VER" '/^## \[/ {h=$0; sub(/^## \[/, "", h); sub(/\].*$/, "", h); f=(h==v); next} f' "$WORK/CHANGELOG.md" > "$WORK/notes.md"
[ -s "$WORK/notes.md" ] || fail '合并后的发行说明为空'
release_set_version "$CURV" "$VER" '改版本号' || fail '改版本号失败'
cat "$WORK/CHANGELOG.md" > CHANGELOG.md
if [ "${#fragments[@]}" -gt 0 ]; then
  for fragment in "${fragments[@]}"; do rm "$fragment"; done
fi
say "版本 ${CURV}→${VER}：${n} 件版本文件；合并 ${#fragments[@]} 条碎片，发行说明自核通过"
