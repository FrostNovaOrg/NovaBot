#!/usr/bin/env bash
# 从干净标签出包，再发布；部署时按远端现状选择首升或换版。
# 本机只打包，不运行测试或起动冒烟。沙盘也实际打包，远端动作只打印。
set -u
export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
export NOVABOT_RELEASE_PUSH=1

usage(){
  cat <<'EOF'
Usage: release.sh [VERSION|auto] [OPTIONS]

发布与部署 NovaBot；VERSION 缺省取根 pom 的三段数字版本。
部署必须给出 --host、--app-dir、--service、--owner；没有默认机器。
只发布到 GitHub、不部署时，使用 --publish-only。

  --repo DIR             覆盖脚本所在仓的根目录
  --host HOST            本机 SSH 配置中的主机别名
  --app-dir DIR          远端应用目录（绝对路径）
  --service NAME         首升前的旧服务名
  --owner USER:GROUP     应用属主（install.sh 使用 USER）
  --require-room         核验必须至少连接一间直播间（缺省只报数）
  --no-wait-window       跳过空闲窗口等待
  --target-file FILE     按行读取 HOST、APP_DIR、SERVICE、OWNER、
                         REQUIRE_ROOM、NO_WAIT_WINDOW 的 KEY=VALUE；
                         两个开关使用 0 或 1，命令行优先
  --publish-only         完成 publish 步骤后结束
  --trial                在临时克隆里改版本、出包；不推标签、不建发布页
  --commit SHA           指定试装提交；只许和 --trial 一起
  --dry-run              在临时克隆里真打包，推送、gh、SSH 只打印
  --dry-run-route ROUTE  模拟 first、hot 或 restart（缺省 first）
  --from STEP            从指定步骤接手（缺省 preflight）
  --wait-max MINUTES     等窗上限，缺省 360 分钟
  --notes-check CMD      预检运行 CMD <发行说明文件>，退 0 才继续
  --help                 显示帮助

步骤：preflight tag package push-tag publish upload install wait deploy verify
auto 只许试装：读取 --host 上的 novabot@ 实例，在 X.Y.90 与 X.Y.91 间轮换。
试装不能从 upload 接手：需重传时从 package 开始，包已在远端时从 install 开始。
临时目录：${RELEASE_WORKDIR:-${TMPDIR:-/tmp}/novabot-release}
EOF
}
arg_stop(){ echo "🛑 停在：参数（$1）"; exit 2; }
need_value(){ [ "$#" -ge 2 ] && [ -n "$2" ] && [[ "$2" != --* ]] || arg_stop "$1 缺少值"; }
VER=""; REPO=""; SANDBOX=0; FROM=preflight; WAIT_MAX=360; NOWAIT=0
TRIAL=0; SB_ROUTE=""; COMMIT=""; PUBLISH_ONLY=0; NOTES_CHECK=""; TARGET_FILE=""
HOST=""; APP=""; UNIT=""; OWNER=""; NEED_ROOM=0
HOST_SET=0; APP_SET=0; UNIT_SET=0; OWNER_SET=0; ROOM_SET=0; NOWAIT_SET=0
while [ $# -gt 0 ]; do
  case "$1" in
    --help) usage; exit 0;;
    --repo) need_value "$@"; REPO="$2"; shift;;
    --host) need_value "$@"; HOST="$2"; HOST_SET=1; shift;;
    --app-dir) need_value "$@"; APP="$2"; APP_SET=1; shift;;
    --service) need_value "$@"; UNIT="$2"; UNIT_SET=1; shift;;
    --owner) need_value "$@"; OWNER="$2"; OWNER_SET=1; shift;;
    --require-room) NEED_ROOM=1; ROOM_SET=1;;
    --no-wait-window) NOWAIT=1; NOWAIT_SET=1;;
    --target-file) need_value "$@"; TARGET_FILE="$2"; shift;;
    --publish-only) PUBLISH_ONLY=1;;
    --dry-run) SANDBOX=1;;
    --dry-run-route) need_value "$@"; SB_ROUTE="$2"; shift;;
    --trial) TRIAL=1;;
    --commit) need_value "$@"; COMMIT="$2"; shift;;
    --from) need_value "$@"; FROM="$2"; shift;;
    --wait-max) need_value "$@"; WAIT_MAX="$2"; shift;;
    --notes-check) need_value "$@"; NOTES_CHECK="$2"; shift;;
    -*) arg_stop "不认识 $1";;
    *) [ -z "$VER" ] || arg_stop "多余的位置参数 $1"; VER="$1";;
  esac
  shift
done
if [ -n "$TARGET_FILE" ]; then
  [ -f "$TARGET_FILE" ] && [ -r "$TARGET_FILE" ] || arg_stop "读不到 ${TARGET_FILE}"
  line_no=0
  while IFS= read -r line || [ -n "$line" ]; do
    line_no=$((line_no+1)); line="${line%$'\r'}"
    case "$line" in ''|\#*) continue;; *=*) ;; *) arg_stop "靶文件第 ${line_no} 行不是 KEY=VALUE";; esac
    key="${line%%=*}"; value="${line#*=}"
    case "$key" in
      HOST) [ "$HOST_SET" = 1 ] || HOST="$value";;
      APP_DIR) [ "$APP_SET" = 1 ] || APP="$value";;
      SERVICE) [ "$UNIT_SET" = 1 ] || UNIT="$value";;
      OWNER) [ "$OWNER_SET" = 1 ] || OWNER="$value";;
      REQUIRE_ROOM) [ "$ROOM_SET" = 1 ] || NEED_ROOM="$value";;
      NO_WAIT_WINDOW) [ "$NOWAIT_SET" = 1 ] || NOWAIT="$value";;
      *) arg_stop "靶文件第 ${line_no} 行键 ${key} 不认识";;
    esac
  done < "$TARGET_FILE"
fi
[ "$PUBLISH_ONLY" = 1 ] || [ -n "$HOST" ] || arg_stop "必须给 --host 等部署参数，或 --publish-only"
[ -z "$COMMIT" ] || [ "$TRIAL" = 1 ] || arg_stop "--commit 只许 --trial"
[ "$TRIAL" = 0 ] || [ "$PUBLISH_ONLY" = 0 ] || arg_stop "--trial 需要部署，不能 --publish-only"
[[ "$WAIT_MAX" =~ ^[0-9]+$ ]] || arg_stop "--wait-max 须为非负整数分钟"
case "$NEED_ROOM:$NOWAIT" in 0:0|0:1|1:0|1:1) ;; *) arg_stop "REQUIRE_ROOM 与 NO_WAIT_WINDOW 须为 0 或 1";; esac
[[ -z "$APP" || "$APP" != */ ]] || arg_stop "应用目录不能以 / 结尾"
# 靶值会写进远端命令与部署脚本，先拒绝 shell 元字符。
if [ "$PUBLISH_ONLY" = 0 ]; then
  [ -n "$APP" ] && [ -n "$UNIT" ] && [ -n "$OWNER" ] || arg_stop "部署必须给 --host、--app-dir、--service、--owner"
  [[ "$HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]*$ ]] || arg_stop "主机别名格式不合"
  [[ "$APP" =~ ^/[a-zA-Z0-9/_.-]+$ ]] || arg_stop "应用目录须为不含 shell 元字符的绝对路径"
  [[ "$UNIT" =~ ^[a-zA-Z0-9][a-zA-Z0-9@._-]*$ ]] || arg_stop "服务名格式不合"
  [[ "$OWNER" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]*:[a-zA-Z0-9_][a-zA-Z0-9_.-]*$ ]] || arg_stop "属主须为 USER:GROUP"
fi
STEPS=(preflight tag package push-tag publish upload install wait deploy verify)
idx_of(){ local i; for i in "${!STEPS[@]}"; do [ "${STEPS[$i]}" = "$1" ] && { echo "$i"; return; }; done; echo -1; }
FROM_I=$(idx_of "$FROM"); [ "$FROM_I" -ge 0 ] || arg_stop "步骤名 $FROM 不在 ${STEPS[*]}"
[ "$PUBLISH_ONLY" = 0 ] || [ "$FROM_I" -le 4 ] || arg_stop "--publish-only 不能从 publish 之后接手"
# 在任何 cd 前固定脚本目录；--repo 只覆盖产品仓，不改变 CI 检查入口。
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)" || arg_stop "找不到脚本目录"
if [ -z "$REPO" ]; then
  REPO=$(git -C "$(dirname "$0")" rev-parse --show-toplevel) || arg_stop "找不到脚本所在仓"
