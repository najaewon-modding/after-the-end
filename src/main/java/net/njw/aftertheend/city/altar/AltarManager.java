package net.njw.aftertheend.city.altar;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;

import java.util.List;

public final class AltarManager {
    private AltarManager() {
    }

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
        getSavedData(server).markGenerated(cityId, placements);
    }

    public static void removeCity(MinecraftServer server, UUID cityId) {
        getSavedData(server).removeCity(cityId);
    }
}
