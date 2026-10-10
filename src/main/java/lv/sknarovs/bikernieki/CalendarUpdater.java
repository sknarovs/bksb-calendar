package lv.sknarovs.bikernieki;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/** One update run: scrape, refuse to replace a good calendar with an empty one, write the file atomically. */
final class CalendarUpdater {
    /** An existing calendar larger than this is assumed to hold real events worth preserving. */
    private static final long PRESERVE_THRESHOLD_BYTES = 500;

    private final CalendarScraper scraper;
    private final Clock clock;
    private final Path output;
    private final int months;

    CalendarUpdater(CalendarScraper scraper, Clock clock, Path output, int months) {
        this.scraper = scraper;
        this.clock = clock;
        this.output = output;
        this.months = months;
    }

    /** Returns whether a new calendar was written. */
    boolean run() {
        System.out.println("[*] Starting CLI scrape: lookahead = " + months + " months, saving to " + output + "...");
        try {
            List<CalendarEvent> events = scraper.scrape(months);
            if (events.isEmpty() && Files.exists(output) && Files.size(output) > PRESERVE_THRESHOLD_BYTES) {
                System.out.println("[!] Scrape returned 0 events. Preserving existing calendar file.");
                return failed();
            }
            write(IcsWriter.write(events, clock.instant()));
            System.out.println("[+] Successfully refreshed. Extracted " + events.size() + " events.");
            System.out.println("[+] Completed! Calendar written to " + output);
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.out.println("[!] Scraper refresh failed: " + e);
            return failed();
        }
    }

    private static boolean failed() {
        System.out.println("[!] Scrape execution failed.");
        return false;
    }

    /** Writes next to the target and renames, so a crash never leaves a truncated calendar behind. */
    private void write(String ics) throws IOException {
        Path tmp = output.resolveSibling(output.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, ics, StandardCharsets.UTF_8);
            Files.move(tmp, output, REPLACE_EXISTING, ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
