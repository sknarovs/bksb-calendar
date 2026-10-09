package lv.sknarovs.bikernieki;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code --test} smoke test. It runs in the shipped binary, so it also proves a native build can parse,
 * hash and fold before {@code run.sh} installs it.
 */
final class SelfTest {
    /** One event cell as bksb.lv renders it. */
    private static final String SAMPLE_PAGE = """
            <div class="jevdaydata"></div>
            <div class="jeveventrow slots1">
                <span class="editlinktip hasjevtip" title="&lt;div class=&quot;jevtt_title&quot; style = &quot;color:#000000;background-color:#00a4e6;&quot;&gt;Test Sacensības&lt;/div&gt;  Kategorija: Autošoseja &lt;br&gt;  Laiks: &lt;a class=&quot;cal_titlelink&quot; href=&quot;/index.php/icalrepeat.detail/2026/05/01/9999/-/test-sacensibas&quot;&gt;08:00-20:00 &lt;/a&gt; &lt;br&gt;  Kur: Lielā trase">
                    <a class="cal_titlelink" href="/index.php/icalrepeat.detail/2026/05/01/9999/-/test-sacensibas">08:00-20:00 Test Sacensības</a>
                </span>
            </div>
            """;

    private static final Map<String, String> EXCLUDED_LOCATION_SPELLINGS = Map.of(
            "BKSB \"Motormuzeja līkums\"", "bksb motormuzeja likums",
            "BKSB spīdveja stadions", "bksb spidveja stadions",
            "BKSB BIROJS", "bksb birojs",
            "BKSB lielā auto stāvvieta", "bksb liela auto stavvieta");

    private SelfTest() {
    }

    static boolean run() {
        System.out.println("[*] Running parser unit tests...");
        String failure = firstFailure();
        if (failure != null) {
            System.out.println("[!] Unit test FAILED: " + failure);
            return false;
        }
        System.out.println("[+] All parser unit tests passed successfully!");
        return true;
    }

    /** Describes the first check that doesn't hold, or returns null when all of them pass. */
    private static String firstFailure() {
        List<CalendarEvent> events = EventParser.parse(SAMPLE_PAGE);
        if (events.size() != 1) {
            return "expected 1 event in the sample page, got " + events.size();
        }
        CalendarEvent event = events.getFirst();
        List<Check> checks = new ArrayList<>(List.of(
                new Check("start", LocalDateTime.of(2026, 5, 1, 8, 0), event.start()),
                new Check("end", LocalDateTime.of(2026, 5, 1, 20, 0), event.end()),
                new Check("summary", "Test Sacensības", event.summary()),
                new Check("category", "Autošoseja", event.category()),
                new Check("location", "Lielā trase", event.location()),
                new Check("url", "https://bksb.lv/index.php/icalrepeat.detail/2026/05/01/9999/-/test-sacensibas",
                        event.url()),
                new Check("uid", "1c7fcc6deaf936b4cc4d5b24c5e7fa71@bikernieku-calendar", event.uid())));
        EXCLUDED_LOCATION_SPELLINGS.forEach((spelling, normalized) -> {
            checks.add(new Check("normalized " + spelling, normalized, EventParser.normalizeLocation(spelling)));
            checks.add(new Check(spelling + " excluded", true, EventParser.isExcluded(spelling)));
        });
        String[] folded = IcsWriter.fold("SUMMARY:" + "A".repeat(100)).split("\r\n");
        checks.add(new Check("folded line count", 2, folded.length));
        checks.add(new Check("continuation line starts with a space", true, folded[folded.length - 1].startsWith(" ")));
        return checks.stream().filter(check -> !check.passed()).map(Check::describe).findFirst().orElse(null);
    }

    private record Check(String name, Object expected, Object actual) {
        boolean passed() {
            return expected.equals(actual);
        }

        String describe() {
            return name + ": expected <" + expected + "> but was <" + actual + ">";
        }
    }
}
