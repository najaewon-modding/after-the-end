package net.njw.beyondthecity.city.generation;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.njw.beyondthecity.BeyondtheCity;
import net.njw.beyondthecity.city.City;
import net.njw.beyondthecity.city.CityManager;
import net.njw.beyondthecity.city.CityRegion;
import net.njw.beyondthecity.city.CitySavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CityPregenerationHandler {
    private static final int WARMUP_RADIUS_CHUNKS = 16;
    private static final int LOG_INTERVAL_CHUNKS = 100;
    private static final int SAVE_INTERVAL_CHUNKS = 100;
    private static final long TIME_BUDGET_NANOS = 5_000_000L;
    private static final int MAX_CHUNKS_PER_TICK = 4;

    private static final List<PregenerationTask> tasks = new ArrayList<>();
    private static int currentTaskIndex;
    private static CitySavedData savedData;
    private static boolean active;

    private CityPregenerationHandler() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        resetRuntimeState();
        savedData = server.getDataStorage().computeIfAbsent(CitySavedData.TYPE);
        for (City city : CityManager.getAccessibleCities(server)) registerCityTasks(server, city);
        active = !tasks.isEmpty();
        if (active) BeyondtheCity.LOGGER.info("City warm-up pregeneration started with {} pending task(s).", tasks.size());
    }

    public static void enqueueCity(MinecraftServer server, City city) {
        if (savedData == null) savedData = server.getDataStorage().computeIfAbsent(CitySavedData.TYPE);
        int before = tasks.size();
        registerCityTasks(server, city);
        if (tasks.size() > before) active = true;
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
        for (PregenerationTask task : tasks) if (task.cityId().equals(city.id()) && task.dimension().equals(dimension)) return;
        ServerLevel level = server.getLevel(dimension);
        if (level == null) return;

        int diameter = WARMUP_RADIUS_CHUNKS * 2 + 1;
        CityRegion warmupRegion = new CityRegion(region.centerChunkX(), region.centerChunkZ(), diameter, diameter);
        long alreadyGenerated = savedData.getPregeneratedChunks(city.id(), dimension);
        tasks.add(new PregenerationTask(city.id(), dimension, new CityPregenerator(level, warmupRegion, 0, alreadyGenerated)));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!active || savedData == null) return;
        long deadline = System.nanoTime() + TIME_BUDGET_NANOS;
        int generated = 0;
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
                BeyondtheCity.LOGGER.info("City warm-up: city={}, dimension={}, progress={}/{}", task.cityId(), task.dimension().identifier(), progress, pregenerator.getTotalChunks());
            }
            if (pregenerator.isFinished()) {
                completeTask(task);
                currentTaskIndex++;
            }
        }
        if (currentTaskIndex >= tasks.size()) {
            active = false;
            BeyondtheCity.LOGGER.info("All city warm-up pregeneration tasks completed.");
        }
    }

    private static void saveTaskProgress(PregenerationTask task) {
        savedData.setPregeneratedChunks(task.cityId(), task.dimension(), task.pregenerator().getGeneratedChunks());
    }

    private static void completeTask(PregenerationTask task) {
        saveTaskProgress(task);
        savedData.markPregenerationCompleted(task.cityId(), task.dimension());
        BeyondtheCity.LOGGER.info("City warm-up completed: city={}, dimension={} ({}/{})", task.cityId(), task.dimension().identifier(), task.pregenerator().getGeneratedChunks(), task.pregenerator().getTotalChunks());
    }

    public static void removeCity(String cityId) {
        int oldIndex = currentTaskIndex;
        List<PregenerationTask> remaining = new ArrayList<>();
        int newIndex = 0;
        for (int i = 0; i < tasks.size(); i++) {
            PregenerationTask task = tasks.get(i);
            if (task.cityId().equals(cityId)) continue;
            if (i < oldIndex) newIndex++;
            remaining.add(task);
        }
        tasks.clear();
        tasks.addAll(remaining);
        currentTaskIndex = Math.min(newIndex, tasks.size());
        active = currentTaskIndex < tasks.size();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        if (savedData != null) {
            for (int i = currentTaskIndex; i < tasks.size(); i++) {
                PregenerationTask task = tasks.get(i);
                if (!task.pregenerator().isFinished()) saveTaskProgress(task);
            }
        }
        resetRuntimeState();
    }

    private static void resetRuntimeState() {
        tasks.clear();
        currentTaskIndex = 0;
        savedData = null;
        active = false;
    }

    private record PregenerationTask(String cityId, ResourceKey<Level> dimension, CityPregenerator pregenerator) { }
}
