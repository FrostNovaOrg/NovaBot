#!/usr/bin/env bash
# 起动冒烟尺：把产物放到一台「一份配置都没有」的机器上，它起不起得来
#
# 用法：tools/boot-smoke.sh [产物目录]        （默认 dist/build）
#       BOOT_SMOKE_PORT=7827 tools/boot-smoke.sh dist/build
# 退码：0＝起来了并答出 setupDone=false；1＝没起来、答不上来、或量不动
#
# ── 为什么单立一把尺 ────────────────────────────────────────────────────
# 整测全绿与「这个包起得来」是两件事，而它们之间没有任何一处把对方钉住：
# 单元测试里每个类都是自己 new 出来的，谁也不经过容器；容器在启动那一刻才第一次
# 按类型去凑构造参数，凑不齐就当场抛异常退出。于是「2578 个判据全绿、包却起不来」
# 是一个结构上完全可能的组合 —— 2026-09-04 实测到的正是这一种：
# 同一个类型在容器里有两个候选（一个类继承了另一个，两个都是组件），
# 注入点要一个，容器不知道该给哪一个，`NoUniqueBeanDefinitionException`，进程退出。
#
# 🔴 这把尺量的是**装配**，不是功能。它只回答一句话：这堆 jar 摆在一起，
#    在没有任何配置文件的情况下，Spring 能不能把上下文装起来并开始服务。
#    功能对不对由整测答；整测答不了的正是这一句。
#
# ── 为什么必须拷到临时目录里跑 ──────────────────────────────────────────
# 🔴 程序会在运行中往当前目录写 application.yml、datasource.json、data/、logs/。
#    直接在 dist/build 里起，跑完一次那个目录就不再是构建产出的那一份 ——
#    而**被写过的产物目录和刚构建出来的产物目录长得一样**，下一次再量就量到了
#    自己上一次留下的痕迹：第二跑的 setupDone 会是 true，红的那一路再也复现不出来。
#    拷贝是一次性的，临时目录跑完就删，每一跑都从同一个起点开始。
#
# 🔴 无条件删 application.yml 与 datasource.json，不是「有才删」：
#    这把尺的前提是「无配置」，而前提要落在谁都拦不住的位置。
#    问一句「它在不在」就等于承认它可能在，那时量到的就不是无配置那条路了。
#
# ── 端口 ────────────────────────────────────────────────────────────────
# 端口从命令行给（--server.port），不写进配置文件：写文件会让 setupDone 变成 true，
# 把要量的那一位当场抹掉。起之前先验端口空 —— 端口被别人占着时程序同样起不来，
# 而那种红与「装配坏了」的红在退码上长得一样。
set -uo pipefail

OUT="${1:-dist/build}"
PORT="${BOOT_SMOKE_PORT:-7827}"
TIMEOUT="${BOOT_SMOKE_TIMEOUT:-90}"
# 认 JAVA_HOME 而不是只认 PATH 上的 java：本机默认 java 与工程验过的那一版不是同一个
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    JAVA_BIN="${JAVA_HOME}/bin/java"
else
    JAVA_BIN="java"
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -d "$OUT" ] || OUT="$REPO_ROOT/$OUT"

if [ ! -f "$OUT/StarBotCore.jar" ]; then
    echo "量不动：$OUT 里没有 StarBotCore.jar" >&2
    exit 1
fi
OUT="$(cd "$OUT" && pwd)"

if ! command -v curl > /dev/null 2>&1; then
    echo "量不动：没有 curl" >&2
    exit 1
fi

echo "==> 产物：$OUT"
if [ -f "$OUT/BUILD-INFO" ]; then
    sed 's/^/    /' "$OUT/BUILD-INFO"
fi

# 端口空不空。lsof 无输出即空；lsof 不在时不假装量过。
# 🔴 /usr/sbin 要单独找一遍：macOS 的 lsof 装在那里，而它不在很多环境的 PATH 上——
#    只用 command -v 会静默走进「未验」那一支，而「没量」与「量过是空的」长得一样
LSOF=""
if command -v lsof > /dev/null 2>&1; then
    LSOF="lsof"
elif [ -x /usr/sbin/lsof ]; then
    LSOF="/usr/sbin/lsof"
fi
if [ -n "$LSOF" ]; then
    if [ -n "$("$LSOF" -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null)" ]; then
        echo "量不动：端口 $PORT 已被占用，起之前就不干净" >&2
        "$LSOF" -nP -iTCP:"$PORT" -sTCP:LISTEN >&2
        exit 1
    fi
else
    echo "    注意：没有 lsof，端口占用未验"
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/novabot-boot-smoke-XXXXXX")"
LOG="$WORK/boot.log"
PID=""

