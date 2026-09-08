package net.njw.aftertheend.city;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkRectangleTest {
    @Test
    void calculatesEvenSizedBounds() {
        ChunkRectangle rectangle = new ChunkRectangle(0, 0, 256, 256);
        assertEquals(-128, rectangle.minX());
        assertEquals(127, rectangle.maxX());
        assertEquals(-128, rectangle.minZ());
        assertEquals(127, rectangle.maxZ());
    }

    @Test
    void calculatesOddSizedBounds() {
        ChunkRectangle rectangle = new ChunkRectangle(10, -10, 3, 5);
        assertEquals(9, rectangle.minX());
        assertEquals(11, rectangle.maxX());
        assertEquals(-12, rectangle.minZ());
        assertEquals(-8, rectangle.maxZ());
    }

    @Test
    void checksContainmentIncludingNegativeCoordinates() {
        ChunkRectangle rectangle = new ChunkRectangle(0, 0, 2, 2);
        assertTrue(rectangle.contains(-1, -1));
        assertTrue(rectangle.contains(0, 0));
        assertFalse(rectangle.contains(1, 0));
        assertFalse(rectangle.contains(0, 1));
    }

    @Test
    void rejectsInvalidSize() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkRectangle(0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ChunkRectangle(0, 0, 1, 0));
    }
}
