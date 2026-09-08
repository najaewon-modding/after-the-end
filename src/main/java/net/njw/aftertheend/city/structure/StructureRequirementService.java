package net.njw.aftertheend.city.structure;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.njw.aftertheend.city.CityRegion;

import java.util.List;

public final class StructureRequirementService {
    public static final int OVERWORLD_LIMIT = StartingAreaRules.OVERWORLD_STRUCTURE_LIMIT_BLOCKS;
    public static final int NETHER_LIMIT = StartingAreaRules.NETHER_STRUCTURE_LIMIT_BLOCKS;

    private StructureRequirementService() {
    }

    public static boolean hasStronghold(ServerLevel level) { return findStructureWithin(level, BuiltinStructures.STRONGHOLD, OVERWORLD_LIMIT) != null; }
    public static boolean hasFortress(ServerLevel level) { return findStructureWithin(level, BuiltinStructures.FORTRESS, NETHER_LIMIT) != null; }

    public static BlockPos findStructureWithin(ServerLevel level, net.minecraft.resources.ResourceKey<Structure> structureKey, int limit) {
        Holder<Structure> structure = level.registryAccess().getOrThrow(structureKey);
        int chunkRadius = (int) Math.ceil(limit / 16.0);
        Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(structure), BlockPos.ZERO, chunkRadius, false);
        if (found == null) return null;
        BlockPos pos = found.getFirst();
        return isInsideSquare(pos.getX(), pos.getZ(), limit) ? pos : null;
    }

    public static BlockPos findNearestStrongholdInsideRegion(ServerLevel level, BlockPos origin, CityRegion region) {
        Holder<Structure> stronghold = level.registryAccess().getOrThrow(BuiltinStructures.STRONGHOLD);
        ChunkGeneratorStructureState structureState = level.getChunkSource().getGeneratorState();
        BlockPos nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (StructurePlacement placement : structureState.getPlacementsForStructure(stronghold)) {
            if (!(placement instanceof ConcentricRingsStructurePlacement rings)) continue;
            List<ChunkPos> positions = structureState.getRingPositionsFor(rings);
            if (positions == null) continue;
            for (ChunkPos chunkPos : positions) {
                BlockPos candidate = placement.getLocatePos(chunkPos);
                if (!region.containsBlock(candidate.getX(), candidate.getZ())) continue;
                double dx = candidate.getX() - origin.getX();
                double dz = candidate.getZ() - origin.getZ();
                double distanceSquared = dx * dx + dz * dz;
                if (distanceSquared < nearestDistanceSquared) {
                    nearestDistanceSquared = distanceSquared;
                    nearest = candidate;
                }
            }
        }
        return nearest;
    }

    private static boolean isInsideSquare(int x, int z, int limit) { return x >= -limit && x <= limit && z >= -limit && z <= limit; }
}
