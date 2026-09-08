package net.njw.aftertheend.city.basecamp;

import net.minecraft.server.MinecraftServer;

import java.util.List;

public final class BasecampManager {
    private BasecampManager() {
    }

    private static BasecampSavedData getSavedData(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(BasecampSavedData.TYPE);
    }

    public static boolean isGenerated(MinecraftServer server, String cityId) {
        return getSavedData(server).isGenerated(cityId);
    }

    public static List<BasecampPlacement> getPlacements(MinecraftServer server, String cityId) {
        return getSavedData(server).getPlacements(cityId);
    }

    public static void markGenerated(MinecraftServer server, String cityId, List<BasecampPlacement> placements) {
        getSavedData(server).markGenerated(cityId, placements);
    }

    public static void removeCity(MinecraftServer server, String cityId) {
        getSavedData(server).removeCity(cityId);
    }
}
