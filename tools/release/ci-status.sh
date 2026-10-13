#!/usr/bin/env bash
# 汇总指定提交的 CI push 运行，失败时只显示 job、步骤与测试类名。
set -u
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR="$SCRIPT_DIR/../.."
GH_REPO_CI=
NOWAIT_CI=0
CI_POLL=30; CI_MAX=1800; CI_APPEAR=180
usage() {
  cat <<'HELP'
用法：ci-status.sh [--repo DIR] [--github-repo OWNER/NAME] <提交> [--no-wait]
查询 ci.yml 对该提交的 push 运行；默认每 30 秒查询，最多等 30 分钟。
--no-wait：运行未结束时立即返回。
退码：0 绿；4 红；5 查不到；6 等待超时；7 gh 查询失败；8 还在跑；2 参数错误。
HELP
}
want=
while [ $# -gt 0 ]; do
  case "$1" in
    --help) usage; exit 0;;
    --repo|--github-repo)
      [ $# -ge 2 ] && [ -n "$2" ] || { usage; exit 2; }
      case "$1" in --repo) REPO_DIR=$2;; --github-repo) GH_REPO_CI=$2;; esac
      shift 2;;
    --no-wait) NOWAIT_CI=1; shift;;
    -*) usage; exit 2;;
    *) [ -z "$want" ] && [ -n "$1" ] || { usage; exit 2; }; want=$1; shift;;
  esac
done
[ -n "$want" ] || { usage; exit 2; }
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

ci_status() {
  local want full t0 out id
  want=$1
  full=$(git -C "$R" rev-parse -q --verify "${want}^{commit}" 2>/dev/null) || full=$want
  t0=$(date +%s)
  while :; do
    out=$(gh run list -R "$GH_REPO_CI" --workflow ci.yml --commit "$full" --event push \
            --json databaseId,status,conclusion,createdAt,updatedAt --limit 20 2>&1) \
      || { echo "🛑 gh 查不通（gh run list 退非 0）：$(printf '%s' "$out" | tail -n 1 | cut -c1-200)"; return 7; }
    # 任一已完成运行非 success 就失败；否则等待全部运行成功。
    out=$(printf '%s' "$out" | python3 -c '
import json, sys
from datetime import datetime
try:
    runs = json.load(sys.stdin)
except ValueError:
    print("坏"); sys.exit()
def seconds(r):
    try:
        f = lambda s: datetime.fromisoformat(s.replace("Z", "+00:00"))
        return int((f(r["updatedAt"]) - f(r["createdAt"])).total_seconds())
    except (KeyError, ValueError, AttributeError):
        return -1
failed = [r for r in runs if r.get("status") == "completed" and r.get("conclusion") != "success"]
running = [r for r in runs if r.get("status") != "completed"]
if failed:
    r = failed[0]; print("红 %s %s %d" % (r["databaseId"], r.get("conclusion") or "?", seconds(r)))
elif running:
    r = running[0]; print("跑 %s %s %d" % (r["databaseId"], r.get("status") or "?", seconds(r)))
elif runs:
    r = runs[0]; print("绿 %s success %d" % (r["databaseId"], seconds(r)))
else:
    print("无")')
    set -- $out
    case "${1:-坏}" in
      绿) if [ "$4" -ge 0 ]; then t0="$(( $4 / 60 )) 分 $(( $4 % 60 )) 秒"; else t0="?"; fi
          echo "✅ CI 绿：run ${2}，结论 ${3}，用时 ${t0}（提交 ${full:0:8}）"; return 0;;
      红) id=$2
          echo "🛑 CI 红：run ${id}，结论 ${3}（提交 ${full:0:8}）"
          # 只印失败 job 与步骤的名字。
          gh run view "$id" -R "$GH_REPO_CI" --json jobs 2>/dev/null | python3 -c '
import json, sys
try:
    jobs = json.load(sys.stdin).get("jobs", [])
except ValueError:
    jobs = None
if jobs is None:
    print("  失败 job／步骤：取不到")
for j in jobs or []:
    if j.get("conclusion") not in ("success", "skipped", None):
        steps = [s.get("name", "?") for s in j.get("steps", []) if s.get("conclusion") == "failure"]
        print("  失败 job：%s（%s）步骤：%s" % (j.get("name", "?"), j.get("conclusion"), "、".join(steps) or "—"))'
          # 只取失败测试类名，不印断言与日志正文。
          if out=$(gh run view "$id" -R "$GH_REPO_CI" --log-failed 2>/dev/null); then
            out=$(printf '%s\n' "$out" | grep -F '<<< FAILURE! -- in ' | sed 's/.*<<< FAILURE! -- in //' | sort -u)
            if [ -n "$out" ]; then printf '%s\n' "$out" | sed 's/^/  失败测试类：/'; else echo "  失败测试类：日志里无 <<< FAILURE! -- in 行（非测试步红）"; fi
          else echo "  失败测试类：取不到（gh run view --log-failed 退非 0）"; fi
          return 4;;
      跑) if [ "$NOWAIT_CI" = 1 ]; then echo "⏳ CI 还在跑：run ${2}（${3}），不等（提交 ${full:0:8}）"; return 8; fi
          echo "⏳ CI 还在跑：run ${2}（${3}），${CI_POLL} 秒后再查";;
      无) if [ "$NOWAIT_CI" = 1 ] || [ $(( $(date +%s) - t0 )) -ge "$CI_APPEAR" ]; then
            echo "🛑 查不到提交 ${full:0:8} 的 CI push 运行（${GH_REPO_CI} 工作流 ci.yml；没推上去，或推的不是它）"; return 5
          fi
          echo "⏳ 还查不到提交 ${full:0:8} 的 CI 运行（刚推的要等登记），${CI_POLL} 秒后再查";;
      *) echo "🛑 gh 查不通（gh run list 回的不是 JSON）"; return 7;;
    esac
    [ $(( $(date +%s) - t0 )) -lt "$CI_MAX" ] || { echo "🛑 等满 $(( CI_MAX / 60 )) 分 CI 仍没出结论（提交 ${full:0:8}）"; return 6; }
    sleep "$CI_POLL"
  done
}
ci_status "$want"
exit $?
