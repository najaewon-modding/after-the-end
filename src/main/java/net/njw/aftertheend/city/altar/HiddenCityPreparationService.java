package net.njw.aftertheend.city.altar;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityManager;

public final class HiddenCityPreparationService {
    private static final int PLANNER_START_DELAY_TICKS = 40;
    private static final ArrayDeque<UUID> QUEUE = new ArrayDeque<>();
    private static final Set<UUID> QUEUED = new HashSet<>();
    private static final Set<UUID> FAILED_THIS_SESSION = new HashSet<>();
    private static List<ChunkPos> pendingChunks = List.of();
    private static List<CompletableFuture<?>> pendingChunkFutures = List.of();

    private static UUID activeCityId;
    private static AltarPlacementService.PreparationSeed pendingPreparationSeed;
    private static AltarPlacementService.Preparation activePreparation;
    private static AltarPlacementPlanner.PreparationStep activeStep;
    private static CompletableFuture<AltarPlacementService.Preparation> pendingPreparationFuture;
    private static CompletableFuture<AltarPlacementPlanner.PreparationStep> pendingAdvanceFuture;
    private static CompletableFuture<?> pendingChunkFuture;
    private static ExecutorService plannerExecutor;
    private static ChunkStatus pendingChunkStatus;
    private static long pendingChunkStartedNanos;
    private static long pendingPlannerStartedNanos;
    private static int plannerStartDelayTicks;

    private HiddenCityPreparationService() { }

    public static void refreshQueue(MinecraftServer server) {
        QUEUE.removeIf(cityId -> {
            City city = CityManager.getCity(server, cityId);
            boolean remove = city == null || CityManager.isCityAccessible(server, cityId) || AltarManager.isGenerated(server, cityId);
            if (remove) QUEUED.remove(cityId);
            return remove;
        });
        for (City city : CityManager.getLockedCities(server)) {
            UUID cityId = city.id();
            if (AltarManager.isGenerated(server, cityId)) continue;
            if (FAILED_THIS_SESSION.contains(cityId)) continue;
            if (cityId.equals(activeCityId) || QUEUED.contains(cityId)) continue;
            QUEUE.addLast(cityId);
            QUEUED.add(cityId);
        }
    }

    public static void removeCity(UUID cityId) {
        if (cityId.equals(activeCityId)) resetActive(true);
        if (QUEUE.remove(cityId)) QUEUED.remove(cityId);
        FAILED_THIS_SESSION.remove(cityId);
    }

    public static boolean isPreparing(UUID cityId) {
        return cityId.equals(activeCityId) || QUEUED.contains(cityId);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (activeCityId == null) {
            refreshQueue(server);
            startNext(server);
            if (activeCityId == null) return;
        }
        tickActive(server);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        resetAll();
        shutdownPlannerExecutor();
    }

    private static void startNext(MinecraftServer server) {
        while (!QUEUE.isEmpty()) {
            UUID cityId = QUEUE.removeFirst();
            QUEUED.remove(cityId);
            City city = CityManager.getCity(server, cityId);
            if (city == null || CityManager.isCityAccessible(server, cityId) || AltarManager.isGenerated(server, cityId)) continue;
            try {
                AltarPlacementService.PreparationSeed preparationSeed = AltarPlacementService.createPreparationSeed(server, city);
                if (preparationSeed == null) continue;
                activeCityId = cityId;
                pendingPreparationSeed = preparationSeed;
                activePreparation = null;
                activeStep = null;
                pendingPreparationFuture = null;
                pendingAdvanceFuture = null;
                plannerStartDelayTicks = PLANNER_START_DELAY_TICKS;
                AfterTheEnd.LOGGER.info(
                        "Hidden city preparation queued: city={}; virtual planning starts after {} ticks on a low-priority worker.",
                        cityId, PLANNER_START_DELAY_TICKS
                );
                return;
            } catch (RuntimeException exception) {
                FAILED_THIS_SESSION.add(cityId);
                AfterTheEnd.LOGGER.error("Could not start hidden city preparation: city={}", cityId, exception);
            }
        }
    }

    private static void tickActive(MinecraftServer server) {
        City city = CityManager.getCity(server, activeCityId);
        if (city == null || CityManager.isCityAccessible(server, activeCityId)) {
            resetActive(true);
            return;
        }
        if (AltarManager.isGenerated(server, activeCityId)) {
            UUID completed = activeCityId;
            resetActive(false);
            AfterTheEnd.LOGGER.info("Hidden city is READY: city={}", completed);
            return;
        }
        try {
            if (activePreparation == null) {
                if (!finishOrStartInitialPlanning()) return;
            }

            if (pendingChunkFuture != null) {
                if (!pendingChunkFuture.isDone()) return;
                finishPendingChunkBatch();
            }

            if (activeStep == null) {
                if (!finishOrStartAdvancePlanning()) return;
                if (activeStep.complete()) {
                    AltarPlacementService.completePreparation(server, city, activePreparation, activeStep.plans());
                    UUID completed = activeCityId;
                    resetActive(false);
                    AfterTheEnd.LOGGER.info("Hidden city preparation completed; city is READY: city={}", completed);
                    refreshQueue(server);
                    return;
                }
            }

            ServerLevel level = activePreparation.level();
            List<ChunkPos> missing = AltarPlacementService.missingRequiredChunks(
                    activePreparation, activeStep.candidateIndex()
            );
            if (!missing.isEmpty()) {
                startChunkBatch(level, missing, activeStep.chunkStatus());
                return;
            }

            AltarPlacementService.exactEvaluateLoaded(activePreparation, activeStep.candidateIndex());
            activeStep = null;
        } catch (RuntimeException exception) {
            UUID failed = activeCityId;
            FAILED_THIS_SESSION.add(failed);
            AfterTheEnd.LOGGER.error("Hidden city preparation failed: city={}", failed, exception);
            resetActive(true);
            refreshQueue(server);
        }
    }

