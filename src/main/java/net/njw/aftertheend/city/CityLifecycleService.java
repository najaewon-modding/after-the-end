package net.njw.aftertheend.city;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.altar.AltarManager;
import net.njw.aftertheend.city.altar.AltarPlacementService;
import net.njw.aftertheend.city.altar.HiddenCityPreparationService;
import net.njw.aftertheend.city.generation.CityPregenerationHandler;
import net.njw.aftertheend.city.placement.CityPlacementService;
import net.njw.aftertheend.network.CitySyncService;

public final class CityLifecycleService {
    public static final int LOCKED_CITY_RESERVE_COUNT = 3;

    private CityLifecycleService() { }

    public static City createAccessibleCity(MinecraftServer server) {
        ensureCanAddUnlockedCity(server);
        UUID cityId = newCityId(server);
        City city = CityPlacementService.placeAccessibleCity(server, cityId, cityId.toString());
        generateAltarsOrRollback(server, city);
        finishCityStateChange(server);
        return city;
    }

    public static City createLockedCity(MinecraftServer server) {
        if (CityManager.getLockedCityCount(server) >= LOCKED_CITY_RESERVE_COUNT) {
            throw new IllegalStateException("Locked city reserve is already full: " + LOCKED_CITY_RESERVE_COUNT + "/" + LOCKED_CITY_RESERVE_COUNT);
        }
        City city = createLockedCityInternal(server);
        finishCityStateChange(server);
        HiddenCityPreparationService.refreshQueue(server);
        return city;
    }

    public static int ensureLockedCityReserve(MinecraftServer server) {
        int created = 0;
        while (CityManager.getLockedCityCount(server) < LOCKED_CITY_RESERVE_COUNT) {
            int slot = CityManager.getLockedCityCount(server) + 1;
            AfterTheEnd.LOGGER.info("Preparing locked city reserve metadata [{}/{}].", slot, LOCKED_CITY_RESERVE_COUNT);
            City city = createLockedCityInternal(server);
            created++;
            AfterTheEnd.LOGGER.info("Prepared locked city reserve metadata [{}/{}]: {}", slot, LOCKED_CITY_RESERVE_COUNT, city.id());
        }
        if (created > 0) finishCityStateChange(server);
        HiddenCityPreparationService.refreshQueue(server);
        return created;
    }

    public static City getNextReadyLockedCity(MinecraftServer server) {
        for (City city : CityManager.getLockedCities(server)) {
            if (AltarManager.isGenerated(server, city.id())) return city;
        }
        return null;
    }

    public static City unlockCity(MinecraftServer server, UUID cityId) {
        City city = requireCity(server, cityId);
        if (CityManager.isCityAccessible(server, cityId)) return city;
        if (!AltarManager.isGenerated(server, cityId)) {
            throw new IllegalStateException("Locked city is still PREPARING and cannot be unlocked yet: " + cityId);
        }
        ensureCanAddUnlockedCity(server);

        CityManager.unlockCity(server, cityId);
        try {
            ensureLockedCityReserve(server);
        } catch (RuntimeException exception) {
            AfterTheEnd.LOGGER.error("City {} was unlocked, but the hidden-city reserve could not be replenished immediately.", cityId, exception);
        }
        finishCityStateChange(server);
        HiddenCityPreparationService.refreshQueue(server);
        return city;
    }

    public static void deleteCity(MinecraftServer server, UUID cityId) {
        if (CityRegistry.STARTING_CITY_ID.equals(cityId)) throw new IllegalArgumentException("Starting city cannot be deleted.");
        City city = requireCity(server, cityId);
        boolean wasLocked = !CityManager.isCityAccessible(server, cityId);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (city.contains(player.level().dimension(), player.getBlockX(), player.getBlockZ())) {
                throw new IllegalStateException("Cannot delete city while player " + player.getName().getString() + " is inside it.");
            }
        }
        HiddenCityPreparationService.removeCity(cityId);
        CityPregenerationHandler.removeCity(cityId);
        AltarManager.removeCity(server, cityId);
        CityManager.removeCity(server, cityId);
        if (wasLocked) ensureLockedCityReserve(server);
        finishCityStateChange(server);
        HiddenCityPreparationService.refreshQueue(server);
    }

    public static void setMaxCityCount(MinecraftServer server, int maxCityCount) {
        CityManager.setMaxCityCount(server, maxCityCount);
        CitySyncService.syncToAll(server);
    }

    private static City createLockedCityInternal(MinecraftServer server) {
        UUID cityId = newCityId(server);
        return CityPlacementService.placeLockedCity(server, cityId, cityId.toString());
    }

    private static void generateAltarsOrRollback(MinecraftServer server, City city) {
        try {
            AltarPlacementService.ensureGenerated(server, city);
        } catch (RuntimeException exception) {
            AltarManager.removeCity(server, city.id());
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

    private static void ensureCanAddUnlockedCity(MinecraftServer server) {
        int current = CityManager.getAccessibleCities(server).size();
        int maximum = CityManager.getMaxCityCount(server);
        if (current >= maximum) throw new IllegalStateException("Maximum unlocked city count reached: " + current + "/" + maximum);
    }

    private static City requireCity(MinecraftServer server, UUID cityId) {
        City city = CityManager.getCity(server, cityId);
        if (city == null) throw new IllegalArgumentException("Unknown city: " + cityId);
        return city;
    }

    private static void finishCityStateChange(MinecraftServer server) {
        PlayerPositionTracker.invalidateAllCityCaches();
        CitySyncService.syncToAll(server);
    }
}
