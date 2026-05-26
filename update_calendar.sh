#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$REPO_DIR"

python3 bikernieki_calendar.py -m 3 -o bikernieki.ics

if git diff --quiet -- bikernieki.ics; then
    echo "[*] No changes in calendar events."
    exit 0
fi

git add bikernieki.ics
git commit -m "Update calendar events [skip ci]"
git push
echo "[+] Pushed updated calendar to GitHub."