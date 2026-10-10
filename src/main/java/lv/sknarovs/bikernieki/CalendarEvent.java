package lv.sknarovs.bikernieki;

import java.time.LocalDateTime;

/** One event as published in the calendar; times are local Europe/Riga time. */
record CalendarEvent(String uid, LocalDateTime start, LocalDateTime end, String summary, String category,
        String location, String url) {
}
