#!/usr/bin/env bash
# Write three 256x256 avatar PNGs and one 1280x720 cover PNG into docs/assets/demo/.
# No network. Fixed seed. Re-run yields the same bytes.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$DIR/run-java.sh" generate
