package net.njw.aftertheend.city.generation;

import net.minecraft.world.level.ChunkPos;
import net.njw.aftertheend.city.CityRegion;

import java.util.Iterator;
import java.util.NoSuchElementException;

public final class SpiralChunkIterator
        implements Iterator<ChunkPos> {

    private final int minChunkX;
    private final int maxChunkX;
    private final int minChunkZ;
    private final int maxChunkZ;

    private int x;
    private int z;

    private int dx = 1;
    private int dz = 0;

    private int segmentLength = 1;
    private int segmentProgress = 0;
    private int segmentsCompleted = 0;

    private long returnedChunks = 0;
    private final long totalChunks;

    public SpiralChunkIterator(
            CityRegion region,
            int marginChunks
    ) {
        if (marginChunks < 0) {
            throw new IllegalArgumentException(
                    "marginChunks must be greater than or equal to 0."
            );
        }

        this.minChunkX =
                region.minChunkX() - marginChunks;

        this.maxChunkX =
                region.maxChunkX() + marginChunks;

        this.minChunkZ =
                region.minChunkZ() - marginChunks;

        this.maxChunkZ =
                region.maxChunkZ() + marginChunks;

        this.x = region.centerChunkX();
        this.z = region.centerChunkZ();

        long width =
                (long) maxChunkX
                        - minChunkX
                        + 1;

        long height =
                (long) maxChunkZ
                        - minChunkZ
                        + 1;

        this.totalChunks =
                width * height;
    }

    @Override
    public boolean hasNext() {
        return returnedChunks < totalChunks;
    }

    @Override
    public ChunkPos next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        while (true) {
            int currentX = x;
            int currentZ = z;

            advance();

            if (!containsChunk(
                    currentX,
                    currentZ
            )) {
                continue;
            }

            returnedChunks++;

            return new ChunkPos(
                    currentX,
                    currentZ
            );
        }
    }

    public void skip(long count) {
        for (
                long i = 0;
                i < count && hasNext();
                i++
        ) {
            next();
        }
    }

    private boolean containsChunk(
            int chunkX,
            int chunkZ
    ) {
        return chunkX >= minChunkX
                && chunkX <= maxChunkX
                && chunkZ >= minChunkZ
                && chunkZ <= maxChunkZ;
    }

    private void advance() {
        x += dx;
        z += dz;

        segmentProgress++;

        if (segmentProgress < segmentLength) {
            return;
        }

        segmentProgress = 0;

        rotateClockwise();

        segmentsCompleted++;

        if (segmentsCompleted % 2 == 0) {
            segmentLength++;
        }
    }

    private void rotateClockwise() {
        int oldDx = dx;

        dx = -dz;
        dz = oldDx;
    }

    public long getTotalChunks() {
        return totalChunks;
    }
}