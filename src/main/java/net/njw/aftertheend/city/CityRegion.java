package net.njw.aftertheend.city;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.ChunkPos;

public record CityRegion(int centerChunkX, int centerChunkZ, int widthChunks, int heightChunks) {
    public static final Codec<CityRegion> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("centerChunkX").forGetter(CityRegion::centerChunkX),
            Codec.INT.fieldOf("centerChunkZ").forGetter(CityRegion::centerChunkZ),
            Codec.INT.fieldOf("widthChunks").forGetter(CityRegion::widthChunks),
            Codec.INT.fieldOf("heightChunks").forGetter(CityRegion::heightChunks)
    ).apply(instance, CityRegion::new));

    public CityRegion {
        new ChunkRectangle(centerChunkX, centerChunkZ, widthChunks, heightChunks);
    }

    private ChunkRectangle bounds() { return new ChunkRectangle(centerChunkX, centerChunkZ, widthChunks, heightChunks); }
    public int minChunkX() { return bounds().minX(); }
    public int maxChunkX() { return bounds().maxX(); }
    public int minChunkZ() { return bounds().minZ(); }
    public int maxChunkZ() { return bounds().maxZ(); }
    public boolean containsChunk(int chunkX, int chunkZ) { return bounds().contains(chunkX, chunkZ); }
    public boolean containsChunk(ChunkPos chunkPos) { return containsChunk(chunkPos.x(), chunkPos.z()); }
    public boolean containsBlock(int blockX, int blockZ) { return containsChunk(blockX >> 4, blockZ >> 4); }
    public int minBlockX() { return minChunkX() * 16; }
    public int maxBlockX() { return (maxChunkX() + 1) * 16 - 1; }
    public int minBlockZ() { return minChunkZ() * 16; }
    public int maxBlockZ() { return (maxChunkZ() + 1) * 16 - 1; }
}
