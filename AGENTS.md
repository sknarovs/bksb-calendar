# AGENTS.md

Java scraper that generates an iCalendar feed for Biķernieku Race Track, shipped as a GraalVM native binary.

## Developer Commands

- **Run tests** (JUnit, no network, works on the host): `./gradlew test`
- **Run on the JVM**: `./gradlew run --args="-m 3 -o bikernieki.ics"`
- **Native binary + native tests** — need GraalVM CE 25 and gcc, so run them in the Fedora toolbox:
  `toolbox run -c fedora-toolbox-44 bash -c 'cd <repo> && export JAVA_HOME=$HOME/.sdkman/candidates/java/25.4.4.1+1-graalce GRAALVM_HOME=$HOME/.sdkman/candidates/java/25.4.4.1+1-graalce && ./gradlew nativeCompile nativeTest'`
  (a non-login shell doesn't load SDKMAN, hence the explicit exports). Output: `build/native/nativeCompile/bikernieki-calendar`.
- **Self-test a built binary**: `build/native/nativeCompile/bikernieki-calendar --test`

## Key Architecture Facts

- **Java 25, Gradle (Kotlin DSL) with the GraalVM Native Build Tools plugin.** The only runtime dependency is jsoup (HTML parsing and entity decoding); everything else is the JDK. Don't add dependencies casually — each one must work in a native image.
- Classes (`src/main/java/lv/sknarovs/bikernieki/`):
  - `Main` / `CliOptions` — entry point and flags `-o`, `-m`, `-t`, `-h`; exit codes 0 success, 1 failure, 2 usage error. Logs go to stdout as UTF-8 regardless of locale.
  - `BksbClient` — fetches month pages: 20 s timeout, 2 attempts, 2 s backoff, 60 s cap per attempt (bksb.lv can be slow).
  - `EventParser` — every `a.cal_titlelink` plus the tooltip on its enclosing `span.editlinktip` (`Kategorija:`, `Kur:`).
  - `CalendarScraper` — month window from a `Clock`, 0.5 s pause between pages, dedupe by UID, sort by start.
  - `IcsWriter` — RFC 5545 output with the Europe/Riga `VTIMEZONE`, folding at 75 octets without splitting UTF-8 characters.
  - `CalendarUpdater` — zero-event guard and atomic write.
  - `SelfTest` — `--test`; CI and `run.sh` run it on every new binary before using it.
- **Excluded locations** (compared after lowercasing and stripping quotes and diacritics): office (`BKSB birojs`), speedway stadium, museum circuit (`BKSB "Motormuzeja līkums"`), large parking lot.
- Events carry the times shown on bksb.lv (there is no buffer). An end that isn't after the start moves to the next day; a title without a valid time is all day (00:00–23:59).
- **The UID formula is a compatibility contract**: MD5 of `start date|start HH:mm|end date|end HH:mm|title|raw location` + `@bikernieku-calendar`. Changing it makes every subscriber's calendar delete and re-add all events.
- **Data preservation guard**: if a scrape returns 0 events while the output file exists and is larger than 500 bytes, the file is kept and the run exits 1. Otherwise the file is written to a temp file and atomically renamed.
- **Native build arguments** in `build.gradle.kts` — keep both:
  - `--enable-url-protocols=https`: jsoup resolves links through `java.net.URL`; without it native images produce empty event links.
  - `-march=compatibility`: GraalVM targets ARMv8.1 by default, but Raspberry Pi 3/4 cores are ARMv8.0.

## Automation / Deployment

- **CI** (`.github/workflows/build.yml`) runs on `ubuntu-24.04-arm` in a `debian:bookworm` container, so the binary only needs glibc 2.36 and runs on any DietPi v10. It runs `test nativeTest nativeCompile`, smoke-tests the binary with `--test`, and uploads `bikernieki-calendar-linux-aarch64` plus its `.sha256`.
- **Releases**: pushing a `v*` tag publishes those two files as a GitHub release. CI cannot reach bksb.lv, so it never scrapes.
- **`run.sh`** is the Pi's daily cron job: `git pull --rebase`, install the latest release into `bin/` (checksum plus `--test` gate; on any failure it keeps the installed binary), run `bin/bikernieki-calendar -m 3 -o bikernieki.ics`, then commit `Update calendar events` and push. `BKSB_RELEASE_URL` overrides the release URL for local testing.
- DTSTAMP changes on every run, so the Pi commits the calendar every day.
- Pushing requires SSH key auth on the Pi (deploy key or personal SSH key).

## Testing

- JUnit 6 tests in `src/test/java`. They never touch the network: pages come from a `MonthSource` stub, and `BksbClientTest` uses a local `HttpServer`.
- Regression fixtures: real bksb.lv pages in `src/test/resources/pages/`, and in `src/test/resources/expected/` the events and calendar the former Python version produced from them (see the README there). The Java output must match them, UIDs included.
- `nativeTest` runs the same suite as a native image; CI runs it on aarch64.
