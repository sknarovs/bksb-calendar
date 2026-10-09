#!/usr/bin/env bash
# Daily cron job for the Raspberry Pi: sync the repo, keep the native binary current with the
# latest GitHub release, regenerate bikernieki.ics and push it.
set -euo pipefail

RELEASE_URL="${BKSB_RELEASE_URL:-https://github.com/sknarovs/bksb-calendar/releases/latest/download}"
ASSET="bikernieki-calendar-linux-$(uname -m)"
BIN="bin/bikernieki-calendar"

sync_repo() {
    git checkout -- bikernieki.ics
    if ! git pull --rebase --quiet; then
        git rebase --abort 2>/dev/null || true
        echo "[!] git pull failed; resolve manually."
        exit 1
    fi
}

# Installs the latest release into $BIN unless it is already current. Fails without touching $BIN.
install_latest_binary() {
    local tmp="$1" want
    curl -fsSL --retry 2 -o "$tmp/$ASSET.sha256" "$RELEASE_URL/$ASSET.sha256" || return 1
    want="$(cut -d' ' -f1 "$tmp/$ASSET.sha256")" || return 1
    if [[ -x "$BIN" && "$(sha256sum "$BIN" | cut -d' ' -f1)" == "$want" ]]; then
        return 0
    fi
    curl -fsSL --retry 2 -o "$tmp/$ASSET" "$RELEASE_URL/$ASSET" || return 1
    (cd "$tmp" && sha256sum --check --quiet "$ASSET.sha256") || return 1
    chmod +x "$tmp/$ASSET" || return 1
    "$tmp/$ASSET" --test || return 1
    mv -f "$tmp/$ASSET" "$BIN" || return 1
    echo "[+] Installed new binary (${want:0:12})."
}

update_binary() {
    local tmp status=0
    mkdir -p bin
    tmp="$(mktemp -d bin/.update.XXXXXX)"
    install_latest_binary "$tmp" || status=$?
    rm -rf "$tmp"
    if (( status != 0 )); then
        if [[ -x "$BIN" ]]; then
            echo "[!] Binary update failed; keeping the installed binary."
        else
            echo "[!] No binary installed and the download failed."
            exit 1
        fi
    fi
}

publish() {
    if git diff --quiet -- bikernieki.ics; then
        echo "[*] No changes in calendar events."
        return
    fi
    git add bikernieki.ics
    git commit -m "Update calendar events"
    git push
    echo "[+] Pushed updated calendar to GitHub."
}

main() {
    cd "$(dirname "$(readlink -f "$0")")"
    sync_repo
    update_binary
    "$BIN" -m 3 -o bikernieki.ics
    publish
}

main "$@"
