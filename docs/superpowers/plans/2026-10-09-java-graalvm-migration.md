# Java + GraalVM Native Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Python scraper with a Java application that GitHub Actions builds into a
GraalVM native binary for the Raspberry Pi, where `run.sh` runs it from cron and pushes the
updated calendar.

**Architecture:**
- **Parsing:** jsoup parses bksb.lv month pages into `CalendarEvent` records.
- **Aggregation:** `CalendarScraper` collects the month window.
- **Output:** `IcsWriter` renders RFC 5545. `CalendarUpdater` applies the data-preservation
  guard and writes the file atomically. `Main` wires the CLI.
- **Build:** Gradle runs the JVM tests and builds the native images.
- **CI:** builds `linux-aarch64` in a Debian Bookworm container and publishes a release on each
  `v*` tag.
- **Pi:** `run.sh` self-updates from the latest release, regenerates the calendar and pushes it.

**Tech Stack:**
- Java 25 and GraalVM CE 25 `native-image`.
- Gradle 9.8.1 (Kotlin DSL) with GraalVM Native Build Tools 1.1.14.
- jsoup 1.23.2, JUnit 6.1.3.
- GitHub Actions, Bash.

**Spec:** `docs/superpowers/specs/2026-10-09-java-graalvm-migration-design.md`

## Global Constraints

**Code and build**

- Package `lv.sknarovs.bikernieki`; main class `lv.sknarovs.bikernieki.Main`; native image name
  `bikernieki-calendar`.
- Java toolchain 25.
- Dependencies:
  - Runtime: only `org.jsoup:jsoup:1.23.2`.
  - Test: `platform("org.junit:junit-bom:6.1.3")`, `org.junit.jupiter:junit-jupiter`, and
    `testRuntimeOnly` `org.junit.platform:junit-platform-launcher`.
- Gradle wrapper 9.8.1, with distribution SHA-256
  `dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f`. Plugin
  `org.graalvm.buildtools.native` version 1.1.14.
- Native build arguments:
  - `--enable-url-protocols=https` on **every** image, main and test. jsoup's `absUrl` resolves
    through `java.net.URL`, and without this flag native images return empty links (verified by a
    spike on 2026-10-09).
  - `-march=compatibility` on the main image. Raspberry Pi 3/4 cores are ARMv8.0, and GraalVM
    defaults to ARMv8.1.
- Native builds run in the toolbox:
  `toolbox run -c fedora-toolbox-44 bash -c 'cd <repo> && export JAVA_HOME=$HOME/.sdkman/candidates/java/25.4.4.1+1-graalce GRAALVM_HOME=$HOME/.sdkman/candidates/java/25.4.4.1+1-graalce && ./gradlew <tasks>'`,
  where `<repo>` is the absolute path of the working copy. The non-login shell doesn't load
  SDKMAN, so both variables are exported explicitly. JVM-only tasks (`./gradlew test`) also run
  on the host.

**Behavior**

- Tests never touch the network. Timeouts, backoff and pacing are constructor parameters, set to
  zero or very short in tests.
- Logs go to stdout with the `[*]`, `[+]`, `[!]` and `[-]` prefixes and the exact strings each task
  gives. Usage errors go to stderr.
- Exit codes: 0 success; 1 for a failed scrape, the guard, or a failed self-test; 2 for a usage
  error.
- UID: lowercase MD5 hex of
  `<start yyyy-MM-dd>|<start HH:mm>|<end yyyy-MM-dd>|<end HH:mm>|<title>|<raw location>`, then
  `@bikernieku-calendar`. This is a compatibility contract; don't change it.
- There is no server mode: `--serve` and `--port` are usage errors.

**Process**

- Never push, merge, tag, or act on the Pi without the user's explicit go-ahead (Task 11).
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Page with no events:** bksb.lv answers 200 with a page that has no events, such as a
   maintenance page or a layout change. Keep the existing calendar, exit 1, commit nothing.
   Test: Task 6 `pagesWithoutEventsKeepExistingCalendar`.
2. **One month fails:** one month's page fails both attempts while the others load. The calendar
   is still written from the months that loaded, as today. Test: Task 4 `failedMonthIsSkipped`.
3. **Bad output path:** the output directory is missing or unwritable. Exit 1 and leave no partial
   or temp file. Test: Task 6 `unwritableOutputFailsCleanly`.
4. **GitHub unreachable:** GitHub can't be reached, or the release asset is missing, when
   `run.sh` checks for updates. Keep using the installed binary and still publish the calendar;
   with no binary installed, exit 1. Test: Task 9 sandbox scenarios C and F.
5. **Bad download:** the downloaded binary is corrupt (checksum mismatch) or can't run (fails
   `--test`, for example with SIGILL). Don't install it; the old binary keeps working.
   Test: Task 9 sandbox scenarios D and E.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `src/test/resources/pages/{2026-07,2026-08,2026-10,2026-11,2026-12}.html` | Real bksb.lv month pages | 1 |
| `src/test/resources/expected/*.tsv`, `calendar.ics`, `README.md` | What the Python version extracts from those pages | 1 |
| `settings.gradle.kts`, `build.gradle.kts`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*` | Build | 2 |
| `.gitignore` | Build output, IDE files, temp file, `bin/` | 2, 6, 9, 10 |
| `src/main/java/lv/sknarovs/bikernieki/CalendarEvent.java` | Event record | 2 |
| `src/main/java/lv/sknarovs/bikernieki/EventParser.java` | Month page → events | 2 |
| `src/main/java/lv/sknarovs/bikernieki/IcsWriter.java` | Events → RFC 5545 text | 3 |
| `src/main/java/lv/sknarovs/bikernieki/MonthSource.java`, `CalendarScraper.java` | Month window, aggregation | 4 |
| `src/main/java/lv/sknarovs/bikernieki/BksbClient.java` | HTTP fetching | 5 |
| `src/main/java/lv/sknarovs/bikernieki/CalendarUpdater.java` | One update run: guard + atomic write | 6 |
| `src/main/java/lv/sknarovs/bikernieki/{SelfTest,CliOptions,Main}.java` | `--test`, options, entry point | 7 |
| `src/test/java/lv/sknarovs/bikernieki/TestData.java`, `*Test.java` | Tests | 2–7 |
| `.github/workflows/build.yml` | CI and releases | 8 |
| `run.sh` | Pi cron entry point | 9 |
| `README.md`, `AGENTS.md` | Docs | 10 |
| `bikernieki_calendar.py`, `update_calendar.sh` | Deleted | 10 |

---

### Task 1: Regression fixtures from the Python version

**Files:**
- Create: `src/test/resources/pages/{2026-07,2026-08,2026-10,2026-11,2026-12}.html`
- Create: `src/test/resources/expected/{2026-07,2026-08,2026-10,2026-11,2026-12}.tsv`
- Create: `src/test/resources/expected/calendar.ics`, `src/test/resources/expected/README.md`

**Interfaces:**
- Produces (used by Tasks 2, 4, 6):
  - `/pages/<yyyy-MM>.html` on the test classpath.
  - `/expected/<yyyy-MM>.tsv`: UTF-8 with `\n` line endings and a trailing newline. A header line
    `uid	start_date	start_time	end_date	end_time	summary	category	location	url`, then one
    tab-separated line per event, in page order, with excluded events removed. Times are `HH:mm`;
    `location` is after the `Bikernieku Trase` default.
  - `/expected/calendar.ics`: the Python output for months 2026-10..2026-12, with every DTSTAMP
    set to `20261009T000000Z`.

- [ ] **Step 1: Fetch the five pages**

```bash
mkdir -p src/test/resources/pages src/test/resources/expected
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
for ym in 2026/07 2026/08 2026/10 2026/11 2026/12; do
  curl -fsS -A "$UA" -o "src/test/resources/pages/${ym/\//-}.html" \
    "https://bksb.lv/index.php/2014-01-03-13-49-44/month.calendar/$ym/01/-"
  sleep 1
