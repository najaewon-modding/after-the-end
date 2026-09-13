package net.njw.aftertheend.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.client.gui.CityListScreen;
import net.njw.aftertheend.network.CityArrivalAltarRequestPayload;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = AfterTheEnd.MODID, value = Dist.CLIENT)
public final class CityKeyHandler {
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(AfterTheEnd.MODID, "city")
    );
    private static final KeyMapping OPEN_CITY_LIST = new KeyMapping(
            "key.njw_after_the_end.open_city_list", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY
    );
    private static final KeyMapping SET_ARRIVAL_ALTAR = new KeyMapping(
            "key.njw_after_the_end.set_arrival_altar", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY
    );

    private CityKeyHandler() { }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(OPEN_CITY_LIST);
        event.register(SET_ARRIVAL_ALTAR);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (OPEN_CITY_LIST.consumeClick()) {
            if (minecraft.player != null && minecraft.screen == null) minecraft.setScreen(new CityListScreen());
        }
        while (SET_ARRIVAL_ALTAR.consumeClick()) {
            if (minecraft.player != null && minecraft.screen == null) {
                ClientPacketDistributor.sendToServer(new CityArrivalAltarRequestPayload());
            }
        }
    }
}
