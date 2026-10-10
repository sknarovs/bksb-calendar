package lv.sknarovs.bikernieki;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

/** Scrapes the Biķernieki race track calendar from bksb.lv into an iCalendar file. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        // Cron runs with a POSIX locale; print UTF-8 anyway so Latvian text in the log stays readable.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        System.exit(run(args));
    }

    /** Runs the command and returns its exit code: 0 success, 1 failure, 2 usage error. */
    static int run(String... args) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (CliOptions.UsageException e) {
            System.err.println(CliOptions.USAGE);
            System.err.println("bikernieki-calendar: error: " + e.getMessage());
            return 2;
        }
        if (options.help()) {
            System.out.println(CliOptions.HELP);
            return 0;
        }
        if (options.test()) {
            return SelfTest.run() ? 0 : 1;
        }
        Clock clock = Clock.systemDefaultZone();
        CalendarScraper scraper = new CalendarScraper(new BksbClient(), clock, Duration.ofMillis(500));
        return new CalendarUpdater(scraper, clock, options.output(), options.months()).run() ? 0 : 1;
    }
}
