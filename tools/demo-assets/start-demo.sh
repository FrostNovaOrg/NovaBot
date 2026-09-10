#!/usr/bin/env bash
# Start NovaBot from dist/build in a throwaway directory, anonymous, 127.0.0.1:7827.
# Prints the one-shot console token, probes HTTP, then stops the process it started.
#
# Adding a streamer goes through the live platform lookup, which needs the network.
# This script does not add a synthetic streamer source; the setup / template / connection
# pages are reachable. The streamers API is expected to return an empty list.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${1:-$ROOT/dist/build}"
PORT="${DEMO_PORT:-7827}"
TIMEOUT="${DEMO_TIMEOUT:-90}"
KEEP="${DEMO_KEEP:-0}"

pick_java() {
    local brew_java="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    local cand=""
    if [ -n "${JAVA_HOME:-}" ]; then
        cand="${JAVA_HOME}/bin/java"
        if [ -e "${JAVA_HOME}/release" ] && "$cand" -version >/dev/null 2>&1; then
            JAVA_BIN="$cand"
            return 0
        fi
    fi
    cand="$(command -v java 2>/dev/null || true)"
    if [ -n "$cand" ] && env -u JAVA_HOME "$cand" -version >/dev/null 2>&1; then
        JAVA_BIN="$cand"
        JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
        return 0
    fi
    cand="${brew_java}/bin/java"
    if env JAVA_HOME="$brew_java" "$cand" -version >/dev/null 2>&1; then
        JAVA_HOME="$brew_java"
        JAVA_BIN="$cand"
        return 0
    fi
    echo "no usable java (need 17+)" >&2
    exit 1
}
pick_java

if [ ! -f "$OUT/NovaBot.jar" ]; then
    echo "missing $OUT/NovaBot.jar" >&2
    exit 1
fi
OUT="$(cd "$OUT" && pwd)"

LSOF=""
if command -v lsof >/dev/null 2>&1; then
    LSOF="lsof"
elif [ -x /usr/sbin/lsof ]; then
    LSOF="/usr/sbin/lsof"
fi
if [ -n "$LSOF" ] && [ -n "$("$LSOF" -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null)" ]; then
    echo "port $PORT is already in use" >&2
    "$LSOF" -nP -iTCP:"$PORT" -sTCP:LISTEN >&2
    exit 1
fi

if pgrep -f 'NovaBot\.jar' >/dev/null 2>&1; then
    echo "NovaBot.jar is already running; not starting a second copy" >&2
    pgrep -f 'NovaBot\.jar' >&2
    exit 1
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/novabot-demo-XXXXXX")"
LOG="$WORK/boot.log"
PID=""

cleanup() {
    if [ -n "${PID:-}" ] && kill -0 "$PID" 2>/dev/null; then
        kill -TERM "$PID" 2>/dev/null || true
        for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
            kill -0 "$PID" 2>/dev/null || break
            sleep 0.5
        done
        if kill -0 "$PID" 2>/dev/null; then
            kill -KILL "$PID" 2>/dev/null || true
            sleep 1
        fi
    fi
    if [ "$KEEP" != "1" ]; then
        rm -rf "$WORK"
    else
        echo "kept $WORK"
    fi
}
trap cleanup EXIT

cp -R "$OUT/." "$WORK/"
rm -f "$WORK/application.yml" "$WORK/datasource.json"

echo "==> work dir $WORK"
echo "==> anonymous console on 127.0.0.1:$PORT"
(
    cd "$WORK" || exit 1
    exec "$JAVA_BIN" -Djava.awt.headless=true -Dfile.encoding=UTF-8 \
        -Dloader.path=lib,plugins,plugins-lib \
        -jar NovaBot.jar \
        --server.port="$PORT" \
        --server.address=127.0.0.1 \
        --novabot.bilibili.account.anonymous=true \
        >>"$LOG" 2>&1
) &
PID=$!
echo "    pid=$PID"

TOKEN=""
CODE_ROOT=""
CODE_CONFIG=""
DEADLINE=$((SECONDS + TIMEOUT))
while [ "$SECONDS" -lt "$DEADLINE" ]; do
    if ! kill -0 "$PID" 2>/dev/null; then
        wait "$PID" || true
        echo "process exited before it answered HTTP" >&2
        tail -n 40 "$LOG" >&2
        exit 1
    fi
    if [ -z "$TOKEN" ]; then
        TOKEN="$(grep -oE 'config[?]token=[A-Za-z0-9_.-]+' "$LOG" 2>/dev/null | tail -n 1 | cut -d= -f2 || true)"
    fi
    CODE_ROOT="$(python3 - "$PORT" / <<'PY'
import http.client, sys
port, path = int(sys.argv[1]), sys.argv[2]
conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
try:
    conn.request("GET", path)
    resp = conn.getresponse()
    print(resp.status)
except Exception:
    print("0")
finally:
    conn.close()
PY
)"
    CODE_CONFIG="$(python3 - "$PORT" /config <<'PY'
import http.client, sys
port, path = int(sys.argv[1]), sys.argv[2]
conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
try:
    conn.request("GET", path)
    resp = conn.getresponse()
    print(resp.status)
except Exception:
    print("0")
finally:
    conn.close()
PY
)"
    # Console lives at /config. Bare / has no handler (404) on this build.
    if [ "$CODE_CONFIG" = "200" ] || [ "$CODE_CONFIG" = "302" ] || [ "$CODE_CONFIG" = "401" ]; then
        break
    fi
    sleep 1
done

if [ "$CODE_CONFIG" != "200" ] && [ "$CODE_CONFIG" != "302" ] && [ "$CODE_CONFIG" != "401" ]; then
    echo "HTTP /config did not answer (root=$CODE_ROOT config=$CODE_CONFIG) within ${TIMEOUT}s" >&2
    tail -n 40 "$LOG" >&2
    exit 1
fi

echo "HTTP / -> ${CODE_ROOT:-empty}"
echo "HTTP /config -> $CODE_CONFIG"
if [ -n "$TOKEN" ]; then
    echo "console token: $TOKEN"
    echo "console url: http://127.0.0.1:${PORT}/config?token=$TOKEN"
    STREAMERS="$(python3 - "$PORT" "$TOKEN" <<'PY'
import http.client, sys
port, token = int(sys.argv[1]), sys.argv[2]
conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
try:
    conn.request("GET", "/config/api/streamers?token=" + token)
    resp = conn.getresponse()
    body = resp.read().decode("utf-8", "replace")
    print(resp.status)
    print(body[:2000])
except Exception as e:
    print("0")
    print(str(e))
finally:
    conn.close()
PY
)"
    echo "streamers API:"
    echo "$STREAMERS"
else
    echo "console token not found in startup log; setup page may still be open at http://127.0.0.1:${PORT}/"
fi

echo "limit: no synthetic streamer source; adding a streamer looks up the live platform and needs the network."
echo "reachable without a streamer: setup, templates, connection pages."

# stop the process we started; trap also runs cleanup
if [ -n "$PID" ]; then
    kill -TERM "$PID" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
        kill -0 "$PID" 2>/dev/null || break
        sleep 0.5
    done
    if kill -0 "$PID" 2>/dev/null; then
        kill -KILL "$PID" 2>/dev/null || true
        sleep 1
    fi
    PID=""
fi

if pgrep -f 'NovaBot\.jar' >/dev/null 2>&1; then
    echo "NovaBot.jar still running after stop" >&2
    pgrep -f 'NovaBot\.jar' >&2
    exit 1
fi
echo "pgrep NovaBot.jar: empty"
