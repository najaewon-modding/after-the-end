package net.njw.aftertheend.city.altar;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityLifecycleService;
import net.njw.aftertheend.city.CityManager;

public final class AltarGenerationHandler {
    private AltarGenerationHandler() { }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        int createdCities = CityLifecycleService.ensureLockedCityReserve(server);
        int generatedAltars = 0;
        for (City city : CityManager.getCities(server)) {
            if (AltarPlacementService.ensureGeneratedIfMissing(server, city)) generatedAltars++;
        }
        if (createdCities > 0) {
            AfterTheEnd.LOGGER.info("Prepared {} locked city/cities during server startup.", createdCities);
        }
        if (generatedAltars > 0) {
            AfterTheEnd.LOGGER.info("Generated missing Altars for {} city/cities during server startup.", generatedAltars);
        }
    }
}
