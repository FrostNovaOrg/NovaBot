#!/usr/bin/env bash
# Start NovaBot from dist/build in a throwaway directory, anonymous, 127.0.0.1:7827.
# Prints the one-shot console token, probes HTTP, then stops the process it started.
# If the token line does not show up within DEMO_TIMEOUT it says so and exits 1.
# Set DEMO_HOLD=<seconds> (default 0) to leave the instance answering for that many
# seconds after the probe before the usual stop, e.g. to screenshot the console.
# Ctrl-C or TERM during the hold stops it early and cleans up as usual.
#
# Set DEMO_SEED=1 to preset sample data before the start: one demo streamer and
# group, a few months of past sessions, today's push records and an engineering
# log, plus a config that keeps the instance from reaching the live platform
# (no live-room connection, no polling, no update check). Platform calls that
# no switch can turn off (the startup streamer lookup and the credential init)
# are routed to a local port nothing listens on, so they fail on this machine
# instead of reaching the network. Dates are computed from the start moment.
# All sample names and numbers are made up; nothing in them comes from a real
# account. Without the switch nothing changes.
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
HOLD="${DEMO_HOLD:-0}"
SEED="${DEMO_SEED:-0}"

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
            sleep 1
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

if [ "$SEED" = "1" ]; then
    # preset sample data (see the header comment); refuses to overwrite, the work
    # directory is fresh from mktemp so anything already there is unexpected
    python3 "$ROOT/tools/demo-assets/demo-seed/make-demo-seed.py" "$WORK"
fi

PROXY_ARGS=""
if [ "$SEED" = "1" ]; then
    # sample-data runs must not open any outbound connection: outbound HTTP is
    # routed to a local port nothing listens on, so every platform call fails on
    # this machine at once (loopback targets keep going direct and are unaffected)
    PROXY_ARGS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9"
fi

echo "==> work dir $WORK"
echo "==> anonymous console on 127.0.0.1:$PORT"
(
    cd "$WORK" || exit 1
    exec "$JAVA_BIN" -Djava.awt.headless=true -Dfile.encoding=UTF-8 \
        -Dloader.path=lib,plugins,plugins-lib $PROXY_ARGS \
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
    # /config answers as soon as the web server is up; the token line is logged later,
    # once startup finishes. Wait for both, or the token read above can come back empty.
    if { [ "$CODE_CONFIG" = "200" ] || [ "$CODE_CONFIG" = "302" ] || [ "$CODE_CONFIG" = "401" ]; } \
        && [ -n "$TOKEN" ]; then
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
    # Without the token the console at /config only answers 401, so there is nothing to
    # look at: say so and stop here instead of holding an instance nobody can enter.
    echo "console token line did not appear in the startup log within ${TIMEOUT}s;" \
        "the console at http://127.0.0.1:${PORT}/config cannot be entered without it" >&2
    if [ "$HOLD" -gt 0 ]; then
        echo "not holding: the instance would not be reachable" >&2
    fi
    tail -n 40 "$LOG" >&2
    exit 1
fi

if [ "$SEED" = "1" ]; then
    echo "seeded: sample data is preset (one demo streamer and group, past sessions, today's records and engineering log); outbound platform calls are switched off or fail on this machine."
else
    echo "limit: no synthetic streamer source; adding a streamer looks up the live platform and needs the network."
    echo "reachable without a streamer: setup, templates, connection pages."
fi

# optional hold: leave the instance answering for DEMO_HOLD seconds, then stop as usual
if [ "$HOLD" -gt 0 ]; then
    echo "holding for ${HOLD}s at http://127.0.0.1:${PORT}/config (Ctrl-C stops early)"
    trap 'exit 130' INT
    trap 'exit 143' TERM
    HOLD_DEADLINE=$((SECONDS + HOLD))
    while [ "$SECONDS" -lt "$HOLD_DEADLINE" ]; do
        if ! kill -0 "$PID" 2>/dev/null; then
            echo "process exited on its own during the hold" >&2
            break
        fi
        sleep 1
    done
fi

# stop the process we started; trap also runs cleanup
if [ -n "$PID" ]; then
    kill -TERM "$PID" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
        kill -0 "$PID" 2>/dev/null || break
        sleep 1
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
