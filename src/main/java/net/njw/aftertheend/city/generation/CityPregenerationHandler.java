package net.njw.aftertheend.city.generation;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerNegotiationEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityRegion;
import net.njw.aftertheend.city.CitySavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CityPregenerationHandler {
    private static final int WARMUP_RADIUS_CHUNKS = 16;
    private static final int LOG_INTERVAL_CHUNKS = 100;
    private static final int SAVE_INTERVAL_CHUNKS = 100;
    private static final long TIME_BUDGET_NANOS = 5_000_000L;
    private static final int MAX_CHUNKS_PER_TICK = 4;
    private static final Component LOAD_KICK_MESSAGE = Component.literal("City chunks are being loaded. Please reconnect after loading has finished.");

    private static final List<PregenerationTask> tasks = new ArrayList<>();
    private static int currentTaskIndex;
    private static CitySavedData savedData;
    private static boolean active;
    private static volatile boolean maintenanceActive;
    private static UUID activeCityId;

    private CityPregenerationHandler() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        resetRuntimeState();
    }

    public static int startCityLoad(MinecraftServer server, City city) {
        if (active) throw new IllegalStateException("City chunk loading is already active for: " + activeCityId);

        tasks.clear();
        currentTaskIndex = 0;
        savedData = server.getDataStorage().computeIfAbsent(CitySavedData.TYPE);
        registerCityTasks(server, city);
        if (tasks.isEmpty()) {
            savedData = null;
            return 0;
        }

        activeCityId = city.id();
        maintenanceActive = true;
        active = true;
        AfterTheEnd.LOGGER.info("City chunk loading scheduled: city={}, tasks={}. Player connections are temporarily disabled.", city.id(), tasks.size());
        return tasks.size();
    }

    private static void registerCityTasks(MinecraftServer server, City city) {
        registerTaskIfNeeded(server, city, Level.OVERWORLD);
        registerTaskIfNeeded(server, city, Level.NETHER);
        for (Map.Entry<ResourceKey<Level>, CityRegion> entry : city.regions().entrySet()) {
            ResourceKey<Level> dimension = entry.getKey();
            if (Level.OVERWORLD.equals(dimension) || Level.NETHER.equals(dimension)) continue;
            registerTaskIfNeeded(server, city, dimension);
        }
    }

    private static void registerTaskIfNeeded(MinecraftServer server, City city, ResourceKey<Level> dimension) {
        CityRegion region = city.getRegion(dimension).orElse(null);
        if (region == null || savedData.isPregenerationCompleted(city.id(), dimension)) return;
        ServerLevel level = server.getLevel(dimension);
        if (level == null) return;

        int diameter = WARMUP_RADIUS_CHUNKS * 2 + 1;
        CityRegion warmupRegion = new CityRegion(region.centerChunkX(), region.centerChunkZ(), diameter, diameter);
        long alreadyGenerated = savedData.getPregeneratedChunks(city.id(), dimension);
        tasks.add(new PregenerationTask(city.id(), dimension, new CityPregenerator(level, warmupRegion, 0, alreadyGenerated)));
    }

    @SubscribeEvent
    public static void onPlayerNegotiation(PlayerNegotiationEvent event) {
        if (maintenanceActive) event.getConnection().disconnect(LOAD_KICK_MESSAGE);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!active || savedData == null) return;
        MinecraftServer server = event.getServer();

        if (!server.getPlayerList().getPlayers().isEmpty()) {
            disconnectAllPlayers(server);
            return;
        }

        long deadline = System.nanoTime() + TIME_BUDGET_NANOS;
        int generated = 0;
        try {
            while (currentTaskIndex < tasks.size() && generated < MAX_CHUNKS_PER_TICK) {
                if (generated > 0 && System.nanoTime() >= deadline) break;
                PregenerationTask task = tasks.get(currentTaskIndex);
                CityPregenerator pregenerator = task.pregenerator();
                if (pregenerator.isFinished()) {
                    completeTask(task);
                    currentTaskIndex++;
                    continue;
                }
                pregenerator.generateNextChunk();
                generated++;
                long progress = pregenerator.getGeneratedChunks();
                if (progress % SAVE_INTERVAL_CHUNKS == 0) saveTaskProgress(task);
                if (progress > 0 && progress % LOG_INTERVAL_CHUNKS == 0) {
                    AfterTheEnd.LOGGER.info("City load: city={}, dimension={}, progress={}/{}", task.cityId(), task.dimension().identifier(), progress, pregenerator.getTotalChunks());
                }
                if (pregenerator.isFinished()) {
                    completeTask(task);
                    currentTaskIndex++;
                }
            }
        } catch (RuntimeException exception) {
            saveRemainingProgress();
            AfterTheEnd.LOGGER.error("City chunk loading failed: city={}. Player connections are enabled again.", activeCityId, exception);
            resetRuntimeState();
            return;
        }

        if (currentTaskIndex >= tasks.size()) {
            UUID completedCityId = activeCityId;
            resetRuntimeState();
            AfterTheEnd.LOGGER.info("City chunk loading completed: city={}. Player connections are enabled again.", completedCityId);
        }
    }

    private static void disconnectAllPlayers(MinecraftServer server) {
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            player.connection.disconnect(LOAD_KICK_MESSAGE);
        }
    }

    private static void saveTaskProgress(PregenerationTask task) {
        savedData.setPregeneratedChunks(task.cityId(), task.dimension(), task.pregenerator().getGeneratedChunks());
    }

    private static void saveRemainingProgress() {
        if (savedData == null) return;
        for (int i = currentTaskIndex; i < tasks.size(); i++) {
            PregenerationTask task = tasks.get(i);
            if (!task.pregenerator().isFinished()) saveTaskProgress(task);
        }
    }

    private static void completeTask(PregenerationTask task) {
        saveTaskProgress(task);
        savedData.markPregenerationCompleted(task.cityId(), task.dimension());
        AfterTheEnd.LOGGER.info("City load completed: city={}, dimension={} ({}/{})", task.cityId(), task.dimension().identifier(), task.pregenerator().getGeneratedChunks(), task.pregenerator().getTotalChunks());
    }

    public static void removeCity(UUID cityId) {
        if (!cityId.equals(activeCityId)) return;
        AfterTheEnd.LOGGER.warn("Active city chunk loading canceled because city {} was removed. Player connections are enabled again.", cityId);
        resetRuntimeState();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        saveRemainingProgress();
        resetRuntimeState();
    }

    private static void resetRuntimeState() {
        tasks.clear();
        currentTaskIndex = 0;
        savedData = null;
        active = false;
        maintenanceActive = false;
        activeCityId = null;
    }

    private record PregenerationTask(UUID cityId, ResourceKey<Level> dimension, CityPregenerator pregenerator) { }
}
