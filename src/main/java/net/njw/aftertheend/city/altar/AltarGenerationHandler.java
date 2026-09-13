package net.njw.aftertheend.city.altar;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityLifecycleService;
import net.njw.aftertheend.city.CityManager;

public final class AltarGenerationHandler {
    private static final int SYNCHRONOUS_LOCKED_CITY_COUNT = 1;

    private AltarGenerationHandler() { }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        int createdCities = CityLifecycleService.ensureLockedCityReserve(server);
        int generatedAccessibleAltars = 0;
        int generatedLockedAltars = 0;

        for (City city : CityManager.getAccessibleCities(server)) {
            if (AltarPlacementService.ensureGeneratedIfMissing(server, city)) generatedAccessibleAltars++;
        }
        for (City city : CityManager.getLockedCities(server).stream().limit(SYNCHRONOUS_LOCKED_CITY_COUNT).toList()) {
            if (AltarPlacementService.ensureGeneratedIfMissing(server, city)) generatedLockedAltars++;
        }

        HiddenCityPreparationService.refreshQueue(server);
        if (createdCities > 0) {
            AfterTheEnd.LOGGER.info("Prepared {} locked city reserve entries during server startup.", createdCities);
        }
        if (generatedAccessibleAltars > 0) {
            AfterTheEnd.LOGGER.info("Generated missing Altars for {} accessible city/cities during server startup.", generatedAccessibleAltars);
        }
        if (generatedLockedAltars > 0) {
            AfterTheEnd.LOGGER.info("Prepared {} locked city/cities synchronously during server startup.", generatedLockedAltars);
        }
    }
}
