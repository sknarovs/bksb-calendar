# Java + GraalVM Native Migration — Design

- **Date:** 2026-10-09
- **Status:** Approved in chat (sections 1–3); awaiting written-spec review
- **Branch:** `java-migration` (local only until cutover)

## 1. Intent

### What was asked

- Migrate the scraper from Python to Java and ship it as a GraalVM native binary.
- GraalVM is installed in a Fedora toolbox (local builds).
- GitHub Actions builds the native binaries.
- The Raspberry Pi runs it from cron; `run.sh` replaces `update_calendar.sh` and is
  responsible for pushing calendar updates to the repository.
- Otherwise it must behave the same as now.

### Answers gathered during design

| Topic | Answer |
|---|---|
| Pi target | `aarch64`, DietPi v10.7.2 (DietPi v10 requires Debian ≥ 12 Bookworm; exact Debian release unknown) |
| Binary delivery | `run.sh` self-updates from the latest GitHub Release; releases are cut by pushing `v*` tags |
| Build tool | Gradle (Kotlin DSL) |

### Constraints discovered in the repo

- GitHub Actions cannot reach `bksb.lv` (commit `a17244a` removed the old Actions workflow for
  that reason). CI can only **build and test**; scraping stays on the Pi.
- The 40-minute event buffer described in `AGENTS.md` no longer exists (removed in `a4066c1`);
  the Python code emits raw event times. The port follows the code, not the stale doc.
- Fully static (musl) native images are documented for Linux x64 only, so the aarch64 binary
  links against glibc and must be built against a glibc no newer than the Pi's.
- GraalVM defaults to `-march=armv8.1-a` on AArch64; Raspberry Pi 3/4 cores are ARMv8.0.
- Local toolchain: GraalVM CE 25.4.4.1.1 (JDK 25) and Temurin 25 via SDKMAN, Gradle 9.8.1;
  the toolbox (`fedora-toolbox-44`) provides gcc, glibc-devel and zlib headers for `native-image`.

### Success criteria

1. For the same input HTML, the Java version produces a byte-identical `.ics` (DTSTAMP aside)
   with identical UIDs, proven by golden tests generated from the current Python code and a
   one-time live side-by-side diff.
2. CLI flags, defaults, log lines and exit codes are unchanged.
3. CI builds a `linux-aarch64` binary that runs on any DietPi v10 Pi, runs the test suite on the
   JVM and as a native image, and publishes a GitHub Release for every `v*` tag.
4. On the Pi, `run.sh` keeps the binary current, regenerates `bikernieki.ics`, commits and pushes,
   and never installs a binary that fails its self-test.
5. The Python script and `update_calendar.sh` are removed; README and AGENTS.md describe the
   Java setup.

### Non-goals

- Any change to scraping, filtering or ICS semantics. Example: DTSTAMP changes on every run, so
  the Pi commits daily even without event changes. That stays as is.
- CI builds for x86-64, macOS or Windows; static musl binaries.
- Running the scraper in CI.
- Redesigning the dashboard.

## 2. Architecture

### 2.1 Repository layout after migration

```
.github/workflows/build.yml
build.gradle.kts
settings.gradle.kts
gradlew, gradlew.bat, gradle/wrapper/{gradle-wrapper.jar, gradle-wrapper.properties}
run.sh
bikernieki.ics
README.md, AGENTS.md, .gitignore
docs/superpowers/specs/…
src/main/java/lv/sknarovs/bikernieki/*.java
src/main/resources/lv/sknarovs/bikernieki/{html5-entities.tsv, dashboard.html}
src/test/java/lv/sknarovs/bikernieki/*Test.java
src/test/resources/golden/{*.html, *.ics, README.md}
```

Removed: `bikernieki_calendar.py`, `update_calendar.sh`.

### 2.2 Components

Package `lv.sknarovs.bikernieki`. Runtime dependencies: the JDK only.

