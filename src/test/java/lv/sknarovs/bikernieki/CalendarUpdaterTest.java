package lv.sknarovs.bikernieki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(out), files.toList());
        }
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