done
grep -c 'class="cal_titlelink"' src/test/resources/pages/*.html
```

Expected: five files, each with a count > 0.

- [ ] **Step 2: Save the generator outside the repo (e.g. your scratchpad) as `generate_expected.py`**

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

- [ ] **Step 3: Run it and sanity-check the output**

Run: `python3 -I <scratch>/generate_expected.py && wc -l src/test/resources/expected/*.tsv && grep -c '^BEGIN:VEVENT' src/test/resources/expected/calendar.ics && tail -q -n +2 src/test/resources/expected/2026-1{0,1,2}.tsv | cut -f1 | sort -u | wc -l`

Expected:
- Every `.tsv` has at least 2 lines.
- The `BEGIN:VEVENT` count equals the unique-UID count printed last.
- Every DTSTAMP in `calendar.ics` reads `20261009T000000Z`.

- [ ] **Step 4: Write `src/test/resources/expected/README.md`**

It must say:
- These files are the output of the Python version at commit `ff57ef7` for the pages in `../pages/`.
- The fetch date and the output of `python3 --version`.
- The generator script verbatim in a fenced block, plus the command used to run it.

- [ ] **Step 5: Commit**

```bash
git add src/test/resources
git commit -m "test: add bksb.lv pages and Python reference output as regression fixtures"
```

---

### Task 2: Gradle project and event parsing

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Modify: `.gitignore`. Append `build/`, `.gradle/`, `.kotlin/`, `.idea/`, `*.iml`, and keep the Python entries until Task 10.
- Create: `src/main/java/lv/sknarovs/bikernieki/CalendarEvent.java`, `EventParser.java`
- Test: `src/test/java/lv/sknarovs/bikernieki/TestData.java`, `EventParserTest.java`, `EventParserRegressionTest.java`

**Interfaces:**
- Consumes: Task 1 fixtures.
- Produces:
  - `record CalendarEvent(String uid, LocalDateTime start, LocalDateTime end, String summary, String category, String location, String url)`
  - `final class EventParser`:
    - `static final String BASE_URL = "https://bksb.lv"`
    - `static List<CalendarEvent> parse(String html)`
    - `static String normalizeLocation(String location)`
    - `static boolean isExcluded(String location)`
  - Test helper `final class TestData`:
    - `static String resource(String path)`: a classpath resource as UTF-8; fails the test if missing.
    - `static String tsv(CalendarEvent e)`: the Task 1 columns joined with `\t`; dates ISO, times
      `HH:mm`.
    - `static String unfold(String ics)`: `ics.replace("\r\n ", "")`.

- [ ] **Step 1: Scaffold the build**

`settings.gradle.kts`: `rootProject.name = "bikernieki-calendar"`. `build.gradle.kts`:

```kotlin
plugins {
    application
    id("org.graalvm.buildtools.native") version "1.1.14"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.23.2")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "lv.sknarovs.bikernieki.Main"
}

tasks.test {
    useJUnitPlatform()
}

graalvmNative {
    toolchainDetection = false
    binaries {
        all {
            resources.autodetect()
            // jsoup resolves links through java.net.URL; native images need the https handler.
            buildArgs.add("--enable-url-protocols=https")
        }
        named("main") {
            imageName = "bikernieki-calendar"
            // GraalVM targets ARMv8.1 by default; Raspberry Pi 3/4 cores are ARMv8.0.
            buildArgs.add("-march=compatibility")
        }
    }
}
```

Append `build/`, `.gradle/`, `.kotlin/`, `.idea/` and `*.iml` to `.gitignore`.

Run: `gradle wrapper --gradle-version 9.8.1 --distribution-type bin --gradle-distribution-sha256-sum dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f && ./gradlew --version && git status --short`
Expected: `Gradle 9.8.1`, and `.idea/` no longer listed as untracked.

- [ ] **Step 2: Write the failing tests**

`EventParserTest.java`:

```java
class EventParserTest {
    static final String HREF = "/index.php/2014-01-03-13-49-44/icalrepeat.detail/2026/10/07/11610/-/x";
    static final String TOOLTIP = "<div class=\"jevtt_title\">X</div>\n  Kategorija: Autošoseja <br>"
            + "  Laiks: <a>08:00</a> <br>  Kur: BKSB kartingu trase\t<p><img src=\"a.jpg\" /></p>\n</div>\n";

    /** One event cell as bksb.lv renders it: the tooltip markup is attribute-escaped. */
    static String page(String href, String title, String tooltip) {
        String escaped = tooltip.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
        return "<html><body><span class=\"editlinktip hasjevtip\" title=\"" + escaped + "\">"
                + "<a class=\"cal_titlelink\" href=\"" + href + "\">" + title + "</a></span></body></html>";
    }

    static CalendarEvent only(String title, String tooltip) {
        List<CalendarEvent> events = EventParser.parse(page(HREF, title, tooltip));
        assertEquals(1, events.size());
        return events.getFirst();
    }

    @Test void readsRangeTitleAndTooltip() {
        CalendarEvent e = only("08:00-20:00 Drifta treniņš", TOOLTIP);
        assertEquals(LocalDateTime.of(2026, 10, 7, 8, 0), e.start());
        assertEquals(LocalDateTime.of(2026, 10, 7, 20, 0), e.end());
        assertEquals("Drifta treniņš", e.summary());
        assertEquals("Autošoseja", e.category());
        assertEquals("BKSB kartingu trase", e.location());
        assertEquals("https://bksb.lv" + HREF, e.url());
    }

    @Test void singleTimeLastsOneHour() {
        assertEquals(LocalDateTime.of(2026, 10, 7, 19, 30), only("18:30 Treniņš", TOOLTIP).end());
    }

    @Test void singleTimeLateInDayEndsNextDay() {
        assertEquals(LocalDateTime.of(2026, 10, 8, 0, 30), only("23:30 Nakts", TOOLTIP).end());
    }

    @Test void rangeEndingAtMidnightEndsNextDay() {
        assertEquals(LocalDateTime.of(2026, 10, 8, 0, 0), only("08:00-00:00 Biroja noma", TOOLTIP).end());
    }

    @Test void titleWithoutTimeIsAllDay() {
        CalendarEvent e = only("Sacensības", TOOLTIP);
        assertEquals(LocalDateTime.of(2026, 10, 7, 0, 0), e.start());
        assertEquals(LocalDateTime.of(2026, 10, 7, 23, 59), e.end());
        assertEquals("Sacensības", e.summary());
    }

    @Test void invalidTimeIsAllDay() {
        CalendarEvent e = only("25:00-26:00 Kļūda", TOOLTIP);
        assertEquals(LocalDateTime.of(2026, 10, 7, 0, 0), e.start());
        assertEquals("25:00-26:00 Kļūda", e.summary());
    }

    @Test void tooltipTextEntitiesAreDecoded() {
        assertEquals("A & B", only("08:00-20:00 X", "Kur: A &amp; B <br>").location());
    }

    @Test void missingLocationUsesDefaultButStaysEmptyInUid() {
        CalendarEvent e = only("08:00-20:00 Treniņš", "Kategorija: Autošoseja <br>");
        assertEquals("Bikernieku Trase", e.location());
        assertEquals("2c376016373a6d1743900aedaf358e7b@bikernieku-calendar", e.uid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BKSB birojs", "BKSB BIROJS", "BKSB Spīdveja stadions", "BKSB spidveja stadions",
            "BKSB \"Motormuzeja līkums\"", "BKSB lielā auto stāvvieta"})
    void excludedLocationsAreSkipped(String location) {
        assertTrue(EventParser.parse(page(HREF, "08:00-20:00 X", "Kur: " + location + " <br>")).isEmpty());
    }

    @Test void locationThatOnlyMentionsAnExcludedPlaceIsKept() {
        only("08:00-20:00 X", "Kur: BKSB lielā drifta trase un Spīdveja stadions <br>");
    }

    @Test void linkWithoutDateIsSkipped() {
        assertTrue(EventParser.parse(page("/index.php/2014-01-03-13-49-44/month.calendar/2026/10/01/-",
                "08:00-20:00 X", TOOLTIP)).isEmpty());
    }

    @Test void normalizesLocations() {
        assertEquals("bksb motormuzeja likums", EventParser.normalizeLocation("BKSB \"Motormuzeja līkums\""));
        assertEquals("bksb spidveja stadions", EventParser.normalizeLocation("BKSB spīdveja  stadions"));
        assertEquals("bksb liela auto stavvieta", EventParser.normalizeLocation("BKSB lielā auto stāvvieta"));
    }
}
```

`EventParserRegressionTest.java`:

```java
class EventParserRegressionTest {
    @ParameterizedTest
    @ValueSource(strings = {"2026-07", "2026-08", "2026-10", "2026-11", "2026-12"})
    void extractsSameEventsAsPythonVersion(String month) {
        List<String> expected = TestData.resource("/expected/" + month + ".tsv").lines().skip(1).toList();
        List<String> actual = EventParser.parse(TestData.resource("/pages/" + month + ".html"))
                .stream().map(TestData::tsv).toList();
        assertEquals(expected, actual);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test`
Expected: compilation fails, because `EventParser` and `CalendarEvent` don't exist yet.

- [ ] **Step 4: Implement `CalendarEvent` and `EventParser`**

`parse(String html)` follows spec §3.2:
- Parse with `Jsoup.parse(html, BASE_URL)` and take every `a.cal_titlelink` in document order.
- **Date:** `icalrepeat\.detail/(\d{4})/(\d{2})/(\d{2})` matched against `href` with `find()`. If
  there is no match, or the date is invalid (`DateTimeException`), skip the link.
- **Title:** `link.wholeText().strip()`.
- **Tooltip:** `link.closest("span.editlinktip")`, then its `title` attribute, or `""` if absent.
- **Category and location:** `Kategorija:\s*([^<]+)` and `Kur:\s*([^<]+)` matched against the
  tooltip, then `Parser.unescapeEntities(group, false).strip()`. A missing label gives `""`.
- **Times:** match the title with `matches()` against `(\d{2}:\d{2})-(\d{2}:\d{2})\s+(.*)`, then
  `(\d{2}:\d{2})\s+(.*)` (end = start plus one hour). Parse times with `LocalTime.parse`.
  Otherwise, or if any time fails to parse, it's all day (00:00–23:59) and the summary is the
  whole title. If the end is not after the start, add one day to the end.
- **Exclusion:** if `isExcluded(location)`, print
  `[-] Excluding event due to location '<location>': <summary>` and skip.
  - `normalizeLocation`: lowercase with `Locale.ROOT`, NFD, remove `\p{M}`, remove the characters
    `"'“”«»`, strip, collapse `\s+` to one space.
  - `isExcluded`: the normalized value equals one of `bksb birojs`, `bksb spidveja stadions`,
    `bksb motormuzeja likums`, `bksb liela auto stavvieta`.
- **Link:** `link.absUrl("href")`.
- **UID:** per Global Constraints, using the *raw* location.
- **Location field:** the location, or `Bikernieku Trase` if it's empty.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL; all `EventParserTest` and all five regression cases pass.

- [ ] **Step 6: Verify the same tests pass as a native image**

Run: in the toolbox (Global Constraints), `./gradlew nativeTest`
Expected: BUILD SUCCESSFUL. The native JUnit summary shows every test passing, including the
regression links, which proves `--enable-url-protocols=https` is in effect.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradlew gradlew.bat gradle .gitignore src
git commit -m "feat: parse bksb.lv month pages with jsoup"
```

---

### Task 3: Calendar output (`IcsWriter`)

**Files:**
- Create: `src/main/java/lv/sknarovs/bikernieki/IcsWriter.java`
- Test: `src/test/java/lv/sknarovs/bikernieki/IcsWriterTest.java`

**Interfaces:**
- Consumes: `CalendarEvent`, and `TestData.unfold` (Task 2).
- Produces: `final class IcsWriter` with:
  - `static String write(List<CalendarEvent> events, Instant dtstamp)`
  - `static String escape(String text)`
  - `static String fold(String line)`

- [ ] **Step 1: Write the failing tests**

```java
class IcsWriterTest {
    static final Instant STAMP = Instant.parse("2026-10-09T00:00:00Z");
    static final CalendarEvent EVENT = new CalendarEvent("abc@bikernieku-calendar",
            LocalDateTime.of(2026, 10, 7, 8, 0), LocalDateTime.of(2026, 10, 8, 0, 0),
            "Noma, birojs; tests", "Telpu noma", "BKSB kartingu trase", "https://bksb.lv/x");

    @Test void escapesTextValues() {
        assertEquals("a\\\\b\\;c\\,d\\ne\\nf", IcsWriter.escape("a\\b;c,d\ne\r\nf"));
    }

    @Test void writesHeaderAndEventBlock() {
        String ics = IcsWriter.write(List.of(EVENT), STAMP);
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Bikernieku Calendar//EN\r\n"
                + "CALSCALE:GREGORIAN\r\nMETHOD:PUBLISH\r\nX-WR-CALNAME:Biķernieku Trases Kalendārs\r\n"
                + "X-WR-TIMEZONE:Europe/Riga\r\nBEGIN:VTIMEZONE\r\nTZID:Europe/Riga\r\n"));
        assertTrue(TestData.unfold(ics).endsWith(String.join("\r\n", "END:VTIMEZONE", "BEGIN:VEVENT",
                "UID:abc@bikernieku-calendar", "DTSTAMP:20261009T000000Z",
                "DTSTART;TZID=Europe/Riga:20261007T080000", "DTEND;TZID=Europe/Riga:20261008T000000",
                "SUMMARY:Noma\\, birojs\\; tests", "LOCATION:BKSB kartingu trase",
                "DESCRIPTION:Kategorija: Telpu noma\\nPasākuma saite: https://bksb.lv/x",
                "END:VEVENT", "END:VCALENDAR", "")));
    }

