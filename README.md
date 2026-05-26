# Biķernieku Race Track Calendar Scraper & iCal Feed

This tool scrapes the events calendar from [bksb.lv](https://bksb.lv/index.php/2014-01-03-13-49-44/month.calendar/) and generates an iCalendar (`.ics`) subscription feed.

The calendar shows events that **block public access** to the race track (open 6:00–23:00 daily). Events at the office, speedway stadium, or museum circuit are excluded since they don't impact visitors. All events include a **40-minute buffer** before and after (as noted on the official site).

---

## Subscribe with iCloud Calendar

**Subscription URL:**
```
http://localhost:8080/calendar.ics
```

Start the local server first (see Local Usage below), then:

### Steps for iCloud Calendar (iPhone / Mac):
1. On **iPhone**: go to **Settings → Calendar → Accounts → Add Account → Other → Add Subscribed Calendar**
2. Paste the URL above and tap **Next**
3. Set a name like `"Biķernieku Trase"`, tap **Save**
4. On **Mac**: open Calendar app → **File → New Calendar Subscription…** → paste the URL

> **Tip:** iCloud Calendar polls subscribed calendars roughly every hour.

---

## How It Works

1. The scraper fetches the current + next 2 months from bksb.lv
2. Events at non-blocking locations are filtered out:
   - `BKSB Birojs` (office)
   - `BKSB Spīdveja stadions` (speedway stadium — separate venue)
   - `BKSB "Motormuzeja līkums"` (museum circuit)
4. Each remaining event gets a **40-minute buffer** added before and after
5. The updated `bikernieki.ics` file is written to disk
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

## CLI Reference

| Flag | Default | Description |
|------|---------|-------------|
| `-o`, `--output` | `bikernieki.ics` | Output file path |
| `-m`, `--months` | `3` | Months to scrape (current + N-1 ahead) |
| `-s`, `--serve` | off | Start local HTTP dashboard server |
| `-p`, `--port` | `8080` | Server port |
| `-t`, `--test` | off | Run self-tests and exit |
