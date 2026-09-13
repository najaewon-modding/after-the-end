package net.njw.aftertheend.city;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityTravelAccessPolicyTest {
    @Test
    void lowerCitiesDoNotRequireActivatedAltar() {
        assertTrue(CityTravelAccessPolicy.canTravelWithoutActivatedAltar(2, 0));
        assertTrue(CityTravelAccessPolicy.canTravelWithoutActivatedAltar(2, 1));
    }

    @Test
    void sameOrHigherCitiesStillRequireActivatedAltar() {
        assertFalse(CityTravelAccessPolicy.canTravelWithoutActivatedAltar(1, 1));
        assertFalse(CityTravelAccessPolicy.canTravelWithoutActivatedAltar(1, 2));
    }

    @Test
    void unknownCurrentCityDoesNotBypassActivatedAltar() {
        assertFalse(CityTravelAccessPolicy.canTravelWithoutActivatedAltar(-1, 0));
    }
}