| Class | Responsibility | Depends on | Python origin |
|---|---|---|---|
| `Main` | Install UTF-8 stdout, parse options, dispatch to self-test / server / one-shot scrape, set exit code | all below | `main()` |
| `CliOptions` | Parse `-o -m -s -p -t -h` into a record | — | `argparse` setup |
| `PyText` | Python string semantics: whitespace set, `strip`, `split`/join, regex compile helper | — | (implicit) |
| `HtmlText` | `unescape` (Python `html.unescape`) and `escape` (Python `html.escape`) | `html5-entities.tsv` | `html` module |
| `HtmlScanner` | Tokenize HTML and emit raw title anchors `(titleText, href, tooltip)` | `HtmlText` | `JEventsHTMLParser` |
| `CalendarEvent` | Record: `startDate, startTime, endDate, endTime, summary, category, location, url, uid` (all `String`) | — | event dict |
| `EventParser` | Raw anchors → `CalendarEvent`s: date, times, category/location, exclusion, midnight crossing, URL, UID | `HtmlScanner`, `PyText` | parsing half of `fetch_and_scrape_month`, helpers |
| `MonthSource` | Interface: `Optional<String> fetchMonth(int year, int month)` | — | — |
| `BksbClient` | `MonthSource` over HTTP with timeout/retry/backoff | JDK `HttpClient` | fetch half of `fetch_and_scrape_month` |
| `CalendarScraper` | Target months from a `Clock`, fetch + parse each, pacing, dedupe, sort | `MonthSource`, `EventParser` | `get_target_months`, `scrape_full_calendar` |
| `IcsWriter` | Text escaping, 74-octet folding, VCALENDAR assembly | — | `escape_ics_text`, `fold_ics_line`, `build_ics_file` |
| `CalendarCache` | Load from disk, refresh under a lock, zero-event guard, atomic file write, dashboard event list | `CalendarScraper`, `IcsWriter` | `CalendarCache` |
| `DashboardServer` | JDK `HttpServer` routes and dashboard rendering | `CalendarCache`, `dashboard.html` | `CalendarHTTPRequestHandler` |
| `SelfTest` | `--test` assertions; doubles as the native-binary smoke test | parser classes | `run_unit_tests` |

Seams for testing: `Clock` (today and DTSTAMP), `MonthSource` (no network in tests), and
constructor-supplied timeouts, backoff and pacing durations (zero in tests).

### 2.3 Data flow

- **One-shot (cron) mode:** `Main` → `CalendarCache.loadFromDisk()` → `refresh()` →
  `CalendarScraper.scrape(months)` → per month `BksbClient.fetchMonth` → `EventParser.parse`
  → dedupe + sort → `IcsWriter.build` → atomic write → exit 0, or exit 1 on failure or guard.
- **Server mode:** `Main` → `loadFromDisk()` (initial `refresh()` if nothing is cached) →
  `DashboardServer.start(port)`; `/calendar.ics` serves the cached bytes and triggers a
  background refresh when stale.

## 3. Behavior parity specification

The Python implementation at commit `ff57ef7` is the reference. Rules below are normative.

### 3.1 Python text semantics (`PyText`)

- **Whitespace** is Python's `str.isspace()` set (29 code points): `U+0009–U+000D`,
  `U+001C–U+001F`, `U+0020`, `U+0085`, `U+00A0`, `U+1680`, `U+2000–U+200A`, `U+2028`,
  `U+2029`, `U+202F`, `U+205F`, `U+3000`. Java's `String.strip()` and default `\s` differ, so
  they are not used.
- `strip()` and `split()`/`" ".join(...)` use that set.
- **Regexes** are compiled with `UNICODE_CHARACTER_CLASS | UNIX_LINES`. Every `\s` is written as
  `[\s\x1c-\x1f]` (Unicode White_Space plus the four separators Python adds). With these flags,
  `\d` is Unicode `Nd`, `.` excludes only `\n`, and `$` matches at the end or before a final `\n`,
  as in Python. `re.match` maps to `^` + `find()`, `re.search` maps to `find()`.