    private static boolean finishOrStartInitialPlanning() {
        if (pendingPreparationFuture == null) {
            if (plannerStartDelayTicks > 0) {
                plannerStartDelayTicks--;
                return false;
            }
            AltarPlacementService.PreparationSeed seed = pendingPreparationSeed;
            if (seed == null) throw new IllegalStateException("Hidden city has no preparation seed.");
            pendingPlannerStartedNanos = System.nanoTime();
            pendingPreparationFuture = CompletableFuture.supplyAsync(
                    () -> AltarPlacementService.buildPreparation(seed), plannerExecutor()
            );
            AfterTheEnd.LOGGER.info("Hidden city virtual planning started on worker: city={}", activeCityId);
            return false;
        }
        if (!pendingPreparationFuture.isDone()) return false;
        try {
            activePreparation = pendingPreparationFuture.join();
        } catch (CompletionException exception) {
            throw new IllegalStateException("Hidden-city virtual planning failed for " + activeCityId, exception.getCause());
        }
        AfterTheEnd.LOGGER.info(
                "Hidden city initial virtual planning completed in {}sec without blocking a server tick: city={}",
                AltarPlacementPlanner.formatSeconds((System.nanoTime() - pendingPlannerStartedNanos) / 1_000_000_000.0), activeCityId
        );
        pendingPreparationFuture = null;
        pendingPreparationSeed = null;
        pendingPlannerStartedNanos = 0L;
        return true;
    }

    private static boolean finishOrStartAdvancePlanning() {
        if (pendingAdvanceFuture == null) {
            pendingAdvanceFuture = CompletableFuture.supplyAsync(
                    () -> AltarPlacementService.advancePreparation(activePreparation), plannerExecutor()
            );
            return false;
        }
        if (!pendingAdvanceFuture.isDone()) return false;
        try {
            activeStep = pendingAdvanceFuture.join();
        } catch (CompletionException exception) {
            throw new IllegalStateException("Hidden-city planner advance failed for " + activeCityId, exception.getCause());
        }
        pendingAdvanceFuture = null;
        return true;
    }

    private static ExecutorService plannerExecutor() {
        if (plannerExecutor == null || plannerExecutor.isShutdown()) {
            plannerExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "after-the-end-hidden-planner");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
        }
        return plannerExecutor;
    }

    private static void shutdownPlannerExecutor() {
        if (plannerExecutor == null) return;
        plannerExecutor.shutdownNow();
        plannerExecutor = null;
    }

    private static void startChunkBatch(ServerLevel level, List<ChunkPos> chunks, ChunkStatus status) {
        pendingChunks = List.copyOf(chunks);
        pendingChunkStatus = status;
        pendingChunkStartedNanos = System.nanoTime();
        List<CompletableFuture<?>> futures = new ArrayList<>(chunks.size());
        for (ChunkPos chunk : chunks) {
            futures.add(level.getChunkSource().getChunkFuture(chunk.x(), chunk.z(), status, true));
        }
        pendingChunkFutures = List.copyOf(futures);
        pendingChunkFuture = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        AfterTheEnd.LOGGER.debug(
                "Hidden city preparation requested async chunk batch size={} status={}",
                chunks.size(), AltarPlacementPlanner.statusName(status)
        );
    }

    private static void finishPendingChunkBatch() {
        try {
            pendingChunkFuture.join();
            for (CompletableFuture<?> future : pendingChunkFutures) future.join();
        } catch (CompletionException exception) {
            throw new IllegalStateException("Async hidden-city chunk batch generation failed at " + pendingChunks, exception.getCause());
        }
        AfterTheEnd.LOGGER.info(
                "chunk batch {} {} chunk(s) {}sec city={}",
                AltarPlacementPlanner.statusName(pendingChunkStatus), pendingChunks.size(),
                AltarPlacementPlanner.formatSeconds((System.nanoTime() - pendingChunkStartedNanos) / 1_000_000_000.0), activeCityId
        );
        pendingChunkFuture = null;
        pendingChunkFutures = List.of();
        pendingChunks = List.of();
        pendingChunkStatus = null;
        pendingChunkStartedNanos = 0L;
    }

    private static void resetActive(boolean cancelFuture) {
        if (cancelFuture) {
            if (pendingPreparationFuture != null) pendingPreparationFuture.cancel(true);
            if (pendingAdvanceFuture != null) pendingAdvanceFuture.cancel(true);
            if (pendingChunkFuture != null) pendingChunkFuture.cancel(false);
            for (CompletableFuture<?> future : pendingChunkFutures) future.cancel(false);
        }
        activeCityId = null;
        pendingPreparationSeed = null;
        activePreparation = null;
        activeStep = null;
        pendingPreparationFuture = null;
        pendingAdvanceFuture = null;
        pendingChunkFuture = null;
        pendingChunkFutures = List.of();
        pendingChunks = List.of();
        pendingChunkStatus = null;
        pendingChunkStartedNanos = 0L;
        pendingPlannerStartedNanos = 0L;
        plannerStartDelayTicks = 0;
    }

    private static void resetAll() {
        resetActive(true);
        QUEUE.clear();
        QUEUED.clear();
        FAILED_THIS_SESSION.clear();
    }
}
