package net.njw.aftertheend.city.altar;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    private static final ArrayDeque<UUID> QUEUE = new ArrayDeque<>();
    private static final Set<UUID> QUEUED = new HashSet<>();
    private static final Set<UUID> FAILED_THIS_SESSION = new HashSet<>();
    private static final ArrayDeque<ChunkPos> CHUNKS_TO_ENSURE = new ArrayDeque<>();

    private static UUID activeCityId;
    private static AltarPlacementService.Preparation activePreparation;
    private static AltarPlacementPlanner.PreparationStep activeStep;
    private static CompletableFuture<?> pendingChunkFuture;
    private static ChunkPos pendingChunk;
    private static ChunkStatus pendingChunkStatus;
    private static long pendingChunkStartedNanos;

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
        if (QUEUED.remove(cityId)) QUEUED.remove(cityId);
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
    }

    private static void startNext(MinecraftServer server) {
        while (!QUEUE.isEmpty()) {
            UUID cityId = QUEUE.removeFirst();
            QUEUED.remove(cityId);
            City city = CityManager.getCity(server, cityId);
            if (city == null || CityManager.isCityAccessible(server, cityId) || AltarManager.isGenerated(server, cityId)) continue;
            try {
                AltarPlacementService.Preparation preparation = AltarPlacementService.beginPreparation(server, city);
                if (preparation == null) continue;
                activeCityId = cityId;
                activePreparation = preparation;
                activeStep = null;
                CHUNKS_TO_ENSURE.clear();
                AfterTheEnd.LOGGER.info("Hidden city preparation started: city={}", cityId);
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
            if (pendingChunkFuture != null) {
                if (!pendingChunkFuture.isDone()) return;
                finishPendingChunk();
            }

            if (activeStep == null) {
                activeStep = AltarPlacementService.advancePreparation(activePreparation);
                if (activeStep.complete()) {
                    AltarPlacementService.completePreparation(server, city, activePreparation, activeStep.plans());
                    UUID completed = activeCityId;
                    resetActive(false);
                    AfterTheEnd.LOGGER.info("Hidden city preparation completed; city is READY: city={}", completed);
                    refreshQueue(server);
                    return;
                }
                CHUNKS_TO_ENSURE.clear();
                CHUNKS_TO_ENSURE.addAll(activeStep.requiredChunks());
            }

            ServerLevel level = activePreparation.level();
            while (!CHUNKS_TO_ENSURE.isEmpty()) {
                ChunkPos next = CHUNKS_TO_ENSURE.peekFirst();
                if (isPrepared(level, next, activeStep.chunkStatus())) {
                    CHUNKS_TO_ENSURE.removeFirst();
                    continue;
                }
                startChunkFuture(level, next, activeStep.chunkStatus());
                return;
            }

            List<ChunkPos> missing = AltarPlacementService.missingRequiredChunks(
                    activePreparation, activeStep.candidateIndex()
            );
            if (!missing.isEmpty()) {
                CHUNKS_TO_ENSURE.addAll(missing);
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

    private static boolean isPrepared(ServerLevel level, ChunkPos chunk, ChunkStatus status) {
        if (status == ChunkStatus.FULL) return level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null;
        return level.getChunkSource().getChunk(chunk.x(), chunk.z(), status, false) != null;
    }

    private static void startChunkFuture(ServerLevel level, ChunkPos chunk, ChunkStatus status) {
        pendingChunk = chunk;
        pendingChunkStatus = status;
        pendingChunkStartedNanos = System.nanoTime();
        pendingChunkFuture = CompletableFuture
                .supplyAsync(() -> level.getChunkSource().getChunkFuture(
                        chunk.x(), chunk.z(), status, true
                ))
                .thenCompose(future -> future);
        AfterTheEnd.LOGGER.debug(
                "Hidden city preparation requested async chunk ({}, {}) status={}",
                chunk.x(), chunk.z(), AltarPlacementPlanner.statusName(status)
        );
    }

    private static void finishPendingChunk() {
        try {
            pendingChunkFuture.join();
        } catch (CompletionException exception) {
            throw new IllegalStateException("Async hidden-city chunk generation failed at " + pendingChunk, exception.getCause());
        }
        AfterTheEnd.LOGGER.info(
                "chunk ({}, {}) {} {}sec city={}",
                pendingChunk.x(), pendingChunk.z(), AltarPlacementPlanner.statusName(pendingChunkStatus),
                AltarPlacementPlanner.formatSeconds((System.nanoTime() - pendingChunkStartedNanos) / 1_000_000_000.0), activeCityId
        );
        if (!CHUNKS_TO_ENSURE.isEmpty() && CHUNKS_TO_ENSURE.peekFirst().equals(pendingChunk)) {
            CHUNKS_TO_ENSURE.removeFirst();
        }
        pendingChunkFuture = null;
        pendingChunk = null;
        pendingChunkStatus = null;
        pendingChunkStartedNanos = 0L;
    }

    private static void resetActive(boolean cancelFuture) {
        if (cancelFuture && pendingChunkFuture != null) pendingChunkFuture.cancel(false);
        activeCityId = null;
        activePreparation = null;
        activeStep = null;
        pendingChunkFuture = null;
        pendingChunk = null;
        pendingChunkStatus = null;
        pendingChunkStartedNanos = 0L;
        CHUNKS_TO_ENSURE.clear();
    }

    private static void resetAll() {
        resetActive(true);
        QUEUE.clear();
        QUEUED.clear();
        FAILED_THIS_SESSION.clear();
    }
}