fi
REPO="$(cd "$REPO" && pwd)" || arg_stop "进不了产品仓"
[ -n "$VER" ] || VER=$(awk '/<parent>/{p=1} /<\/parent>/{p=0; next} !p && /<version>/{sub(/.*<version>/, ""); sub(/<\/version>.*/, ""); print; exit}' "$REPO/pom.xml")
[ "$VER" = auto ] || [[ "$VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || arg_stop "版本号须三段纯数字如 5.8.0，给的是 ${VER}"
if [ "$VER" = auto ]; then
  [ "$TRIAL" = 1 ] && [ -n "$HOST" ] || arg_stop "auto 只许 --trial 并指定 --host"
  CURV=$(git -C "$REPO" show "${COMMIT:-HEAD}:pom.xml" 2>/dev/null | awk '/<parent>/{p=1} /<\/parent>/{p=0; next} !p && /<version>/{sub(/.*<version>/, ""); sub(/<\/version>.*/, ""); print; exit}')
  [[ "$CURV" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || arg_stop "${COMMIT:-HEAD} 的根 pom 读不出版本号，读到「${CURV}」"
  XY="${CURV%.*}"
  if [ "$SANDBOX" = 1 ]; then
    VER="${XY}.90"; echo "〔沙盘〕试装号自动取 ${VER}（沙盘不问 ${HOST}）"
  else
    run=""
    for i in 1 2 3; do
      run=$(ssh -o ConnectTimeout=25 "$HOST" "systemctl list-units --type=service --state=running --no-legend --plain 'novabot@*'" 2>/dev/null) && break
      run=""; [ "$i" = 3 ] || sleep 20
    done
    [ -n "$run" ] || arg_stop "问不到 ${HOST} 正在跑哪一版，三次皆无回"
    case "$run" in *"novabot@${XY}.90.service"*) VER="${XY}.91";; *) VER="${XY}.90";; esac
    echo "试装号自动取 ${VER}（${HOST} 正在跑：$(printf '%s\n' "$run" | awk '{print $1}' | tr '\n' ' ')）"
  fi
  [[ "$VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || arg_stop "试装号定不出，得「${VER}」"
fi
SCRATCH="${RELEASE_WORKDIR:-${TMPDIR:-/tmp}/novabot-release}"
mkdir -p "$SCRATCH" || arg_stop "建不了临时目录 ${SCRATCH}"
SCRATCH="$(cd "$SCRATCH" && pwd)" || arg_stop "进不了临时目录"
for bin_dir in /usr/local/bin /opt/homebrew/bin; do
  [ ! -d "$bin_dir" ] || export PATH="$bin_dir:$PATH"
done
if [ "$FROM_I" -le 2 ]; then
  java17(){
    local version
    version=$("$1" -version 2>&1) || return 1
    [[ "$version" =~ version[[:space:]]+\"17(\.|\") ]]
  }
  if [ -n "${JAVA_HOME:-}" ]; then
    java17 "$JAVA_HOME/bin/java" || arg_stop "找不到 Java 17"
  else
    JAVA_HOME=""
    if [ -x /usr/libexec/java_home ]; then
      candidate=$(/usr/libexec/java_home -v 17 2>/dev/null) &&
        java17 "$candidate/bin/java" && JAVA_HOME="$candidate"
    fi
    if [ -z "$JAVA_HOME" ] && command -v brew >/dev/null 2>&1; then
      candidate=$(brew --prefix openjdk@17 2>/dev/null) &&
        candidate="$candidate/libexec/openjdk.jdk/Contents/Home" &&
        java17 "$candidate/bin/java" && JAVA_HOME="$candidate"
    fi
    if [ -z "$JAVA_HOME" ]; then
      for candidate in /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; do
        if java17 "$candidate/bin/java"; then JAVA_HOME="$candidate"; break; fi
      done
    fi
    [ -n "$JAVA_HOME" ] || java17 java || arg_stop "找不到 Java 17"
  fi
  if [ -n "$JAVA_HOME" ]; then export JAVA_HOME; export PATH="$JAVA_HOME/bin:$PATH"; else unset JAVA_HOME; fi
fi
TAG="v${VER}"; [ "$SANDBOX" = 1 ] && TAG="v${VER}-sandbox"
[ "$TRIAL" = 1 ] && TAG="v${VER}-trial"
LOG="$SCRATCH/发版-${VER}-$(date +%Y%m%d-%H%M%S).log"
say(){ printf '%s\t%s\n' "$(date '+%F %T')" "$1" | tee -a "$LOG"; }
stop(){ say "🛑 停在：$1——$2"; say "日志 $LOG"; exit 1; }
should(){ [ "$(idx_of "$1")" -ge "$FROM_I" ]; }
ci_status(){ bash "$SCRIPT_DIR/ci-status.sh" "$@"; }

probe_cmd() {
  local APP="$1"
  printf '%s\n' 'cd '"${APP}"'/logs/$(TZ=Asia/Shanghai date +%Y-%m) || exit 9; cat $(ls -t novabot-*.log starbot-*.log 2>/dev/null | head -2 | sort) | grep -oE "(LIVE|PREPARING): [0-9]+" | awk '"'"'{last[$2]=$1} END{l=""; for(r in last) if(last[r]=="LIVE:") l=l r" "; if(l=="") print "ALLIDLE"; else print "LIVE " l}'"'"
}
buildinfo_ok() {
  local commit="$1" tree="$2" tag="$3" repo="${4:-.}"
  [ -n "$commit" ] && [ -n "$tree" ] || return 1
  local gotc gott
  gotc=$(git -C "$repo" rev-parse "${tag}^{commit}" 2>/dev/null) || return 1
  gott=$(git -C "$repo" rev-parse "${tag}^{tree}" 2>/dev/null) || return 1
  [ "$commit" = "$gotc" ] || [ "$tree" = "$gott" ]
}

# 远端已开始的长步骤不随本地停止；标签不删，打印接手方法。
REMOTE_LOG=""; LOCAL_TAG_PENDING=0
interrupted(){
  trap - TERM INT
  [ -z "$REMOTE_LOG" ] || say "远端那步仍在跑，日志在 ${HOST}:${REMOTE_LOG}"
  if [ "$LOCAL_TAG_PENDING" = 1 ]; then
    say "本地标签 ${TAG} 留着；先核远端标签实况，同一提交重试走 --from package；改代码先 git tag -d ${TAG} 再从头跑"
  fi
  say "🛑 停在：中断——收到 $1；日志 $LOG"
  exit "$2"
}
trap 'interrupted TERM 143' TERM
trap 'interrupted INT 130' INT

# 本地标签取提交；远端查询失败不当作标签不存在。
tag_status(){
  local out
  TL=$(git rev-parse -q --verify "refs/tags/${TAG}^{commit}" 2>/dev/null || true)
  if [ "$SANDBOX" = 1 ]; then
    say "〔沙盘〕跳过 git ls-remote --tags origin ${TAG}（远端标签模拟为空）"
    TR=""; return 0
  fi
  out=$(git ls-remote --tags origin "refs/tags/${TAG}" "refs/tags/${TAG}^{}") || stop "$1" "查不了远端标签（git ls-remote 退非 0）"
  TR=$(printf '%s\n' "$out" | awk -v t="refs/tags/${TAG}^{}" '$2==t{print $1}')
  [ -n "$TR" ] || TR=$(printf '%s\n' "$out" | awk -v t="refs/tags/${TAG}" '$2==t{print $1}')
  return 0
}
tag_check(){
  tag_status "$1"
  [ -n "$TL" ] || stop "$1" "本地没有标签 ${TAG}（先 \`--from tag\`）"
  if [ -n "$TR" ] && [ "$TR" != "$TL" ]; then stop "$1" "远端 ${TAG} 指向 ${TR:0:8}，本地指向 ${TL:0:8}（不覆盖，人来核）"; fi
  if [ "$TRIAL" = 0 ] && [ -z "$TR" ]; then LOCAL_TAG_PENDING=1; fi
  return 0
}
case "$SB_ROUTE" in ''|first|hot|restart) ;; *) stop preflight "--dry-run-route 只认 first|hot|restart（给的是 ${SB_ROUTE}）";; esac
[ -z "$SB_ROUTE" ] || [ "$SANDBOX" = 1 ] || stop preflight "--dry-run-route 只在 --dry-run 下认"
[ "$TRIAL" = 0 ] || [ "$FROM" != upload ] || stop preflight "试装不能从 upload 接手：要重传就 --from package；包已在靶机就 --from install"
STAGE="${APP}-stage-${VER}"
RUSER="${OWNER%%:*}"
if [ "$PUBLISH_ONLY" = 1 ]; then say "只发布，不部署"
else say "靶＝${HOST}：ssh ${HOST}／${APP}／旧服务 ${UNIT}／install.sh --dir ${APP} --user ${RUSER}／新单元 novabot@${VER}"; fi
run_remote(){ if [ "$SANDBOX" = 1 ]; then say "〔沙盘〕$*"; return 0; fi; "$@"; }
ssh_m(){ ssh -o ConnectTimeout=25 -o ServerAliveInterval=10 "$HOST" "$@"; }
SSHOUT=""
ssh_try(){
  local i
  for i in 1 2 3; do
    SSHOUT=$(ssh_m "$1" 2>/dev/null || true)
    [ -n "$SSHOUT" ] && return 0
    [ "$i" = 3 ] && break
    say "探针无回（ssh 抖），重试 $((i+1))／3"; sleep 20
  done
  SSHOUT=""; return 1
}

# 起跑只留最近两棵失败克隆，成功则删本趟克隆。
sandbox_prune(){
  local d
  find "$SCRATCH" -maxdepth 1 -type d -name 'sandbox-??????' -exec stat -f '%m %N' {} + 2>/dev/null \
    | sort -rn | tail -n +3 | cut -d' ' -f2- | while IFS= read -r d; do
        [ -n "$d" ] && [ -d "$d" ] && rm -rf "$d"
      done
  return 0
}
sandbox_cleanup(){
  [ "${SANDBOX:-0}" = 1 ] || return 0
  [ -n "${SB:-}" ] && [ -d "$SB" ] || return 0
  cd "$SCRATCH" 2>/dev/null || cd /
  rm -rf "$SB"
  return 0
}
trial_cleanup(){
  if [ -n "$TRY" ] && [ -d "$TRY" ]; then cd "$SCRATCH" 2>/dev/null || cd /; rm -rf "$TRY"; say "试装克隆已删"; fi
}
if [ "$SANDBOX" = 1 ]; then
  sandbox_prune
  SB="$SCRATCH/sandbox-$(date +%H%M%S)"
  git clone -q "$REPO" "$SB" || stop preflight "沙盘克隆失败"
  REPO="$SB"; say "〔沙盘〕仓＝$SB 标签＝${TAG}（远端动作只打印；讫则删，停则留）"
fi
trial_prune(){
  local d
  find "$SCRATCH" -maxdepth 1 -type d -name 'trial-??????' -exec stat -f '%m %N' {} + 2>/dev/null \
    | sort -rn | tail -n +3 | cut -d' ' -f2- | while IFS= read -r d; do
        [ -n "$d" ] && [ -d "$d" ] && rm -rf "$d"
      done
  return 0
}
TRY=""
if [ "$TRIAL" = 1 ]; then
  ORIG="$REPO"
  git -C "$ORIG" rev-parse -q --verify "refs/tags/v${VER}" >/dev/null && stop preflight "本地已有标签 v${VER}（试装号不许与真版本撞）"
  if [ "$SANDBOX" = 1 ]; then
    say "〔沙盘〕跳过远端标签查询；本机产品仓的标签已由克隆核过"
  else
    out=$(git -C "$ORIG" ls-remote --tags origin "refs/tags/v${VER}") || stop preflight "查不了远端标签（git ls-remote 退非 0）"
    [ -z "$out" ] || stop preflight "远端已有标签 v${VER}（试装号不许与真版本撞）"
    trial_prune
    TRY="$SCRATCH/trial-$(date +%H%M%S)"
    git clone -q "$ORIG" "$TRY" || stop preflight "试装克隆失败"
    REPO="$TRY"
  fi
  if [ -n "$COMMIT" ]; then
    git -C "$REPO" checkout -q --detach "$COMMIT" || stop preflight "克隆里切不到提交 ${COMMIT}"
    say "试装：按指定提交出包 $(git -C "$REPO" rev-parse --short HEAD)"
  fi
  cd "$REPO" || stop preflight "进不了 $REPO"
  CURV=$(awk '/<parent>/{p=1} /<\/parent>/{p=0; next} !p && /<version>/{sub(/.*<version>/, ""); sub(/<\/version>.*/, ""); print; exit}' pom.xml)
  printf '%s\n' "$CURV" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' || stop preflight "根 pom 读不出现版本号（读到「${CURV}」）"
  [ "$CURV" != "$VER" ] || stop preflight "试装号 ${VER} 与现版本相同"
  PAT=">${CURV//./\\.}<"
  EXR=templates/nova-example-plugin/README.md
  [ -f "$EXR" ] || stop preflight "克隆里没有 ${EXR}"
  grep -q "$PAT" "$EXR" || stop preflight "${EXR} 里没有 ${CURV}（版本文件变了，人来看）"
  n=0
  while IFS= read -r p; do
    grep -q "$PAT" "$p" || continue
    sed -i '' "s#${PAT}#>${VER}<#g" "$p" || stop preflight "改不了 $p"
    say "试装改版本号：${p}"
    n=$((n+1))
  done < <(git ls-files -- 'pom.xml' '*/pom.xml'; echo "$EXR")
  [ "$n" -gt 1 ] || stop preflight "一份 pom 也没改到"
  left=$(git ls-files -- 'pom.xml' '*/pom.xml' | while IFS= read -r p; do grep -l "$PAT" "$p"; done)
  [ -z "$left" ] || stop preflight "改完仍有 pom 带 ${CURV}：${left}"
  grep -q ">${VER//./\\.}<" "$EXR" && ! grep -q "$PAT" "$EXR" || stop preflight "改完 ${EXR} 不是 ${VER}"
  git -c user.name='NovaBot trial' -c user.email='trial@localhost' commit -q -am "trial build ${VER}" || stop preflight "克隆里提交失败"
  git tag "$TAG" || stop preflight "克隆里打 ${TAG} 失败"
  say "试装：克隆 ${REPO}；${n} 件 ${CURV}→${VER}（$((n-1)) 份 pom＋${EXR}），提交 $(git rev-parse --short HEAD)、克隆内标签 ${TAG}（不推）"
fi
cd "$REPO" || stop preflight "进不了 $REPO"

# 预检与发布共用这一个发行说明文件。
NOTES="$SCRATCH/release-notes-${VER}.md"
prepare_notes(){
  [ "$TRIAL" = 0 ] || return 0
  awk -v v="$VER" '$0 ~ "^## \\["v"\\]"{f=1;next} /^## \[/{f=0} f' CHANGELOG.md > "$NOTES"
  [ -s "$NOTES" ] || stop preflight "CHANGELOG ${VER} 段为空"
  say "发行说明：${NOTES}（$(wc -l < "$NOTES" | tr -d ' ') 行）"
  if [ -n "$NOTES_CHECK" ]; then
    HOUT=$(bash -c "$NOTES_CHECK \"\$1\"" -- "$NOTES" 2>&1); rc=$?
    [ -z "$HOUT" ] || say "$HOUT"
    [ "$rc" = 0 ] || stop preflight "发行说明检查退 ${rc}（${NOTES}，输出见上）"
  fi
}
HEAD=$(git rev-parse --short HEAD)
if should preflight; then
  say "── preflight（HEAD ${HEAD}）"
  [ -z "$(git status --short)" ] || stop preflight "树不净：$(git status --short | wc -l | tr -d ' ') 件"
  grep -q "<version>${VER}</version>" pom.xml || stop preflight "根 pom 版本不是 ${VER}（先更新版本号）"
  if [ "$TRIAL" = 1 ]; then
    say "试装：跳过 CHANGELOG 段、标签未有（v${VER} 上面已查，${TAG} 只在克隆里）、HEAD＝origin/main、CI、发行说明"
  else
    grep -q "^## \[${VER}\]" CHANGELOG.md || stop preflight "CHANGELOG 无 ## [${VER}] 段"
    if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then stop preflight "本地已有标签 ${TAG}（不覆盖；上趟停在发包留下的，同一提交走 --from package，改过代码先 git tag -d ${TAG}）"; fi
    if [ "$SANDBOX" = 0 ] && git ls-remote --tags origin "refs/tags/${TAG}" | grep -q .; then stop preflight "远端已有标签 ${TAG}"; fi
    if [ "$SANDBOX" = 0 ]; then
      git fetch -q origin main || stop preflight "fetch 失败"
      [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || stop preflight "HEAD 未推到 origin/main（先推）"
      CIOUT=$(ci_status "$(git rev-parse HEAD)" --no-wait); rc=$?
      say "$CIOUT"
      case "$rc" in 0|8) ;; *) stop preflight "CI 没过（ci-status 退 ${rc}，见上行）";; esac
    else
      say "〔沙盘〕跳过远端标签查询、fetch 与 HEAD＝origin/main、CI"
    fi
    prepare_notes
  fi
  # 发包在 git archive 的导出树里运行，登记清单必须与工作树相同且非空。
  find "$SCRATCH" -maxdepth 1 -type d -name 'archive-??????' -exec rm -rf {} + 2>/dev/null || true
  T=$(mktemp -d "$SCRATCH/archive-XXXXXX") || stop preflight "建不了导出树临时目录"
  mkdir -p "$T/tree"
  git archive HEAD -o "$T/HEAD.tar" || stop preflight "git archive HEAD 退非 0"
  tar -xf "$T/HEAD.tar" -C "$T/tree" || stop preflight "导出树解包退非 0"
  ( cd "$T/tree" && bash tools/console-page-registry.sh ) > "$T/reg-archive.tsv" || stop preflight "导出树登记尺退 $?"
  bash tools/console-page-registry.sh > "$T/reg-worktree.tsv" || stop preflight "工作树登记尺退 $?"
  RA=$(wc -l < "$T/reg-archive.tsv" | tr -d ' '); RW=$(wc -l < "$T/reg-worktree.tsv" | tr -d ' ')
  keep_archive(){ say "导出树留 ${T}（reg-archive.tsv／reg-worktree.tsv 在里头，供 diff；下趟预检起跑自清）"; }
  [ "$RW" -gt 0 ] || { keep_archive; stop preflight "工作树登记尺 0 行（尺哑或无登记，不作数）"; }
  diff -q "$T/reg-archive.tsv" "$T/reg-worktree.tsv" >/dev/null \
    || { keep_archive; stop preflight "导出树登记清单与工作树不一致（导出 ${RA} 行／工作树 ${RW} 行）"; }
  say "预检：导出树登记尺 ${RA} 行＝工作树"
  rm -rf "$T"
  if [ "$TRIAL" = 1 ]; then say "预检过（试装）：pom ${VER}、树净、导出树登记尺对上"
  elif [ "$SANDBOX" = 1 ]; then say "预检过（沙盘）：pom ${VER}、CHANGELOG 段、标签空"
  else say "预检过：pom ${VER}、CHANGELOG 段 $(wc -l < "$NOTES" | tr -d ' ') 行、标签空"; fi
fi

if should tag && [ "$TRIAL" = 1 ]; then
  say "── tag 跳过（试装：克隆内标签 ${TAG} 已建，不推）"
elif should tag; then
  say "── tag ${TAG} → ${HEAD}（只建本地；发包过了才推）"
  tag_status tag
  HEADF=$(git rev-parse HEAD)
  if [ -n "$TR" ] && [ "$TR" != "$HEADF" ]; then stop tag "远端已有 ${TAG} 指向 ${TR:0:8}，不是 HEAD ${HEAD}（不覆盖）"; fi
  if [ -n "$TL" ]; then
    [ "$TL" = "$HEADF" ] || stop tag "本地已有 ${TAG} 指向 ${TL:0:8}，不是 HEAD ${HEAD}（核过后 git tag -d ${TAG} 再跑）"
    say "本地已有 ${TAG} → HEAD（上趟留下），沿用"
  else
    LOCAL_TAG_PENDING=1
    git tag -a "$TAG" -m "NovaBot ${VER}" || stop tag "git tag 失败"
    say "标签 ${TAG} 已建（本地，未推）"
  fi
  [ -n "$TR" ] || LOCAL_TAG_PENDING=1
fi

TB="$REPO/dist/NovaBot-${VER}.tar.gz"
package_stop(){ say "本地标签 ${TAG} 留着、远端未推：同一提交重试走 --from package；改代码先 git tag -d ${TAG} 再从头跑"; stop package "$1"; }
if should package; then
  [ "$FROM" = package ] && tag_check package
  PKG=(--package --skip-tests --no-smoke)
  say "── package ./build.sh --from-ref=${TAG} ${PKG[*]}（约 5 分）"
  PL="$SCRATCH/package-${VER}.log"
  ./build.sh "--from-ref=${TAG}" "${PKG[@]}" > "$PL" 2>&1 || package_stop "发包退非 0（$PL 尾：$(tail -1 "$PL" | cut -c1-120)）"
  [ -f "$TB" ] || package_stop "没有 $TB"
  BI=$(tar -xzOf "$TB" ./BUILD-INFO 2>/dev/null)
  bi_commit=$(printf '%s\n' "$BI" | sed -n 's/^commit=//p')
  bi_tree=$(printf '%s\n' "$BI" | sed -n 's/^tree=//p')
  buildinfo_ok "$bi_commit" "$bi_tree" "$TAG" "$REPO" || package_stop "BUILD-INFO commit 不是标签 ${TAG} 所指（commit 与 tree 皆不合）"
  echo "$BI" | grep -q '^dirty=false$' || package_stop "BUILD-INFO dirty≠false"
  echo "$BI" | grep -q "^version=${VER}\$" || package_stop "BUILD-INFO version≠${VER}"
  tar -tzf "$TB" | grep -q "^\./lib/novacore-${VER}\.jar\$" || package_stop "包里 lib 没有 novacore-${VER}.jar"
  if [ "$TRIAL" = 1 ]; then say "试装包 BUILD-INFO 原样："; printf '%s\n' "$BI" | while IFS= read -r l; do say "  ${l}"; done; fi
  say "包 $(stat -f %z "$TB") B sha $(shasum -a 256 "$TB" | cut -c1-16)… BUILD-INFO 对上"
fi
SHA=$(shasum -a 256 "$TB" 2>/dev/null | cut -d' ' -f1)

# 发布接手也核远端标签，避免 gh 隐式创建一个指向默认分支的标签。
if [ "$TRIAL" = 1 ]; then
  { should push-tag || should publish; } && say "── push-tag、publish 跳过（试装：不打对外标签、不推、不建发布页）"
elif should push-tag || [ "$FROM" = publish ]; then
  say "── push-tag ${TAG}"
  tag_check push-tag
  if [ "$SANDBOX" = 1 ]; then say "〔沙盘〕沙盘不核 CI"
  else
    say "── 核 CI（标签所指 ${TL:0:8}；还在跑就等，上限 30 分）"
    CIOUT=$(ci_status "$TL"); rc=$?
    say "$CIOUT"
    [ "$rc" = 0 ] || stop push-tag "CI 不绿（ci-status 退 ${rc}，见上行），不推标、不发布"
  fi
  if [ -n "$TR" ]; then say "远端已有 ${TAG} → ${TR:0:8}＝本地所指，跳过推"
  else
    LOCAL_TAG_PENDING=1
    run_remote git push -q origin "$TAG" || stop push-tag "推标签失败"
    say "标签 ${TAG} 已$( [ "$SANDBOX" = 1 ] && echo '推（沙盘只打印）' || echo '推远端')"
  fi
  LOCAL_TAG_PENDING=0
fi
if should publish && [ "$TRIAL" = 0 ]; then
  say "── publish gh release ${TAG}"
  if [ "$SANDBOX" = 1 ]; then
    say "〔沙盘〕gh release create ${TAG} ${TB} --title \"NovaBot ${VER}\" --notes-file ${NOTES} --latest"
  else
    gh release create "$TAG" "$TB" --title "NovaBot ${VER}" --notes-file "$NOTES" --latest >/dev/null || stop publish "gh release create 失败"
    DL="$SCRATCH/dl-${VER}.tar.gz"; rm -f "$DL"
    gh release download "$TAG" -p "NovaBot-${VER}.tar.gz" -O "$DL" || stop publish "回下载失败"
    [ "$(shasum -a 256 "$DL" | cut -d' ' -f1)" = "$SHA" ] || stop publish "回下 sha 不一致"
    say "release ${TAG} 已建（Latest），回下 sha 一致"
  fi
fi
if [ "$PUBLISH_ONLY" = 1 ]; then
  say "✅ 发版讫 ${TAG}（HEAD ${HEAD}；日志 ${LOG}）"
  sandbox_cleanup
  exit 0
fi

# 靶值在本地展开，远端脚本可直接读出会操作的服务与目录。
DEPLOY="$SCRATCH/deploy-${VER}.sh"
ISH_L="$SCRATCH/install-${VER}.sh"
RLOG="/root/deploy-${VER}.log"
ILOG="/root/deploy-${VER}-install.log"
cat > "$DEPLOY" <<EOS
#!/usr/bin/env bash
# 在 ${HOST} 以 root 运行：bash /root/deploy-${VER}.sh <子命令>
# probe 看现状；install 只装不换；plan 只读问路；first 首升；switch 换版；measure 量断档。
set -Eeuo pipefail
VER=${VER}; APP=${APP}; OLD_UNIT=${UNIT}; RUSER=${RUSER}
TB=/root/NovaBot-${VER}.tar.gz; ISH=/root/install-${VER}.sh; STAGE=${STAGE}
SW=/usr/local/sbin/novabot-switch-version; NEW=novabot@${VER}; RLOG=${RLOG}
DROPIN=/etc/systemd/system/novabot@.service.d
OLDVER=""; STEPN=""; PHASE=""; OLD_EN=""
now(){ date '+%Y-%m-%d %H:%M:%S'; }
running(){ systemctl list-units --type=service --state=running --no-legend --plain 'novabot@*' 2>/dev/null | awk '{print \$1}'; }
# 解包目录不含 build.sh，install.sh 会把它当发行包装。
unpack(){
  echo "包 sha256 \$(sha256sum "\$TB" | cut -c1-64)"
  echo "安装脚本 sha256 \$(sha256sum "\$ISH" | cut -c1-64)"
  rm -rf "\$STAGE"; mkdir -p "\$STAGE"; tar -xzf "\$TB" -C "\$STAGE"
  echo "--- 新包 BUILD-INFO"; cat "\$STAGE/BUILD-INFO"
  [ -f "\$STAGE/NovaBot.jar" ] || { echo "🛑 包里没有 NovaBot.jar"; exit 3; }
  [ -f "\$STAGE/lib/novacore-\${VER}.jar" ] || { echo "🛑 包里 lib 没有 novacore-\${VER}.jar"; exit 3; }
  [ -f "\$STAGE/novabot@.service" ] || { echo "🛑 包里没有 novabot@.service"; exit 3; }
  [ -f "\$STAGE/tools/switch-version.sh" ] || { echo "🛑 包里没有 tools/switch-version.sh"; exit 3; }
  [ ! -e "\$STAGE/build.sh" ] || { echo "🛑 解包目录里有 build.sh，install.sh 会当源码去构建"; exit 3; }
  cp "\$ISH" "\$STAGE/install.sh"; chmod 755 "\$STAGE/install.sh"
}
probe(){
  local run n rel=无 old cur
  if [ -d "\$APP/releases" ]; then rel=有; fi
  run=\$(running | tr '\n' ' ')
  n=\$(printf '%s' "\$run" | wc -w | tr -d ' ')
  old=\$(systemctl is-active "\$OLD_UNIT" 2>/dev/null || true)
  echo "实况 releases/：\${rel}；在跑的 novabot@：\${run:-无}；旧服务 \${OLD_UNIT}：\${old:-读不到}"
  if [ "\$rel" = 无 ]; then
    if [ "\$n" = 0 ]; then echo "ROUTE first"; else echo "ROUTE other"; fi
    return 0
  fi
  case "\$old" in active|activating|reloading|deactivating) echo "ROUTE other"; return 0;; esac
  if [ "\$n" = 1 ]; then cur=\${run%% *}; cur=\${cur#novabot@}; echo "ROUTE after \${cur%.service}"
  else echo "ROUTE other"; fi
}
do_install(){
  unpack
  echo "--- 装：install.sh --dir \${APP} --user \${RUSER} --no-switch --no-packages"
  "\$STAGE/install.sh" --dir "\$APP" --user "\$RUSER" --no-switch --no-packages
  rm -rf "\$STAGE"
  echo "装讫：\${APP}/releases/\${VER}"
}
do_plan(){ local rc=0; "\$SW" --plan "\$VER" || rc=\$?; echo "PLANRC \${rc}"; }
# 停旧前失败保留旧服务并还原自启；停旧后失败起回上一版。
first_fail(){
  local rc=\$?
  [ -n "\${1:-}" ] && rc=\$1
  trap - ERR; set +e
  echo "🛑 首升在「\${STEPN}」失败（退 \${rc}）"
  if [ "\$PHASE" = 停后 ] && ! systemctl is-active --quiet "\$OLD_UNIT"; then
    echo "--- 旧服务 \${OLD_UNIT} 已停：停 \${NEW}、关它的开机自启，起 novabot@\${OLDVER} 并设开机自启"
    systemctl stop "\$NEW"; systemctl disable "\$NEW"
    systemctl enable "novabot@\${OLDVER}"; systemctl start "novabot@\${OLDVER}"; sleep 3
    echo "起回 novabot@\${OLDVER}：\$(systemctl is-active "novabot@\${OLDVER}")；开机自启 \$(systemctl is-enabled "novabot@\${OLDVER}")（旧服务 \${OLD_UNIT} 保持停）"
  else
    echo "--- 旧服务 \${OLD_UNIT} 没停（现 \$(systemctl is-active "\$OLD_UNIT")）：不起别的，开机自启还原成装前的样子"
    if [ "\$OLD_EN" = enabled ]; then systemctl enable "\$OLD_UNIT"; fi
    if [ "\$(systemctl is-enabled "\$NEW" 2>/dev/null)" = enabled ]; then systemctl disable "\$NEW"; fi
    echo "开机自启：\${OLD_UNIT} \$(systemctl is-enabled "\$OLD_UNIT" 2>/dev/null)（装前 \${OLD_EN:-读不到}）；\${NEW} \$(systemctl is-enabled "\$NEW" 2>/dev/null)"
    echo "releases/ 下现有：\$(ls -1 "\$APP/releases" 2>/dev/null | tr '\n' ' ')"
    echo "旧服务照跑；安装目录可能已搬了一半（旧版的件可能已挪进 releases/<旧版>/），要人来收拾，收拾好之前别重启旧服务，机器也别重启（旧服务起不来，新版自启也关着，重启后什么都不跑）"
  fi
  exit 1
}
do_first(){
  local mh mm nmh nmm oldact d n=0
  PHASE=装
  unpack
  mh=\$(systemctl show "\$OLD_UNIT" -p MemoryHigh --value)
  mm=\$(systemctl show "\$OLD_UNIT" -p MemoryMax --value)
  OLD_EN=\$(systemctl is-enabled "\$OLD_UNIT" 2>/dev/null || true)
  oldact=\$(systemctl is-active "\$OLD_UNIT" 2>/dev/null || true)
  echo "旧服务 \${OLD_UNIT}：\${oldact:-读不到}，开机自启 \${OLD_EN:-读不到}；MemoryHigh=\${mh} MemoryMax=\${mm}"
  echo "旧单元 \${OLD_UNIT}.service"
  trap first_fail ERR
  STEPN="装"; echo "--- 装（旧服务照跑）：install.sh --dir \${APP} --user \${RUSER} --no-packages"
  "\$STAGE/install.sh" --dir "\$APP" --user "\$RUSER" --no-packages
  STEPN="装后核"
  if [ ! -f "\$APP/releases/\$VER/BUILD-INFO" ]; then echo "🛑 装后没有 \${APP}/releases/\${VER}/BUILD-INFO"; first_fail 4; fi
  for d in "\$APP"/releases/*; do
    [ -d "\$d" ] || continue
    d=\${d##*/}; [ "\$d" = "\$VER" ] && continue
    n=\$((n+1)); OLDVER=\$d
  done
  if [ "\$n" != 1 ]; then echo "🛑 releases/ 下除 \${VER} 外有 \${n} 个版本目录（期恰 1 个＝旧版）"; first_fail 4; fi
  if ! printf '%s\n' "\$OLDVER" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+\$'; then echo "🛑 releases/ 下旧版目录名不是版本号：\${OLDVER}"; first_fail 4; fi
  if [ ! -f /etc/systemd/system/novabot@.service ]; then echo "🛑 装后没有 /etc/systemd/system/novabot@.service"; first_fail 4; fi
  echo "装后核过：releases/\${VER}/BUILD-INFO 在；旧版 \${OLDVER}（releases/ 下除 \${VER} 外恰此一份）；单元模板在"
  echo "上一版 \${OLDVER}"
  STEPN="照搬内存上限"
  nmh=\$(systemctl show "\$NEW" -p MemoryHigh --value)
  nmm=\$(systemctl show "\$NEW" -p MemoryMax --value)
  if [ "\$mh" = "\$nmh" ] && [ "\$mm" = "\$nmm" ]; then
    echo "内存上限与 \${NEW} 相同（MemoryHigh=\${nmh} MemoryMax=\${nmm}），不写覆盖"
  else
    # 模板的覆盖目录不会随旧单元清理，后续安装也保留这份内存上限。
    mkdir -p "\$DROPIN"
    printf '[Service]\nMemoryHigh=%s\nMemoryMax=%s\n' "\$mh" "\$mm" > "\$DROPIN/50-memory.conf"
    systemctl daemon-reload
    echo "内存上限照搬旧服务，写进 \${DROPIN}/50-memory.conf：\${NEW} 由 \${nmh}／\${nmm} 改为 \$(systemctl show "\$NEW" -p MemoryHigh --value)／\$(systemctl show "\$NEW" -p MemoryMax --value)"
  fi
  PHASE=停后
  echo "停起点 \$(now)"
  STEPN="停旧服务"; echo "--- 停 \${OLD_UNIT}、关它的开机自启"
  systemctl stop "\$OLD_UNIT"
  systemctl disable "\$OLD_UNIT"
  STEPN="起新版"; echo "--- 起 \${NEW}"
  echo "起于 \$(now)"
  systemctl start "\$NEW"
  sleep 3
  if ! systemctl is-active --quiet "\$NEW"; then echo "🛑 \${NEW} 起后 3 秒不在跑（\$(systemctl is-active "\$NEW")）"; first_fail 4; fi
  trap - ERR
  rm -rf "\$STAGE"
  echo "部署讫：\${NEW} 在跑，开机自启 \$(systemctl is-enabled "\$NEW")"
}
do_switch(){
  local cur p rc=0
  cur=\$(running | sed -n 1p)
  [ -n "\$cur" ] || { echo "🛑 没有在跑的 novabot@，不换"; exit 3; }
  p=\${cur#novabot@}
  echo "旧单元 \${cur}"
  echo "上一版 \${p%.service}"
  echo "停起点 \$(now)"
  echo "起于 \$(now)"
  echo "--- novabot-switch-version \${VER}"
  "\$SW" "\$VER" || rc=\$?
  if [ "\$rc" != 0 ]; then echo "🛑 换版工具退 \${rc}（原样输出见上；它自己会把旧版起回来）"; exit "\$rc"; fi
  echo "部署讫：\${NEW}"
}
do_measure(){
  local ou since ts tc
  ou=\$(sed -n 's/^旧单元 //p' "\$RLOG" 2>/dev/null | tail -1 || true)
  since=\$(sed -n 's/^停起点 //p' "\$RLOG" 2>/dev/null | tail -1 || true)
  if [ -z "\$ou" ] || [ -z "\$since" ]; then echo "GAP 部署日志里没有旧单元或停起点"; return 0; fi
  ts=\$(journalctl -u "\$ou" --since "\$since" -o short-unix --no-pager 2>/dev/null | grep -E 'Stopping|Stopped' | sed -n 1p | awk '{print \$1}' || true)
  tc=\$(journalctl -u "\$NEW" --since "\$since" -o short-unix --no-pager 2>/dev/null | grep '已连接到直播间' | sed -n 1p | awk '{print \$1}' || true)
  if [ -z "\$ts" ]; then echo "GAP 没取到 \${ou} 停机那条日志的时刻"
  elif [ -z "\$tc" ]; then echo "GAP 没连上"
  else awk -v a="\$ts" -v b="\$tc" 'BEGIN{printf "GAP %.1f\n", b-a}'; fi
}
case "\${1:-}" in
  probe) probe;; install) do_install;; plan) do_plan;; first) do_first;; switch) do_switch;; measure) do_measure;;
  *) echo "用法: bash \$0 probe|install|plan|first|switch|measure"; exit 2;;
