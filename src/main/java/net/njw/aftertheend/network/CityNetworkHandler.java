package net.njw.aftertheend.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class CityNetworkHandler {
    private static final String NETWORK_VERSION = "4";

    private CityNetworkHandler() { }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);
        registrar.playToClient(CitySyncPayload.TYPE, CitySyncPayload.STREAM_CODEC);
        registrar.playToClient(AltarActivationPayload.TYPE, AltarActivationPayload.STREAM_CODEC);
        registrar.playToServer(CityTeleportRequestPayload.TYPE, CityTeleportRequestPayload.STREAM_CODEC, CityTeleportGate::handleRequest);
        registrar.playToServer(CityRenameRequestPayload.TYPE, CityRenameRequestPayload.STREAM_CODEC, CityRenameRequestPayload::handle);
    }
}
