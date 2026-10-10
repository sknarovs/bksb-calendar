# Biķernieku Race Track Calendar Scraper & iCal Feed

This tool scrapes the events calendar from [bksb.lv](https://bksb.lv/index.php/2014-01-03-13-49-44/month.calendar/) and generates an iCalendar (`.ics`) subscription feed.

The calendar shows events that **block public access** to the race track (open 6:00–23:00 daily). Events at the office, speedway stadium, large parking lot, or museum circuit are excluded since they don't impact visitors.

---

## Subscribe with iCloud Calendar

Once set up on your Raspberry Pi, the `.ics` file is pushed to GitHub and stays updated daily.

**Subscription URL:**
```
https://ej.uz/bksb_calendar
```

**Raw GitHub URL:**
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

1. A small Java program, compiled to a native binary with GraalVM, fetches the current and the next two months from bksb.lv.
2. Events at locations that don't affect visitors are dropped:
   - `BKSB Birojs` (office)
   - `BKSB Spīdveja stadions` (speedway stadium — separate venue)
   - `BKSB "Motormuzeja līkums"` (museum circuit)
   - `BKSB lielā auto stāvvieta` (large parking lot — no track impact)
3. Every day the Raspberry Pi runs `run.sh`, which regenerates `bikernieki.ics`, commits it and pushes it to GitHub
4. Your subscribed calendar picks up the changes automatically

GitHub Actions only builds the binary: it can't reach bksb.lv, so the scraping happens on the Pi.

If a scrape finds no events at all while a calendar already exists (for example when bksb.lv is down), the existing file is kept and nothing is pushed.

---

## Development

The JVM build and tests need JDK 25. The native binary needs GraalVM CE 25 and a C toolchain (gcc, glibc and zlib headers); on Fedora Atomic that lives in a toolbox, with GraalVM installed through SDKMAN.

```bash
# Run the tests (works on the host)
./gradlew test

# Run on the JVM
./gradlew run --args="-m 3 -o bikernieki.ics"

# Build the native binary and run the tests as a native image (inside the toolbox)
toolbox enter
export GRAALVM_HOME=$HOME/.sdkman/candidates/java/25.4.4.1+1-graalce
./gradlew nativeCompile nativeTest

# Use the native binary
build/native/nativeCompile/bikernieki-calendar --test
build/native/nativeCompile/bikernieki-calendar -m 3 -o bikernieki.ics
```

---

## Releasing

```bash
git tag v1.1.0
git push origin v1.1.0
```

The GitHub Actions workflow runs the tests (on the JVM and as a native image), builds `bikernieki-calendar-linux-aarch64` in a Debian 12 container — so it runs on any DietPi based on Debian 12 or newer — and publishes it with a `.sha256` file as a GitHub release. The Pi installs it on its next run. Pushes to `main` and pull requests run the same build without publishing.

---

## Raspberry Pi Automation (DietPi)

`run.sh` is the daily cron job. Each run it:

1. pulls the latest commits (`git pull --rebase`)
2. installs the binary from the latest GitHub release into `bin/` when its checksum changed — only after the new binary passes its own `--test`; if GitHub can't be reached or the download is broken, it keeps using the installed binary
3. regenerates `bikernieki.ics`
4. commits and pushes it if it changed

It needs a 64-bit (aarch64) OS such as DietPi v10, plus `git` and `curl`, which DietPi ships with.

### 1. Clone the repo

```bash
git clone https://github.com/sknarovs/bksb-calendar.git ~/bksb-calendar
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

### 3. Run it once

```bash
cd ~/bksb-calendar
./run.sh
```

The first run downloads the binary from the latest release, generates the calendar and pushes it.

### 4. Add a daily cron job

```bash
crontab -e
```

Add this line to run every day at 04:00 (cron uses the Pi's local time zone):
```
0 4 * * * /home/username/bksb-calendar/run.sh >> /tmp/bikernieku-cron.log 2>&1
```

### Switching from the Python version

If the Pi still runs `update_calendar.sh`:

1. `cd ~/bksb-calendar && git pull --rebase && ./run.sh`
2. `crontab -e` and replace `update_calendar.sh` with `run.sh` in the existing line

Python is no longer needed.

---

## CLI Reference

| Flag | Default | Description |
|------|---------|-------------|
| `-o`, `--output` | `bikernieki.ics` | Output file path |
| `-m`, `--months` | `3` | Months to scrape (current + N-1 ahead) |
| `-t`, `--test` | off | Run the built-in self-test and exit |
| `-h`, `--help` | | Show help and exit |

Exit codes: `0` success, `1` the scrape or the self-test failed (an existing calendar is kept), `2` invalid arguments.
