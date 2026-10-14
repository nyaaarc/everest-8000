#!/usr/bin/env bash
# Starts a Prism Launcher instance with the Cinema API switched on, and returns once the game process is up.
# The API itself comes up a little later; poll it with the Python client (Cinema.wait_ready()).
#
# Usage (Git Bash): launch_game.sh "<instance name>" [server address, default localhost] [port, default 25580]
# Env: PRISM_DIR (default %APPDATA%/PrismLauncher), PRISM_EXE (prismlauncher.exe path), CINEMA_WORLD=1 to skip --server
# Writes <instance>/minecraft/config/everest-cinema.json with a fresh random token on every launch.
set -u
name=${1:?instance name}
server=${2:-localhost}
port=${3:-25580}
PRISM_DIR=${PRISM_DIR:-$(cygpath "$APPDATA")/PrismLauncher}
PRISM_EXE=${PRISM_EXE:-"$LOCALAPPDATA\Programs\PrismLauncher\prismlauncher.exe"}
inst="$PRISM_DIR/instances/$name/minecraft"
[ -d "$inst" ] || { echo "ABORT: no instance folder $inst"; exit 2; }
ls "$inst/mods"/everest-cinema-*.jar >/dev/null 2>&1 || { echo "ABORT: the Cinema mod jar isn't in $inst/mods"; exit 2; }

if tasklist //FI "IMAGENAME eq javaw.exe" 2>/dev/null | grep -qi javaw.exe; then
    echo "ABORT: Minecraft is already running"; exit 2
fi

token=$(python -c "import secrets; print(secrets.token_hex(16))")
mkdir -p "$inst/config"
printf '{"enabled": true, "port": %d, "token": "%s", "ffmpeg": "ffmpeg"}\n' "$port" "$token" > "$inst/config/everest-cinema.json"

# Prism only scans for instances at startup, so start it fresh.
if tasklist //FI "IMAGENAME eq prismlauncher.exe" 2>/dev/null | grep -qi prismlauncher.exe; then
    taskkill //IM prismlauncher.exe >/dev/null 2>&1
    for _ in $(seq 1 20); do tasklist //FI "IMAGENAME eq prismlauncher.exe" 2>/dev/null | grep -qi prismlauncher.exe || break; sleep 1; done
    taskkill //IM prismlauncher.exe //F >/dev/null 2>&1
fi
args="'--launch','\"$name\"'"
[ "${CINEMA_WORLD:-0}" = 1 ] || args="$args,'--server','$server'"
powershell.exe -NoProfile -Command "Start-Process -FilePath '$PRISM_EXE' -ArgumentList $args"
echo "launched $(date +%T)"
for _ in $(seq 1 60); do tasklist //FI "IMAGENAME eq javaw.exe" 2>/dev/null | grep -qi javaw.exe && break; sleep 2; done
if ! tasklist //FI "IMAGENAME eq javaw.exe" 2>/dev/null | grep -qi javaw.exe; then
    echo "ABORT: the game didn't start within 2 minutes. Prism log tail:"
    tail -5 "$PRISM_DIR/logs/PrismLauncher-0.log"
    exit 4
fi
echo "game running $(date +%T); API on 127.0.0.1:$port (token in $inst/config/everest-cinema.json)"
