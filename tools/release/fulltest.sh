#!/usr/bin/env bash
# 在指定构建机启动后台命令，用短连接轮询结果；连接失败时继续等待。
set -u
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR="$SCRIPT_DIR/../.."
HOST=; REMOTE_REPO=; RUNS_DIR=; REMOTE_CMD=; RUN_AS=
BRANCH=fulltest; MAX_MINUTES=85; want=
usage() {
  cat <<'HELP'
用法：fulltest.sh [--repo DIR] --host H --remote-repo PATH --runs-dir DIR --remote-cmd CMD
                   [--run-as USER] [--branch NAME] [--max-minutes N] <提交>
推送指定提交，在远端后台运行 CMD；{commit} 会替换为完整提交号。
--run-as：指定接收推送的账户；--branch 默认 fulltest；--max-minutes 默认 85。
每 30 秒查询日志，每 10 分钟打印进度；日志末行 remote-exit=<退码>。
退码：0 远端成功；9 远端命令失败；1 推送、起跑失败或超时；2 参数错误。
HELP
}
while [ $# -gt 0 ]; do
  case "$1" in
    --help) usage; exit 0;;
    --repo|--host|--remote-repo|--runs-dir|--remote-cmd|--run-as|--branch|--max-minutes)
      [ $# -ge 2 ] && [ -n "$2" ] || { usage; exit 2; }
      case "$1" in
        --repo) REPO_DIR=$2;; --host) HOST=$2;; --remote-repo) REMOTE_REPO=$2;;
        --runs-dir) RUNS_DIR=$2;; --remote-cmd) REMOTE_CMD=$2;; --run-as) RUN_AS=$2;;
        --branch) BRANCH=$2;; --max-minutes) MAX_MINUTES=$2;;
      esac
      shift 2;;
    -*) usage; exit 2;;
    *) [ -z "$want" ] && [ -n "$1" ] || { usage; exit 2; }; want=$1; shift;;
  esac
done
[ -n "$HOST" ] && [ -n "$REMOTE_REPO" ] && [ -n "$RUNS_DIR" ] && [ -n "$REMOTE_CMD" ] && [ -n "$want" ] || { usage; exit 2; }
[[ "$HOST" =~ ^[A-Za-z0-9_][A-Za-z0-9_.@:-]*$ ]] || { usage; exit 2; }
[[ "$MAX_MINUTES" =~ ^[1-9][0-9]{0,6}$ ]] || { usage; exit 2; }
if [ -n "$RUN_AS" ]; then [[ "$RUN_AS" =~ ^[A-Za-z_][A-Za-z0-9_-]*$ ]] || { usage; exit 2; }; fi
git check-ref-format "refs/heads/$BRANCH" >/dev/null 2>&1 || { usage; exit 2; }
R=$(git -C "$REPO_DIR" rev-parse --show-toplevel 2>/dev/null) || { echo "🛑 仓目录无效"; exit 2; }
full=$(git -C "$R" rev-parse -q --verify "${want}^{commit}") || { echo "🛑 主线仓里没有提交 ${want}"; exit 2; }
push_args=(push -q)
if [ -n "$RUN_AS" ]; then push_args+=("--receive-pack=runuser -u $RUN_AS -- git-receive-pack"); fi
NOVABOT_RELEASE_PUSH=1 git -C "$R" "${push_args[@]}" \
  "$HOST:$REMOTE_REPO" "+${full}:refs/heads/$BRANCH" || { echo "🛑 推到 ${HOST} 失败"; exit 1; }
echo "已推 ${full:0:8} 到 ${HOST} 完整构建机（${BRANCH} 分支），起跑指定命令"
FT_POLL=30; FT_MAX=$(( 10#$MAX_MINUTES * 60 )); FT_TICK=600
runlog="$RUNS_DIR/${full}-$(date +%Y%m%d%H%M%S).log"
# 参数按远端 shell 的单引号规则引用，保留空格和引号。
shell_quote() {
  printf "'"
  printf '%s' "$1" | sed "s/'/'\\\\''/g"
  printf "'"
}
remote_cmd=${REMOTE_CMD//\{commit\}/$full}
# 子 shell 保证命令使用 exit 时也会记录最终退码。
runner="(
$remote_cmd
); r=\$?; echo; echo remote-exit=\$r"
ssh -n -o ConnectTimeout=20 "$HOST" \
  "mkdir -p $(shell_quote "$RUNS_DIR") && { nohup sh -c $(shell_quote "$runner") > $(shell_quote "$runlog") 2>&1 < /dev/null & }" \
  || { echo "🛑 在 ${HOST} 上起跑失败（ssh 退非 0）"; exit 1; }
echo "${HOST} 上已在后台跑，结果写 ${HOST}:${runlog}；每 ${FT_POLL} 秒短连接读一次，最多等 $(( FT_MAX / 60 )) 分"
t0=$(date +%s); tick=$FT_TICK
while :; do
  sleep "$FT_POLL"
  # 断连或文件尚未出现都不算结果，下一次继续读末 20 行。
  if out=$(ssh -n -o ConnectTimeout=20 -o ServerAliveInterval=10 "$HOST" "tail -n 20 $(shell_quote "$runlog")" 2>/dev/null); then
    rc=${out##*$'\n'}
    case "$rc" in remote-exit=*) rc=${rc#remote-exit=}; [[ "$rc" =~ ^[0-9]+$ ]] && break;; esac
  fi
  w=$(( $(date +%s) - t0 ))
  [ "$w" -lt "$FT_MAX" ] || { echo "🛑 等满 $(( FT_MAX / 60 )) 分仍没读到结果（提交 ${full:0:8}）：${HOST} 上那次可能还在跑，结果文件 ${HOST}:${runlog}"; exit 1; }
  [ "$w" -lt "$tick" ] || { echo "⏳ 还在跑，已等 $(( w / 60 )) 分"; tick=$(( tick + FT_TICK )); }
done
printf '%s\n' "$out"
[ "$rc" = 0 ] || { echo "🛑 ${HOST} 完整构建没过（远端命令退 ${rc}，提交 ${full:0:8}）"; exit 9; }
echo "✅ ${HOST} 完整构建过（提交 ${full:0:8}）"; exit 0
