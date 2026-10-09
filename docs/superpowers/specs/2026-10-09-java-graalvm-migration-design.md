# Java + GraalVM Native Migration — Design

- **Date:** 2026-10-09
- **Status:** Revision 3 (idiomatic Java, server mode removed); awaiting re-review
- **Branch:** `java-migration` (local only until cutover)

## 1. Intent

### What was asked

- Migrate the scraper from Python to Java and ship it as a GraalVM native binary.
- GraalVM is installed in a Fedora toolbox (local builds).
- GitHub Actions builds the native binaries.
- The Raspberry Pi runs it from cron; `run.sh` replaces `update_calendar.sh` and is
  responsible for pushing calendar updates to the repository.
- Otherwise it must keep working as it does now.
- Spec review, round 1: it must be an ordinary Java application under the hood, **not a
  replica of the Python code**. That means no Python-style string helpers, whitespace classes
  or parser clones.
- Spec review, round 2: **server mode is dropped** (the `--serve` dashboard and feed).

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
  events carry the times shown on bksb.lv.
- Fully static (musl) native images are documented for Linux x64 only, so the aarch64 binary
  links against glibc and must be built against a glibc no newer than the Pi's.
- GraalVM defaults to `-march=armv8.1-a` on AArch64; Raspberry Pi 3/4 cores are ARMv8.0.
- Local toolchain: GraalVM CE 25.4.4.1.1 (JDK 25) and Temurin 25 via SDKMAN, Gradle 9.8.1;
  the toolbox (`fedora-toolbox-44`) provides gcc, glibc-devel and zlib headers for `native-image`.

### Success criteria

1. On real bksb.lv pages the Java version produces the same events as the Python version:
   same dates, times, summaries, categories, locations, links and **UIDs**. This is proven by
   regression tests against the current script's output and a one-time live comparison. The
   `.ics` carries the same properties and values; formatting such as line-fold positions may
   differ.
2. The remaining CLI flags (`-o`, `-m`, `-t`, `-h`), their defaults, the exit codes and the
   data-preservation guard are unchanged. Log lines keep their style and content.
3. CI builds a `linux-aarch64` binary that runs on any DietPi v10 Pi, runs the test suite on the
   JVM and as a native image, and publishes a GitHub Release for every `v*` tag.
4. On the Pi, `run.sh` keeps the binary current, regenerates `bikernieki.ics`, commits and pushes,
   and never installs a binary that fails its self-test.
5. The Python script and `update_calendar.sh` are removed; README and AGENTS.md describe the
   Java setup.

### Non-goals

- Server mode: the dashboard, the `/calendar.ics` feed, and the `--serve`/`--port` flags.
- Reproducing Python implementation details such as string and whitespace semantics,
  `html.parser` quirks, entity edge cases, or exception message wording.
- Changing what is scraped, filtered or published. Example: DTSTAMP changes on every run, so
  the Pi commits daily even without event changes. That stays as is.
