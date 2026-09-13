package net.njw.aftertheend.city.altar;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.CityLifecycleService;

public final class AltarGenerationHandler {
    private AltarGenerationHandler() { }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        int createdCities = CityLifecycleService.ensureLockedCityReserve(server);
        HiddenCityPreparationService.refreshQueue(server);

        if (createdCities > 0) {
            AfterTheEnd.LOGGER.info("Prepared {} locked city reserve entries during server startup.", createdCities);
        }
        AfterTheEnd.LOGGER.info("Queued missing city Altars for incremental background preparation.");
    }
}
