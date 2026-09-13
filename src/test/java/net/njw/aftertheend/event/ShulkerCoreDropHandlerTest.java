package net.njw.aftertheend.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShulkerCoreDropHandlerTest {
    @Test
    void usesHalfTheVanillaShulkerShellChance() {
        assertEquals(0.25D, ShulkerCoreDropHandler.dropChance(0));
        assertEquals(0.28125D, ShulkerCoreDropHandler.dropChance(1));
        assertEquals(0.3125D, ShulkerCoreDropHandler.dropChance(2));
        assertEquals(0.34375D, ShulkerCoreDropHandler.dropChance(3));
    }

    @Test
    void capsChanceAtHalf() {
        assertEquals(0.5D, ShulkerCoreDropHandler.dropChance(99));
    }
}
