package lv.sknarovs.bikernieki;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/** Command-line options. The flags and defaults are the ones the Python version had, minus server mode. */
record CliOptions(Path output, int months, boolean test, boolean help) {
    static final String USAGE = "usage: bikernieki-calendar [-h] [-o OUTPUT] [-m MONTHS] [-t]";
    static final String HELP = USAGE + "\n\n" + """
            Bikernieki Race Track Calendar Parser and ICS Generator

            options:
              -h, --help            show this help message and exit
              -o, --output OUTPUT   Output path for static ICS file (default: bikernieki.ics)
              -m, --months MONTHS   Number of months to scrape including current (default: 3)
              -t, --test            Run self-testing harness and parser validations""";

    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    /** Accepts {@code --opt value}, {@code --opt=value}, {@code -o value} and {@code -ovalue}. */
    static CliOptions parse(String... args) throws UsageException {
        Path output = Path.of("bikernieki.ics");
        int months = 3;
        boolean test = false;
        boolean help = false;
        Deque<String> tokens = tokens(args);
        while (!tokens.isEmpty()) {
            String option = tokens.removeFirst();
            switch (option) {
                case "-o", "--output" -> output = Path.of(value(tokens, "-o/--output"));
                case "-m", "--months" -> months = integer(value(tokens, "-m/--months"), "-m/--months");
                case "-t", "--test" -> test = true;
                case "-h", "--help" -> help = true;
                default -> throw new UsageException("unrecognized arguments: " + option);
            }
        }
        return new CliOptions(output, months, test, help);
    }

    /** Splits {@code --opt=value} and {@code -ovalue} into separate option and value tokens. */
    private static Deque<String> tokens(String... args) {
        Deque<String> tokens = new ArrayDeque<>();
        for (String arg : args) {
            int equals = arg.indexOf('=');
            if (arg.startsWith("--") && equals > 0) {
                tokens.add(arg.substring(0, equals));
                tokens.add(arg.substring(equals + 1));
            } else if ((arg.startsWith("-o") || arg.startsWith("-m")) && arg.length() > 2) {
                tokens.add(arg.substring(0, 2));
                tokens.add(arg.substring(2));
            } else {
                tokens.add(arg);
            }
        }
        return tokens;
    }

    private static String value(Deque<String> tokens, String option) throws UsageException {
        String next = tokens.peekFirst();
        if (next == null || (next.startsWith("-") && !next.matches("-\\d+"))) {
            throw new UsageException("argument " + option + ": expected one argument");
        }
        return tokens.removeFirst();
    }

    private static int integer(String value, String option) throws UsageException {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new UsageException("argument " + option + ": invalid int value: '" + value + "'");
        }
    }
}