esac
EOS
bash -n "$DEPLOY" || stop upload "部署脚本语法红"

if should upload; then
  # 本地包不在时，从发行页取回并核 BUILD-INFO，不能空手开始 SSH 重试。
  if [ ! -f "$TB" ]; then
    if [ "$SANDBOX" = 1 ]; then
      say "〔沙盘〕gh release download ${TAG} -p NovaBot-${VER}.tar.gz -O ${TB}"
    else
      say "本地无 ${TB}，从 release ${TAG} 取回"
      mkdir -p "$(dirname "$TB")"
      gh release download "$TAG" -p "NovaBot-${VER}.tar.gz" -O "$TB" || stop upload "本地无包，且 gh release download ${TAG} 取不回"
      BI=$(tar -xzOf "$TB" ./BUILD-INFO 2>/dev/null)
      bi_commit=$(printf '%s\n' "$BI" | sed -n 's/^commit=//p')
      bi_tree=$(printf '%s\n' "$BI" | sed -n 's/^tree=//p')
      buildinfo_ok "$bi_commit" "$bi_tree" "$TAG" "$REPO" || stop upload "取回包的 BUILD-INFO commit 不是标签 ${TAG} 所指（commit 与 tree 皆不合）"
      echo "$BI" | grep -q '^dirty=false$' || stop upload "取回包的 BUILD-INFO dirty≠false"
      SHA=$(shasum -a 256 "$TB" | cut -d' ' -f1)
      say "取回 $(stat -f %z "$TB") B sha $(printf '%s' "$SHA" | cut -c1-16)… BUILD-INFO 对上"
    fi
  fi
  ISH_REF="$TAG"
  if ! git rev-parse -q --verify "refs/tags/${TAG}^{commit}" >/dev/null; then
    if [ "$SANDBOX" = 1 ] && git rev-parse -q --verify "refs/tags/v${VER}^{commit}" >/dev/null; then
      ISH_REF="v${VER}"; say "〔沙盘〕克隆里没有 ${TAG}，安装脚本取 v${VER} 那次提交"
    else
      stop upload "没有标签 ${TAG}，取不了同一提交的 install.sh"
    fi
  fi
  git show "${ISH_REF}:install.sh" > "$ISH_L" || stop upload "git show ${ISH_REF}:install.sh 退非 0"
  [ -s "$ISH_L" ] || stop upload "${ISH_REF} 里的 install.sh 是空的"
  ISHA=$(shasum -a 256 "$ISH_L" | cut -d' ' -f1)
  say "安装脚本 ${ISH_REF}:install.sh $(wc -c < "$ISH_L" | tr -d ' ') B sha $(printf '%s' "$ISHA" | cut -c1-16)…"
  say "── upload 到 ${HOST}（重试 ≤6）"
  if [ "$SANDBOX" = 1 ]; then
    say "〔沙盘〕cat ${TB} | ssh ${HOST} 'cat > /root/NovaBot-${VER}.tar.gz.part && mv … && sha256sum'"
    say "〔沙盘〕cat ${ISH_L} | ssh ${HOST} 'cat > /root/install-${VER}.sh.part && mv … && sha256sum'（比 sha ${ISHA:0:16}…）"
    say "〔沙盘〕deploy-${VER}.sh → /root（bash -n）"
  else
    ok=0
    for i in 1 2 3 4 5 6; do
      if cat "$TB" | ssh_m "cat > /root/NovaBot-${VER}.tar.gz.part && mv /root/NovaBot-${VER}.tar.gz.part /root/NovaBot-${VER}.tar.gz && sha256sum /root/NovaBot-${VER}.tar.gz" 2>/dev/null | grep -q "^${SHA}"; then ok=1; say "传包成（第 $i 次）"; break; fi
      say "传包第 $i 次失败"
    done
    [ "$ok" = 1 ] || stop upload "六次都没传成"
    ok=0
    for i in 1 2 3 4 5 6; do
      if cat "$ISH_L" | ssh_m "cat > /root/install-${VER}.sh.part && mv /root/install-${VER}.sh.part /root/install-${VER}.sh && sha256sum /root/install-${VER}.sh" 2>/dev/null | grep -q "^${ISHA}"; then ok=1; say "安装脚本传成（第 $i 次）"; break; fi
      say "安装脚本第 $i 次没传成"
    done
    [ "$ok" = 1 ] || stop upload "安装脚本六次都没传成"
    ok=0
    for i in 1 2 3 4; do
      if cat "$DEPLOY" | ssh_m "cat > /root/deploy-${VER}.sh && bash -n /root/deploy-${VER}.sh && echo SCRIPT_OK" 2>/dev/null | grep -q SCRIPT_OK; then ok=1; break; fi
    done
    [ "$ok" = 1 ] || stop upload "部署脚本没传成"
    say "deploy-${VER}.sh 已在 /root"
  fi
