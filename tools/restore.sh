#!/usr/bin/env bash
# Put the TV back the way it was before tools/disable.sh, using the state recorded at that time:
# packages that were already disabled stay disabled, Home goes back to the component that owned it,
# and the animation scales go back to their recorded values. Pass the same list you ran disable with.
#
# Usage: TV_HOST=192.168.1.50 tools/restore.sh examples/xiaomi-mi-tv-4a/debloat.txt [stock.launcher.package]
set -euo pipefail
LIST="${1:?usage: restore.sh <debloat list> [stock launcher package]}"
# Resolve the list against the caller's directory before moving into the repo.
LIST_DIR="$(cd "$(dirname "$LIST")" && pwd)"
LIST="$LIST_DIR/$(basename "$LIST")"
cd "$(dirname "$0")/.."
STOCK="${2:-com.google.android.tvlauncher}"
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
# recovery.py reads and validates the list itself, prints the batch for tv.py and says on stderr what
# it will leave alone. Without a record it warns and falls back to enabling the whole list. pipefail
# keeps a refused plan from reaching the TV.
"$PY" -I tools/recovery.py --commands "$LIST" "$STOCK" | "$PY" -I tools/tv.py --batch
