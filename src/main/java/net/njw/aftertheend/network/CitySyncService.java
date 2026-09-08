package net.njw.aftertheend.network;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 서버의 accessible city 상태를
 * 클라이언트들에게 동기화한다.
 */
public final class CitySyncService {

    private CitySyncService() {
    }

    /*
     * =========================================================
     * Login
     * =========================================================
     */

    @SubscribeEvent
    public static void onPlayerLogin(
            PlayerEvent.PlayerLoggedInEvent event
    ) {
        if (
                !(event.getEntity()
                        instanceof ServerPlayer player)
        ) {
            return;
        }

        syncToPlayer(
                player
        );
    }

    /*
     * =========================================================
     * Single Player Sync
     * =========================================================
     */

    public static void syncToPlayer(
            ServerPlayer player
    ) {
        MinecraftServer server =
                player.level()
                        .getServer();

        if (server == null) {
            return;
        }

        PacketDistributor.sendToPlayer(
                player,
                createPayload(
                        server
                )
        );
    }

    /*
     * =========================================================
     * All Players Sync
     * =========================================================
     */

    public static void syncToAll(
            MinecraftServer server
    ) {
        CitySyncPayload payload =
                createPayload(
                        server
                );

        for (
                ServerPlayer player :
                server.getPlayerList()
                        .getPlayers()
        ) {
            PacketDistributor.sendToPlayer(
                    player,
                    payload
            );
        }
    }

    /*
     * =========================================================
     * Snapshot Creation
     * =========================================================
     */

    private static CitySyncPayload createPayload(
            MinecraftServer server
    ) {
        List<CitySyncPayload.CityData> cities =
                new ArrayList<>();

        for (
                City city :
                CityManager.getCities(
                        server
                )
        ) {
            boolean unlocked =
                    CityManager.isCityAccessible(
                            server,
                            city.id()
                    );

            cities.add(
                    CitySyncPayload.CityData.fromCity(
                            city,
                            unlocked
                    )
            );
        }

        return new CitySyncPayload(
                cities
        );
    }
}