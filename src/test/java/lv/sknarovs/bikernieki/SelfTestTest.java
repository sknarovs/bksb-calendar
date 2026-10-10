package lv.sknarovs.bikernieki;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SelfTestTest {
    @Test void passes() {
        assertTrue(SelfTest.run());
    }
}
