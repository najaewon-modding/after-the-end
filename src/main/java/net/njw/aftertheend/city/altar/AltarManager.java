package net.njw.aftertheend.city.altar;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.njw.aftertheend.AfterTheEnd;

public final class AltarManager {
    private static final int[][] HIDDEN_CHEST_OFFSETS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private AltarManager() { }

    private static AltarSavedData getSavedData(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(AltarSavedData.TYPE);
    }

    public static boolean isGenerated(MinecraftServer server, UUID cityId) {
        return getSavedData(server).isGenerated(cityId);
    }

    public static List<AltarPlacement> getPlacements(MinecraftServer server, UUID cityId) {
        return getSavedData(server).getPlacements(cityId);
    }

    public static int getActivatedCount(MinecraftServer server, UUID cityId) {
        return getSavedData(server).getActivatedCount(cityId);
    }

    public static AltarSavedData.ActivationClaim claimActivation(
            MinecraftServer server, UUID cityId, int blockX, int y, int blockZ, int maximumActivated
    ) {
        return getSavedData(server).claimActivation(cityId, blockX, y, blockZ, maximumActivated);
    }

    public static boolean setActivated(MinecraftServer server, UUID cityId, int blockX, int y, int blockZ, boolean activated) {
        return getSavedData(server).setActivated(cityId, blockX, y, blockZ, activated);
    }

    public static void markGenerated(MinecraftServer server, UUID cityId, List<AltarPlacement> placements) {
        placeHiddenRewardChests(server, placements);
        getSavedData(server).markGenerated(cityId, placements);
    }

    public static void removeCity(MinecraftServer server, UUID cityId) {
        getSavedData(server).removeCity(cityId);
    }

    private static void placeHiddenRewardChests(MinecraftServer server, List<AltarPlacement> placements) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) return;
        for (AltarPlacement placement : placements) {
            BlockPos chestPos = findHiddenRewardChestPosition(level, placement);
            if (chestPos == null) {
                AfterTheEnd.LOGGER.warn(
                        "Could not find a covered second-floor reward chest position for Altar at {},{},{}",
                        placement.blockX(), placement.y(), placement.blockZ()
                );
                continue;
            }
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        }
    }

    private static BlockPos findHiddenRewardChestPosition(ServerLevel level, AltarPlacement placement) {
        int centerOffset = placement.large() ? 13 : 5;
        int centerX = placement.blockX() + centerOffset;
        int centerZ = placement.blockZ() + centerOffset;
        int secondFloorY = placement.y() + 1;

        for (int[] offset : HIDDEN_CHEST_OFFSETS) {
            BlockPos candidate = new BlockPos(centerX + offset[0], secondFloorY, centerZ + offset[1]);
            BlockState secondFloor = level.getBlockState(candidate);
            BlockState thirdFloorCover = level.getBlockState(candidate.above());
            if (secondFloor.canOcclude() && thirdFloorCover.canOcclude()) return candidate;
        }
        return null;
    }
}
