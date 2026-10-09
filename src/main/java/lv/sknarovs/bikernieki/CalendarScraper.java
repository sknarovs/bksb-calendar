package lv.sknarovs.bikernieki;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Collects the events of the current month and the following ones into one calendar. */
final class CalendarScraper {
    private final MonthSource source;
    private final Clock clock;
    private final Duration pacing;

    CalendarScraper(MonthSource source, Clock clock, Duration pacing) {
        this.source = source;
        this.clock = clock;
        this.pacing = pacing;
    }

    /**
     * Events of {@code months} months starting with the current one, deduplicated by UID and sorted by start.
     * A month that can't be loaded contributes no events.
     */
    List<CalendarEvent> scrape(int months) throws InterruptedException {
        Map<String, CalendarEvent> byUid = new LinkedHashMap<>();
        for (YearMonth month : targetMonths(LocalDate.now(clock), months)) {
            source.fetch(month).map(EventParser::parse)
                    .ifPresent(events -> events.forEach(event -> byUid.putIfAbsent(event.uid(), event)));
            Thread.sleep(pacing); // friendly rate limiting towards bksb.lv
        }
        List<CalendarEvent> events = new ArrayList<>(byUid.values());
        events.sort(Comparator.comparing(CalendarEvent::start));
        return events;
    }

    static List<YearMonth> targetMonths(LocalDate today, int count) {
        YearMonth first = YearMonth.from(today);
        return IntStream.range(0, count).mapToObj(first::plusMonths).toList();
    }
}
