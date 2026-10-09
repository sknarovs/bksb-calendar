package lv.sknarovs.bikernieki;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

/** Shared helpers for reading test fixtures and comparing calendar output. */
final class TestData {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private TestData() {
    }

    /** Reads a classpath resource as UTF-8, failing the test if it is missing. */
    static String resource(String path) {
        try (InputStream in = TestData.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing test resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Renders an event in the column order of the {@code expected/*.tsv} files. */
    static String tsv(CalendarEvent e) {
        return String.join("\t", e.uid(),
                e.start().toLocalDate().toString(), e.start().format(TIME),
                e.end().toLocalDate().toString(), e.end().format(TIME),
                e.summary(), e.category(), e.location(), e.url());
    }

    /** Undoes RFC 5545 line folding. */
    static String unfold(String ics) {
        return ics.replace("\r\n ", "");
    }
}
