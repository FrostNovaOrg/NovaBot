#!/usr/bin/env bash
# 推送 main 与版本标签；main 推送完成后查询 CI 和依赖漏洞读数。
set -u
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR="$SCRIPT_DIR/../.."
GH_REPO_CI=
PRE_PUSH_CHECK=
DEPS_COMMIT=
DEPS_ONLY=0
refs=()
usage() {
  cat <<'HELP'
用法：push.sh [--repo DIR] [--github-repo OWNER/NAME] [--pre-push-check CMD] [REF...]
默认推 main，只接受 main 和已存在的 v* 标签；树不净时拒绝推送。
--pre-push-check CMD：推送前运行 CMD <仓根>，成功才推送。
push.sh [--repo DIR] [--github-repo OWNER/NAME] --deps <提交>
--deps：只打印依赖漏洞读数，不推送；读数不影响 CI 退码。
退码：1 树不净或推送失败；2 参数错误；3 推前检查失败；main 的其余退码同 ci-status.sh。
HELP
}
while [ $# -gt 0 ]; do
  case "$1" in
    --help) usage; exit 0;;
    --repo|--github-repo|--pre-push-check|--deps)
      [ $# -ge 2 ] && [ -n "$2" ] || { usage; exit 2; }
      case "$1" in
        --repo) REPO_DIR=$2;;
        --github-repo) GH_REPO_CI=$2;;
        --pre-push-check) PRE_PUSH_CHECK=$2;;
        --deps) [ "$DEPS_ONLY" = 0 ] || { usage; exit 2; }; DEPS_ONLY=1; DEPS_COMMIT=$2;;
      esac
      shift 2;;
    -*) usage; exit 2;;
    *) refs+=("$1"); shift;;
  esac
