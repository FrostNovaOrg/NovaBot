#!/usr/bin/env bash
# Render the two report images used by the manual from the synthetic demo
# session, in the factory default palette:
#   09-stream-report.png      the whole report
#   01-stream-report-top.png  its top 1340 rows
# Usage: bash tools/demo-assets/render-manual-report.sh [--publish]
#
# By default the images go to target/demo-assets/manual and manual/images is
# not touched; compare them there first. --publish writes them into
# manual/images instead, replacing the two files.
#
# Runs DemoAssetsTest#writesManualReportImages (with -Dmanual.publish=true
# under --publish). The README sample docs/assets/report-demo.png is not
# touched; see render-report.sh.
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
PUBLISH=false
case "${1:-}" in
    '') ;;
    --publish) PUBLISH=true ;;
    *) echo "usage: render-manual-report.sh [--publish]" >&2; exit 2 ;;
esac
pick_java_home

cd "$ROOT"
exec env JAVA_HOME="$JAVA_HOME" mvn -B -f "$ROOT/pom.xml" \
    -pl :novacore,:nova-core,:nova-bilibili,:nova-report \
    -Dmanual.publish="$PUBLISH" \
    -Dtest='DemoAssetsTest#writesManualReportImages' \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test
