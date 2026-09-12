package net.njw.aftertheend.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityManager;
import net.njw.aftertheend.city.altar.AltarManager;
import net.njw.aftertheend.city.altar.AltarPlacement;

public final class CitySyncService {
    private CitySyncService() { }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) syncToPlayer(player);
    }

    public static void syncToPlayer(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server != null) PacketDistributor.sendToPlayer(player, createPayload(server));
    }

    public static void syncToAll(MinecraftServer server) {
        CitySyncPayload payload = createPayload(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) PacketDistributor.sendToPlayer(player, payload);
    }

    private static CitySyncPayload createPayload(MinecraftServer server) {
        List<CitySyncPayload.CityData> cities = new ArrayList<>();
        City nextLockedCity = CityManager.getNextLockedCity(server);
        for (City city : CityManager.getCities(server)) {
            boolean unlocked = CityManager.isCityAccessible(server, city.id());
            if (!unlocked && (nextLockedCity == null || !city.id().equals(nextLockedCity.id()))) continue;

            List<CitySyncPayload.AltarData> activatedAltars = unlocked
                    ? AltarManager.getPlacements(server, city.id()).stream()
                            .filter(AltarPlacement::activated)
                            .map(placement -> new CitySyncPayload.AltarData(
                                    placement.blockX(), placement.y(), placement.blockZ(), placement.large()))
                            .toList()
                    : List.of();
            cities.add(CitySyncPayload.CityData.fromCity(city, unlocked, activatedAltars));
        }
        return new CitySyncPayload(cities);
    }
}