fi

# SSH 断开不打断远端；末行退码为完成标记，读回整份日志。
RRC=""
remote_run(){
  local i out
  RRC=""
  if [ "$SANDBOX" = 1 ]; then
    say "〔沙盘〕ssh ${HOST} 'nohup bash -c \"bash /root/deploy-${VER}.sh $1; echo 退码 …\" > $2 2>&1 &'"
    RRC=0; return 0
  fi
  REMOTE_LOG="$2"
  ssh_m "rm -f $2; nohup bash -c 'bash /root/deploy-${VER}.sh $1; echo \"退码 \$?\"' > $2 2>&1 < /dev/null &" || stop "$4" "远端起不动"
  for i in $(seq 1 $(( $3 / 5 ))); do
    sleep 5
    out=$(ssh_m "grep '^退码 ' $2 | tail -1" 2>/dev/null || true)
    RRC=$(printf '%s\n' "$out" | sed -n 's/^退码 //p' | tail -1)
    [ -n "$RRC" ] && break
  done
  [ -z "$RRC" ] || REMOTE_LOG=""
  if ssh_try "cat $2"; then
    say "── 远端日志 $2："
    printf '%s\n' "$SSHOUT" | while IFS= read -r l; do say "  ${l}"; done
  else
    say "读不回远端日志 $2"
  fi
  [ -n "$RRC" ] || stop "$4" "$3 秒内没等到远端跑完（看 ssh ${HOST} cat $2）"
}

