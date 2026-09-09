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

    public static void markGenerated(MinecraftServer server, UUID cityId, List<AltarPlacement> placements) {
        getSavedData(server).markGenerated(cityId, placements);
    }

    public static void removeCity(MinecraftServer server, UUID cityId) {
        getSavedData(server).removeCity(cityId);
    }
}
