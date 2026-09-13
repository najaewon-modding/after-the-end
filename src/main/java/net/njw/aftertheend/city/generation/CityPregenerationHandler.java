package net.njw.aftertheend.city.generation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

public final class CityPregenerationHandler {
    private static final int SAVE_INTERVAL_CHUNKS = 100;
    private static final long TIME_BUDGET_NANOS = 5_000_000L;
    private static final long PROGRESS_LOG_INTERVAL_NANOS = 10_000_000_000L;
    private static final int MAX_CHUNKS_PER_TICK = 4;
    private static final Component LOAD_KICK_MESSAGE = Component.translatable("message.njw_after_the_end.city_load.in_progress");

    private static final List<PregenerationTask> tasks = new ArrayList<>();
    private static int currentTaskIndex;
    private static CitySavedData savedData;
    private static boolean active;
    private static volatile boolean maintenanceActive;
    private static UUID activeCityId;
    private static boolean allCitiesLoad;
    private static int selectedCityCount;
    private static long lastProgressLogNanos;

    private CityPregenerationHandler() { }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        resetRuntimeState();
    }

    public static int startCityLoad(MinecraftServer server, City city) {
        return startLoad(server, List.of(city), city.id(), false);
    }

    public static int startAllCityLoads(MinecraftServer server, Collection<City> cities) {
        return startLoad(server, List.copyOf(cities), null, true);
    }

    private static int startLoad(MinecraftServer server, List<City> cities, UUID cityId, boolean allCities) {
        if (active) throw new IllegalStateException("City chunk loading is already active: " + activeLoadDescription());
        tasks.clear();
        currentTaskIndex = 0;
        savedData = server.getDataStorage().computeIfAbsent(CitySavedData.TYPE);
        for (City city : cities) registerCityTasks(server, city);
        if (tasks.isEmpty()) {
            savedData = null;
            return 0;
        }
        activeCityId = cityId;
        allCitiesLoad = allCities;
        selectedCityCount = cities.size();
        maintenanceActive = true;
        active = true;
        lastProgressLogNanos = System.nanoTime();
        AfterTheEnd.LOGGER.info(
                "City chunk loading scheduled: scope={}, cities={}, tasks={}, progress={}/{} ({}%). Player connections are temporarily disabled.",
                activeLoadDescription(), selectedCityCount, tasks.size(), getGeneratedChunks(), getTotalChunks(),
                percentage(getGeneratedChunks(), getTotalChunks())
        );
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
        if (region == null) return;
        ServerLevel level = server.getLevel(dimension);
        if (level == null) return;
        long savedProgress = savedData.getPregeneratedChunks(city.id(), dimension);
        CityPregenerator pregenerator = new CityPregenerator(level, region, 0, savedProgress);
        if (savedProgress != pregenerator.getGeneratedChunks()) {
            savedData.setPregeneratedChunks(city.id(), dimension, pregenerator.getGeneratedChunks());
        }
        if (pregenerator.isFinished()) {
            savedData.markPregenerationCompleted(city.id(), dimension);
            return;
        }
        tasks.add(new PregenerationTask(city.id(), dimension, pregenerator));
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
                long now = System.nanoTime();
                if (now - lastProgressLogNanos >= PROGRESS_LOG_INTERVAL_NANOS) {
                    logProgress(task);
                    lastProgressLogNanos = now;
                }
                if (pregenerator.isFinished()) {
                    completeTask(task);
                    currentTaskIndex++;
                }
            }
        } catch (RuntimeException exception) {
            saveRemainingProgress();
            String scope = activeLoadDescription();
            AfterTheEnd.LOGGER.error("City chunk loading failed: scope={}. Player connections are enabled again.", scope, exception);
            resetRuntimeState();
            return;
        }

        if (currentTaskIndex >= tasks.size()) {
            String scope = activeLoadDescription();
            long generatedChunks = getGeneratedChunks();
            long totalChunks = getTotalChunks();
            int cityCount = selectedCityCount;
            int taskCount = tasks.size();
            resetRuntimeState();
            AfterTheEnd.LOGGER.info(
                    "City chunk loading completed: scope={}, cities={}, tasks={}, progress={}/{} (100.0%). Player connections are enabled again.",
                    scope, cityCount, taskCount, generatedChunks, totalChunks
            );
        }
    }

    private static void disconnectAllPlayers(MinecraftServer server) {
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) player.connection.disconnect(LOAD_KICK_MESSAGE);
    }

    private static void logProgress(PregenerationTask task) {
        long taskGenerated = task.pregenerator().getGeneratedChunks();
        long taskTotal = task.pregenerator().getTotalChunks();
        long overallGenerated = getGeneratedChunks();
        long overallTotal = getTotalChunks();
        AfterTheEnd.LOGGER.info(
                "City load progress: task={}/{}, city={}, dimension={}, taskProgress={}/{} ({}%), overall={}/{} ({}%)",
                currentTaskIndex + 1, tasks.size(), task.cityId(), task.dimension().identifier(),
                taskGenerated, taskTotal, percentage(taskGenerated, taskTotal),
                overallGenerated, overallTotal, percentage(overallGenerated, overallTotal)
        );
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
        AfterTheEnd.LOGGER.info(
                "City load task completed: task={}/{}, city={}, dimension={}, progress={}/{}; overall={}/{} ({}%)",
                currentTaskIndex + 1, tasks.size(), task.cityId(), task.dimension().identifier(),
                task.pregenerator().getGeneratedChunks(), task.pregenerator().getTotalChunks(),
                getGeneratedChunks(), getTotalChunks(), percentage(getGeneratedChunks(), getTotalChunks())
        );
    }

    private static long getGeneratedChunks() {
        long generated = 0L;
        for (PregenerationTask task : tasks) generated += task.pregenerator().getGeneratedChunks();
        return generated;
    }

    private static long getTotalChunks() {
        long total = 0L;
        for (PregenerationTask task : tasks) total += task.pregenerator().getTotalChunks();
        return total;
    }

    private static double percentage(long generated, long total) {
        if (total <= 0L) return 100.0D;
        return Math.round(generated * 1000.0D / total) / 10.0D;
    }

    private static String activeLoadDescription() {
        return allCitiesLoad ? "all-cities" : "city=" + activeCityId;
    }

    public static void removeCity(UUID cityId) {
        if (!active) return;
        boolean included = cityId.equals(activeCityId)
                || allCitiesLoad && tasks.stream().anyMatch(task -> task.cityId().equals(cityId));
        if (!included) return;
        saveRemainingProgress();
        AfterTheEnd.LOGGER.warn(
                "Active city chunk loading canceled because city {} was removed. Player connections are enabled again.", cityId
        );
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
        allCitiesLoad = false;
        selectedCityCount = 0;
        lastProgressLogNanos = 0L;
    }

    private record PregenerationTask(UUID cityId, ResourceKey<Level> dimension, CityPregenerator pregenerator) { }
}