ROUTE=""; CUR=""; PLAN=""
probe_route(){
  local r
  if [ "$SANDBOX" = 1 ]; then
    say "〔沙盘〕ssh ${HOST} 'bash /root/deploy-${VER}.sh probe'（沙盘取不到实况，按 --dry-run-route ${SB_ROUTE:-first}）"
    case "${SB_ROUTE:-first}" in first) ROUTE=first;; *) ROUTE=after; CUR="<在跑的版本>";; esac
  else
    ssh_try "bash /root/deploy-${VER}.sh probe" || stop 探路 "探针无回"
    printf '%s\n' "$SSHOUT" | while IFS= read -r l; do say "  ${l}"; done
    r=$(printf '%s\n' "$SSHOUT" | sed -n 's/^ROUTE //p' | tail -1)
    case "$r" in
      first) ROUTE=first;;
      after\ ?*) ROUTE=after; CUR="${r#after }";;
      *) stop 探路 "靶机现状既不是首升也不是之后（实况见上行），不猜，人来看";;
    esac
  fi
  if [ "$ROUTE" = first ]; then say "探路：首升（${UNIT} 照跑时装 → 核过 → 停 ${UNIT}、起 novabot@${VER}，先装后停）"
  else say "探路：之后（在跑 novabot@${CUR}）"; fi
}
# --plan 的两种路由只可有一种，且必须退 0。
plan_route(){
  local prc h o
  if [ "$SANDBOX" = 1 ]; then
    say "〔沙盘〕ssh ${HOST} 'bash /root/deploy-${VER}.sh plan'（即 novabot-switch-version --plan ${VER}；沙盘按 --dry-run-route ${SB_ROUTE}）"
    case "$SB_ROUTE" in hot) PLAN=hot;; *) PLAN=ord;; esac
  else
    ssh_try "bash /root/deploy-${VER}.sh plan" || stop 问路 "探针无回"
    printf '%s\n' "$SSHOUT" | while IFS= read -r l; do say "  ${l}"; done
    prc=$(printf '%s\n' "$SSHOUT" | sed -n 's/^PLANRC //p' | tail -1)
    h=$(printf '%s\n' "$SSHOUT" | grep -c '^这次会走热交接' || true)
    o=$(printf '%s\n' "$SSHOUT" | grep -c '^这次会走普通重启' || true)
    if [ "$prc" = 0 ] && [ "$h" -gt 0 ] && [ "$o" = 0 ]; then PLAN=hot
    elif [ "$prc" = 0 ] && [ "$o" -gt 0 ] && [ "$h" = 0 ]; then PLAN=ord
    else stop 问路 "换版工具 --plan 没说清走哪条路（退 ${prc:-读不到}，原样输出见上）"; fi
  fi
  if [ "$PLAN" = hot ]; then say "问路：热交接（不等窗）"; else say "问路：普通重启（先等窗）"; fi
}
if should install || should wait || should deploy; then probe_route; fi
if should install; then
  if [ "$ROUTE" = first ]; then
    say "── install：首升不在这一步（并在部署里：旧服务照跑时装，核过再停旧起新）"
  else
    say "── install（install.sh --dir ${APP} --user ${RUSER} --no-switch --no-packages，只装不换）"
    remote_run install "$ILOG" 600 install
    [ "$RRC" = 0 ] || stop install "install.sh 没装成（远端退 ${RRC}，见上面远端日志）；机器上在跑的没动"
  fi
