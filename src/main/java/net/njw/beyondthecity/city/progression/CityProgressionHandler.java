package net.njw.beyondthecity.city.progression;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.njw.beyondthecity.BeyondtheCity;
import net.njw.beyondthecity.city.City;
import net.njw.beyondthecity.city.CityLifecycleService;
import net.njw.beyondthecity.city.CityManager;

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
            BeyondtheCity.LOGGER.info("Ender Dragon defeated, but maximum city count has already been reached: {}/{}", current, maximum);
            return;
        }
        try {
            City city = CityLifecycleService.createAccessibleCity(server);
            BeyondtheCity.LOGGER.info("Unlocked new city after Ender Dragon defeat: city={}, name={}, cityCount={}/{}", city.id(), city.name(), current + 1, maximum);
        } catch (RuntimeException exception) {
            BeyondtheCity.LOGGER.error("Failed to create a city after Ender Dragon defeat.", exception);
        }
    }
}
