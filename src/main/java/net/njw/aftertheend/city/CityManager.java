package net.njw.aftertheend.city;

import java.util.Collection;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.njw.aftertheend.city.altar.AltarManager;

public final class CityManager {
    private CityManager() { }

    static CitySavedData getSavedData(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(CitySavedData.TYPE);
    }

    public static City getStartingCity(MinecraftServer server) {
        City city = getSavedData(server).getCity(CityRegistry.STARTING_CITY_ID);
        if (city == null) throw new IllegalStateException("Starting city is missing.");
        return city;
    }

    public static Collection<City> getCities(MinecraftServer server) { return getSavedData(server).getCities(); }
    public static Collection<City> getAccessibleCities(MinecraftServer server) { return getSavedData(server).getAccessibleCities(); }
    public static Collection<City> getLockedCities(MinecraftServer server) { return getSavedData(server).getLockedCities(); }
    public static int getLockedCityCount(MinecraftServer server) { return getSavedData(server).getLockedCityCount(); }

    public static City getNextLockedCity(MinecraftServer server) {
        for (City city : getSavedData(server).getLockedCities()) {
            if (AltarManager.isGenerated(server, city.id())) return city;
        }
        return null;
    }

    public static City getCity(MinecraftServer server, UUID cityId) { return getSavedData(server).getCity(cityId); }
    public static void addCity(MinecraftServer server, City city) { getSavedData(server).addCity(city); }
    public static void addAccessibleCity(MinecraftServer server, City city) { getSavedData(server).addAccessibleCity(city); }
    public static void removeCity(MinecraftServer server, UUID cityId) { getSavedData(server).removeCity(cityId); }
    public static boolean isCityAccessible(MinecraftServer server, UUID cityId) { return getSavedData(server).isCityAccessible(cityId); }
    public static void unlockCity(MinecraftServer server, UUID cityId) { getSavedData(server).unlockCity(cityId); }
    public static int getMaxCityCount(MinecraftServer server) { return getSavedData(server).getMaxCityCount(); }
    public static void setMaxCityCount(MinecraftServer server, int maxCityCount) { getSavedData(server).setMaxCityCount(maxCityCount); }

    public static CitySavedData.CityArrivalPosition getCityArrivalPosition(MinecraftServer server, UUID cityId, ResourceKey<Level> dimension) {
        return getSavedData(server).getCityArrivalPosition(cityId, dimension);
    }
    public static void setCityArrivalPosition(MinecraftServer server, UUID cityId, ResourceKey<Level> dimension, int blockX, int y, int blockZ) {
        getSavedData(server).setCityArrivalPosition(cityId, dimension, blockX, y, blockZ);
    }
    public static void clearCityArrivalPosition(MinecraftServer server, UUID cityId, ResourceKey<Level> dimension) {
        getSavedData(server).clearCityArrivalPosition(cityId, dimension);
    }
    public static void clearCityArrivalPositions(MinecraftServer server, UUID cityId) { getSavedData(server).clearCityArrivalPositions(cityId); }

    public static City findCityContaining(MinecraftServer server, ResourceKey<Level> dimension, int blockX, int blockZ) {
        return getSavedData(server).findCityContaining(dimension, blockX, blockZ, false);
    }
    public static City findAccessibleCityContaining(MinecraftServer server, ResourceKey<Level> dimension, int blockX, int blockZ) {
        return getSavedData(server).findCityContaining(dimension, blockX, blockZ, true);
    }
    public static boolean isInsideAnyCity(MinecraftServer server, ResourceKey<Level> dimension, int blockX, int blockZ) {
        return findCityContaining(server, dimension, blockX, blockZ) != null;
    }
    public static boolean isInsideAccessibleCity(MinecraftServer server, ResourceKey<Level> dimension, int blockX, int blockZ) {
        return findAccessibleCityContaining(server, dimension, blockX, blockZ) != null;
    }
}
