#!/usr/bin/env bash
# Run the demo asset generator / report renderer.
# Usage: bash tools/demo-assets/run-java.sh [generate|render|all]
#
# Invokes DemoAssetsTest via the reactor (same local store as lane verify).
# generate and render both refresh docs/assets/demo plus report-demo.png —
# the report needs the avatars sitting next to it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
M2="${MAVEN_REPO_LOCAL:-$HOME/.m2-lanes/novabot/repository}"

if [ ! -x "${JAVA_HOME}/bin/java" ]; then
    echo "missing java: ${JAVA_HOME}/bin/java" >&2
    exit 1
fi

cd "$ROOT"
exec env JAVA_HOME="$JAVA_HOME" mvn -B -Pinstall -f "$ROOT/pom.xml" \
    -Dmaven.repo.local="$M2" \
    -Dtest=DemoAssetsTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    verify
