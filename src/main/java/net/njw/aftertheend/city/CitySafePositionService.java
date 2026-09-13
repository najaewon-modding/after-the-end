package net.njw.aftertheend.city;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

public final class CitySafePositionService {
    private static final int SEARCH_RADIUS_BLOCKS = 64;
    private static final int SEARCH_STEP_BLOCKS = 4;

    private CitySafePositionService() { }

    public static SafePosition findStartingCityFallback(MinecraftServer server, ServerLevel level) {
        City startingCity = CityManager.getStartingCity(server);
        CityRegion region = startingCity.getRegion(level.dimension()).orElse(null);
        if (region == null) return null;

        int centerX = region.centerChunkX() * 16;
        int centerZ = region.centerChunkZ() * 16;
        SafePosition center = findAt(level, region, centerX, centerZ);
        if (center != null) return center;

        for (int radius = SEARCH_STEP_BLOCKS; radius <= SEARCH_RADIUS_BLOCKS; radius += SEARCH_STEP_BLOCKS) {
            for (int offset = -radius; offset <= radius; offset += SEARCH_STEP_BLOCKS) {
                SafePosition result = findAt(level, region, centerX + offset, centerZ - radius);
                if (result != null) return result;
                result = findAt(level, region, centerX + offset, centerZ + radius);
                if (result != null) return result;
                result = findAt(level, region, centerX - radius, centerZ + offset);
                if (result != null) return result;
                result = findAt(level, region, centerX + radius, centerZ + offset);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static SafePosition findAt(ServerLevel level, CityRegion region, int x, int z) {
        if (!region.containsBlock(x, z)) return null;
        level.getChunk(x >> 4, z >> 4);
        if (Level.NETHER.equals(level.dimension())) {
            for (int y = level.getMaxY() - 2; y > level.getMinY(); y--) {
                if (isSafeStandingPosition(level, x, y, z)) return new SafePosition(x + 0.5D, y, z + 0.5D);
            }
            return null;
        }
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (isSafeStandingPosition(level, x, surfaceY, z)) return new SafePosition(x + 0.5D, surfaceY, z + 0.5D);
        for (int offset = 1; offset <= 3; offset++) {
            int y = surfaceY - offset;
            if (isSafeStandingPosition(level, x, y, z)) return new SafePosition(x + 0.5D, y, z + 0.5D);
        }
        return null;
    }

    public static boolean isSafeStandingPosition(ServerLevel level, int x, int y, int z) {
        if (y <= level.getMinY() || y + 1 >= level.getMaxY()) return false;
        BlockPos floor = new BlockPos(x, y - 1, z);
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = new BlockPos(x, y + 1, z);
        BlockState floorState = level.getBlockState(floor);
        if (!floorState.getFluidState().isEmpty()) return false;
        if (floorState.is(Blocks.BEDROCK) || floorState.is(Blocks.MAGMA_BLOCK) || floorState.is(Blocks.CAMPFIRE)
                || floorState.is(Blocks.SOUL_CAMPFIRE) || floorState.is(Blocks.CACTUS)) return false;
        if (!floorState.isFaceSturdy(level, floor, Direction.UP)) return false;
        return isSafeSpace(level, feet) && isSafeSpace(level, head);
    }

    private static boolean isSafeSpace(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty() || !state.getCollisionShape(level, pos).isEmpty()) return false;
        return !state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE) && !state.is(Blocks.POWDER_SNOW)
                && !state.is(Blocks.SWEET_BERRY_BUSH) && !state.is(Blocks.WITHER_ROSE)
                && !state.is(Blocks.NETHER_PORTAL) && !state.is(Blocks.END_PORTAL) && !state.is(Blocks.END_GATEWAY);
    }

    public record SafePosition(double x, double y, double z) { }
}
