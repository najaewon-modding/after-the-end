package net.njw.aftertheend.city.altar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AltarActivationPolicyTest {
    private static final int MAX_ALTARS = 3;

    @Test
    void firstActivationUnlocksWhenCitySlotRemains() {
        assertTrue(AltarActivationPolicy.canActivate(0, MAX_ALTARS, 4, 5));
        assertTrue(AltarActivationPolicy.unlocksCity(0, MAX_ALTARS, 4, 5));
    }

    @Test
    void firstActivationIsBlockedAtCityLimit() {
        assertFalse(AltarActivationPolicy.canActivate(0, MAX_ALTARS, 5, 5));
        assertFalse(AltarActivationPolicy.unlocksCity(0, MAX_ALTARS, 5, 5));
    }

    @Test
    void secondAndThirdAltarsRemainAvailableAtCityLimit() {
        assertTrue(AltarActivationPolicy.canActivate(1, MAX_ALTARS, 5, 5));
        assertFalse(AltarActivationPolicy.unlocksCity(1, MAX_ALTARS, 5, 5));
        assertTrue(AltarActivationPolicy.canActivate(2, MAX_ALTARS, 5, 5));
        assertFalse(AltarActivationPolicy.unlocksCity(2, MAX_ALTARS, 5, 5));
    }

    @Test
    void fourthAltarIsAlwaysBlocked() {
        assertFalse(AltarActivationPolicy.canActivate(3, MAX_ALTARS, 1, 5));
        assertFalse(AltarActivationPolicy.unlocksCity(3, MAX_ALTARS, 1, 5));
    }

    @Test
    void dragonEggMustMeetOrExceedCityRank() {
        assertFalse(AltarActivationPolicy.isDragonEggRankSufficient(2, 3));
        assertTrue(AltarActivationPolicy.isDragonEggRankSufficient(3, 3));
        assertTrue(AltarActivationPolicy.isDragonEggRankSufficient(4, 3));
    }
}
