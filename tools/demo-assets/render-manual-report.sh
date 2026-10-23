#!/usr/bin/env bash
# Render the two report images used by the manual from the synthetic demo
# session, in the factory default palette:
#   manual/images/09-stream-report.png      the whole report
#   manual/images/01-stream-report-top.png  its top 1340 rows
# Usage: bash tools/demo-assets/render-manual-report.sh
#
# Runs DemoAssetsTest#writesManualReportImages with -Dmanual.publish=true.
# Without that flag the images go to target/demo-assets/manual. The README
# sample docs/assets/report-demo.png is not touched; see render-report.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# Same choice as run-java.sh: a newer default JDK leaves Lombok inert.
pick_java_home() {
    local brew_java="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    if [ -n "${JAVA_HOME:-}" ] && [ -e "${JAVA_HOME}/release" ] && "${JAVA_HOME}/bin/java" -version >/dev/null 2>&1; then
        return 0
    fi
    if "${brew_java}/bin/java" -version >/dev/null 2>&1; then
        JAVA_HOME="$brew_java"
        return 0
    fi
    local cand
    cand="$(command -v java 2>/dev/null || true)"
    if [ -n "$cand" ]; then
        JAVA_HOME="$(cd "$(dirname "$cand")/.." && pwd)"
        return 0
    fi
    echo "no usable java (need 17+)" >&2
    exit 1
}
pick_java_home

cd "$ROOT"
exec env JAVA_HOME="$JAVA_HOME" mvn -B -f "$ROOT/pom.xml" \
    -pl :novacore,:nova-core,:nova-bilibili,:nova-report \
    -Dmanual.publish=true \
    -Dtest='DemoAssetsTest#writesManualReportImages' \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test
