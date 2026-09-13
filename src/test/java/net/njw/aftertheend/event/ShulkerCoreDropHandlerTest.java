package net.njw.aftertheend.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShulkerCoreDropHandlerTest {
    @Test
    void onlyDropsWhenShellDroppedAndCoinFlipPasses() {
        assertTrue(ShulkerCoreDropHandler.shouldDropCore(true, 0.0D));
        assertTrue(ShulkerCoreDropHandler.shouldDropCore(true, 0.499999D));
        assertFalse(ShulkerCoreDropHandler.shouldDropCore(true, 0.5D));
        assertFalse(ShulkerCoreDropHandler.shouldDropCore(false, 0.0D));
    }
}
