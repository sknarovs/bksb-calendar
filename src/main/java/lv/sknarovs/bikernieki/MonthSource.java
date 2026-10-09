package lv.sknarovs.bikernieki;

import java.time.YearMonth;
import java.util.Optional;

/** Supplies the HTML of one bksb.lv month page, or nothing when the page couldn't be loaded. */
@FunctionalInterface
interface MonthSource {
    Optional<String> fetch(YearMonth month);
}
