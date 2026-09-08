package net.njw.aftertheend.city.generation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.njw.aftertheend.city.CityRegion;

public final class CityPregenerator {

    private final ServerLevel level;
    private final SpiralChunkIterator iterator;

    private long generatedChunks;
    private final long totalChunks;

    public CityPregenerator(
            ServerLevel level,
            CityRegion region,
            int marginChunks,
            long alreadyGeneratedChunks
    ) {
        this.level = level;

        this.iterator =
                new SpiralChunkIterator(
                        region,
                        marginChunks
                );

        this.totalChunks =
                iterator.getTotalChunks();

        this.generatedChunks =
                Math.min(
                        alreadyGeneratedChunks,
                        totalChunks
                );

        iterator.skip(
                this.generatedChunks
        );
    }

    public boolean hasNext() {
        return iterator.hasNext();
    }

    public void generateNextChunk() {
        if (!iterator.hasNext()) {
            return;
        }

        ChunkPos chunkPos =
                iterator.next();

        level.getChunk(
                chunkPos.x(),
                chunkPos.z(),
                ChunkStatus.FULL,
                true
        );

        generatedChunks++;
    }

    public boolean isFinished() {
        return !iterator.hasNext();
    }

    public long getGeneratedChunks() {
        return generatedChunks;
    }

    public long getTotalChunks() {
        return totalChunks;
    }

    public double getProgress() {
        if (totalChunks == 0) {
            return 1.0;
        }

        return (double) generatedChunks
                / totalChunks;
    }
}