package net.njw.aftertheend.city;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.njw.aftertheend.city.basecamp.BasecampManager;
import net.njw.aftertheend.city.basecamp.BasecampPlacementService;
import net.njw.aftertheend.city.generation.CityPregenerationHandler;
import net.njw.aftertheend.city.placement.CityPlacementService;
import net.njw.aftertheend.network.CitySyncService;

public final class CityLifecycleService {
    private CityLifecycleService() {
    }

    public static City createAccessibleCity(MinecraftServer server) {
        ensureCanCreateCity(server);
        UUID cityId = newCityId(server);
        City city = CityPlacementService.placeAccessibleCity(server, cityId, cityId.toString());
        generateBasecampsOrRollback(server, city);
        PlayerPositionTracker.invalidateAllCityCaches();
        CitySyncService.syncToAll(server);
        return city;
    }

    public static City createLockedCity(MinecraftServer server) {
        ensureCanCreateCity(server);
        UUID cityId = newCityId(server);
        City city = CityPlacementService.placeLockedCity(server, cityId, cityId.toString());
        generateBasecampsOrRollback(server, city);
        PlayerPositionTracker.invalidateAllCityCaches();
        CitySyncService.syncToAll(server);
        return city;
    }

    public static City unlockCity(MinecraftServer server, UUID cityId) {
        City city = requireCity(server, cityId);
        BasecampPlacementService.ensureGenerated(server, city);
        if (CityManager.isCityAccessible(server, cityId)) return city;
        CityManager.unlockCity(server, cityId);
        PlayerPositionTracker.invalidateAllCityCaches();
        CitySyncService.syncToAll(server);
        return city;
    }

    public static void deleteCity(MinecraftServer server, UUID cityId) {
        if (CityRegistry.STARTING_CITY_ID.equals(cityId)) throw new IllegalArgumentException("Starting city cannot be deleted.");
        City city = requireCity(server, cityId);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (city.contains(player.level().dimension(), player.getBlockX(), player.getBlockZ())) {
                throw new IllegalStateException("Cannot delete city while player " + player.getName().getString() + " is inside it.");
            }
        }
        CityPregenerationHandler.removeCity(cityId);
        BasecampManager.removeCity(server, cityId);
        CityManager.removeCity(server, cityId);
        PlayerPositionTracker.invalidateAllCityCaches();
        CitySyncService.syncToAll(server);
    }

    public static void setMaxCityCount(MinecraftServer server, int maxCityCount) {
        CityManager.setMaxCityCount(server, maxCityCount);
        CitySyncService.syncToAll(server);
    }

    private static void generateBasecampsOrRollback(MinecraftServer server, City city) {
        try {
            BasecampPlacementService.ensureGenerated(server, city);
        } catch (RuntimeException exception) {
            BasecampManager.removeCity(server, city.id());
            CityManager.removeCity(server, city.id());
            PlayerPositionTracker.invalidateAllCityCaches();
            throw exception;
        }
    }

    private static UUID newCityId(MinecraftServer server) {
        UUID cityId;
        do {
            cityId = UUID.randomUUID();
        } while (CityManager.getCity(server, cityId) != null);
        return cityId;
    }

    private static void ensureCanCreateCity(MinecraftServer server) {
        int current = CityManager.getCities(server).size();
        int maximum = CityManager.getMaxCityCount(server);
        if (current >= maximum) throw new IllegalStateException("Maximum city count reached: " + current + "/" + maximum);
    }

    private static City requireCity(MinecraftServer server, UUID cityId) {
        City city = CityManager.getCity(server, cityId);
        if (city == null) throw new IllegalArgumentException("Unknown city: " + cityId);
        return city;
    }
}
