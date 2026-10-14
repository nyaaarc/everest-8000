#!/usr/bin/env bash
# Headless batch render: launches the game with the Cinema API, renders a shots file, restores state, quits.
#
# Usage (Git Bash): render_shots.sh <shots.json> [out-dir] [render_shots.py options: --shots a,b --preview --stills N ...]
# Env: CINEMA_INSTANCE (Prism instance name, default "Everest Cinema"), CINEMA_SERVER (default localhost),
#      CINEMA_PORT (default 25580), CINEMA_TIMEOUT_MIN (kill the game after this many minutes, default 120),
#      CINEMA_PRE_HOOK / CINEMA_POST_HOOK (commands run before launch / after the game exits, e.g. a check that the
#      account isn't online, or a player-data backup and restore; a non-zero pre-hook aborts).
set -u
here="$(cd "$(dirname "$0")" && pwd)"
spec=${1:?shots.json}
shift
out=""
if [ $# -gt 0 ] && [ "${1#--}" = "$1" ]; then out=$1; shift; fi
INSTANCE=${CINEMA_INSTANCE:-Everest Cinema}
PRISM_DIR=${PRISM_DIR:-$(cygpath "$APPDATA")/PrismLauncher}
inst_dir="$PRISM_DIR/instances/$INSTANCE"
limit=${CINEMA_TIMEOUT_MIN:-120}

python "$here/render_shots.py" "$spec" --validate-only "$@" || exit 2
if [ -n "${CINEMA_PRE_HOOK:-}" ]; then
    bash -c "$CINEMA_PRE_HOOK" || { echo "ABORT: pre-hook failed"; exit 3; }
fi
bash "$here/launch_game.sh" "$INSTANCE" "${CINEMA_SERVER:-localhost}" "${CINEMA_PORT:-25580}" || exit 4

args=("$spec" --instance "$inst_dir")
[ -n "$out" ] && args+=(--out "$out")
timeout $((limit * 60)) python "$here/render_shots.py" "${args[@]}" "$@"
status=$?
# The runner asks the game to quit; make sure it is gone.
for _ in $(seq 1 60); do tasklist //FI "IMAGENAME eq javaw.exe" 2>/dev/null | grep -qi javaw.exe || break; sleep 2; done
if tasklist //FI "IMAGENAME eq javaw.exe" 2>/dev/null | grep -qi javaw.exe; then
    echo "game still running after the batch; killing it"
    taskkill //IM javaw.exe //F >/dev/null 2>&1
fi
if [ -n "${CINEMA_POST_HOOK:-}" ]; then bash -c "$CINEMA_POST_HOOK"; fi
echo "--- API log tail"
tail -20 "$inst_dir/minecraft/cinema/api.log" 2>/dev/null
exit $status
