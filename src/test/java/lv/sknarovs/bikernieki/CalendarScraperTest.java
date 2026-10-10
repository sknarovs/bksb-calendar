package lv.sknarovs.bikernieki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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
        long distinct = TestData.resource("/expected/2026-10.tsv").lines().skip(1)
                .map(row -> row.split("\t")[0]).distinct().count();
        assertEquals(distinct, events.size());
    }
}