done
if [ "$DEPS_ONLY" = 1 ] && { [ ${#refs[@]} -ne 0 ] || [ -n "$PRE_PUSH_CHECK" ]; }; then usage; exit 2; fi
# 默认使用脚本所在仓；覆盖路径也必须是 Git 仓。
R=$(git -C "$REPO_DIR" rev-parse --show-toplevel 2>/dev/null) || { echo "🛑 仓目录无效"; exit 2; }
if [ -z "$GH_REPO_CI" ]; then
  origin=$(git -C "$R" remote get-url origin 2>/dev/null) || { echo "🛑 读不到 origin 仓名，请用 --github-repo"; exit 2; }
  case "$origin" in
    *://*) origin=${origin#*://}; origin=${origin#*/};;
    *:*) origin=${origin#*:};;
    *) echo "🛑 推不出 GitHub 仓名，请用 --github-repo"; exit 2;;
  esac
  origin=${origin%/}; GH_REPO_CI=${origin%.git}
fi
[[ "$GH_REPO_CI" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { echo "🛑 GitHub 仓名要用 OWNER/NAME"; exit 2; }

dependency_status() {
  local full id tmp out rc m n p
  full=$(git -C "$R" rev-parse -q --verify "${1}^{commit}" 2>/dev/null) \
    || { echo "依赖漏洞：读不到／没扫全（主线仓里没有提交 ${1}）"; return 0; }
  # 使用该提交最新一趟已完成的 push 运行附件。
  id=$(gh run list -R "$GH_REPO_CI" --workflow ci.yml --commit "$full" --event push --status completed \
         --json databaseId --jq '.[0].databaseId // empty' 2>&1) \
    || { echo "依赖漏洞：读不到／没扫全（gh 查不通：$(printf '%s' "$id" | tail -n 1 | cut -c1-160)）"; return 0; }
  [ -n "$id" ] || { echo "依赖漏洞：读不到／没扫全（查不到提交 ${full:0:8} 跑完的 CI 运行）"; return 0; }
  tmp=$(mktemp -d) || { echo "依赖漏洞：读不到／没扫全（建不了临时目录）"; return 0; }
  # 复用该提交的汇总脚本与报告布局，保持和 CI 相同的条数及完整性检查。
  mkdir -p "$tmp/w"
  if ! git -C "$R" archive -o "$tmp/src.tar" "$full" .github pom.xml 2>/dev/null \
       || ! tar -x -f "$tmp/src.tar" -C "$tmp/w" 2>/dev/null \
       || [ ! -f "$tmp/w/.github/scripts/summarize-dependency-check.sh" ]; then
    echo "依赖漏洞：读不到／没扫全（提交 ${full:0:8} 里取不出 .github/scripts/summarize-dependency-check.sh）"
  elif ! gh run download "$id" -R "$GH_REPO_CI" -n dependency-check-reports -D "$tmp/r" > /dev/null 2>&1; then
    echo "依赖漏洞：读不到／没扫全（run ${id} 的附件 dependency-check-reports 取不到）"
  else
    cp -R "$tmp/r/." "$tmp/w/"
    out=$(cd "$tmp/w" && chmod +x .github/scripts/*.sh && env GITHUB_STEP_SUMMARY="$tmp/sum.md" SCAN_OUTCOME=success \
            NVD_API_KEY=x SCAN_SOURCES='本机复算' bash .github/scripts/summarize-dependency-check.sh 2>&1); rc=$?
    m=$(sed -n 's/^报告 \([0-9]*\) 份.*/\1/p' "$tmp/sum.md" 2>/dev/null | head -n 1)
    n=$(sed -n 's/.*发现 \([0-9]*\) 条 CVSSv3.*/\1/p' "$tmp/sum.md" 2>/dev/null | head -n 1)
    p=$(sed -n 's/.*报告有 \([0-9]*\) 项问题.*/\1/p' <<< "$out" | head -n 1)
    if [ "$rc" != 0 ]; then echo "依赖漏洞：读不到／没扫全（CI 汇总脚本退 ${rc}：$(printf '%s' "$out" | tail -n 1 | cut -c1-160)）"
    elif [ -n "$p" ] || grep -q '⚠️' "$tmp/sum.md"; then
      echo "依赖漏洞：读不到／没扫全（CI 汇总：报告有 ${p:-?} 项问题，$(sed -n 's/.*（\(可判 [0-9]*\/[0-9]* 份\)）.*/\1/p' "$tmp/sum.md" | head -n 1)；可判的里高危 ${n:-0} 条）"
    elif grep -q '✅ 本次未发现' "$tmp/sum.md"; then echo "依赖漏洞：高危 0 条（${m:-?} 份报告）"
    elif [ -n "$n" ]; then echo "依赖漏洞：高危 ${n} 条（${m:-?} 份报告）"
    else echo "依赖漏洞：读不到／没扫全（CI 汇总的结论读不出）"
    fi
  fi
  rm -rf "$tmp"; return 0
}
if [ "$DEPS_ONLY" = 1 ]; then dependency_status "$DEPS_COMMIT"; exit 0; fi
[ ${#refs[@]} -gt 0 ] || refs=(main)
for b in "${refs[@]}"; do
  case "$b" in
    main) ;;
    v*) git -C "$R" show-ref --verify --quiet "refs/tags/$b" || { echo "🛑 不认识的分支/标签 ${b}（只推 main／v* 标签）"; exit 2; };;
    *) echo "🛑 不认识的分支/标签 ${b}（只推 main／v* 标签）"; exit 2;;
  esac
done
[ -z "$(git -C "$R" status --short)" ] || { echo "🛑 主线树不净，不推（$(git -C "$R" status --short | wc -l | tr -d ' ') 件）"; exit 1; }
if [ -n "$PRE_PUSH_CHECK" ]; then
  # 仓根作为一个参数传入；命令的输出仅在检查失败时显示。
  out=$(bash -c "$PRE_PUSH_CHECK \"\$1\"" bash "$R" 2>&1); rc=$?
  if [ "$rc" != 0 ]; then printf '%s\n' "$out"; echo "🛑 推前检查失败（退 ${rc}）"; exit 3; fi
fi
# 明确指定引用，避免同名分支和标签混淆。
push_refs=()
for b in "${refs[@]}"; do
  case "$b" in main) push_refs+=(refs/heads/main:refs/heads/main);; *) push_refs+=("refs/tags/$b:refs/tags/$b");; esac
done
NOVABOT_RELEASE_PUSH=1 git -C "$R" push origin "${push_refs[@]}" || exit 1
echo "✅ 已推 ${refs[*]}（$(git -C "$R" rev-parse --short HEAD)）"
# 推送已成功，CI 的退码仍需报告；依赖读数只供查看。
for b in "${refs[@]}"; do
  if [ "$b" = main ]; then
    head=$(git -C "$R" rev-parse HEAD)
    "$SCRIPT_DIR/ci-status.sh" --repo "$R" --github-repo "$GH_REPO_CI" "$head"; rc=$?
    dependency_status "$head"
    exit "$rc"
  fi
done
exit 0
