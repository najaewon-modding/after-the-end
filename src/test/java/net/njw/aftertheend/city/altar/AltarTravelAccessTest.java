package net.njw.aftertheend.city.altar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AltarTravelAccessTest {
    @Test
    void smallAltarRequiresPlayerInsideInsetFootprint() {
        assertTrue(AltarTravelAccess.isInsideInteractionArea(105.5, 66.0, 205.5, 100, 64, 200, false));
        assertFalse(AltarTravelAccess.isInsideInteractionArea(100.99, 66.0, 205.5, 100, 64, 200, false));
        assertFalse(AltarTravelAccess.isInsideInteractionArea(110.0, 66.0, 205.5, 100, 64, 200, false));
        assertFalse(AltarTravelAccess.isInsideInteractionArea(105.5, 72.0, 205.5, 100, 64, 200, false));
    }

    @Test
    void largeAltarUsesSameInsetRuleWithLargerFootprint() {
        assertTrue(AltarTravelAccess.isInsideInteractionArea(113.5, 68.0, 213.5, 100, 64, 200, true));
        assertTrue(AltarTravelAccess.isInsideInteractionArea(125.99, 73.99, 213.5, 100, 64, 200, true));
        assertFalse(AltarTravelAccess.isInsideInteractionArea(126.0, 68.0, 213.5, 100, 64, 200, true));
        assertFalse(AltarTravelAccess.isInsideInteractionArea(113.5, 75.0, 213.5, 100, 64, 200, true));
    }
}
