package lv.sknarovs.bikernieki;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Renders events as an RFC 5545 calendar in Europe/Riga time. */
final class IcsWriter {
    private static final List<String> HEADER = List.of(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Bikernieku Calendar//EN",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            "X-WR-CALNAME:Biķernieku Trases Kalendārs",
            "X-WR-TIMEZONE:Europe/Riga");

    /** Latvian time (EET/EEST), so calendar apps don't need their own Europe/Riga definition. */
    private static final List<String> RIGA_TIMEZONE = List.of(
            "BEGIN:VTIMEZONE",
            "TZID:Europe/Riga",
            "BEGIN:STANDARD",
            "DTSTART:19701025T040000",
            "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU",
            "TZOFFSETFROM:+0300",
            "TZOFFSETTO:+0200",
            "TZNAME:EET",
            "END:STANDARD",
            "BEGIN:DAYLIGHT",
            "DTSTART:19700329T030000",
            "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU",
            "TZOFFSETFROM:+0200",
            "TZOFFSETTO:+0300",
            "TZNAME:EEST",
            "END:DAYLIGHT",
            "END:VTIMEZONE");

    private static final DateTimeFormatter UTC_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final int MAX_LINE_OCTETS = 75;

    private IcsWriter() {
    }

    static String write(List<CalendarEvent> events, Instant dtstamp) {
        List<String> lines = new ArrayList<>(HEADER);
        lines.addAll(RIGA_TIMEZONE);
        String stamp = UTC_STAMP.format(dtstamp);
        for (CalendarEvent event : events) {
            lines.add("BEGIN:VEVENT");
            lines.add("UID:" + event.uid());
            lines.add("DTSTAMP:" + stamp);
            lines.add("DTSTART;TZID=Europe/Riga:" + LOCAL_TIME.format(event.start()));
            lines.add("DTEND;TZID=Europe/Riga:" + LOCAL_TIME.format(event.end()));
            lines.add("SUMMARY:" + escape(event.summary()));
            String location = escape(event.location());
            if (!location.isEmpty()) {
                lines.add("LOCATION:" + location);
            }
            lines.add("DESCRIPTION:" + escape(description(event)));
            lines.add("END:VEVENT");
        }
        lines.add("END:VCALENDAR");

        StringBuilder ics = new StringBuilder();
        for (String line : lines) {
            ics.append(fold(line)).append("\r\n");
        }
        return ics.toString();
    }

    /** Escapes a TEXT value: backslash, semicolon, comma and line breaks. */
    static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    /** Splits a content line into physical lines of at most 75 octets without breaking a character. */
    static String fold(String line) {
        StringBuilder folded = new StringBuilder(line.length() + 8);
        int octets = 0;
        for (int i = 0; i < line.length(); ) {
            int codePoint = line.codePointAt(i);
            int size = utf8Length(codePoint);
            if (octets + size > MAX_LINE_OCTETS) {
                folded.append("\r\n ");
                octets = 1;
            }
            folded.appendCodePoint(codePoint);
            octets += size;
            i += Character.charCount(codePoint);
        }
        return folded.toString();
    }

    private static String description(CalendarEvent event) {
        String link = "Pasākuma saite: " + event.url();
        return event.category().isEmpty() ? link : "Kategorija: " + event.category() + "\n" + link;
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }
}
