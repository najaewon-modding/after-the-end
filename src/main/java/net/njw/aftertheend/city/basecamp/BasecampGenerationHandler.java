package net.njw.aftertheend.city.basecamp;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityManager;

public final class BasecampGenerationHandler {
    private BasecampGenerationHandler() { }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        int generated = 0;
        for (City city : CityManager.getCities(server)) {
            if (BasecampManager.isGenerated(server, city.id())) continue;
            BasecampPlacementService.ensureGenerated(server, city);
            generated++;
        }
        if (generated > 0) AfterTheEnd.LOGGER.info("Generated missing Basecamps for {} city/cities during server startup.", generated);
    }
}