    @Test void omitsEmptyCategory() {
        CalendarEvent noCategory = new CalendarEvent("u", EVENT.start(), EVENT.end(), "S", "", "L", "https://bksb.lv/x");
        assertTrue(TestData.unfold(IcsWriter.write(List.of(noCategory), STAMP))
                .contains("\r\nDESCRIPTION:Pasākuma saite: https://bksb.lv/x\r\n"));
    }

    @Test void writesEmptyCalendar() {
        String ics = IcsWriter.write(List.of(), STAMP);
        assertFalse(ics.contains("BEGIN:VEVENT"));
        assertTrue(ics.endsWith("END:VTIMEZONE\r\nEND:VCALENDAR\r\n"));
    }

    @Test void keepsLinesOf75Octets() {
        String line = "SUMMARY:" + "A".repeat(67);
        assertEquals(line, IcsWriter.fold(line));
    }

    @Test void foldsLongLinesAt75Octets() {
        String line = "SUMMARY:" + "A".repeat(100);
        String[] physical = IcsWriter.fold(line).split("\r\n");
        assertEquals(2, physical.length);
        assertEquals(75, physical[0].getBytes(UTF_8).length);
        assertTrue(physical[1].startsWith(" "));
        assertEquals(line, TestData.unfold(IcsWriter.fold(line)));
    }

