package net.njw.aftertheend.city.altar;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public final class AltarManager {
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
            int centerOffset = placement.large() ? 13 : 5;
            BlockPos chestPos = new BlockPos(
                    placement.blockX() + centerOffset,
                    placement.y() + 1,
                    placement.blockZ() + centerOffset
            );
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        }
    }
}
