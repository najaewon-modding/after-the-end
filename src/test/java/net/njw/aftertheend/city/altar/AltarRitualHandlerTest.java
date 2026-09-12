package net.njw.aftertheend.city.altar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AltarRitualHandlerTest {
    @Test
    void firstActivationUnlocksWhenCitySlotRemains() {
        assertTrue(AltarRitualHandler.canActivate(0, 4, 5));
        assertTrue(AltarRitualHandler.unlocksCity(0, 4, 5));
    }

    @Test
    void firstActivationIsBlockedAtCityLimit() {
        assertFalse(AltarRitualHandler.canActivate(0, 5, 5));
        assertFalse(AltarRitualHandler.unlocksCity(0, 5, 5));
    }

    @Test
    void secondAndThirdAltarsRemainAvailableAtCityLimit() {
        assertTrue(AltarRitualHandler.canActivate(1, 5, 5));
        assertFalse(AltarRitualHandler.unlocksCity(1, 5, 5));
        assertTrue(AltarRitualHandler.canActivate(2, 5, 5));
        assertFalse(AltarRitualHandler.unlocksCity(2, 5, 5));
    }

    @Test
    void fourthAltarIsAlwaysBlocked() {
        assertFalse(AltarRitualHandler.canActivate(3, 1, 5));
        assertFalse(AltarRitualHandler.unlocksCity(3, 1, 5));
    }
}