- CI builds for x86-64, macOS or Windows; static musl binaries.
- Running the scraper in CI.

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
src/test/java/lv/sknarovs/bikernieki/*Test.java
src/test/resources/pages/{2026-07,2026-08,2026-10,2026-11,2026-12}.html
src/test/resources/expected/{2026-07,2026-08,2026-10,2026-11,2026-12}.tsv, README.md
```

Removed: `bikernieki_calendar.py`, `update_calendar.sh`.

### 2.2 Components

- **Package:** `lv.sknarovs.bikernieki`.
- **Runtime dependency:** **jsoup 1.23.2**, used for HTML parsing and entity decoding. Its entity
  data is compiled into classes, so no native-image resource configuration is needed.
- **Everything else is the JDK:** `java.net.http`, `java.time`, `java.nio.file`,
  `java.security.MessageDigest`.

| Class | Responsibility |
|---|---|
| `Main` | Install UTF-8 stdout, parse options, run the self-test or the update, set the exit code |
| `CliOptions` | Record plus parser for `-o -m -t -h` |
| `CalendarEvent` | Record: `uid`, `start`/`end` (`LocalDateTime`), `summary`, `category`, `location`, `url` |
| `EventParser` | Month page (jsoup `Document`) → `List<CalendarEvent>` using the rules in §3.2 |
| `MonthSource` | Interface: `Optional<String> fetch(YearMonth month)` |
| `BksbClient` | `MonthSource` over `java.net.http.HttpClient` with timeouts and retry |
| `CalendarScraper` | Target months from a `Clock`, fetch + parse each, pacing, dedupe, sort |
| `IcsWriter` | Events → RFC 5545 calendar text |
| `CalendarUpdater` | One update run: scrape, apply the data-preservation guard, write the file atomically, log the outcome |
| `SelfTest` | `--test` smoke test, also used by CI and `run.sh` on the native binary |

Test seams: `Clock` (today and DTSTAMP), `MonthSource` (no network in tests), and
constructor-supplied timeouts, backoff and pacing durations (zero in tests).

### 2.3 Data flow

`Main` → `CalendarUpdater.run()` → `CalendarScraper.scrape()` → per month `BksbClient.fetch` →
`EventParser.parse` → dedupe + sort → guard → `IcsWriter.write` → atomic file write → exit 0, or
exit 1 on failure or guard.

## 3. Functional behavior

The Python script at commit `ff57ef7` defines *what* the application does. The Java code does it
the Java way: jsoup, `java.time`, `String.strip()` and plain regular expressions.

### 3.1 Fetching (`BksbClient`, `CalendarScraper`)

- **Target months:** the current month (system time zone) plus the next `months - 1`.
- **Request:**
  - URL: `https://bksb.lv/index.php/2014-01-03-13-49-44/month.calendar/{yyyy}/{MM}/01/-`.
  - The existing browser `User-Agent`; redirects followed.
  - Timeouts: 20 s connect, 20 s for the response, and a 60 s cap per attempt.
- **Retries:** 2 attempts, 2 s apart. A non-2xx status, I/O error or timeout counts as a failed
  attempt.
- **Failed months:** a month whose attempts all fail is logged and contributes no events; the
  other months still count, as today.
- **Pacing:** 0.5 s pause after each month.

### 3.2 Event extraction (`EventParser`)

The page is parsed with jsoup using the page URL as base URI. For every `a.cal_titlelink`:

1. **Date:** taken from `icalrepeat.detail/yyyy/MM/dd` in the link's `href`. A link without a
   valid date is skipped.
2. **Title:** the link's whole text, trimmed. Internal whitespace is kept, since the title feeds
   the UID.
3. **Category and location:** read from the `title` attribute of the enclosing
   `span.editlinktip` (the tooltip markup). Each is the text after `Kategorija:` / `Kur:` up to
   the next tag, entity-decoded with `Parser.unescapeEntities` and trimmed. A missing label
   gives an empty string.
4. **Times and summary:**

   | Title format | Times | Summary |
   |---|---|---|
   | `HH:mm-HH:mm Summary` | as given | `Summary` |
   | `HH:mm Summary` | one hour from start | `Summary` |
   | anything else, or an invalid time | all day, 00:00–23:59 | the whole title |

   If the end is not after the start, the end moves to the next day. This covers `08:00-00:00`,
   overnight ranges, and one-hour events starting at 23:xx.
5. **Exclusion:** the location is normalized: lowercase, quotes removed, diacritics stripped via
   Unicode NFD, whitespace collapsed. It is skipped with the log line
   `[-] Excluding event due to location '<location>': <summary>` when it equals one of:
   `bksb birojs`, `bksb spidveja stadions`, `bksb motormuzeja likums`,
   `bksb liela auto stavvieta`.
6. **Link:** `href` resolved against the page URL (`absUrl`).
7. **Location shown:** the location, or `Bikernieku Trase` when empty.
8. **UID:** MD5 hex of
   `<start date>|<start HH:mm>|<end date>|<end HH:mm>|<title>|<location>`, followed by
   `@bikernieku-calendar`.
   - Dates are ISO `yyyy-MM-dd`; the title is the one from step 2; the location is the raw
     value, possibly empty, *before* the default above.
   - **This formula is a compatibility contract.** Keeping it means existing calendar
     subscriptions see the same events instead of deletions and re-additions.

### 3.3 Aggregation

Events are deduplicated by UID (first occurrence wins), then stable-sorted by start.

### 3.4 Calendar output (`IcsWriter`)

Properties appear in the same order as in today's file.

- **Calendar header:** `VERSION:2.0`, `PRODID:-//Bikernieku Calendar//EN`, `CALSCALE:GREGORIAN`,
  `METHOD:PUBLISH`, `X-WR-CALNAME:Biķernieku Trases Kalendārs`, `X-WR-TIMEZONE:Europe/Riga`,
  followed by the existing Europe/Riga `VTIMEZONE` definition.
- **Per event:**
  - `UID`.
  - `DTSTAMP`: the run time, UTC.
  - `DTSTART` / `DTEND` with `TZID=Europe/Riga`, local `yyyyMMdd'T'HHmmss`.
  - `SUMMARY` and `LOCATION`.
  - `DESCRIPTION`: a `Kategorija: <category>` line when the category is non-empty, then
    `Pasākuma saite: <link>`.
- **Format:** RFC 5545 text escaping (backslash, `;`, `,`, line breaks → `\n`) and line folding
  at 75 octets without splitting UTF-8 characters; CRLF line endings; UTF-8.

### 3.5 Update run, guard and file writing (`CalendarUpdater`)

1. Log `[*] Starting CLI scrape: lookahead = <m> months, saving to <path>...` and scrape.
2. **Guard:** if the scrape yields 0 events while the output file exists and is larger than
   500 bytes, log `[!] Scrape returned 0 events. Preserving existing calendar file.` and fail.
   This is unchanged.
3. Otherwise write a temporary file in the same directory and atomically move it over the
   target. A crash can never leave a truncated calendar for `run.sh` to commit.
4. Log the event count and `[+] Completed! Calendar written to <path>`.

Any unexpected error is logged as `[!] Scraper refresh failed: <error>`, followed by
`[!] Scrape execution failed.`, and fails the run.

### 3.6 Command line (`CliOptions`, `Main`)

- **Flags** (same names and defaults as today, minus the server flags):
  - `-o/--output` (default `bikernieki.ics`).
  - `-m/--months` (default `3`).
  - `-t/--test`.
  - `-h/--help`.
- **Exit codes:**
  - 0 for success.
  - 1 for a failed scrape (or the guard) or a failed self-test.
  - 2 for usage errors, including the removed `--serve`/`--port`. Usage is printed to stderr.
- **Logs:** stdout, encoded as UTF-8 regardless of locale, so cron logs show Latvian text
  correctly. They keep the existing `[*]`, `[+]`, `[!]` and `[-]` style and content: fetch URLs,
  failed attempts, exclusions, event counts, guard and completion messages.

### 3.7 Self-test (`--test`)

`SelfTest` parses an embedded sample of bksb.lv markup (the snippet the Python self-test uses)
and checks:

- date, times, summary, category and location;
- normalization of the four excluded locations;
- line folding;
- UID stability.

It prints a pass or fail line and exits 0 or 1. CI runs it on the native binary, and `run.sh`
runs it before installing a downloaded binary.

### 3.8 Accepted differences from the Python version

- `--serve` and `--port` are removed, together with the dashboard and the `/calendar.ics` feed.
  The two disk-cache log lines that Python printed at startup (`Found existing calendar file`,
  `Loaded N events`) go with them.
- Long lines fold at the RFC limit of 75 octets instead of 74. The first Java-generated commit
  reflows some lines, but calendar apps see identical data.
- Diacritics are stripped generically (NFD) rather than with a fixed Latvian letter map. This
  gives the same matches for the four excluded names.
- Invalid times such as `25:00` are treated as "no time" (all day) instead of being written
  verbatim. They don't occur in published data.
- Entity decoding and trimming follow jsoup and Java rules. Results differ only for unusual
  input, such as non-breaking spaces at title edges.
- Malformed markup, such as attributes without values, no longer aborts the whole scrape.

## 4. Build (Gradle)

- **Wrapper:** Gradle **9.8.1**, with `distributionSha256Sum` set. `settings.gradle.kts` sets
  `rootProject.name = "bikernieki-calendar"`.
- **Plugins in `build.gradle.kts`:**
  - `application`: `mainClass = lv.sknarovs.bikernieki.Main`, so
    `./gradlew run --args="-m 3"` works on the JVM.
  - `org.graalvm.buildtools.native` version **1.1.14**.
- **Java:** `java.toolchain.languageVersion = 25`; repository `mavenCentral()`.
- **Dependencies:**
  - Runtime: `implementation("org.jsoup:jsoup:1.23.2")`.
  - Test: `platform("org.junit:junit-bom:6.1.3")` and `org.junit.jupiter:junit-jupiter`.
  - Test runtime: `org.junit.platform:junit-platform-launcher`.
  - `tasks.test { useJUnitPlatform() }`.
- **`graalvmNative`:**
  - `toolchainDetection = false`: `native-image` comes from `GRAALVM_HOME`, then `JAVA_HOME`.
  - `binaries.all { resources.autodetect() }` bundles the test pages into the `nativeTest` image.
  - `binaries.all { buildArgs.add("--enable-url-protocols=https") }`: jsoup's `absUrl` resolves
    links through `java.net.URL`, and native images lack the `https:` handler without it. A
    spike on 2026-10-09 showed every link coming back empty without the flag.
  - `binaries.main`: `imageName = "bikernieki-calendar"` and `buildArgs.add("-march=compatibility")`.
- **Local commands:**
  - `./gradlew test` works on the host or in the toolbox.
  - Inside the toolbox: `GRAALVM_HOME=~/.sdkman/candidates/java/25.4.4.1+1-graalce ./gradlew nativeCompile nativeTest`.
    The output is `build/native/nativeCompile/bikernieki-calendar`.

## 5. CI/CD (`.github/workflows/build.yml`)

- **Triggers:**
  - `push` to `main` with `paths-ignore: [bikernieki.ics, '**/*.md']`, so the Pi's daily
    commits don't start builds.
  - `push` of tags `v*` (GitHub never applies path filters to tag pushes).
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
   - `git checkout HEAD -- bikernieki.ics` (the file is regenerated, so this drops staged and unstaged leftovers from an
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

- **Regression tests on real pages:**
  - `src/test/resources/pages/` holds five month pages fetched from bksb.lv on 2026-10-09:
    - `2026-07`: all four excluded locations, with case and diacritic variants.
    - `2026-08`: 90 events, including two midnight crossings.
    - `2026-10`, `2026-11`, `2026-12`: the live cron window.
  - For each page, `expected/<page>.tsv` lists the events the current Python script extracts
    (excluded events removed, page order), one per line. Columns: UID, start date, start time,
    end date, end time, summary, category, location, link.
  - The files are generated once from the script at `ff57ef7` with the network stubbed;
    `expected/README.md` records how.
  - `EventParser` output for each page must match its file exactly, UIDs included. A pipeline
    test feeds `2026-10` to `2026-12` through `CalendarScraper` with a stub `MonthSource` and
    checks the deduplicated, sorted result.
- **Unit tests** per class:
  - Title formats, including single-time, all-day, `08:00-00:00`, a single time at 23:30 and an
    invalid time.
  - Excluded-location variants and the missing-location default.
  - An entity-encoded tooltip.
  - ICS escaping, folding with multibyte characters at the boundary, and document structure.
  - `CalendarUpdater`: the guard, atomic write, and the failure paths.
  - CLI parsing and exit codes, including rejection of `--serve`.
  - `BksbClient` against a local JDK `HttpServer` (test-only): success, 5xx then success, and
    timeout.
  - `SelfTest` passes.
  - Tests never touch the network.
- **Native:** CI runs the full JUnit suite as an aarch64 native image (`nativeTest`) plus
  `--test` on the release binary, and `run.sh` runs `--test` again before installing.
- **One-time live check** (toolbox, before cutover): run the Python script and the native
  binary back to back against live bksb.lv. After unfolding lines and dropping DTSTAMP, the two
  calendars must be identical.

## 8. Repository cleanup and docs

- **`.gitignore`:** replace the Python entries with `build/`, `.gradle/`, `.kotlin/`, `/bin/`,
  `.idea/`, `*.iml`.
- **README:**
  - Local build in the toolbox, the JVM run, and the native binary usage.
  - The CLI reference (without `--serve`/`--port`).
  - Releasing (tag push).
  - Pi setup with `run.sh`: SSH deploy key as today, first run, crontab line.
  - The subscription URLs stay.
- **AGENTS.md:** rewritten for the Java layout and commands. It drops the HTTP server section
  and the stale buffer and test claims, and keeps the data-guard and UID notes.

## 9. Rollout (cutover)

1. Finish on `java-migration`, with the live check passing. Merge into `main` after pulling the
   Pi's latest calendar commits, then push.
2. Tag and push `v1.0.0`, and wait for the `release` job.
3. On the Pi: `cd ~/bksb-calendar && git pull && ./run.sh`. This first run downloads the binary,
   generates the calendar and pushes it. The resulting commit reflows folded lines (§3.8), but
   the events and UIDs are unchanged.
4. `crontab -e`: replace `update_calendar.sh` with `run.sh`.

Between steps 1 and 3 the old cron job's `git push` fails, because it never pulls, so steps 1–4
should happen on the same day. Python remains installed on the Pi but is unused.

## 10. Risks and mitigations

| Risk | Mitigation |
|---|---|
| glibc newer on the build host than on the Pi | Build in `debian:bookworm` (2.36), the oldest Debian DietPi v10 supports |
| CPU features unsupported on Pi 3/4 (SIGILL) | `-march=compatibility`; `run.sh` runs `--test` before installing a binary |
| 16K-page kernel on Pi 5 | Native Image assumes ≥ 64K pages by default (`SubstrateOptions.getPageSize`) |
| jsoup, HTTPS or MD5 not working in the native image | Spike done on 2026-10-09: HTTPS, jsoup, MD5 and NFD work, and links need `--enable-url-protocols=https`. `nativeTest` + `--test` guard regressions |
| Java extracts different events or UIDs than Python | Regression tests on five real pages; live comparison before cutover |
| Push rejected or conflicting on the Pi | `git pull --rebase` before generating; abort and fail loudly on conflict |
| GitHub unreachable on the Pi | Update step falls back to the installed binary |
| Bad release reaches the Pi | Self-test gate; roll back by marking the previous release as latest |
