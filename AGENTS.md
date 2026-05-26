# AGENTS.md

Single-file Python scraper that generates an iCalendar feed for Biķernieku Race Track.

## Developer Commands

- **Run self-tests** (built-in, no pytest): `python3 bikernieki_calendar.py --test`
- **Generate ICS file**: `python3 bikernieki_calendar.py -m 3 -o bikernieki.ics`
- **Start local dashboard/server**: `python3 bikernieki_calendar.py --serve --port 8080`

## Key Architecture Facts

- **Pure stdlib Python 3** — no `requirements.txt`, `pyproject.toml`, or external packages. Do not create virtualenvs or install dependencies.
- **Single script** (`bikernieki_calendar.py`, ~1000 lines) contains scraper, ICS generator, HTTP dashboard server, and self-tests.
- Uses `html.parser.HTMLParser` to scrape JEvents calendar HTML from `bksb.lv`.
- Network requests use a **20-second timeout with 1 retry** (2-second backoff) because `bksb.lv` can be slow to respond.
- **Excludes non-blocking locations** (office, speedway stadium, museum circuit) via normalized string matching on tooltip "Kur:" field.
- Adds a **40-minute buffer** before and after every event; handles midnight crossovers.
- **Data preservation guard**: if scraping returns 0 events and a valid `.ics` file already exists, the script aborts with exit code 1 instead of overwriting the file with an empty calendar.
- Generates stable UIDs with MD5 hashes so calendar apps can deduplicate correctly.
- Implements RFC 5545 UTF-8 byte folding manually (`fold_ics_line`).

## Automation / Deployment

- **`update_calendar.sh`** runs the scraper and pushes changes to GitHub — intended for a cron job on a home server (e.g. Raspberry Pi).
- Cron job runs daily; the `.ics` file in the repo stays current for calendar subscription.
- Requires SSH key auth for `git push` to GitHub (deploy key or personal SSH key).

## Testing

- Tests are self-contained assertions inside `run_unit_tests()`. No external test runner.
- Verifies: HTML parser extraction, time buffer math, midnight crossing, location normalization, line folding, and UID stability.

## HTTP Server Behavior

- Serves `/calendar.ics` (subscription feed) and `/` (dashboard UI).
- Auto-refreshes cache in a **background thread** if the file is older than 12 hours, to avoid blocking requests.
- Falls back to disk cache if scraping fails.
