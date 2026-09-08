package net.njw.aftertheend.client.worldgen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.njw.aftertheend.AfterTheEnd;

@EventBusSubscriber(modid = AfterTheEnd.MODID, value = Dist.CLIENT)
public final class WorldCreationSeedHandler {
    private static final int ATTEMPTS_PER_TICK = 32;
    private static CreateWorldScreen pendingScreen;
    private static SeedSearchService.SearchTask pendingTask;

    private WorldCreationSeedHandler() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen screen)) return;
        if (!screen.getUiState().getSeed().isBlank()) return;
        pendingScreen = screen;
        pendingTask = SeedSearchService.createTask(screen.getUiState().getSettings());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (pendingScreen == null || pendingTask == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != pendingScreen) {
            clearPending();
            return;
        }
        if (!pendingScreen.getUiState().getSeed().isBlank()) {
            clearPending();
            return;
        }
        pendingTask.advance(ATTEMPTS_PER_TICK);
        if (!pendingTask.isFinished()) return;
        if (pendingTask.hasResult()) {
            long seed = pendingTask.result();
            pendingScreen.getUiState().setSeed(Long.toString(seed));
            AfterTheEnd.LOGGER.info("Selected seed {} for After the End after {} attempts", seed, pendingTask.attempts());
        } else {
            AfterTheEnd.LOGGER.warn("Could not find a suitable seed within {} attempts.", SeedSearchService.MAX_ATTEMPTS);
        }
        clearPending();
    }

    private static void clearPending() {
        pendingScreen = null;
        pendingTask = null;
    }
}
