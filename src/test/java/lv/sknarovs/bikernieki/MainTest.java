package lv.sknarovs.bikernieki;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MainTest {
    @Test void exitCodes() {
        assertEquals(0, Main.run("--help"));
        assertEquals(0, Main.run("--test"));
        assertEquals(2, Main.run("--serve"));
        assertEquals(2, Main.run("-m", "abc"));
    }
}
