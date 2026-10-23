#!/usr/bin/env bash
# 汇总指定提交的 CI push 运行，失败时只显示 job、步骤与测试类名。
set -u
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR="$SCRIPT_DIR/../.."
GH_REPO_CI=
NOWAIT_CI=0
CI_POLL=30; CI_MAX=1800; CI_APPEAR=180
# --no-wait 时两种查法都回空，隔 CI_EMPTY_WAIT 秒再查，最多再查 CI_EMPTY_TRIES 轮。
CI_EMPTY_WAIT=10; CI_EMPTY_TRIES=2
usage() {
  cat <<'HELP'
用法：ci-status.sh [--repo DIR] [--github-repo OWNER/NAME] <提交> [--no-wait]
查询 ci.yml 对该提交的 push 运行；默认每 30 秒查询，最多等 30 分钟。
有两种查法：先按工作流查，回空列表时再按提交查；两种都查不到会重查。
--no-wait：运行未结束时立即返回；查不到时隔 10 秒再查，最多再查 2 轮。
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

# 任一已完成运行非 success 就失败；否则等待全部运行成功。
# 参数 list：读 gh run list 的输出。参数 api：读按提交查的 workflow_runs，
# 只留 ci.yml 的 push 运行（推 main 时别的工作流也会跑），换成 gh run list 的字段再判。
JUDGE_PY='
import json, sys
from datetime import datetime
try:
    runs = json.load(sys.stdin)
except ValueError:
    print("坏"); sys.exit()
if sys.argv[1] == "api":
    if not isinstance(runs, dict) or not isinstance(runs.get("workflow_runs"), list):
        print("坏"); sys.exit()
    runs = [{"databaseId": r.get("id"), "status": r.get("status"), "conclusion": r.get("conclusion"),
             "createdAt": r.get("created_at"), "updatedAt": r.get("updated_at")}
            for r in runs["workflow_runs"]
            if r.get("path") == ".github/workflows/ci.yml" and r.get("event") == "push"]
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
    print("无")'

ci_status() {
  local want full t0 out alt id via seen=0 empty=0
  want=$1
  full=$(git -C "$R" rev-parse -q --verify "${want}^{commit}" 2>/dev/null) || full=$want
  t0=$(date +%s)
  while :; do
    out=$(gh run list -R "$GH_REPO_CI" --workflow ci.yml --commit "$full" --event push \
            --json databaseId,status,conclusion,createdAt,updatedAt --limit 20 2>&1) \
      || { echo "🛑 gh 查不通（gh run list 退非 0）：$(printf '%s' "$out" | tail -n 1 | cut -c1-200)"; return 7; }
    out=$(printf '%s' "$out" | python3 -c "$JUDGE_PY" list)
    via=
    # GitHub 偶发按工作流查回空列表（HTTP 200、总数 0，不是限流），同一时刻按提交查仍查得到。
    # 按提交查失败或回的不是 JSON，这一轮照「无」算；退 7 只看上面按工作流那一查。
    if [ "$out" = 无 ] \
       && alt=$(gh api "repos/$GH_REPO_CI/actions/runs?head_sha=$full&event=push&per_page=50" 2>/dev/null); then
      alt=$(printf '%s' "$alt" | python3 -c "$JUDGE_PY" api)
      case "$alt" in 绿\ *|红\ *|跑\ *) out=$alt; via=1;; esac
    fi
    set -- $out
    if [ -n "$via" ]; then echo "ℹ️ 按工作流查回了空列表，这一轮是按提交查到的（GitHub 偶发如此）"; fi
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
          seen=1
          echo "⏳ CI 还在跑：run ${2}（${3}），${CI_POLL} 秒后再查";;
      无) if [ "$NOWAIT_CI" = 1 ]; then
            if [ "$empty" -lt "$CI_EMPTY_TRIES" ]; then
              empty=$(( empty + 1 ))
              echo "⏳ 两种查法都没查到提交 ${full:0:8} 的 CI 运行，${CI_EMPTY_WAIT} 秒后重查（第 ${empty}/${CI_EMPTY_TRIES} 次）"
              sleep "$CI_EMPTY_WAIT"; continue
            fi
            echo "🛑 查不到提交 ${full:0:8} 的 CI push 运行（${GH_REPO_CI} 工作流 ci.yml，两种查法共查 $(( CI_EMPTY_TRIES + 1 )) 轮；没推上去，或推的不是它）"; return 5
          fi
          # 本次已查到过这个提交的运行，之后的回空只当查询抖动，一直等到 CI_MAX。
          if [ "$seen" = 1 ]; then
            echo "⏳ 刚才还查得到提交 ${full:0:8} 的 CI 运行，这回两种查法都没回（当查询抖动），${CI_POLL} 秒后再查"
          elif [ $(( $(date +%s) - t0 )) -ge "$CI_APPEAR" ]; then
            echo "🛑 查不到提交 ${full:0:8} 的 CI push 运行（${GH_REPO_CI} 工作流 ci.yml，两种查法都查过；没推上去，或推的不是它）"; return 5
          else
            echo "⏳ 还查不到提交 ${full:0:8} 的 CI 运行（刚推的要等登记），${CI_POLL} 秒后再查"
          fi;;
      *) echo "🛑 gh 查不通（gh run list 回的不是 JSON）"; return 7;;
    esac
    [ $(( $(date +%s) - t0 )) -lt "$CI_MAX" ] || { echo "🛑 等满 $(( CI_MAX / 60 )) 分 CI 仍没出结论（提交 ${full:0:8}）"; return 6; }
    sleep "$CI_POLL"
  done
}
ci_status "$want"
exit $?
