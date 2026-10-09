# Regression fixtures

The files in this directory are what the Python version of the scraper
(`bikernieki_calendar.py` at commit `ff57ef7`, run with Python 3.14.8) extracted from the month
pages in `../pages/`. They were fetched from bksb.lv on 2026-10-10.

The Java tests compare their own output with these files, so they show that the Java version
finds the same events (including UIDs) as the version it replaced.

| File | Content |
|---|---|
| `<yyyy-MM>.tsv` | Events found on `../pages/<yyyy-MM>.html` after location exclusions, in page order. Tab-separated: `uid`, `start_date`, `start_time`, `end_date`, `end_time`, `summary`, `category`, `location`, `url`. |
| `calendar.ics` | Calendar the Python version built for the window 2026-10 to 2026-12, with every `DTSTAMP` set to `20261009T000000Z`. |

What the pages cover:

- **2026-07:** all four excluded locations, with case and diacritic variants.
- **2026-08:** the busiest month, with 90 events.
- **2026-10 to 2026-12:** the live three-month window.

Midnight crossings appear only on excluded office events (`08:00-00:00 Biroja noma…`). Kept
overnight events are covered by unit tests instead.

## How they were generated

From the repository root, with the Python version still present:

```bash
python3 -I generate_expected.py
```

`generate_expected.py`:

```python
# Regenerates src/test/resources/expected/ from the Python version (run from the repo root).
import importlib.util, io, re
from pathlib import Path

spec = importlib.util.spec_from_file_location("bc", "bikernieki_calendar.py")
bc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bc)

PAGES = Path("src/test/resources/pages")
EXPECTED = Path("src/test/resources/expected")
FIELDS = ["uid", "start_date", "start_time", "end_date", "end_time",
          "summary", "category", "location", "url"]


def fake_urlopen(request, timeout=None):
    year, month = re.search(r"/month\.calendar/(\d{4})/(\d{2})/", request.full_url).groups()
    return io.BytesIO((PAGES / f"{year}-{month}.html").read_bytes())


bc.urllib.request.urlopen = fake_urlopen
bc.time.sleep = lambda seconds: None

for page in sorted(PAGES.glob("*.html")):
    year, month = map(int, page.stem.split("-"))
    rows = ["\t".join(FIELDS)]
    for event in bc.fetch_and_scrape_month(year, month):
        values = [event[field] for field in FIELDS]
        assert not any("\t" in v or "\n" in v for v in values), values
        rows.append("\t".join(values))
    (EXPECTED / f"{page.stem}.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")

bc.get_target_months = lambda count: [(2026, 10), (2026, 11), (2026, 12)]
ics = bc.build_ics_file(bc.scrape_full_calendar(3))
ics = re.sub(r"DTSTAMP:\d{8}T\d{6}Z", "DTSTAMP:20261009T000000Z", ics)
(EXPECTED / "calendar.ics").write_bytes(ics.encode("utf-8"))
```

The Python version was removed after the migration. To regenerate these files, check out
commit `ff57ef7` first.
