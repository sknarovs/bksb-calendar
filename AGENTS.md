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
- Network requests use a **20-second timeout with 1 retry** (2-second backoff) because `bksb.lv` is often unreachable from GitHub Actions runners. Worst-case total hang is ~2 minutes, not 5+.
- **Excludes non-blocking locations** (office, speedway stadium, museum circuit) via normalized string matching on tooltip "Kur:" field.
- Adds a **40-minute buffer** before and after every event; handles midnight crossovers.
- **Data preservation guard**: if scraping returns 0 events and a valid `.ics` file already exists, the script aborts with exit code 1 instead of overwriting the file with an empty calendar.
- Generates stable UIDs with MD5 hashes so calendar apps can deduplicate correctly.
- Implements RFC 5545 UTF-8 byte folding manually (`fold_ics_line`).

## CI / Deployment

- GitHub Actions workflow (`.github/workflows/update_calendar.yml`) runs daily at 04:00 UTC and on manual dispatch.
- Workflow **commits `bikernieki.ics` back to the repo**; requires `contents: write` permission.
- Users subscribe to the raw GitHub URL of the `.ics` file; iCloud polls roughly hourly.

## Testing

- Tests are self-contained assertions inside `run_unit_tests()`. No external test runner.
- Verifies: HTML parser extraction, time buffer math, midnight crossing, location normalization, line folding, and UID stability.

## HTTP Server Behavior

- Serves `/calendar.ics` (subscription feed) and `/` (dashboard UI).
- Auto-refreshes cache in a **background thread** if the file is older than 12 hours, to avoid blocking requests.
- Falls back to disk cache if scraping fails.
