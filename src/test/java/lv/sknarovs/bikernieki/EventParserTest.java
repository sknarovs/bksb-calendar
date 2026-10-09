package lv.sknarovs.bikernieki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
