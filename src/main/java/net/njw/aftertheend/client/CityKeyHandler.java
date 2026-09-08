package net.njw.aftertheend.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.client.gui.CityListScreen;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(
        modid = AfterTheEnd.MODID,
        value = Dist.CLIENT
)
public final class CityKeyHandler {

    private static final KeyMapping.Category
            CATEGORY =
            new KeyMapping.Category(
                    Identifier.fromNamespaceAndPath(
                            AfterTheEnd.MODID,
                            "city"
                    )
            );

    /*
     * 임시 기본키는 C.
     *
     * Minecraft Controls 메뉴에서
     * 사용자가 자유롭게 변경할 수 있다.
     */
    private static final KeyMapping
            OPEN_CITY_LIST =
            new KeyMapping(
                    "key.njw_after_the_end.open_city_list",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_C,
                    CATEGORY
            );

    private CityKeyHandler() {
    }

    /*
     * =========================================================
     * Key Registration
     * =========================================================
     */

    @SubscribeEvent
    public static void onRegisterKeyMappings(
            RegisterKeyMappingsEvent event
    ) {
        event.registerCategory(
                CATEGORY
        );

        event.register(
                OPEN_CITY_LIST
        );
    }

    /*
     * =========================================================
     * Client Tick
     * =========================================================
     */

    @SubscribeEvent
    public static void onClientTick(
            ClientTickEvent.Post event
    ) {
        while (
                OPEN_CITY_LIST.consumeClick()
        ) {
            Minecraft minecraft =
                    Minecraft.getInstance();

            /*
             * 실제 월드 안에 있고
             * 다른 GUI가 열려 있지 않을 때만 연다.
             */
            if (
                    minecraft.player != null
                            && minecraft.screen == null
            ) {
                minecraft.setScreen(
                        new CityListScreen()
                );
            }
        }
    }
}