cleanup() {
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
        # 只杀本脚本自己起的那一个 pid，不按进程名找
        kill -TERM "$PID" 2>/dev/null
        for _ in $(seq 1 20); do
            kill -0 "$PID" 2>/dev/null || break
            sleep 0.5
        done
        if kill -0 "$PID" 2>/dev/null; then
            echo "    优雅停机 10 秒未退，改 KILL"
            kill -KILL "$PID" 2>/dev/null
            sleep 1
        fi
    fi
    rm -rf "$WORK"
}
trap cleanup EXIT

cp -R "$OUT/." "$WORK/"
# 前提：无配置。见文件头
rm -f "$WORK/application.yml" "$WORK/datasource.json"

echo "==> 起：$JAVA_BIN -jar StarBotCore.jar --server.port=$PORT （工作目录 ${WORK}）"
"$JAVA_BIN" -version > "$LOG" 2>&1
(
    cd "$WORK" || exit 1
    exec "$JAVA_BIN" -Djava.awt.headless=true -Dfile.encoding=UTF-8 \
        -Dloader.path=lib,plugins-lib -jar StarBotCore.jar \
        --server.port="$PORT" >> "$LOG" 2>&1
) &
PID=$!
echo "    pid=$PID"

STATE_URL="http://127.0.0.1:$PORT/config/api/auth/state"
DEADLINE=$((SECONDS + TIMEOUT))
BODY=""
TOKEN=""
RESULT=1

# 🔴 这条接口要带启动令牌。未配口令时控制台走的是「令牌即凭据」那一形态，
#    不带令牌一律 401，回的是一句「访问令牌不正确」——那句话与「程序没起来」在
#    退码上长得一样，而它其实说明程序已经起来了。令牌从启动日志里取，
#    与使用者照着日志里那个地址点进去是同一条路
while [ "$SECONDS" -lt "$DEADLINE" ]; do
    if ! kill -0 "$PID" 2>/dev/null; then
        wait "$PID"; code=$?
        echo
        echo "红：进程在答出之前就退了（退码 ${code}）"
        RESULT=1
        break
    fi

    if [ -z "$TOKEN" ]; then
        TOKEN="$(grep -oE 'config[?]token=[A-Za-z0-9_.-]+' "$LOG" 2>/dev/null | tail -n 1 | cut -d= -f2)"
    fi

    if [ -n "$TOKEN" ]; then
        BODY="$(curl -s -m 3 "$STATE_URL?token=$TOKEN" 2>/dev/null)"
        case "$BODY" in
            *'"setupDone":false'*)
                echo
                echo "绿：${SECONDS} 秒内起来并答出 setupDone=false"
                echo "    $STATE_URL -> $BODY"
                RESULT=0
                break
                ;;
            *'"setupDone":true'*)
                echo
                echo "红：答出的是 setupDone=true —— 这一跑不是无配置那条路"
                echo "    $STATE_URL -> $BODY"
                RESULT=1
                break
                ;;
        esac
    fi
    sleep 1
done

if [ "$RESULT" -ne 0 ] && [ "$SECONDS" -ge "$DEADLINE" ]; then
    echo
    if [ -z "$TOKEN" ]; then
        echo "红：$TIMEOUT 秒内启动日志上没出现控制台地址，取不到启动令牌"
    else
        echo "红：$TIMEOUT 秒内没能答出 setupDone —— 最后一次拿到的是：${BODY:-（空）}"
    fi
fi

# 红的时候先把 Spring 那段自陈搬出来。日志末几行常是无关的框架提示，
# 而「为什么起不来」写在更靠上的位置——只印末几行等于报了个红却答不了是谁
if [ "$RESULT" -ne 0 ] && grep -q "APPLICATION FAILED TO START" "$LOG" 2>/dev/null; then
    echo
    echo "==> 程序自陈的失败原因"
    sed -n '/APPLICATION FAILED TO START/,/^Action:/p' "$LOG" | sed 's/^/    /'
fi

echo
echo "==> 启动日志末 5 行"
tail -n 5 "$LOG" | sed 's/^/    /'

# 🔴 日志随临时目录一起删。红的那一跑若只剩这 5 行，等于报了个「起不来」却答不了为什么——
#    而失败原因通常在更靠上的堆栈里（末几行常是无关的框架提示）。要留就显式给个落点
if [ -n "${BOOT_SMOKE_LOG:-}" ]; then
    cp "$LOG" "$BOOT_SMOKE_LOG" && echo "    完整日志已留：$BOOT_SMOKE_LOG"
fi

exit "$RESULT"
