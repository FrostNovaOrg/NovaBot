#!/usr/bin/env bash
# Feed a synthetic 90-minute session into BilibiliLiveReportPainter.paint
# and write docs/assets/report-demo.png (width 1100-1300, at most 600 KB).
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$DIR/run-java.sh" render
