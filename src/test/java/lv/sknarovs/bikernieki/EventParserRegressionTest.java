package lv.sknarovs.bikernieki;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Compares the parser with what the Python version extracted from the same pages. */
class EventParserRegressionTest {
    @ParameterizedTest
    @ValueSource(strings = {"2026-07", "2026-08", "2026-10", "2026-11", "2026-12"})
    void extractsSameEventsAsPythonVersion(String month) {
        List<String> expected = TestData.resource("/expected/" + month + ".tsv").lines().skip(1).toList();
        List<String> actual = EventParser.parse(TestData.resource("/pages/" + month + ".html"))
                .stream().map(TestData::tsv).toList();
        assertEquals(expected, actual);
    }
}