    @Test void foldNeverSplitsCharacters() {
        String line = "SUMMARY:" + "ā".repeat(40) + "🏁".repeat(20);
        for (String physical : IcsWriter.fold(line).split("\r\n")) {
            assertTrue(physical.getBytes(UTF_8).length <= 75);
            assertTrue(UTF_8.newEncoder().canEncode(physical), "split surrogate pair");
        }
        assertEquals(line, TestData.unfold(IcsWriter.fold(line)));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*IcsWriterTest'`
Expected: compilation fails, because `IcsWriter` doesn't exist yet.

- [ ] **Step 3: Implement `IcsWriter`**

- **Header and VTIMEZONE:** copy the header lines and the `VTIMEZONE` block verbatim from
  `bikernieki_calendar.py:28-44` and `:320-331`.
- **Timestamps:** DTSTAMP is `yyyyMMdd'T'HHmmss'Z'` in UTC. DTSTART and DTEND are
  `yyyyMMdd'T'HHmmss` with the prefixes `DTSTART;TZID=Europe/Riga:` and `DTEND;TZID=Europe/Riga:`.
- **Event property order:** `BEGIN:VEVENT`, `UID`, `DTSTAMP`, `DTSTART`, `DTEND`, `SUMMARY`,
  `LOCATION` (only if its escaped value is non-empty), `DESCRIPTION`, `END:VEVENT`.
- **DESCRIPTION:** `escape(String.join("\n", parts))`, where `parts` is
  `Kategorija: <category>` (only if non-empty) followed by `Pasākuma saite: <url>`.
- **`escape`:** backslash first, then `;` and `,`, then `\r\n`, `\n` and `\r` each become `\n`.
- **`fold`:** walk the code points while counting the UTF-8 octets of the current physical line.
  Before a code point that would take the line past 75, emit `"\r\n "` and restart the count at 1.
- **`write`:** fold each line, join them with `\r\n`, and add a trailing `\r\n`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: render events as RFC 5545 calendar"
```

---

### Task 4: Month window and aggregation (`MonthSource`, `CalendarScraper`)

**Files:**
- Create: `src/main/java/lv/sknarovs/bikernieki/MonthSource.java`, `CalendarScraper.java`
- Test: `src/test/java/lv/sknarovs/bikernieki/CalendarScraperTest.java`

**Interfaces:**
- Consumes: `EventParser.parse` (Task 2); `IcsWriter.write` and `TestData` (tests only).
- Produces:
  - `@FunctionalInterface interface MonthSource { Optional<String> fetch(YearMonth month); }`
  - `final class CalendarScraper`:
    - `CalendarScraper(MonthSource source, Clock clock, Duration pacing)`
    - `List<CalendarEvent> scrape(int months) throws InterruptedException`
    - `static List<YearMonth> targetMonths(LocalDate today, int count)`
  - Test constants reused by Task 6:
    - `CalendarScraperTest.CLOCK`: fixed at `2026-10-09T00:00:00Z` in `Europe/Riga`.
    - `CalendarScraperTest.PAGES`: a `MonthSource` serving `/pages/<yyyy-MM>.html`.

- [ ] **Step 1: Write the failing tests**

```java
class CalendarScraperTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneId.of("Europe/Riga"));
    static final MonthSource PAGES = month -> Optional.of(TestData.resource("/pages/" + month + ".html"));

    static List<CalendarEvent> scrape(MonthSource source) throws InterruptedException {
        return new CalendarScraper(source, CLOCK, Duration.ZERO).scrape(3);
    }

