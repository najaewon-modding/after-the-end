package net.njw.aftertheend.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.njw.aftertheend.city.CityTeleportService;

public final class CityNetworkHandler {

    /*
     * 네트워크 protocol version.
     */
    private static final String NETWORK_VERSION =
            "1";

    private CityNetworkHandler() {
    }

    public static void registerPayloads(
            RegisterPayloadHandlersEvent event
    ) {
        PayloadRegistrar registrar =
                event.registrar(
                        NETWORK_VERSION
                );

        /*
         * =====================================================
         * Server -> Client
         * =====================================================
         *
         * 도시 목록 동기화.
         *
         * client handler는
         * CityClientNetworkHandler에서 등록한다.
         */

        registrar.playToClient(
                CitySyncPayload.TYPE,
                CitySyncPayload.STREAM_CODEC
        );

        /*
         * =====================================================
         * Client -> Server
         * =====================================================
         *
         * 도시 이동 요청.
         */

        registrar.playToServer(
                CityTeleportRequestPayload.TYPE,
                CityTeleportRequestPayload.STREAM_CODEC,
                CityTeleportService::handleRequest
        );
    }
}