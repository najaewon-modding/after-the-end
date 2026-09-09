package net.njw.aftertheend.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.network.AltarActivationPayload;
import net.njw.aftertheend.network.CitySyncPayload;

@EventBusSubscriber(
        modid = AfterTheEnd.MODID,
        value = Dist.CLIENT
)
public final class CityClientNetworkHandler {
    private CityClientNetworkHandler() { }

    @SubscribeEvent
    public static void onRegisterClientPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(
                CitySyncPayload.TYPE,
                (payload, context) -> ClientCityManager.replaceCities(payload.cities())
        );
        event.register(
                AltarActivationPayload.TYPE,
                (payload, context) -> AltarActivationClientEffects.handle(payload)
        );
    }
}
