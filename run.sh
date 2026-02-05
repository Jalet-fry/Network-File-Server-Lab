#!/bin/bash
APP_BIN="build/install/Network-File-Server-Lab/bin/Network-File-Server-Lab"
if [ ! -f "$APP_BIN" ]; then
    echo "Error: Application not built. Run ./BUILD_PROJECT.sh first."
    exit 1
fi
chmod +x "$APP_BIN"
./$APP_BIN "$@"
