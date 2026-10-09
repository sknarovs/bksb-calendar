package lv.sknarovs.bikernieki;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/** Extracts calendar events from a bksb.lv (JEvents) month page. */
final class EventParser {
    static final String BASE_URL = "https://bksb.lv";

    private static final Pattern LINK_DATE = Pattern.compile("icalrepeat\\.detail/(\\d{4})/(\\d{2})/(\\d{2})");
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{2}:\\d{2})-(\\d{2}:\\d{2})\\s+(.*)");
    private static final Pattern SINGLE_TIME = Pattern.compile("(\\d{2}:\\d{2})\\s+(.*)");
    private static final Pattern CATEGORY = Pattern.compile("Kategorija:\\s*([^<]+)");
    private static final Pattern LOCATION = Pattern.compile("Kur:\\s*([^<]+)");
    private static final Pattern QUOTES = Pattern.compile("[\"'“”«»]");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");
    private static final String DEFAULT_LOCATION = "Bikernieku Trase";

    /** Locations whose events don't block public access to the track, in normalized form. */
    private static final Set<String> EXCLUDED_LOCATIONS = Set.of(
            "bksb birojs", "bksb spidveja stadions", "bksb motormuzeja likums", "bksb liela auto stavvieta");

    private EventParser() {
    }

    static List<CalendarEvent> parse(String html) {
        List<CalendarEvent> events = new ArrayList<>();
        for (Element link : Jsoup.parse(html, BASE_URL).select("a.cal_titlelink")) {
            LocalDate date = linkDate(link.attr("href"));
            if (date == null) {
                continue;
            }
            String title = link.wholeText().strip();
            Element tooltipSpan = link.closest("span.editlinktip");
            String tooltip = tooltipSpan == null ? "" : tooltipSpan.attr("title");
            String location = tooltipField(LOCATION, tooltip);
            Timing timing = Timing.of(date, title);
            if (isExcluded(location)) {
                System.out.println("[-] Excluding event due to location '" + location + "': " + timing.summary());
                continue;
            }
            events.add(new CalendarEvent(uid(timing, title, location), timing.start(), timing.end(),
                    timing.summary(), tooltipField(CATEGORY, tooltip),
                    location.isEmpty() ? DEFAULT_LOCATION : location, link.absUrl("href")));
        }
        return events;
    }

    /** Lowercases, drops quotes and diacritics, and collapses whitespace so location variants compare equal. */
    static String normalizeLocation(String location) {
        String decomposed = Normalizer.normalize(location.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        String plain = QUOTES.matcher(COMBINING_MARKS.matcher(decomposed).replaceAll("")).replaceAll("");
        return WHITESPACE.matcher(plain.strip()).replaceAll(" ");
    }

    static boolean isExcluded(String location) {
        return EXCLUDED_LOCATIONS.contains(normalizeLocation(location));
    }

    private static LocalDate linkDate(String href) {
        Matcher m = LINK_DATE.matcher(href);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** Text after a tooltip label up to the next tag; the tooltip text itself may still hold entities. */
    private static String tooltipField(Pattern label, String tooltip) {
        Matcher m = label.matcher(tooltip);
        return m.find() ? Parser.unescapeEntities(m.group(1), false).strip() : "";
    }

    /**
     * Stable across runs so calendar apps can match events. The key format (and the raw, possibly empty,
     * location in it) must not change, or every subscriber sees all events deleted and re-added.
     */
    private static String uid(Timing timing, String title, String location) {
        String key = String.join("|", timing.start().toLocalDate().toString(), timing.start().format(HOUR_MINUTE),
                timing.end().toLocalDate().toString(), timing.end().format(HOUR_MINUTE), title, location);
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest) + "@bikernieku-calendar";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required on every Java platform", e);
        }
    }

    /** Start, end and summary read from a link title such as {@code 08:00-20:00 Summary}. */
    private record Timing(LocalDateTime start, LocalDateTime end, String summary) {
        static Timing of(LocalDate date, String title) {
            try {
                Matcher range = TIME_RANGE.matcher(title);
                if (range.matches()) {
                    return within(date, LocalTime.parse(range.group(1)), LocalTime.parse(range.group(2)),
                            range.group(3));
                }
                Matcher single = SINGLE_TIME.matcher(title);
                if (single.matches()) {
                    LocalTime start = LocalTime.parse(single.group(1));
                    return within(date, start, start.plusHours(1), single.group(2));
                }
            } catch (DateTimeException e) {
                // Not a time of day (e.g. "25:00"): fall through and treat the title as an all-day event.
            }
            return new Timing(date.atStartOfDay(), date.atTime(23, 59), title);
        }

        /** An end that isn't after the start belongs to the next day (overnight events). */
        private static Timing within(LocalDate date, LocalTime start, LocalTime end, String summary) {
            LocalDateTime from = date.atTime(start);
            LocalDateTime to = date.atTime(end);
            return new Timing(from, to.isAfter(from) ? to : to.plusDays(1), summary);
        }
    }
}
