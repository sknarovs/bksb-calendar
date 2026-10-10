package lv.sknarovs.bikernieki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CliOptionsTest {
    @Test void defaults() throws Exception {
        assertEquals(new CliOptions(Path.of("bikernieki.ics"), 3, false, false), CliOptions.parse());
    }

    @Test void acceptsShortLongAndAttachedForms() throws Exception {
        CliOptions expected = new CliOptions(Path.of("x.ics"), 5, false, false);
        assertEquals(expected, CliOptions.parse("-o", "x.ics", "-m", "5"));
        assertEquals(expected, CliOptions.parse("--output", "x.ics", "--months", "5"));
        assertEquals(expected, CliOptions.parse("--output=x.ics", "--months=5"));
        assertEquals(expected, CliOptions.parse("-ox.ics", "-m5"));
    }

    @Test void flags() throws Exception {
        assertTrue(CliOptions.parse("-t").test());
        assertTrue(CliOptions.parse("--test").test());
        assertTrue(CliOptions.parse("-h").help());
        assertTrue(CliOptions.parse("--help").help());
    }

    @Test void rejectsBadArguments() {
        for (String[] args : List.of(new String[] {"--serve"}, new String[] {"-s"}, new String[] {"-p", "8080"},
                new String[] {"-m", "abc"}, new String[] {"-o"}, new String[] {"extra"})) {
            assertThrows(CliOptions.UsageException.class, () -> CliOptions.parse(args), String.join(" ", args));
        }
    }
}
