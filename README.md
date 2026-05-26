# Biķernieku Race Track Calendar Scraper & iCal Feed

This tool scrapes the events calendar from [bksb.lv](https://bksb.lv/index.php/2014-01-03-13-49-44/month.calendar/) and generates an iCalendar (`.ics`) subscription feed.

The calendar shows events that **block public access** to the race track (open 6:00–23:00 daily). Events at the office, speedway stadium, or museum circuit are excluded since they don't impact visitors. All events include a **40-minute buffer** before and after (as noted on the official site).

---

## Subscribe with iCloud Calendar

Once set up on your Raspberry Pi, the `.ics` file is pushed to GitHub and stays updated daily.

**Subscription URL:**
```
https://raw.githubusercontent.com/sknarovs/bksb-calendar/main/bikernieki.ics
```


### Steps for iCloud Calendar (iPhone / Mac):
1. On **iPhone**: go to **Settings → Calendar → Accounts → Add Account → Other → Add Subscribed Calendar**
2. Paste the raw GitHub URL above and tap **Next**
3. Set a name like `"Biķernieku Trase"`, tap **Save**
4. On **Mac**: open Calendar app → **File → New Calendar Subscription…** → paste the URL

> **Tip:** iCloud Calendar polls subscribed calendars roughly every hour, so events appear shortly after each daily update.

---

## How It Works

1. The scraper fetches the current + next 2 months from bksb.lv
2. Events at non-blocking locations are filtered out:
   - `BKSB Birojs` (office)
   - `BKSB Spīdveja stadions` (speedway stadium — separate venue)
   - `BKSB "Motormuzeja līkums"` (museum circuit)
4. Each remaining event gets a **40-minute buffer** added before and after
5. The updated `bikernieki.ics` file is committed and pushed to GitHub
6. Your subscribed calendar picks up the changes automatically

---

## Local Usage

Requires only Python 3 — no packages needed.

```bash
# Run scraper once and save to file
python3 bikernieki_calendar.py -m 3 -o bikernieki.ics

# Run local dashboard server (http://localhost:8080)
python3 bikernieki_calendar.py --serve --port 8080

# Run self-tests
python3 bikernieki_calendar.py --test
```

---

## Raspberry Pi Automation (DietPi)

The included `update_calendar.sh` script scrapes the calendar and pushes changes to GitHub. Set it up as a daily cron job on your Pi.

### 1. Clone the repo and verify it works

```bash
git clone https://github.com/sknarovs/bksb-calendar.git ~/bksb-calendar
cd ~/bksb-calendar
python3 bikernieki_calendar.py --test
python3 bikernieki_calendar.py -m 3 -o bikernieki.ics
```

### 2. Set up Git authentication

DietPi has `git` pre-installed. For pushing to GitHub, use an SSH key:

```bash
ssh-keygen -t ed25519 -C "dietpi-bikernieku" -f ~/.ssh/id_ed25519 -N ""
cat ~/.ssh/id_ed25519.pub
# Add this key as a Deploy Key (with write access) at github.com/sknarovs/bksb-calendar/settings/keys
# Or add it as an SSH key to your GitHub account

# Switch the remote to SSH:
cd ~/bksb-calendar
git remote set-url origin git@github.com:sknarovs/bksb-calendar.git
```

Test it:
```bash
git push  # should succeed without prompting for a password
```

### 3. Add a daily cron job

```bash
crontab -e
```

Add this line to run every day at 07:00 Riga time (05:00 UTC):
```
0 5 * * * /home/username/bksb-calendar/update_calendar.sh >> /tmp/bikernieku-cron.log 2>&1
```

---

## CLI Reference

| Flag | Default | Description |
|------|---------|-------------|
| `-o`, `--output` | `bikernieki.ics` | Output file path |
| `-m`, `--months` | `3` | Months to scrape (current + N-1 ahead) |
| `-s`, `--serve` | off | Start local HTTP dashboard server |
| `-p`, `--port` | `8080` | Server port |
| `-t`, `--test` | off | Run self-tests and exit |
