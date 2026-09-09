package net.njw.aftertheend.city.basecamp;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;

import java.util.List;

public final class BasecampManager {
    private BasecampManager() {
    }

    private static BasecampSavedData getSavedData(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(BasecampSavedData.TYPE);
    }

    public static boolean isGenerated(MinecraftServer server, UUID cityId) {
        return getSavedData(server).isGenerated(cityId);
    }

    public static List<BasecampPlacement> getPlacements(MinecraftServer server, UUID cityId) {
        return getSavedData(server).getPlacements(cityId);
    }

    public static void markGenerated(MinecraftServer server, UUID cityId, List<BasecampPlacement> placements) {
        getSavedData(server).markGenerated(cityId, placements);
    }

    public static void removeCity(MinecraftServer server, UUID cityId) {
        getSavedData(server).removeCity(cityId);
    }
}
