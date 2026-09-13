package net.njw.aftertheend.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.config.ClientConfig;
import net.njw.aftertheend.network.GuideBookRequestPayload;

@EventBusSubscriber(modid = AfterTheEnd.MODID, value = Dist.CLIENT)
public final class GuideBookClientHandler {
    private GuideBookClientHandler() { }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (ClientConfig.GIVE_GUIDE_BOOK_ON_JOIN.get()) {
            ClientPacketDistributor.sendToServer(new GuideBookRequestPayload());
        }
    }
}
