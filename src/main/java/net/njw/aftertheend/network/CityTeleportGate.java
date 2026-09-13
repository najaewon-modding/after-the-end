package net.njw.aftertheend.network;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityManager;
import net.njw.aftertheend.city.CityTeleportService;
import net.njw.aftertheend.city.CityTravelAccessPolicy;
import net.njw.aftertheend.city.altar.AltarManager;
import net.njw.aftertheend.city.altar.AltarPlacement;
import net.njw.aftertheend.city.altar.AltarTravelAccess;

public final class CityTeleportGate {
    private CityTeleportGate() { }

    public static void handleRequest(CityTeleportRequestPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (!player.level().dimension().equals(Level.OVERWORLD)) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.city_move.overworld_only"));
            return;
        }
        MinecraftServer server = player.level().getServer();
        City currentCity = CityManager.findAccessibleCityContaining(
                server, Level.OVERWORLD, player.getBlockX(), player.getBlockZ()
        );
        if (currentCity == null) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.city_move.not_in_city"));
            return;
        }

        City city = CityManager.getCity(server, payload.cityId());
        if (city != null && CityManager.isCityAccessible(server, payload.cityId())
                && !canTravelWithoutActivatedAltar(server, currentCity, payload.cityId())
                && !isInsideActivatedAltar(player)) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.city_move.requires_activated_altar"));
            return;
        }
        CityTeleportService.handleRequest(payload, context);
    }

    private static boolean canTravelWithoutActivatedAltar(MinecraftServer server, City currentCity, java.util.UUID targetCityId) {
        int currentIndex = -1;
        int targetIndex = -1;
        int index = 0;
        for (City city : CityManager.getCities(server)) {
            if (city.id().equals(currentCity.id())) currentIndex = index;
            if (city.id().equals(targetCityId)) targetIndex = index;
            index++;
        }
        return CityTravelAccessPolicy.canTravelWithoutActivatedAltar(currentIndex, targetIndex);
    }

    private static boolean isInsideActivatedAltar(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        for (City city : CityManager.getAccessibleCities(server)) {
            for (AltarPlacement altar : AltarManager.getPlacements(server, city.id())) {
                if (!altar.activated()) continue;
                if (AltarTravelAccess.isInsideInteractionArea(
                        player.getX(), player.getY(), player.getZ(),
                        altar.blockX(), altar.y(), altar.blockZ(), altar.large())) return true;
            }
        }
        return false;
    }
}
