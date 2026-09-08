package net.njw.aftertheend.city.progression;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityLifecycleService;
import net.njw.aftertheend.city.CityManager;

public final class CityProgressionHandler {
    private CityProgressionHandler() {
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || !(dragon.level() instanceof ServerLevel serverLevel)) return;
        MinecraftServer server = serverLevel.getServer();
        int current = CityManager.getCities(server).size();
        int maximum = CityManager.getMaxCityCount(server);
        if (current >= maximum) {
            AfterTheEnd.LOGGER.info("Ender Dragon defeated, but maximum city count has already been reached: {}/{}", current, maximum);
            return;
        }
        try {
            City city = CityLifecycleService.createAccessibleCity(server);
            AfterTheEnd.LOGGER.info("Unlocked new city after Ender Dragon defeat: city={}, name={}, cityCount={}/{}", city.id(), city.name(), current + 1, maximum);
        } catch (RuntimeException exception) {
            AfterTheEnd.LOGGER.error("Failed to create a city after Ender Dragon defeat.", exception);
        }
    }
}