fi
if [ "$ROUTE" = after ] && { should wait || should deploy; }; then plan_route; fi

# 月目录按远端的 Asia/Shanghai 时钟计算；空回不能当成无人直播。
PROBE=$(probe_cmd "$APP")
if should wait; then
  if [ "$ROUTE" = after ] && [ "$PLAN" = hot ]; then say "── wait 跳过（热交接不等窗）"
  elif [ "$NOWAIT" = 1 ]; then say "── wait 跳过（--no-wait-window）"
  elif [ "$SANDBOX" = 1 ]; then say "〔沙盘〕等窗：探针模拟 ALLIDLE"
  else
    say "── wait（每 3 分探一次，上限 ${WAIT_MAX} 分）"
    t0=$(date +%s); okw=0
    while :; do
      out=$(ssh_m "$PROBE" 2>/dev/null); prc=$?
      r=$(printf '%s\n' "$out" | grep -E '^(ALLIDLE|LIVE)' | head -1)
      if [ "$prc" = 9 ]; then say "探针退 9（日志月目录不在）"
      else
        case "$r" in ALLIDLE) okw=1; say "全员空闲，可部署"; break;; LIVE*) say "在播：${r#LIVE }（继续等）";; *) say "探针无回（ssh 抖）";; esac
      fi
      [ $(( ($(date +%s) - t0) / 60 )) -lt "$WAIT_MAX" ] || break
      sleep 180
    done
    [ "$okw" = 1 ] || stop wait "超 ${WAIT_MAX} 分仍有人在播；包、安装脚本与部署脚本已在 ${HOST}，稍后 \`tools/release/release.sh ${VER} --host ${HOST} --app-dir ${APP} --service ${UNIT} --owner ${OWNER}$( [ "$NEED_ROOM" = 1 ] && echo ' --require-room')$( [ "$TRIAL" = 1 ] && echo ' --trial') --from wait\`"
  fi
