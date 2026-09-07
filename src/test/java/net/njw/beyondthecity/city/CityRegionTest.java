package net.njw.beyondthecity.city;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityRegionTest {
    @Test
    void calculatesEvenSizedBounds() {
        CityRegion region = new CityRegion(0, 0, 256, 256);
        assertEquals(-128, region.minChunkX());
        assertEquals(127, region.maxChunkX());
        assertEquals(-128, region.minChunkZ());
        assertEquals(127, region.maxChunkZ());
    }

    @Test
    void handlesNegativeBlockCoordinates() {
        CityRegion region = new CityRegion(0, 0, 2, 2);
        assertTrue(region.containsBlock(-1, -1));
        assertTrue(region.containsBlock(15, 15));
        assertFalse(region.containsBlock(16, 0));
        assertFalse(region.containsBlock(0, 16));
    }

    @Test
    void calculatesOddSizedBounds() {
        CityRegion region = new CityRegion(10, -10, 3, 5);
        assertEquals(9, region.minChunkX());
        assertEquals(11, region.maxChunkX());
        assertEquals(-12, region.minChunkZ());
        assertEquals(-8, region.maxChunkZ());
    }
}
