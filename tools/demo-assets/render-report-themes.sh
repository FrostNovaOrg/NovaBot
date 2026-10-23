#!/usr/bin/env bash
# Render the report palette samples: three report kinds (live report, weekly
# report, dynamic push) for every named palette in ReportPalette.PRESETS,
# drawn from the synthetic demo data, scaled to 540 px JPEG and embedded as
# data URIs in samples/report-themes/index.html with the contrast table.
# Usage: bash tools/demo-assets/render-report-themes.sh
#
# Runs ReportThemeSamplesTest with -Dreport.themes.publish=true. Without that
# flag the page is not written: the normal test run skips it, and the page is
# not kept in the repository.
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
    -Dreport.themes.publish=true \
    -Dtest=ReportThemeSamplesTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test
