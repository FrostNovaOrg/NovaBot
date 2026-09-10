#!/usr/bin/env bash
# Feed a synthetic 90-minute session into BilibiliLiveReportPainter.paint
# and write docs/assets/report-demo.png (width 1100-1300, at most 600 KB)
# plus docs/assets/report-demo-top.png (top 1200x1340 crop, at most 200 KB).
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$DIR/run-java.sh" render
