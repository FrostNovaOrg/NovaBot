#!/usr/bin/env bash
# Run the demo asset generator / report renderer.
# Usage: bash tools/demo-assets/run-java.sh [generate|render|all]
#
# Invokes DemoAssetsTest via the reactor and publishes into docs/assets/
# (-Ddemo.publish=true). Without that flag, tests write target/demo-assets/
# and leave the working tree clean. generate and render both refresh
# docs/assets/demo plus report-demo.png and report-demo-top.png —
# the report needs the avatars sitting next to it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
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
M2="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"

cd "$ROOT"
exec env JAVA_HOME="$JAVA_HOME" mvn -B -Pinstall -f "$ROOT/pom.xml" \
    -Dmaven.repo.local="$M2" \
    -Ddemo.publish=true \
    -Dtest=DemoAssetsTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    verify
