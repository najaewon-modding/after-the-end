package net.njw.aftertheend.city;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CityLifecycleServiceTest {
    @Test
    void capsLockedReserveAtThree() {
        assertEquals(3, CityLifecycleService.calculateLockedCityReserveTarget(10, 5));
    }

    @Test
    void shrinksLockedReserveNearCityLimit() {
        assertEquals(2, CityLifecycleService.calculateLockedCityReserveTarget(10, 8));
        assertEquals(1, CityLifecycleService.calculateLockedCityReserveTarget(10, 9));
    }

    @Test
    void stopsPreparingReserveAtOrBeyondCityLimit() {
        assertEquals(0, CityLifecycleService.calculateLockedCityReserveTarget(10, 10));
        assertEquals(0, CityLifecycleService.calculateLockedCityReserveTarget(10, 11));
    }
}