fi
if should deploy; then
  if [ "$ROUTE" = first ]; then
    say "── deploy·首升（核包 → 记 ${UNIT} 内存上限与开机自启 → ${UNIT} 照跑时 install.sh --dir ${APP} --user ${RUSER} --no-packages → 核 releases/ 与单元模板 → 内存上限不同就照搬 → 停 ${UNIT}、关自启 → 起 novabot@${VER}；只断起新版那一会儿）"
    remote_run first "$RLOG" 900 deploy
    [ "$RRC" = 0 ] || stop deploy "首升没成（远端退 ${RRC}）；见上面远端日志：停旧之前失败＝旧服务照跑、安装目录要人收拾，收拾好之前别重启旧服务、机器也别重启（旧服务起不来、新版自启也关着）；停旧之后失败＝看「起回」那行"
  else
    say "── deploy·换版（novabot-switch-version ${VER}，$( [ "$PLAN" = hot ] && echo 热交接 || echo 普通重启)）"
    remote_run switch "$RLOG" 900 deploy
    [ "$RRC" = 0 ] || stop deploy "换版工具退 ${RRC}（原样输出见上面远端日志；它自己会把旧版起回来）"
  fi
fi

NU="novabot@${VER}"
if should verify; then
  say "── verify（${NU}）"
  if [ "$SANDBOX" = 1 ]; then
    say "〔沙盘〕journalctl -u ${NU} 横幅 NovaBot - (v${VER})／登录／已连接到直播间／ERROR 0／NRestarts 0／${APP}/releases/${VER}/BUILD-INFO"
    [ "$SB_ROUTE" = hot ] && say "〔沙盘〕另报（只报不判）：${RLOG} 里换版工具那句「已换到 …，中间断了约 N 秒（起点到 ready）」原样"
    say "〔沙盘〕另报（只报不判）：ssh ${HOST} 'bash /root/deploy-${VER}.sh measure'＝停旧版到第一间直播间连上几秒"
    say "〔沙盘〕核验红不自动退回，停语印：在 ${HOST} 上 sudo /usr/local/sbin/novabot-switch-version <上一版>（读 ${RLOG}「上一版」行）"
  else
    ssh_try "grep -E '^(起于|上一版) ' ${RLOG} 2>/dev/null; grep '中间断了约' ${RLOG} 2>/dev/null; echo T0END" || stop verify "探针无回"
    T0=$(printf '%s\n' "$SSHOUT" | sed -n 's/^起于 //p' | tail -1)
    PREV=$(printf '%s\n' "$SSHOUT" | sed -n 's/^上一版 //p' | tail -1)
    GAPL=$(printf '%s\n' "$SSHOUT" | grep '中间断了约' | tail -1)
    ROLLBACK="sudo /usr/local/sbin/novabot-switch-version ${PREV:-<上一版，${RLOG} 里没读到>}"
    verify_stop(){ say "退回（核验红不自动退回；要退就在 ${HOST} 上跑）：${ROLLBACK}"; stop verify "$1"; }
    if [ -n "$T0" ]; then SINCE="$T0"; say "核验窗起点 T0＝${T0}（${RLOG}）"
    else SINCE="10 min ago"; say "部署日志无「起于」行，核验窗回落 --since '10 min ago'"; fi
    Q="journalctl -u ${NU} -a --no-pager --since '${SINCE}'"
    # 登录与连房日志稍后才出现，每 15 秒读一次，最多等 120 秒。
    okv=0
    for i in $(seq 1 9); do
      ssh_try "${Q} | grep -c 'NovaBot - (v${VER})'; ${Q} | grep -c '已使用保存的登录凭据登录'; ${Q} | grep -c '已连接到直播间'; ${Q} | grep -c ' ERROR '; systemctl show ${NU} -p NRestarts --value; grep '^commit=' ${APP}/releases/${VER}/BUILD-INFO | cut -d= -f2; grep '^tree=' ${APP}/releases/${VER}/BUILD-INFO | cut -d= -f2" || verify_stop "探针无回"
      # 探针返回空白分隔的五个计数与两个哈希，按原顺序读取。
      # shellcheck disable=SC2046
      set -- $(printf '%s\n' "$SSHOUT" | tr '\n' ' ')
      [ $# -ge 7 ] || verify_stop "探针回了 $# 个数（期 7）：$(printf '%s' "$SSHOUT" | tr '\n' ' ')"
      if [ "$1" -ge 1 ] && [ "$2" -ge 1 ] && { [ "$NEED_ROOM" = 0 ] || [ "$3" -ge 1 ]; }; then okv=1; break; fi
      say "第 ${i}／9 探：横幅 ${1}／登录 ${2}／连房 ${3}（登录或连房还没落日志，等 15 秒）"
      sleep 15
    done
    say "横幅 ${1}／登录 ${2}／连房 ${3}$( [ "$NEED_ROOM" = 0 ] && echo '（靶 '"${HOST}"'：只报数不判红）')／ERROR ${4}／NRestarts ${5}／BUILD-INFO ${6:0:7}"
    [ -n "$GAPL" ] && say "换版工具原话：${GAPL}"
    if ssh_try "bash /root/deploy-${VER}.sh measure"; then
      G=$(printf '%s\n' "$SSHOUT" | sed -n 's/^GAP //p' | tail -1)
      case "$G" in
        ''|*[!0-9.]*) say "停旧版到第一间直播间连上：${G:-读不到}" ;;
        *) say "停旧版到第一间直播间连上：${G} 秒（journalctl 时间戳）" ;;
      esac
    else say "停旧版到第一间直播间连上：探针无回，没量到"; fi
    [ "$1" -ge 1 ] || verify_stop "没见 v${VER} 横幅"
    if [ "$NEED_ROOM" = 1 ]; then
      [ "$okv" = 1 ] || verify_stop "等满 120 秒仍没见登录（${2}）或连上直播间（${3}）"
    else
      [ "$okv" = 1 ] || verify_stop "等满 120 秒仍没见登录（${2}）"
    fi
    [ "$4" = 0 ] || verify_stop "ERROR ${4}"
    [ "$5" = 0 ] || verify_stop "NRestarts ${5}"
    buildinfo_ok "$6" "$7" "$TAG" "$REPO" || verify_stop "BUILD-INFO commit ${6} ≠ 标签 ${TAG} 所指（commit 与 tree 皆不合）"
  fi
fi
say "✅ 发版讫 ${TAG}（HEAD ${HEAD}；日志 ${LOG}）"
sandbox_cleanup
trial_cleanup
