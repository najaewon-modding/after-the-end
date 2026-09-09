package net.njw.aftertheend.client;

import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.njw.aftertheend.AfterTheEnd;

@EventBusSubscriber(modid = AfterTheEnd.MODID, value = Dist.CLIENT)
public final class CityBossBarRenderHandler {
    private static final String CITY_MOVE_BOSS_BAR_KEY = "message.njw_after_the_end.city_move.boss_bar";
    private static boolean shiftNextBar;

    private CityBossBarRenderHandler() { }

    @SubscribeEvent
    public static void onBossEventProgress(CustomizeGuiOverlayEvent.BossEventProgress event) {
        shiftNextBar = event.getBossEvent().getName().getContents() instanceof TranslatableContents contents
                && CITY_MOVE_BOSS_BAR_KEY.equals(contents.getKey());
    }

    public static int consumeBarY(int y) {
        boolean shift = shiftNextBar;
        shiftNextBar = false;
        return shift ? y + 1 : y;
    }
}
