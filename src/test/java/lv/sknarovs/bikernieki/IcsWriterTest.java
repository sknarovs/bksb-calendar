package lv.sknarovs.bikernieki;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

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