- **Lowercasing** uses `toLowerCase(Locale.ROOT)`; number formatting uses `Locale.ROOT`.

### 3.2 HTML scanning (`HtmlScanner`)

Mirrors CPython 3.14 `html.parser.HTMLParser` (the version used to generate the goldens) for:

- **Start tags:** name lowercased; attributes with lowercased names; values double-quoted,
  single-quoted or unquoted. Values are passed through `HtmlText.unescape`. The last duplicate
  wins. A self-closing tag (`<a …/>`) is a start tag followed by an end tag.
- **End tags:** name lowercased.
- **Comments** (`<!-- … -->`, including `--!>` endings), declarations (`<!DOCTYPE …>`, `<!…>`),
  CDATA sections and processing instructions (`<?…>`) are skipped.
- **Raw-text elements** `script, style, xmp, iframe, noembed, noframes` and escapable-raw-text
  elements `textarea, title`: their content runs to the matching end tag and is never scanned
  for tags.
- **Text** between tags is passed through `HtmlText.unescape` (Python's `convert_charrefs=True`).
- A `<` that does not start a valid construct is literal text.

The extraction state machine is identical to `JEventsHTMLParser`:

- `<span>` whose `class` contains `editlinktip` → `tooltip = title` attribute (or `""`).
- `<a>` whose `class` contains `cal_titlelink` → `inTitle = true`, `href = href` attribute
  (or `""`), `title = ""`.
- Text while `inTitle` → appended to `title`.
- `</a>` while `inTitle` → emit `(PyText.strip(title), href, tooltip)`, then reset `inTitle`,
  `href` and `tooltip`.

### 3.3 Entities (`HtmlText`)

- `unescape` implements Python's algorithm exactly:
  - Pattern: `&(#[0-9]+;?|#[xX][0-9a-fA-F]+;?|[^\t\n\f <&#;]{1,32};?)`.
  - Numeric references: first Python's 34-entry `_invalid_charrefs` remap; then surrogates or
    values above `0x10FFFF` become `U+FFFD` (arbitrarily long digit strings included); then
    Python's 126-entry `_invalid_codepoints` become `""`.
  - Named references: exact match in the HTML5 table; otherwise the longest prefix of at least
    2 chars that is in the table, plus the remainder; otherwise the literal text.
- `html5-entities.tsv` is generated once from Python's `html.entities.html5`: 2,231 names,
  including 106 legacy names without `;`. Each line is `name<TAB>hex code points separated by
  spaces`. The two numeric tables are Java constants copied from CPython.
- `escape` matches `html.escape(s, quote=True)`: `&`→`&amp;` first, then `<`, `>`, `"`→`&quot;`,
  `'`→`&#x27;`.

### 3.4 Event extraction (`EventParser`)

For each raw anchor, in document order:

1. **Date:** `icalrepeat\.detail/(\d{4})/(\d{2})/(\d{2})` searched in `href`. No match → skip.
   `date = y-m-d`.
2. **Times and summary** from `titleText`:
   - `^(\d{2}:\d{2})-(\d{2}:\d{2})\s+(.*)$` → start, end, summary.
   - else `^(\d{2}:\d{2})\s+(.*)$` → start, end = `(h+1)%24` and the same minutes, formatted
     `%02d:%02d`, summary.
   - else `00:00`, `23:59`, `summary = titleText`.
3. **Category and location:** `tooltip` is unescaped **again**, a deliberate second pass that
   Python performs. `Kategorija:\s*([^<]+)` and `Kur:\s*([^<]+)` are each stripped, then have
   `<[^>]+>` removed, then are stripped again; a missing match gives `""`.
4. **Exclusion:** `normalize(location)` is exactly one of `bksb birojs`,
   `bksb spidveja stadions`, `bksb motormuzeja likums`, `bksb liela auto stavvieta` → log
   `[-] Excluding event due to location '<location>': <summary>` and skip. `normalize`:
   - lowercase;
   - remove `" ' “ ” « »`;
   - map `ā ē ī ū ō ķ ļ ņ ģ š ž` → `a e i u o k l n g s z` (no other letters, e.g. `č` stays);
   - collapse whitespace.
5. **Midnight crossing:** if `endTime <= startTime` (string comparison), parse both as
   `yyyy-MM-dd HH:mm`. If the end is not after the start, `endDate = date + 1 day`. Any parse
   failure leaves `endDate = date`.
6. **URL:** `"https://bksb.lv" + href` if `href` starts with `/`, else `href`.
7. **UID:** lowercase hex MD5 of the UTF-8 bytes of
   `date|startTime|endDate|endTime|titleText|location`, plus `@bikernieku-calendar`. This uses
   the raw `titleText` and the raw (possibly empty) `location`.
8. **Location field:** `location`, or `Bikernieku Trase` when empty.

### 3.5 Fetching (`BksbClient`)

- **URL:** `https://bksb.lv/index.php/2014-01-03-13-49-44/month.calendar/{year}/{MM}/01/-`. Log
  `[*] Fetching calendar: <url>`.
- **Request:** `User-Agent` set to the existing Chrome 120 string; HTTP/1.1, as urllib uses;
  normal redirects followed; 20 s connect timeout and 20 s request (response) timeout. Each
  attempt is also capped at 60 s in total, so a stalled body can't hang the cron job. Python's
  per-read socket timeout had no overall cap.
- **Retries:** 2 attempts with 2 s between them. Each failure logs
  `[!] Attempt <n>/2 failed for <year>/<MM>: <error>`. A non-2xx status, I/O error, timeout or
  **malformed UTF-8** (strict decoder, matching `bytes.decode('utf-8')`) counts as a failure.
- **After both attempts fail:** log `[!] Error loading calendar for <year>/<MM> after retries:
  <error>` and contribute no events for that month.
- Exception message texts differ from Python's; everything else in these lines is identical.

### 3.6 Aggregation (`CalendarScraper`)

- **Months:** the current month from `LocalDate.now(clock)` (system time zone), plus the next
  `months - 1` months with year rollover. `months <= 0` gives no months.
- After each month, including the last, sleep 0.5 s.
- **Dedupe** by UID, keeping the first occurrence. Then a **stable** sort by
  `(startDate, startTime)` as strings.

### 3.7 ICS output (`IcsWriter`)

The output is byte-identical to `build_ics_file`:

- **Header** lines `BEGIN:VCALENDAR`, `VERSION:2.0`, `PRODID:-//Bikernieku Calendar//EN`,
  `CALSCALE:GREGORIAN`, `METHOD:PUBLISH`, `X-WR-CALNAME:Biķernieku Trases Kalendārs`,
  `X-WR-TIMEZONE:Europe/Riga`, followed by the existing Europe/Riga `VTIMEZONE` block verbatim.
- **DTSTAMP:** a single UTC timestamp `yyyyMMdd'T'HHmmss'Z'` from the clock, shared by all events.
- **Per event:**
  - `BEGIN:VEVENT`, `UID`, `DTSTAMP`.
  - `DTSTART;TZID=Europe/Riga:` and `DTEND;TZID=Europe/Riga:` values: the date with `-` removed,
    `T`, the time with `:` removed, `00`.
  - `SUMMARY`; `LOCATION` (only when the escaped value is non-empty).
  - `DESCRIPTION:` + escape(join(`"\n"`, parts)), where `parts` is `Kategorija: <cat>` (only if
    the category is non-empty) followed by `Pasākuma saite: <url>`. The newline is escaped to
    a literal `\n`.
  - `END:VEVENT`.
- The file ends with `END:VCALENDAR`.
- **Escaping:** `\` → `\\`, then `,` → `\,`, `;` → `\;`, newline → `\n`. `\r` is untouched.
- **Folding:** a line of at most 75 UTF-8 bytes is unchanged. A longer line is cut so that no
  segment exceeds 74 bytes and no UTF-8 sequence is split. Each break inserts `CRLF` + space,
  and the space counts toward the next segment's 74.
- Lines are joined with `CRLF`, plus a trailing `CRLF`. The file is written as UTF-8 bytes.

### 3.8 Cache, guard and file I/O (`CalendarCache`)

- **`loadFromDisk`:** if the output file exists, log `[*] Found existing calendar file: <path>`,
  read the bytes, set `lastUpdated` from the file's mtime (local time), derive dashboard events
  (§3.10) and log `[*] Loaded <n> events from local disk cache.`. Errors log
  `[!] Error loading <path>: <error>`.
- **`refresh`** (serialized by a lock):
  1. Log `[*] Refreshing calendar cache...` and scrape.
  2. **Guard:** if there are 0 events, the file exists and it is larger than 500 bytes, log
     `[!] Scrape returned 0 events. Preserving existing calendar file.` and return `false`.
  3. Otherwise build the ICS and write it to a temporary file in the same directory, then move
     it atomically over the target.
  4. Update the in-memory state and `lastUpdated`, log
     `[+] Successfully refreshed. Extracted <n> events.` and return `true`.
  - Any exception logs `[!] Scraper refresh failed: <error>` and returns `false`.

### 3.9 CLI (`CliOptions`, `Main`)

- **Flags** (same names and defaults):
  - `-o/--output` (default `bikernieki.ics`).
  - `-m/--months` int (default `3`).
  - `-s/--serve`.
  - `-p/--port` int (default `8080`).
  - `-t/--test`.
  - `-h/--help`.
- **Accepted forms:** `--opt value`, `--opt=value`, `-o value`, `-ovalue`.
- **Usage errors** (unknown argument, missing or non-integer value) print usage and an
  `error: …` line to **stderr**, then exit with code **2**, as argparse does. `--help` prints
  to stdout and exits 0.
- **Modes:**
  - `--test`: run `SelfTest`, exit 0 or 1.
  - `--serve`: server mode.
  - Otherwise: `loadFromDisk`, log
    `[*] Starting CLI scrape: lookahead = <m> months, saving to <path>...`, `refresh`, then
    `[+] Completed! Calendar written to <path>` with exit 0, or `[!] Scrape execution failed.`
    with exit 1.
- All log output goes to stdout through a UTF-8 `PrintStream`, so Latvian text is printed
  correctly even under cron's `POSIX` locale.

### 3.10 Server (`DashboardServer`)

- **Startup:** if no ICS bytes are cached (file missing, unreadable or empty), log
  `[*] No calendar file found on disk. Performing initial scrape...` and refresh. Bind all interfaces on `--port` with a single-threaded executor, like
  Python's `HTTPServer`. Log `[+] Web Server running at: http://localhost:<port>/` and
  `[+] Subscribe to your calendar at: http://localhost:<port>/calendar.ics`. On shutdown (signal),
  log `[*] Shutting down server...`.
- **Routing** compares the raw request target (path + query) exactly, as `self.path` does.
  Per-request logging is suppressed. Non-GET methods get 501.

| Target | Behavior |
|---|---|
| `/calendar.ics` | If `lastUpdated` is unset or more than 12 h old: log `[*] Cache expired. Scraping in background...` and start a background refresh unless one is already running. Respond 200 with `Content-Type: text/calendar; charset=utf-8`, `Content-Disposition: attachment; filename=bikernieki.ics`, `Access-Control-Allow-Origin: *`, `Content-Length`, and the cached ICS bytes. |
| `/`, `/index.html` | 200 `text/html; charset=utf-8`; dashboard rendered from `dashboard.html` (the Python page verbatim, with `{{ }}` un-doubled and named placeholders for event count, status time and table rows). The rows have the same markup; summary, date, location and category are `HtmlText.escape`d, and the time cell is not escaped (as in Python). |
| `/refresh` | Log `[*] Force refresh requested via Web UI`, refresh synchronously, then 303 with `Location: /`. |
| anything else | 404 with body `404 Not Found`. |

- **Dashboard events loaded from disk** come from the same quick ICS parse as Python: field
  regexes, `\,` `\;` `\\` unescapes and the category regex. The parse runs on unfolded lines
  (deviation 2). Status time is `yyyy-MM-dd HH:mm:ss`, or `Never`.

### 3.11 Self-test (`SelfTest`)

- Ports `run_unit_tests` with the same mock HTML, assertions and messages:
  `[*] Running parser unit tests...` and `[+] All parser unit tests passed successfully!`, or
  `[!] Unit test FAILED: …`.
- Additional checks exercise what the native image must bundle: decoding `&mdash;` through the
  entity resource, loading the dashboard template, and computing MD5.
- Returns `true`/`false`; `Main` maps it to exit 0/1.

### 3.12 Intentional deviations

1. `--serve` serves the stored `.ics` bytes unchanged. Python's text-mode read converted CRLF to
   LF.
2. The dashboard's quick ICS parse unfolds folded lines first. Python truncated long titles.
3. At most one background refresh runs at a time. Python could stack up several.
4. A refresh blocked by the zero-event guard keeps the previous events on the dashboard.
   Python cleared the in-memory list while keeping the file.
5. Valueless `class`, `title` or `href` attributes are treated as empty strings. Python raised
   `TypeError` and aborted the whole scrape.

None of these affect the generated `.ics` file.

## 4. Build (Gradle)

- **Wrapper:** Gradle **9.8.1**, with `distributionSha256Sum` set. `settings.gradle.kts` sets
  `rootProject.name = "bikernieki-calendar"`.
- **Plugins in `build.gradle.kts`:**
  - `application`: `mainClass = lv.sknarovs.bikernieki.Main`, so
    `./gradlew run --args="-m 3"` works on the JVM.
  - `org.graalvm.buildtools.native` version **1.1.14**.
- **Java and tests:** `java.toolchain.languageVersion = 25`; repository `mavenCentral()`.
  - Test dependencies: `platform("org.junit:junit-bom:6.1.3")`, `org.junit.jupiter:junit-jupiter`.
  - Test runtime: `org.junit.platform:junit-platform-launcher`.
  - `tasks.test { useJUnitPlatform() }`.
- **`graalvmNative`:**
  - `toolchainDetection = false`: `native-image` comes from `GRAALVM_HOME`, then `JAVA_HOME`.
  - `binaries.all { resources.autodetect() }` bundles the entity table, dashboard template and
    test fixtures.
  - `binaries.main`: `imageName = "bikernieki-calendar"` and `buildArgs.add("-march=compatibility")`.
- **Local commands:**
  - `./gradlew test` works on the host or in the toolbox.
  - Inside the toolbox: `GRAALVM_HOME=~/.sdkman/candidates/java/25.4.4.1+1-graalce ./gradlew nativeCompile nativeTest`.
    The output is `build/native/nativeCompile/bikernieki-calendar`.

## 5. CI/CD (`.github/workflows/build.yml`)

- **Triggers:**
  - `push` to `main` with `paths-ignore: [bikernieki.ics, '**/*.md']`, so the Pi's daily
    commits don't start builds.
  - `push` of tags `v*` (path filters don't apply to tags).
  - `pull_request` and `workflow_dispatch`.
- **Permissions:** workflow-level `contents: read`.
- **Job `build`:**
  - Runs on `ubuntu-24.04-arm` inside container `debian:bookworm` (glibc 2.36), with
    `timeout-minutes: 30`.
  - Steps:
    1. `apt-get install --no-install-recommends ca-certificates curl git gcc libc6-dev zlib1g-dev`.
    2. `actions/checkout@v7`.
    3. `graalvm/setup-graalvm@v1` with `java-version: '25'` and `distribution: graalvm-community`.
    4. `gradle/actions/setup-gradle@v6` (caching and wrapper validation).
    5. `./gradlew test nativeTest nativeCompile`.
    6. `build/native/nativeCompile/bikernieki-calendar --test`.
    7. Copy the binary to `dist/bikernieki-calendar-linux-aarch64` and write
       `dist/bikernieki-calendar-linux-aarch64.sha256` (`sha256sum` format).
    8. `actions/upload-artifact@v7`.
- **Job `release`:**
  - Runs only when `startsWith(github.ref, 'refs/tags/v')`, `needs: build`, on `ubuntu-latest`
    with `contents: write`.
  - Steps: `actions/download-artifact@v8`, then
    `gh release create "$GITHUB_REF_NAME" dist/* --repo "$GITHUB_REPOSITORY" --title "$GITHUB_REF_NAME" --generate-notes`.
- **Release asset contract** (the API `run.sh` depends on):
  `https://github.com/sknarovs/bksb-calendar/releases/latest/download/bikernieki-calendar-linux-<uname -m>`
  plus the same name with `.sha256`.

## 6. `run.sh`

Bash with `set -euo pipefail`. The entire body is in functions invoked by a final `main "$@"`,
so replacing `run.sh` during `git pull` cannot affect the running copy.

1. `cd` to the script's directory.
2. **Sync:**
   - `git checkout -- bikernieki.ics` (the file is regenerated, so this drops leftovers from an
     interrupted run).
   - `git pull --rebase --quiet`. On failure, run `git rebase --abort` (ignoring errors), log
     `[!] git pull failed; resolve manually.` and exit 1.
3. **Self-update** of `bin/bikernieki-calendar` (`bin/` is gitignored). The asset name is
   `bikernieki-calendar-linux-$(uname -m)`.
   1. `mkdir -p bin` and create `tmp=$(mktemp -d bin/.update.XXXXXX)`. Being on the same
      filesystem as the target makes the final `mv` an atomic rename, which `/tmp` (tmpfs on
      DietPi) wouldn't. The directory is removed on every exit path.
   2. Download `<asset>.sha256` into `tmp` with `curl -fsSL --retry 2`.
   3. If the installed binary's SHA-256 equals the first field of that file, stop here.
   4. Otherwise download `<asset>` into `tmp` and run `sha256sum --check` inside `tmp`.
   5. Mark it executable and run its `--test`. Only if that passes, `mv -f` it over
      `bin/bikernieki-calendar` and log `[+] Installed new binary (<first 12 hex chars>).`.
   6. Any failure in steps 2, 4 or 5 logs a warning and continues with the installed binary,
      or exits 1 if none exists.
4. **Generate:** `bin/bikernieki-calendar -m 3 -o bikernieki.ics`. A non-zero exit stops the
   script, as now.
5. **Publish** (unchanged from `update_calendar.sh`): if `git diff --quiet -- bikernieki.ics`,
   log `[*] No changes in calendar events.` and exit 0. Otherwise `git add`,
   `git commit -m "Update calendar events"`, `git push`, then log
   `[+] Pushed updated calendar to GitHub.`.

Pi requirements: bash, git, curl, coreutils (`sha256sum`, `mktemp`), already present on DietPi.
Cron line (README): `0 4 * * * /home/<user>/bksb-calendar/run.sh >> /tmp/bikernieku-cron.log 2>&1`.

## 7. Testing

- **Golden parity fixtures** (`src/test/resources/golden/`):
  - Real month pages fetched from bksb.lv:
    - `2026-07`: all four excluded locations, with case and diacritic variants.
    - `2026-08`: 90 events, including two real midnight crossings.
    - `2026-10`, `2026-11`, `2026-12`: the live cron window.
  - A handwritten `edge-cases.html` covering:
    - single-time, all-day, overnight and equal start/end events;
    - excluded locations with quotes, diacritics and odd whitespace; a missing location;
    - named, numeric, legacy (no `;`), invalid and unknown entities, and double-escaped
      tooltips;
    - nested tags in titles; fake title anchors inside `<script>`, `<title>` and comments;
    - multibyte titles that fold at the 74-byte boundary; `, ; \` and newlines in text;
    - duplicate events across pages; absolute hrefs; valueless attributes other than
      `class`/`title`/`href` (Python crashes on those three, so deviation 5 is covered by
      Java-only unit tests instead).
  - Expected outputs come from running the **unmodified Python functions** with the network,
    sleeps and month list stubbed, using CPython 3.14.8. DTSTAMP is normalized to
    `20261009T000000Z`. Outputs: one `.ics` per page and one combined `calendar.ics` covering
    all real pages (cross-month dedupe and sort).
  - `golden/README.md` records the source commit (`ff57ef7`), the Python version and the
    procedure. The generator script is not kept, since Python leaves the repo.
- **Unit tests** cover each class:
  - Folding boundaries, escaping, the unescape tables, time parsing, normalization and UIDs.
  - CLI parsing and exit codes.
  - Guard, atomic write and disk load.
  - `BksbClient` against a local `HttpServer`: success, 5xx then success, timeout and invalid
    UTF-8.
  - `DashboardServer` routes on an ephemeral port with a stub `MonthSource`.
  - `SelfTest` returns `true`.
  - Tests never touch the network.
- **Native:** CI runs the full JUnit suite as an aarch64 native image (`nativeTest`) plus
  `--test` on the release binary, and `run.sh` runs `--test` again before installing.
- **One-time live check** (toolbox, before cutover): run the Python script and the native
  binary back to back against live bksb.lv. After normalizing DTSTAMP, the files must be
  identical.

## 8. Repository cleanup and docs

- **`.gitignore`:** replace the Python entries with `build/`, `.gradle/`, `.kotlin/`, `/bin/`,
  `.idea/`, `*.iml`.
- **README:**
  - Local build in the toolbox, the JVM run, and the native binary usage.
  - The CLI reference (unchanged flags).
  - Releasing (tag push).
  - Pi setup with `run.sh`: SSH deploy key as today, first run, crontab line.
  - The subscription URLs stay.
- **AGENTS.md:** rewritten for the Java layout and commands. It drops the stale buffer and test
  claims and keeps the data-guard and UID notes.

## 9. Rollout (cutover)

1. Finish on `java-migration`, with the live check passing. Merge into `main` after pulling the
   Pi's latest calendar commits, then push.
2. Tag and push `v1.0.0`, and wait for the `release` job.
3. On the Pi: `cd ~/bksb-calendar && git pull && ./run.sh`. This first run downloads the binary,
   generates the calendar and pushes it.
4. `crontab -e`: replace `update_calendar.sh` with `run.sh`.

Between steps 1 and 3 the old cron job's `git push` fails, because it never pulls, so steps 1–4
should happen on the same day. Python remains installed on the Pi but is unused.

## 10. Risks and mitigations

| Risk | Mitigation |
|---|---|
| glibc newer on the build host than on the Pi | Build in `debian:bookworm` (2.36), the oldest Debian DietPi v10 supports |
| CPU features unsupported on Pi 3/4 (SIGILL) | `-march=compatibility`; `run.sh` runs `--test` before installing a binary |
| 16K-page kernel on Pi 5 | Native Image assumes ≥ 64K pages by default (`SubstrateOptions.getPageSize`) |
| HTTPS, MD5, `HttpServer` or resources missing from the native image | First implementation step is a native feasibility spike in the toolbox; `nativeTest` + `--test` in CI |
| Parser divergence from Python | Golden tests on real and edge-case pages; live side-by-side diff before cutover |
| Push rejected or conflicting on the Pi | `git pull --rebase` before generating; abort and fail loudly on conflict |
| GitHub unreachable on the Pi | Update step falls back to the installed binary |
| Bad release reaches the Pi | Self-test gate; roll back by marking the previous release as latest |