    @Test void targetMonthsRollOverTheYear() {
        assertEquals(List.of(YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1)),
                CalendarScraper.targetMonths(LocalDate.of(2026, 11, 30), 3));
        assertEquals(List.of(), CalendarScraper.targetMonths(LocalDate.of(2026, 11, 30), 0));
    }

    @Test void producesSameCalendarAsPythonVersion() throws Exception {
        assertEquals(TestData.unfold(TestData.resource("/expected/calendar.ics")),
                TestData.unfold(IcsWriter.write(scrape(PAGES), CLOCK.instant())));
    }

    @Test void failedMonthIsSkipped() throws Exception {
        List<CalendarEvent> events = scrape(m -> m.getMonthValue() == 11 ? Optional.empty() : PAGES.fetch(m));
        assertTrue(events.stream().anyMatch(e -> e.start().getMonthValue() == 10));
        assertTrue(events.stream().noneMatch(e -> e.start().getMonthValue() == 11));
    }

    @Test void sameEventOnSeveralPagesAppearsOnce() throws Exception {
        List<CalendarEvent> events = scrape(m -> PAGES.fetch(YearMonth.of(2026, 10)));
        long distinct = EventParser.parse(TestData.resource("/pages/2026-10.html")).stream()
                .map(CalendarEvent::uid).distinct().count();
        assertEquals(distinct, events.size());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*CalendarScraperTest'`
Expected: compilation fails, because `MonthSource` and `CalendarScraper` don't exist yet.

- [ ] **Step 3: Implement `MonthSource` and `CalendarScraper`**

- `targetMonths`: `YearMonth.from(today).plusMonths(i)` for `i` in `[0, count)`.
- `scrape`:
  - For each month of `targetMonths(LocalDate.now(clock), months)`, add `source.fetch(month)`
    mapped through `EventParser::parse`, then `Thread.sleep(pacing)`.
  - Dedupe by UID with `LinkedHashMap.putIfAbsent`, keeping the first occurrence.
  - Stable-sort by `start` with `List.sort`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. `producesSameCalendarAsPythonVersion` passes, which is the offline
form of the spec's "same `.ics`" criterion.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: aggregate a month window into one sorted, deduplicated event list"
```

---

### Task 5: HTTP fetching (`BksbClient`)

**Files:**
- Create: `src/main/java/lv/sknarovs/bikernieki/BksbClient.java`
- Test: `src/test/java/lv/sknarovs/bikernieki/BksbClientTest.java`

**Interfaces:**
- Consumes: `MonthSource` (Task 4) and `EventParser.BASE_URL` (Task 2).
- Produces: `final class BksbClient implements MonthSource` with:
  - `static final String USER_AGENT`
  - `BksbClient()`: equivalent to
    `BksbClient(EventParser.BASE_URL, Duration.ofSeconds(20), Duration.ofSeconds(60), Duration.ofSeconds(2))`.
  - `BksbClient(String baseUrl, Duration timeout, Duration attemptCap, Duration backoff)`
  - `static String monthUrl(String baseUrl, YearMonth month)`
  - `Optional<String> fetch(YearMonth month)`

- [ ] **Step 1: Write the failing tests**

They use a local `com.sun.net.httpserver.HttpServer`; no external network.

```java
class BksbClientTest {
    HttpServer server;
    final AtomicInteger requests = new AtomicInteger();

    BksbClient clientFor(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { requests.incrementAndGet(); handler.handle(exchange); });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return new BksbClient("http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofMillis(300), Duration.ofMillis(800), Duration.ZERO);
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void monthUrlUsesTwoDigitMonth() {
        assertEquals("https://bksb.lv/index.php/2014-01-03-13-49-44/month.calendar/2026/03/01/-",
                BksbClient.monthUrl("https://bksb.lv", YearMonth.of(2026, 3)));
    }

    @Test void fetchesMonthPageWithBrowserUserAgent() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> agent = new AtomicReference<>();
        BksbClient client = clientFor(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            agent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            respond(exchange, 200, "<html>Biķernieki</html>");
        });
        assertEquals(Optional.of("<html>Biķernieki</html>"), client.fetch(YearMonth.of(2026, 10)));
        assertEquals("/index.php/2014-01-03-13-49-44/month.calendar/2026/10/01/-", path.get());
        assertEquals(BksbClient.USER_AGENT, agent.get());
    }

    @Test void retriesOnceAfterServerError() throws IOException {
        BksbClient client = clientFor(exchange -> respond(exchange, requests.get() == 1 ? 500 : 200, "ok"));
        assertEquals(Optional.of("ok"), client.fetch(YearMonth.of(2026, 10)));
        assertEquals(2, requests.get());
    }

    @Test void givesUpAfterTwoFailedAttempts() throws IOException {
        BksbClient client = clientFor(exchange -> respond(exchange, 503, "down"));
        assertEquals(Optional.empty(), client.fetch(YearMonth.of(2026, 10)));
        assertEquals(2, requests.get());
    }

    @Test void slowServerTimesOut() throws IOException {
        BksbClient client = clientFor(exchange -> {
            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            respond(exchange, 200, "late");
        });
        assertTimeout(Duration.ofSeconds(3),
                () -> assertEquals(Optional.empty(), client.fetch(YearMonth.of(2026, 10))));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*BksbClientTest'`
Expected: compilation fails, because `BksbClient` doesn't exist yet.

- [ ] **Step 3: Implement `BksbClient`**

- **`USER_AGENT`:**
  `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36`.
- **`monthUrl`:** `baseUrl + "/index.php/2014-01-03-13-49-44/month.calendar/" + yyyy + "/" + MM + "/01/-"`.
- **Client:** one `HttpClient` per instance, with `connectTimeout(timeout)` and
  `followRedirects(NORMAL)`.
- **Request:** a GET with the `User-Agent` header and `.timeout(timeout)`.
- **Attempts:** each attempt is `sendAsync(request, BodyHandlers.ofString()).get(attemptCap)`,
  cancelling the future on timeout. A status outside 200–299 is a failure (`HTTP <status>`).
  There are 2 attempts with `Thread.sleep(backoff)` between them. If interrupted, restore the
  interrupt flag and return empty.
- **Logs**, exactly:
  - `[*] Fetching calendar: <url>`, once per month.
  - `[!] Attempt <n>/2 failed for <yyyy>/<MM>: <error>`
  - `[!] Error loading calendar for <yyyy>/<MM> after retries: <error>`

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: fetch bksb.lv month pages with timeouts and one retry"
```

---

### Task 6: Update run with the data-preservation guard (`CalendarUpdater`)

**Files:**
- Create: `src/main/java/lv/sknarovs/bikernieki/CalendarUpdater.java`
- Modify: `.gitignore` (append `*.tmp`)
- Test: `src/test/java/lv/sknarovs/bikernieki/CalendarUpdaterTest.java`

**Interfaces:**
- Consumes: `CalendarScraper`, `MonthSource` (Task 4), `IcsWriter.write` (Task 3), and
  `CalendarScraperTest.CLOCK`/`PAGES` (tests).
- Produces: `final class CalendarUpdater` with
  `CalendarUpdater(CalendarScraper scraper, Clock clock, Path output, int months)` and
  `boolean run()`.

- [ ] **Step 1: Write the failing tests**

```java
class CalendarUpdaterTest {
    @TempDir Path dir;

    boolean update(MonthSource source, Path output) {
        Clock clock = CalendarScraperTest.CLOCK;
        return new CalendarUpdater(new CalendarScraper(source, clock, Duration.ZERO), clock, output, 3).run();
    }

    @Test void writesCalendarWithoutLeavingTempFiles() throws IOException {
        Path out = dir.resolve("bikernieki.ics");
        assertTrue(update(CalendarScraperTest.PAGES, out));
        assertEquals(TestData.unfold(TestData.resource("/expected/calendar.ics")), TestData.unfold(Files.readString(out)));
        try (Stream<Path> files = Files.list(dir)) { assertEquals(List.of(out), files.toList()); }
    }

    @Test void failedScrapeKeepsExistingCalendar() throws IOException {
        Path out = Files.writeString(dir.resolve("bikernieki.ics"), "X".repeat(600));
        assertFalse(update(month -> Optional.empty(), out));
        assertEquals("X".repeat(600), Files.readString(out));
    }

    @Test void pagesWithoutEventsKeepExistingCalendar() throws IOException {
        Path out = Files.writeString(dir.resolve("bikernieki.ics"), "X".repeat(600));
        assertFalse(update(month -> Optional.of("<html><body>Apkope</body></html>"), out));
        assertEquals("X".repeat(600), Files.readString(out));
    }

    @Test void smallExistingFileIsReplacedByEmptyCalendar() throws IOException {
        Path out = Files.writeString(dir.resolve("bikernieki.ics"), "tiny");
        assertTrue(update(month -> Optional.empty(), out));
        assertTrue(Files.readString(out).startsWith("BEGIN:VCALENDAR"));
    }

    @Test void unwritableOutputFailsCleanly() {
        Path out = dir.resolve("missing/bikernieki.ics");
        assertFalse(update(CalendarScraperTest.PAGES, out));
        assertFalse(Files.exists(out));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*CalendarUpdaterTest'`
Expected: compilation fails, because `CalendarUpdater` doesn't exist yet.

- [ ] **Step 3: Implement `CalendarUpdater.run()`**

- **Logs**, exactly:
  - Start: `[*] Starting CLI scrape: lookahead = <months> months, saving to <output>...`
  - Guard: `[!] Scrape returned 0 events. Preserving existing calendar file.`
  - Success: `[+] Successfully refreshed. Extracted <n> events.`, then
    `[+] Completed! Calendar written to <output>`.
  - Exception: `[!] Scraper refresh failed: <exception>`.
  - Every failure, guard included, ends with `[!] Scrape execution failed.`
- **Guard:** the scrape returned 0 events, `output` exists, and `Files.size(output) > 500`.
  Return `false`.
- **Write:**
  1. `IcsWriter.write(events, clock.instant())` to
     `output.resolveSibling(output.getFileName() + ".tmp")` as UTF-8.
  2. `Files.move(tmp, output, REPLACE_EXISTING, ATOMIC_MOVE)`.
  3. On any failure, delete the temp file if it exists.
- **Exceptions:** catch `Exception` and return `false`, re-interrupting on
  `InterruptedException`.
- **`.gitignore`:** append `*.tmp`, so a temp file left by a crash is never committed.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src .gitignore
git commit -m "feat: write the calendar atomically behind the zero-event guard"
```

---

### Task 7: Self-test, CLI and the native binary

**Files:**
- Create: `src/main/java/lv/sknarovs/bikernieki/SelfTest.java`, `CliOptions.java`, `Main.java`
- Test: `src/test/java/lv/sknarovs/bikernieki/SelfTestTest.java`, `CliOptionsTest.java`, `MainTest.java`

**Interfaces:**
- Consumes:
  - `EventParser.parse`, `normalizeLocation` and `isExcluded` (Task 2).
  - `IcsWriter.fold` (Task 3).
  - `CalendarScraper` (Task 4), `BksbClient` (Task 5), `CalendarUpdater` (Task 6).
- Produces:
  - `final class SelfTest { static boolean run(); }`
  - `record CliOptions(Path output, int months, boolean test, boolean help)` with:
    - `static CliOptions parse(String... args) throws UsageException`
    - `static final String USAGE` and `static final String HELP`
    - `static final class UsageException extends Exception`
  - `public final class Main { public static void main(String[] args); static int run(String... args); }`
  - The binary `build/native/nativeCompile/bikernieki-calendar`, used by Tasks 9 and 10.

- [ ] **Step 1: Write the failing tests**

```java
class SelfTestTest {
    @Test void passes() { assertTrue(SelfTest.run()); }
}

class CliOptionsTest {
    @Test void defaults() throws Exception {
        assertEquals(new CliOptions(Path.of("bikernieki.ics"), 3, false, false), CliOptions.parse());
    }

    @Test void acceptsShortLongAndAttachedForms() throws Exception {
        CliOptions expected = new CliOptions(Path.of("x.ics"), 5, false, false);
        assertEquals(expected, CliOptions.parse("-o", "x.ics", "-m", "5"));
        assertEquals(expected, CliOptions.parse("--output", "x.ics", "--months", "5"));
        assertEquals(expected, CliOptions.parse("--output=x.ics", "--months=5"));
        assertEquals(expected, CliOptions.parse("-ox.ics", "-m5"));
    }

    @Test void flags() throws Exception {
        assertTrue(CliOptions.parse("-t").test());
        assertTrue(CliOptions.parse("--test").test());
        assertTrue(CliOptions.parse("-h").help());
        assertTrue(CliOptions.parse("--help").help());
    }

    @Test void rejectsBadArguments() {
        for (String[] args : List.of(new String[] {"--serve"}, new String[] {"-s"}, new String[] {"-p", "8080"},
                new String[] {"-m", "abc"}, new String[] {"-o"}, new String[] {"extra"})) {
            assertThrows(CliOptions.UsageException.class, () -> CliOptions.parse(args), String.join(" ", args));
        }
    }
}

class MainTest {
    @Test void exitCodes() {
        assertEquals(0, Main.run("--help"));
        assertEquals(0, Main.run("--test"));
        assertEquals(2, Main.run("--serve"));
        assertEquals(2, Main.run("-m", "abc"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test`
Expected: compilation fails, because `SelfTest`, `CliOptions` and `Main` don't exist yet.

- [ ] **Step 3: Implement `SelfTest`, `CliOptions` and `Main`**

`SelfTest.run()`:
- Print `[*] Running parser unit tests...`.
- Parse the `mock_html` string from `bikernieki_calendar.py:910-917`, copied verbatim into a text
  block, and check:
  - exactly one event;
  - start `2026-05-01T08:00`, end `2026-05-01T20:00`;
  - summary `Test Sacensības`, category `Autošoseja`, location `Lielā trase`;
  - url `https://bksb.lv/index.php/icalrepeat.detail/2026/05/01/9999/-/test-sacensibas`;
  - uid `1c7fcc6deaf936b4cc4d5b24c5e7fa71@bikernieku-calendar`.
- Check `normalizeLocation` and `isExcluded` on these four pairs:
  - `BKSB "Motormuzeja līkums"` → `bksb motormuzeja likums`
  - `BKSB spīdveja stadions` → `bksb spidveja stadions`
  - `BKSB BIROJS` → `bksb birojs`
  - `BKSB lielā auto stāvvieta` → `bksb liela auto stavvieta`
- Check that `IcsWriter.fold("SUMMARY:" + "A".repeat(100))` gives 2 physical lines, the second
  starting with a space.
- The first failed check prints `[!] Unit test FAILED: <check>` and returns `false`. Use explicit
  `if` checks, not `assert`, because assertions are disabled by default on the JVM and in native
  images.
- Success prints `[+] All parser unit tests passed successfully!`.

`CliOptions`:
- **Flags:**
  - `-o/--output <path>`, default `bikernieki.ics`.
  - `-m/--months <int>`, default `3`.
  - `-t/--test`.
  - `-h/--help`.
- **Forms:** `--opt value`, `--opt=value`, `-o value` and `-ovalue`.
- **Errors:** anything else, a missing value, or a non-integer month count throws `UsageException`
  with a readable message.
- **Copy:** `USAGE` is `usage: bikernieki-calendar [-h] [-o OUTPUT] [-m MONTHS] [-t]`. `HELP` is:

```
usage: bikernieki-calendar [-h] [-o OUTPUT] [-m MONTHS] [-t]

Bikernieki Race Track Calendar Parser and ICS Generator

options:
  -h, --help            show this help message and exit
  -o, --output OUTPUT   Output path for static ICS file (default: bikernieki.ics)
  -m, --months MONTHS   Number of months to scrape including current (default: 3)
  -t, --test            Run self-testing harness and parser validations
```

`Main`:
- `main` replaces `System.out` with
  `new PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF_8)`, then calls
  `System.exit(run(args))`.
- `run(args)`:
  - **Usage error:** print `USAGE` and `bikernieki-calendar: error: <message>` to stderr and
    return 2.
  - **`help`:** print `HELP` and return 0.
  - **`test`:** return `SelfTest.run() ? 0 : 1`.
  - **Otherwise:** run
    `new CalendarUpdater(new CalendarScraper(new BksbClient(), clock, Duration.ofMillis(500)), clock, output, months)`
    with `clock = Clock.systemDefaultZone()`, and return 0 or 1 from `run()`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Build and exercise the native binary**

Run: in the toolbox, `./gradlew nativeTest nativeCompile`. Then on the host:

```bash
B=build/native/nativeCompile/bikernieki-calendar; T=$(mktemp -d)
$B --test; echo "exit=$?"
$B --serve; echo "exit=$?"
env -i PATH=/usr/bin:/bin LANG=C TZ=Europe/Riga $B -m 1 -o "$T/smoke.ics"; echo "exit=$?"
grep -c '^BEGIN:VEVENT' "$T/smoke.ics"
```

Expected:
- `nativeTest` passes.
- `--test` prints the pass line and `exit=0`.
- `--serve` prints usage plus an error on stderr and `exit=2`.
- The live run logs Latvian letters intact (for example `Biķernieku` or `stāvvieta` in exclusion
  lines) and gives `exit=0`.
- The `BEGIN:VEVENT` count is > 0.

- [ ] **Step 6: Commit**

```bash
git add src
git commit -m "feat: add CLI, self-test and native entry point"
```

---

### Task 8: GitHub Actions build and release

**Files:**
- Create: `.github/workflows/build.yml`

**Interfaces:**
- Consumes: the Gradle tasks `test`, `nativeTest` and `nativeCompile` (Task 2 build) and the
  binary's `--test` (Task 7).
- Produces: the release asset contract that `run.sh` (Task 9) depends on:
  `https://github.com/sknarovs/bksb-calendar/releases/latest/download/bikernieki-calendar-linux-aarch64`
  and the same path with `.sha256`, in `sha256sum` format.

- [ ] **Step 1: Write the workflow**

```yaml
name: Build

on:
  push:
    branches: [main]
    tags: ['v*']
    paths-ignore:
      - bikernieki.ics
      - '**/*.md'
  pull_request:
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build:
    name: Build linux-aarch64
    runs-on: ubuntu-24.04-arm
    # Debian 12 links the binary against glibc 2.36, the oldest Debian that DietPi v10 supports.
    container: debian:bookworm
    timeout-minutes: 30
    steps:
      - name: Install native toolchain
        run: |
          apt-get update
          apt-get install -y --no-install-recommends ca-certificates curl git gcc libc6-dev zlib1g-dev
      - uses: actions/checkout@v7
      - uses: graalvm/setup-graalvm@v1
        with:
          java-version: '25'
          distribution: graalvm-community
      - uses: gradle/actions/setup-gradle@v6
      - name: Test and build native binary
        run: ./gradlew test nativeTest nativeCompile
      - name: Smoke-test native binary
        run: build/native/nativeCompile/bikernieki-calendar --test
      - name: Package
        run: |
          mkdir dist
          cp build/native/nativeCompile/bikernieki-calendar dist/bikernieki-calendar-linux-aarch64
          cd dist
          sha256sum bikernieki-calendar-linux-aarch64 > bikernieki-calendar-linux-aarch64.sha256
      - uses: actions/upload-artifact@v7
        with:
          name: bikernieki-calendar-linux-aarch64
          path: dist/
          if-no-files-found: error

  release:
    name: Publish release
    if: startsWith(github.ref, 'refs/tags/v')
    needs: build
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: actions/download-artifact@v8
        with:
          name: bikernieki-calendar-linux-aarch64
          path: dist
      - name: Create GitHub release
        env:
          GH_TOKEN: ${{ github.token }}
        run: gh release create "$GITHUB_REF_NAME" dist/* --repo "$GITHUB_REPOSITORY" --title "$GITHUB_REF_NAME" --generate-notes
```

- [ ] **Step 2: Lint it**

Run: `podman run --rm --security-opt label=disable -v "$PWD:/repo:ro" -w /repo docker.io/rhysd/actionlint:1.7.12 -color; echo "exit=$?"`
Expected: no findings, `exit=0`. The `label=disable` option avoids SELinux relabelling of the repo
on Fedora. The workflow first runs for real in Task 11.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/build.yml
git commit -m "ci: build linux-aarch64 native binary and release on v* tags"
```

---

### Task 9: `run.sh` for the Pi

**Files:**
- Create: `run.sh` (mode 755)
- Modify: `.gitignore` (append `/bin/`)

**Interfaces:**
- Consumes:
  - The release asset contract (Task 8).
  - The binary's `--test`, `-m` and `-o` (Task 7).
  - The binary from Task 7 for the sandbox.
- Produces: the cron entry point. `BKSB_RELEASE_URL` overrides the release base URL; it exists for
  the sandbox scenarios below.

- [ ] **Step 1: Write `run.sh`**

Every command inside `install_latest_binary` carries its own `|| return 1`, because the function
runs in an `||` context where `set -e` is suspended.

```bash
#!/usr/bin/env bash
# Daily cron job for the Raspberry Pi: sync the repo, keep the native binary current with the
# latest GitHub release, regenerate bikernieki.ics and push it.
set -euo pipefail

RELEASE_URL="${BKSB_RELEASE_URL:-https://github.com/sknarovs/bksb-calendar/releases/latest/download}"
ASSET="bikernieki-calendar-linux-$(uname -m)"
BIN="bin/bikernieki-calendar"

sync_repo() {
    git checkout HEAD -- bikernieki.ics
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
```

- [ ] **Step 2: Syntax-check and commit**

Commit first: the sandbox clones the repo, so `run.sh` must be in it. Append `/bin/` to
`.gitignore` before committing.

```bash
chmod 755 run.sh && bash -n run.sh
git add run.sh .gitignore
git commit -m "feat: add run.sh cron entry point with release self-update"
```

- [ ] **Step 3: Verify in a sandbox**

The sandbox has a local bare remote and file:// "releases" built from the Task 7 binary.

```bash
SB=$(mktemp -d); ASSET="bikernieki-calendar-linux-$(uname -m)"; BIN=build/native/nativeCompile/bikernieki-calendar
git clone -q --bare . "$SB/remote.git" && git clone -q "$SB/remote.git" "$SB/pi" && git clone -q "$SB/remote.git" "$SB/pi2"
mkdir "$SB/release" "$SB/bad-sum" "$SB/broken"
cp "$BIN" "$SB/release/$ASSET" && (cd "$SB/release" && sha256sum "$ASSET" > "$ASSET.sha256")
cp "$BIN" "$SB/bad-sum/$ASSET" && printf '%064d  %s\n' 0 "$ASSET" > "$SB/bad-sum/$ASSET.sha256"
printf '#!/bin/sh\nexit 1\n' > "$SB/broken/$ASSET" && (cd "$SB/broken" && sha256sum "$ASSET" > "$ASSET.sha256")
GOOD=$(sha256sum "$BIN" | cut -d' ' -f1)
```

Run each scenario as `BKSB_RELEASE_URL=file://$SB/<dir> $SB/pi/run.sh; echo "exit=$?"`:

| # | Release dir | Expected |
|---|---|---|
| A | `release` | `[+] Installed new binary (` with the first 12 characters of `$GOOD`, scrape logs, `[+] Pushed updated calendar to GitHub.`, `exit=0`. `git -C "$SB/remote.git" log -1 --format=%s java-migration` prints `Update calendar events`. |
| B | `release` | No `Installed` line; pushes again (DTSTAMP changed); `exit=0` |
| C | `nowhere` | `[!] Binary update failed; keeping the installed binary.`, scrape still runs, `exit=0` |
| D | `bad-sum` | Same warning; `sha256sum $SB/pi/bin/bikernieki-calendar` still equals `$GOOD`; `exit=0` |
| E | `broken` | Same warning; the installed binary still equals `$GOOD`; `exit=0` |
| F | `nowhere`, run as `$SB/pi2/run.sh` (a clone with no `bin/`) | `[!] No binary installed and the download failed.`, `exit=1` |

Finally, `ls -A "$SB/pi/bin"` must list only `bikernieki-calendar`, with no leftover `.update.*`
directories. Then run `rm -rf "$SB"`. If any scenario fails, fix `run.sh`, commit, and rerun all
six.

---

### Task 10: Live comparison, retire Python, docs

**Files:**
- Delete: `bikernieki_calendar.py`, `update_calendar.sh`
- Modify: `.gitignore`. Remove the Python entries; the final list is `build/`, `.gradle/`,
  `.kotlin/`, `.idea/`, `*.iml`, `*.tmp`, `/bin/`.
- Modify: `README.md`, `AGENTS.md`

**Interfaces:**
- Consumes: the native binary (Task 7), `run.sh` (Task 9) and the workflow (Task 8). These docs
  describe them.

- [ ] **Step 1: Live comparison against the Python version**

This is the gate: don't delete anything until it passes.

```bash
T=$(mktemp -d)
python3 bikernieki_calendar.py -m 3 -o "$T/python.ics" && build/native/nativeCompile/bikernieki-calendar -m 3 -o "$T/java.ics"
normalize() { perl -0pe 's/\r\n //g' "$1" | grep -v '^DTSTAMP:'; }
diff <(normalize "$T/python.ics") <(normalize "$T/java.ics") && echo IDENTICAL
```

Expected: `IDENTICAL`. If bksb.lv changed between the two runs, rerun. Any other difference is a
bug to fix in the owning task before continuing.

- [ ] **Step 2: Delete the Python version and clean up `.gitignore`**

Run: `git rm bikernieki_calendar.py update_calendar.sh`, then edit `.gitignore` to the final list
above.

- [ ] **Step 3: Rewrite `README.md`**

Keep the intro, the "Subscribe with iCloud Calendar" section and both URLs unchanged. Replace the
rest with:

1. **How it works:**
   - The Java native binary scrapes the current and next two months.
   - The four excluded locations are listed.
   - `run.sh` commits and pushes the calendar.
2. **Development**, with JDK 25 and GraalVM CE 25 in the toolbox via SDKMAN:
   - `./gradlew test`
   - `./gradlew run --args="-m 3 -o bikernieki.ics"`
   - The toolbox native command from Global Constraints with `nativeCompile nativeTest`.
   - `build/native/nativeCompile/bikernieki-calendar --test`
3. **Releasing:** `git tag vX.Y.Z && git push origin vX.Y.Z`. CI tests and builds
   `linux-aarch64` and publishes the release, and the Pi installs it on its next run.
4. **Raspberry Pi automation (DietPi):**
   - Clone the repo.
   - The existing SSH deploy-key steps.
   - First run `./run.sh`.
   - Cron line `0 4 * * * /home/<user>/bksb-calendar/run.sh >> /tmp/bikernieku-cron.log 2>&1`.
   - What `run.sh` does: pull, self-update with checksum and `--test` gate, generate, commit and
     push.
   - A one-time note for switching an existing Pi from `update_calendar.sh`.
5. **CLI reference:** `-o`, `-m`, `-t`, `-h` with defaults, and exit codes 0/1/2.

- [ ] **Step 4: Rewrite `AGENTS.md`**

Same headings as today, with Java content. Drop the "HTTP Server Behavior" section and the stale
40-minute buffer and test claims. State:

- **Commands:** `./gradlew test`, the native commands in the toolbox, and `--test`.
- **Architecture:** one line per class, and jsoup as the only runtime dependency.
- **Native build arguments**, with their reasons: `--enable-url-protocols=https` and
  `-march=compatibility`.
- **Rules:**
  - The excluded locations.
  - The UID formula as a compatibility contract that must not change.
  - The zero-event guard, and the atomic write.
  - Fetching: 20 s timeout, 2 attempts, 2 s backoff, 60 s cap.
  - Folding at 75 octets.
- **Testing:**
  - Tests never hit the network.
  - The regression fixtures (`src/test/resources/expected/README.md`).
  - `nativeTest`.
- **Deployment:**
  - CI: Bookworm container on `ubuntu-24.04-arm`; `v*` tags trigger releases.
  - The `run.sh` flow; cron daily on the Pi.
- **Gotchas:**
  - CI can't reach bksb.lv.
  - DTSTAMP changes on every run, so there is a commit every day.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew test && git grep -nI -e python -e '\.py\b' -- ':!docs' ':!src/test/resources/expected/README.md'; git status --short`
Expected:
- BUILD SUCCESSFUL.
- `git grep` finds only README/AGENTS lines that intentionally mention the former Python version,
  if any.
- The status lists only the intended changes.

```bash
git add -A README.md AGENTS.md .gitignore
git commit -m "Retire the Python version; document the Java build and run.sh"
```

---

### Task 11: Rollout (needs the user's go-ahead at each step)

**Files:** none. This task is pushing, CI, release, and steps on the Pi.

- [ ] **Step 1: Get CI green on a pull request**

Ask the user before pushing. Then run `git push -u origin java-migration`. The user opens the pull
request at `https://github.com/sknarovs/bksb-calendar/compare/main...java-migration?expand=1`.

Check: `curl -fsSL "https://api.github.com/repos/sknarovs/bksb-calendar/actions/runs?branch=java-migration&per_page=1" | grep -E '"(status|conclusion)"'`
Expected: `"status": "completed"`, `"conclusion": "success"`. If the run fails, fix it, push, and
recheck.

- [ ] **Step 2: Merge into `main`**

Get the user's go-ahead, and do steps 2–4 on the same day: the Pi's old cron job can't push once
`main` moves. The user merges the pull request on GitHub, or locally:
`git switch main && git pull --ff-only && git merge --no-ff java-migration && git push`.

- [ ] **Step 3: Tag `v1.0.0` and confirm the release**

Get the user's go-ahead, then run
`git fetch origin && git tag v1.0.0 origin/main && git push origin v1.0.0`. When the workflow
finishes, run:
`curl -fsSL https://github.com/sknarovs/bksb-calendar/releases/latest/download/bikernieki-calendar-linux-aarch64.sha256`
Expected: one line, `<64 hex>  bikernieki-calendar-linux-aarch64`.

- [ ] **Step 4: Cut over on the Pi (the user does this)**

1. On the Pi: `cd ~/bksb-calendar && git pull --rebase && ./run.sh`.
   Expected: `[+] Installed new binary (…)`, scrape logs and
   `[+] Pushed updated calendar to GitHub.`. The commit diff reflows folded lines but keeps every
   UID.
2. `crontab -e`: replace `update_calendar.sh` with `run.sh` in the existing line.

- [ ] **Step 5: Confirm the first unattended run**

The morning after, check `/tmp/bikernieku-cron.log` on the Pi and confirm the latest
`Update calendar events` commit on GitHub has that day's timestamp.
