package net.njw.aftertheend.city.generation;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.event.entity.player.PlayerNegotiationEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityRegion;

public final class CityPregenerationHandler {
    private static final int BATCH_SIZE = 32;
    private static final int QUEUE_THRESHOLD = 8;
    private static final int SAVE_INTERVAL_CHUNKS = 4096;
    private static final int MAX_REPAIR_PASSES = 3;
    private static final long PROGRESS_LOG_INTERVAL_NANOS = 10_000_000_000L;
    private static final Component LOAD_KICK_MESSAGE = Component.translatable("message.njw_after_the_end.city_load.in_progress");
    private static final Comparator<ChunkPos> REGION_MAJOR_ORDER = Comparator
            .comparingInt((ChunkPos pos) -> Math.floorDiv(pos.x(), 32))
            .thenComparingInt(pos -> Math.floorDiv(pos.z(), 32))
            .thenComparingInt(pos -> Math.floorMod(pos.x(), 32))
            .thenComparingInt(pos -> Math.floorMod(pos.z(), 32));

    private static volatile boolean maintenanceActive;
    private static LoadSession activeSession;

    private CityPregenerationHandler() { }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        resetRuntimeState();
    }

    public static synchronized int startAllCityLoads(MinecraftServer server, Collection<City> cities) {
        if (activeSession != null) throw new IllegalStateException("City chunk loading is already active.");
        List<DimensionPlan> plans = buildPlans(server, cities);
        if (plans.isEmpty()) return 0;
        LoadSession session = new LoadSession(server, cities.size(), plans);
        activeSession = session;
        maintenanceActive = true;
        AfterTheEnd.LOGGER.info(
                "City chunk loading scheduled: scope=all-cities, cities={}, dimensions={}, uniqueChunks={}. Player connections are temporarily disabled.",
                cities.size(), plans.size(), session.totalChunks
        );
        session.start();
        return plans.size();
    }

    @SubscribeEvent
    public static void onPlayerNegotiation(PlayerNegotiationEvent event) {
        if (maintenanceActive) event.getConnection().disconnect(LOAD_KICK_MESSAGE);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!maintenanceActive) return;
        MinecraftServer server = event.getServer();
        if (!server.getPlayerList().getPlayers().isEmpty()) disconnectAllPlayers(server);
    }

    @SubscribeEvent
    public static synchronized void onServerStopped(ServerStoppedEvent event) {
        if (activeSession != null) activeSession.stop();
        resetRuntimeState();
    }

    private static List<DimensionPlan> buildPlans(MinecraftServer server, Collection<City> cities) {
        Map<ResourceKey<Level>, LongOpenHashSet> targets = new LinkedHashMap<>();
        for (City city : cities) {
            for (Map.Entry<ResourceKey<Level>, CityRegion> entry : city.regions().entrySet()) {
                ResourceKey<Level> dimension = entry.getKey();
                if (server.getLevel(dimension) == null) continue;
                CityRegion region = entry.getValue();
                LongOpenHashSet chunks = targets.computeIfAbsent(dimension, ignored -> new LongOpenHashSet());
                for (int chunkX = region.minChunkX(); chunkX <= region.maxChunkX(); chunkX++) {
                    for (int chunkZ = region.minChunkZ(); chunkZ <= region.maxChunkZ(); chunkZ++) {
                        chunks.add(ChunkPos.pack(chunkX, chunkZ));
                    }
                }
            }
        }

        List<ResourceKey<Level>> dimensions = new ArrayList<>(targets.keySet());
        dimensions.sort(Comparator
                .comparingInt(CityPregenerationHandler::dimensionPriority)
                .thenComparing(key -> key.identifier().toString()));

        List<DimensionPlan> plans = new ArrayList<>(dimensions.size());
        for (ResourceKey<Level> dimension : dimensions) {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) continue;
            LongOpenHashSet packedChunks = targets.get(dimension);
            List<ChunkPos> chunks = new ArrayList<>(packedChunks.size());
            var iterator = packedChunks.iterator();
            while (iterator.hasNext()) chunks.add(ChunkPos.unpack(iterator.nextLong()));
            chunks.sort(REGION_MAJOR_ORDER);
            if (!chunks.isEmpty()) plans.add(new DimensionPlan(dimension, level, List.copyOf(chunks)));
        }
        return List.copyOf(plans);
    }

    private static int dimensionPriority(ResourceKey<Level> dimension) {
        if (Level.OVERWORLD.equals(dimension)) return 0;
        if (Level.NETHER.equals(dimension)) return 1;
        return 2;
    }

    private static void disconnectAllPlayers(MinecraftServer server) {
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) player.connection.disconnect(LOAD_KICK_MESSAGE);
    }

    private static synchronized void completeSession(LoadSession session) {
        if (activeSession != session) return;
        activeSession = null;
        maintenanceActive = false;
        long elapsedMillis = (System.nanoTime() - session.startedNanos) / 1_000_000L;
        AfterTheEnd.LOGGER.info(
                "City chunk loading completed and verified: cities={}, dimensions={}, chunks={}, generatedAttempts={}, generationErrors={}, elapsed={} ms. Player connections are enabled again.",
                session.cityCount, session.originalPlans.size(), session.totalChunks, session.generatedAttempts.get(),
                session.generationErrors.get(), elapsedMillis
        );
    }

    private static synchronized void failSession(LoadSession session, Throwable throwable) {
        if (activeSession != session) return;
        activeSession = null;
        maintenanceActive = true;
        AfterTheEnd.LOGGER.error(
                "City chunk loading failed before verification completed. Player connections remain disabled; fix the error and run /city load all again.",
                throwable
        );
    }

    private static synchronized void resetRuntimeState() {
        activeSession = null;
        maintenanceActive = false;
    }

    private static boolean isChunkFullyGenerated(ServerLevel level, ChunkPos chunkPos) {
        CollectFields collectFields = new CollectFields(new FieldSelector(StringTag.TYPE, "Status"));
        level.getChunkSource().chunkMap.chunkScanner().scanChunk(chunkPos, collectFields).join();
        return collectFields.getResult() instanceof CompoundTag compoundTag
                && compoundTag.getString("Status").equals("minecraft:full");
    }

    private record DimensionPlan(ResourceKey<Level> dimension, ServerLevel level, List<ChunkPos> chunks) { }

    private static final class LoadSession {
        private final MinecraftServer server;
        private final int cityCount;
        private final List<DimensionPlan> originalPlans;
        private final long totalChunks;
        private final long startedNanos = System.nanoTime();
        private final AtomicLong initialProcessed = new AtomicLong();
        private final AtomicLong generatedAttempts = new AtomicLong();
        private final AtomicLong generationErrors = new AtomicLong();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile long lastProgressLogNanos = startedNanos;
        private volatile GenerationPass currentPass;

        private LoadSession(MinecraftServer server, int cityCount, List<DimensionPlan> plans) {
            this.server = server;
            this.cityCount = cityCount;
            this.originalPlans = plans;
            this.totalChunks = plans.stream().mapToLong(plan -> plan.chunks().size()).sum();
        }

        private void start() {
            runPlans(originalPlans, 0, 0);
        }

        private void runPlans(List<DimensionPlan> plans, int index, int repairPass) {
            if (stopped.get()) return;
            if (index >= plans.size()) {
                flushAndAudit(repairPass);
                return;
            }
            DimensionPlan plan = plans.get(index);
            GenerationPass pass = new GenerationPass(this, plan, repairPass, () -> runPlans(plans, index + 1, repairPass));
            currentPass = pass;
            pass.start();
        }

        private void flushAndAudit(int repairPass) {
            if (stopped.get()) return;
            currentPass = null;
            try {
                AfterTheEnd.LOGGER.info("City load generation pass {} finished. Flushing chunks to disk before verification.", repairPass);
                server.saveEverything(false, true, true);
            } catch (RuntimeException exception) {
                fail(exception);
                return;
            }
            CompletableFuture.runAsync(() -> audit(repairPass), Util.backgroundExecutor())
                    .exceptionally(throwable -> {
                        fail(throwable);
                        return null;
                    });
        }

        private void audit(int repairPass) {
            if (stopped.get()) return;
            long checked = 0L;
            long missingCount = 0L;
            long lastLog = System.nanoTime();
            List<DimensionPlan> missingPlans = new ArrayList<>();
            for (DimensionPlan plan : originalPlans) {
                List<ChunkPos> missing = new ArrayList<>();
                for (ChunkPos chunkPos : plan.chunks()) {
                    if (stopped.get()) return;
                    if (!isChunkFullyGenerated(plan.level(), chunkPos)) {
                        missing.add(chunkPos);
                        missingCount++;
                    }
                    checked++;
                    long now = System.nanoTime();
                    if (now - lastLog >= PROGRESS_LOG_INTERVAL_NANOS) {
                        AfterTheEnd.LOGGER.info(
                                "City load verification progress: checked={}/{} ({}%), missing={}",
                                checked, totalChunks, percentage(checked, totalChunks), missingCount
                        );
                        lastLog = now;
                    }
                }
                if (!missing.isEmpty()) missingPlans.add(new DimensionPlan(plan.dimension(), plan.level(), List.copyOf(missing)));
            }

            long finalChecked = checked;
            long finalMissingCount = missingCount;
            server.submit(() -> handleAuditResult(repairPass, missingPlans, finalChecked, finalMissingCount));
        }

        private void handleAuditResult(int repairPass, List<DimensionPlan> missingPlans, long checked, long missingCount) {
            if (stopped.get()) return;
            AfterTheEnd.LOGGER.info(
                    "City load verification finished: checked={}/{}, full={}, missing={}.",
                    checked, totalChunks, checked - missingCount, missingCount
            );
            if (missingCount == 0L) {
                stopped.set(true);
                completeSession(this);
                return;
            }
            if (repairPass >= MAX_REPAIR_PASSES) {
                fail(new IllegalStateException("Chunk verification still found " + missingCount
                        + " missing or incomplete chunks after " + repairPass + " repair passes."));
                return;
            }
            AfterTheEnd.LOGGER.warn(
                    "City load verification found {} missing or incomplete chunks. Starting repair pass {}/{}.",
                    missingCount, repairPass + 1, MAX_REPAIR_PASSES
            );
            runPlans(List.copyOf(missingPlans), 0, repairPass + 1);
        }

        private void recordProcessed(DimensionPlan plan, int repairPass, long passProcessed, long passTotal,
                                     long passGenerated, long passErrors, long passSkipped) {
            if (repairPass == 0) initialProcessed.incrementAndGet();
            maybeLogProgress(plan, repairPass, passProcessed, passTotal, passGenerated, passErrors, passSkipped);
        }

        private void recordGenerated(DimensionPlan plan, int repairPass, long passProcessed, long passTotal,
                                     long passGenerated, long passErrors, long passSkipped) {
            long generated = generatedAttempts.incrementAndGet();
            recordProcessed(plan, repairPass, passProcessed, passTotal, passGenerated, passErrors, passSkipped);
            if (generated % SAVE_INTERVAL_CHUNKS == 0L) {
                server.submit(() -> {
                    if (!stopped.get()) plan.level().save(null, false, false);
                });
            }
        }

        private void recordGenerationError(DimensionPlan plan, int repairPass, long passProcessed, long passTotal,
                                           long passGenerated, long passErrors, long passSkipped) {
            generationErrors.incrementAndGet();
            recordProcessed(plan, repairPass, passProcessed, passTotal, passGenerated, passErrors, passSkipped);
        }

        private synchronized void maybeLogProgress(DimensionPlan plan, int repairPass, long passProcessed, long passTotal,
                                                   long passGenerated, long passErrors, long passSkipped) {
            long now = System.nanoTime();
            if (now - lastProgressLogNanos < PROGRESS_LOG_INTERVAL_NANOS) return;
            lastProgressLogNanos = now;
            AfterTheEnd.LOGGER.info(
                    "City load progress: phase=generation, pass={}, dimension={}, dimensionProgress={}/{} ({}%), generated={}, skipped={}, errors={}, overallInitial={}/{} ({}%)",
                    repairPass == 0 ? "initial" : "repair-" + repairPass, plan.dimension().identifier(),
                    passProcessed, passTotal, percentage(passProcessed, passTotal),
                    passGenerated, passSkipped, passErrors, initialProcessed.get(), totalChunks,
                    percentage(initialProcessed.get(), totalChunks)
            );
        }

        private void fail(Throwable throwable) {
            if (!stopped.compareAndSet(false, true)) return;
            GenerationPass pass = currentPass;
            if (pass != null) pass.stop();
            failSession(this, unwrap(throwable));
        }

        private void stop() {
            stopped.set(true);
            GenerationPass pass = currentPass;
            if (pass != null) pass.stop();
        }
    }

    private static final class GenerationPass {
        private final LoadSession session;
        private final DimensionPlan plan;
        private final int repairPass;
        private final Runnable completion;
        private final Object queueLock = new Object();
        private final AtomicInteger queuedCount = new AtomicInteger();
        private final AtomicLong generatedCount = new AtomicLong();
        private final AtomicLong errorCount = new AtomicLong();
        private final AtomicLong skippedCount = new AtomicLong();
        private final AtomicLong processedCount = new AtomicLong();
        private volatile boolean stopped;
        private boolean completed;
        private int nextIndex;

        private GenerationPass(LoadSession session, DimensionPlan plan, int repairPass, Runnable completion) {
            this.session = session;
            this.plan = plan;
            this.repairPass = repairPass;
            this.completion = completion;
        }

        private void start() {
            schedulePump();
        }

        private void stop() {
            synchronized (queueLock) {
                stopped = true;
            }
        }

        private void schedulePump() {
            CompletableFuture.runAsync(this::tryEnqueueTasks, Util.backgroundExecutor())
                    .exceptionally(throwable -> {
                        session.fail(throwable);
                        return null;
                    });
        }

        private void tryEnqueueTasks() {
            synchronized (queueLock) {
                if (stopped || session.stopped.get() || completed) return;
                int enqueueCount = BATCH_SIZE - queuedCount.get();
                if (enqueueCount <= 0) return;

                List<ChunkPos> chunks = collectChunks(enqueueCount);
                if (!chunks.isEmpty()) {
                    queuedCount.addAndGet(chunks.size());
                    session.server.submit(() -> enqueueChunks(chunks));
                    return;
                }

                if (nextIndex >= plan.chunks().size() && queuedCount.get() == 0) {
                    completed = true;
                    session.server.submit(completion);
                }
            }
        }

        private List<ChunkPos> collectChunks(int count) {
            List<ChunkPos> chunks = new ArrayList<>(count);
            while (chunks.size() < count && nextIndex < plan.chunks().size()) {
                ChunkPos chunkPos = plan.chunks().get(nextIndex++);
                if (isChunkFullyGenerated(plan.level(), chunkPos)) {
                    long skipped = skippedCount.incrementAndGet();
                    long processed = processedCount.incrementAndGet();
                    session.recordProcessed(plan, repairPass, processed, plan.chunks().size(),
                            generatedCount.get(), errorCount.get(), skipped);
                    continue;
                }
                chunks.add(chunkPos);
            }
            return chunks;
        }

        private void enqueueChunks(List<ChunkPos> chunks) {
            if (stopped || session.stopped.get()) {
                queuedCount.addAndGet(-chunks.size());
                return;
            }
            ServerChunkCache chunkSource = plan.level().getChunkSource();
            for (ChunkPos chunkPos : chunks) {
                chunkSource.addTicketWithRadius(NeoForgeMod.GENERATE_FORCED_TICKET.value(), chunkPos, 0);
            }
            chunkSource.tick(() -> false, true);
            ChunkMap chunkMap = chunkSource.chunkMap;

            for (ChunkPos chunkPos : chunks) {
                long packed = ChunkPos.pack(chunkPos.x(), chunkPos.z());
                ChunkHolder holder = chunkMap.getVisibleChunkIfPresent(packed);
                if (holder == null) {
                    AfterTheEnd.LOGGER.warn("Added generation ticket for chunk but no holder was created: dimension={}, chunk=({}, {}).",
                            plan.dimension().identifier(), chunkPos.x(), chunkPos.z());
                    acceptChunkResult(chunkPos, ChunkHolder.UNLOADED_CHUNK);
                    continue;
                }
                holder.scheduleChunkGenerationTask(ChunkStatus.FULL, chunkMap).whenCompleteAsync((result, throwable) -> {
                    if (throwable == null) {
                        acceptChunkResult(chunkPos, result);
                    } else {
                        AfterTheEnd.LOGGER.warn("Unexpected error while generating chunk: dimension={}, chunk=({}, {}).",
                                plan.dimension().identifier(), chunkPos.x(), chunkPos.z(), throwable);
                        acceptChunkResult(chunkPos, ChunkHolder.UNLOADED_CHUNK);
                    }
                }, runnable -> chunkMap.scheduleOnMainThreadMailbox(runnable));
            }
        }

        private void acceptChunkResult(ChunkPos chunkPos, ChunkResult<ChunkAccess> result) {
            session.server.submit(() -> plan.level().getChunkSource().removeTicketWithRadius(
                    NeoForgeMod.GENERATE_FORCED_TICKET.value(), chunkPos, 0
            ));

            long processed = processedCount.incrementAndGet();
            if (result.isSuccess()) {
                long generated = generatedCount.incrementAndGet();
                session.recordGenerated(plan, repairPass, processed, plan.chunks().size(),
                        generated, errorCount.get(), skippedCount.get());
            } else {
                long errors = errorCount.incrementAndGet();
                session.recordGenerationError(plan, repairPass, processed, plan.chunks().size(),
                        generatedCount.get(), errors, skippedCount.get());
            }

            int queued = queuedCount.decrementAndGet();
            if (!stopped && !session.stopped.get() && queued <= QUEUE_THRESHOLD) schedulePump();
        }
    }

    private static double percentage(long current, long total) {
        if (total <= 0L) return 100.0D;
        return Math.round(current * 1000.0D / total) / 10.0D;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }
}
