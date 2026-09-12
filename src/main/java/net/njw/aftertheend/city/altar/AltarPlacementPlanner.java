package net.njw.aftertheend.city.altar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.CityRegion;

final class AltarPlacementPlanner {
    private static final int FPS_CANDIDATE_COUNT = 64;
    private static final int FPS_RESERVE_CANDIDATE_COUNT = 32;
    private static final int VIRTUAL_SURFACE_BENCHMARK_SAMPLES = 6;
    private static final int VIRTUAL_WORKER_COUNT = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    private static final AtomicInteger VIRTUAL_THREAD_SEQUENCE = new AtomicInteger();
    private static ExecutorService virtualExecutor;
    private static final int SMALL_REFINE_MIN_CANDIDATES = 24;
    private static final int SMALL_REFINE_MULTIPLIER = 4;
    private static final int CENTER_AXIS_SAMPLES = 4;
    private static final int OPTIMIZER_RESTARTS = 16;
    private static final int OPTIMIZER_MAX_PASSES = 12;
    private static final int CITY_EDGE_MARGIN = 8;
    private static final int MIN_STRUCTURE_GAP = 12;
    private static final double QUARTER_LIMIT = 0.25;
    private static final double EFFECTIVE_REJECT_LIMIT = 0.30;
    private static final double EFFECTIVE_REJECT_PENALTY = 1_000_000.0;
    private static final double OVERLAP_PENALTY = 100_000_000.0;
    private static final double MIN_DISTANCE_WEIGHT = 260.0;
    private static final double PAIR_DISTANCE_WEIGHT = 18.0;
    private static final double AVERAGE_DISTANCE_REWARD = 3.0;
    private static final double ACTUAL_ABSOLUTE_SCORE_LIMIT = 2_000.0;
    private static final long OPTIMIZER_SEED_SALT = 0x6a09e667f3bcc909L;

    private static final TemplateSize SMALL = new TemplateSize(11, 7);
    private static final TemplateSize LARGE = new TemplateSize(27, 10);
    private static final int DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT = 3;
    private static final int LARGE_VIRTUAL_SAMPLE_AXIS_COUNT = 5;
    private static final int FOOTPRINT_SAMPLE_COUNT = DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT * DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT;
    private static final Map<FootprintStencilKey, FootprintStencil> FOOTPRINT_STENCILS = new ConcurrentHashMap<>();

    private AltarPlacementPlanner() { }

    private static synchronized ExecutorService virtualExecutor() {
        if (virtualExecutor == null || virtualExecutor.isShutdown()) {
            virtualExecutor = Executors.newFixedThreadPool(
                    VIRTUAL_WORKER_COUNT,
                    runnable -> {
                        Thread thread = new Thread(runnable, "after-the-end-altar-virtual-" + VIRTUAL_THREAD_SEQUENCE.incrementAndGet());
                        thread.setDaemon(true);
                        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
                        return thread;
                    }
            );
        }
        return virtualExecutor;
    }

    static synchronized void shutdownVirtualExecutor() {
        ExecutorService executor = virtualExecutor;
        virtualExecutor = null;
        if (executor != null) executor.shutdownNow();
    }

    static PreparationSession beginPreparation(ServerLevel level, CityRegion region, List<Request> requests, long seed, UUID cityId) {
        if (requests.isEmpty()) throw new IllegalArgumentException("Altar preparation requires at least one request.");
        int count = requests.size();
        boolean hasLarge = requests.stream().anyMatch(Request::large);
        List<ChunkSeed> allSampledChunks = farthestPointSampleChunks(
                region, seed, FPS_CANDIDATE_COUNT + FPS_RESERVE_CANDIDATE_COUNT
        );
        int initialCandidateCount = Math.min(FPS_CANDIDATE_COUNT, allSampledChunks.size());
        List<ChunkSeed> sampledChunks = List.copyOf(allSampledChunks.subList(0, initialCandidateCount));
        List<ChunkSeed> reserveChunks = List.copyOf(allSampledChunks.subList(initialCandidateCount, allSampledChunks.size()));
        SearchBounds smallBounds = searchBounds(region, SMALL);
        SearchBounds largeBounds = searchBounds(region, LARGE);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        VirtualSurfaceMode virtualSurfaceMode = chooseVirtualSurfaceMode(
                level, generator, randomState, sampledChunks, cityId
        );
        Map<Long, SurfaceSample> virtualSurfaceCache = new ConcurrentHashMap<>();
        List<Candidate> candidates = new ArrayList<>(sampledChunks.size() + reserveChunks.size());

        long coarseStarted = System.nanoTime();
        List<CompletableFuture<VirtualSiteEvaluation>> coarseFutures = new ArrayList<>(sampledChunks.size());
        for (int index = 0; index < sampledChunks.size(); index++) {
            final int candidateIndex = index;
            ChunkSeed chunk = sampledChunks.get(index);
            coarseFutures.add(CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                Site site = evaluateVirtualSiteCoarse(
                        level, generator, randomState, virtualSurfaceCache, chunk, SMALL, smallBounds, virtualSurfaceMode
                );
                return new VirtualSiteEvaluation(candidateIndex, site, elapsedSeconds(started));
            }, virtualExecutor()));
        }
        for (int index = 0; index < sampledChunks.size(); index++) {
            ChunkSeed chunk = sampledChunks.get(index);
            VirtualSiteEvaluation evaluation = joinVirtual(coarseFutures.get(index), cityId, "Small coarse");
            candidates.add(new Candidate(chunk, evaluation.site()));
            AfterTheEnd.LOGGER.debug(
                    "[{}/{}] {}sec chunk=({}, {}) small-coarse={} city={}",
                    index + 1, sampledChunks.size(), formatSeconds(evaluation.seconds()),
                    chunk.chunkX(), chunk.chunkZ(), format(evaluation.site().score()), cityId
            );
        }
        double targetDistance = preferredDistance(region, count);
        AfterTheEnd.LOGGER.debug(
                "Altar planner {}: coarse-evaluated {} Small FPS candidates in {}sec using {} virtual worker(s).",
                cityId, candidates.size(), formatSeconds(elapsedSeconds(coarseStarted)), VIRTUAL_WORKER_COUNT
        );

        RandomSource coarseRandom = RandomSource.create(mix(seed ^ OPTIMIZER_SEED_SALT ^ 0x510e527fade682d1L));
        SelectionResult coarseSelection = optimizeSmallSelection(candidates, count, hasLarge, targetDistance, coarseRandom);
        if (coarseSelection == null) throw new IllegalStateException("Virtual coarse Altar selection unexpectedly failed.");
        int refineTarget = Math.min(candidates.size(), Math.max(SMALL_REFINE_MIN_CANDIDATES, count * SMALL_REFINE_MULTIPLIER));
        boolean[] refine = new boolean[candidates.size()];
        int refineCount = 0;
        for (int index : coarseSelection.selected()) {
            if (!refine[index]) {
                refine[index] = true;
                refineCount++;
            }
        }
        List<Integer> coarseRank = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) coarseRank.add(index);
        coarseRank.sort(Comparator.comparingDouble(index -> candidates.get(index).coarseSmall.score()));
        for (int index : coarseRank) {
            if (refineCount >= refineTarget) break;
            if (!refine[index]) {
                refine[index] = true;
                refineCount++;
            }
        }
        List<Integer> refineIndices = new ArrayList<>(refineTarget);
        for (int index = 0; index < candidates.size(); index++) if (refine[index]) refineIndices.add(index);
        long refineStarted = System.nanoTime();
        List<CompletableFuture<VirtualSiteEvaluation>> refineFutures = new ArrayList<>(refineIndices.size());
        for (int candidateIndex : refineIndices) {
            Candidate candidate = candidates.get(candidateIndex);
            refineFutures.add(CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                Site site = evaluateVirtualSite(
                        level, generator, randomState, virtualSurfaceCache, candidate.chunk, SMALL, smallBounds,
                        virtualSurfaceMode, DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT
                );
                return new VirtualSiteEvaluation(candidateIndex, site, elapsedSeconds(started));
            }, virtualExecutor()));
        }
        for (int position = 0; position < refineIndices.size(); position++) {
            int candidateIndex = refineIndices.get(position);
            Candidate candidate = candidates.get(candidateIndex);
            VirtualSiteEvaluation evaluation = joinVirtual(refineFutures.get(position), cityId, "Small refine");
            candidate.virtualSmall = evaluation.site();
            candidate.smallRefined = true;
            AfterTheEnd.LOGGER.debug(
                    "[{}/{}] {}sec chunk=({}, {}) small-refine={} city={}",
                    position + 1, refineTarget, formatSeconds(evaluation.seconds()), candidate.chunk.chunkX(),
                    candidate.chunk.chunkZ(), format(candidate.virtualSmall.score()), cityId
            );
        }
        AfterTheEnd.LOGGER.debug(
                "Altar planner {}: refined {} of {} Small candidates in {}sec.",
                cityId, refineIndices.size(), candidates.size(), formatSeconds(elapsedSeconds(refineStarted))
        );
        return new PreparationSession(
                level, List.copyOf(requests), seed, cityId, count, hasLarge, smallBounds, largeBounds,
                generator, randomState, virtualSurfaceCache, candidates, new ArrayList<>(reserveChunks),
                virtualSurfaceMode, targetDistance
        );
    }

    static PreparationStep advancePreparation(PreparationSession session) {
        if (session.completedPlans != null) return PreparationStep.complete(session.completedPlans);
        while (true) {
            if (session.selection == null) {
                RandomSource random = RandomSource.create(mix(
                        session.seed ^ OPTIMIZER_SEED_SALT ^ ((long) session.optimizationRound++ * 0x9e3779b97f4a7c15L)
                ));
                session.selection = optimizeSmallSelection(
                        session.candidates, session.count, session.hasLarge, session.targetDistance, random
                );
                if (session.selection == null) {
                    if (expandReserveCandidates(session)) continue;
                    if (!session.degradedMode) {
                        enableDegradedMode(session);
                        continue;
                    }
                    throw new IllegalStateException(
                            "No structurally safe Altar candidate combination remains after reserve expansion and degraded terrain fallback."
                    );
                }
            }
            SelectionResult selection = session.selection;

            List<Integer> selectedToRefine = new ArrayList<>();
            for (int candidateIndex : selection.selected()) {
                Candidate candidate = session.candidates.get(candidateIndex);
                if (!candidate.smallRefined && !candidate.smallExactEvaluated) selectedToRefine.add(candidateIndex);
            }
            if (!selectedToRefine.isEmpty()) {
                refineSmallCandidates(session, selectedToRefine, "small-refine-selected");
                session.selection = null;
                continue;
            }

            int rejectedSmall = firstRejectedSmall(selection, session.candidates);
            if (rejectedSmall >= 0) {
                Candidate candidate = session.candidates.get(rejectedSmall);
                AfterTheEnd.LOGGER.debug(
                        "Altar planner {}: Small exact candidate sample={} chunk=({}, {}) rejected; re-optimizing.",
                        session.cityId, candidate.chunk.sampleIndex(), candidate.chunk.chunkX(), candidate.chunk.chunkZ()
                );
                session.selection = null;
                continue;
            }
            for (int candidateIndex : selection.selected()) {
                Candidate candidate = session.candidates.get(candidateIndex);
                if (!candidate.smallExactEvaluated) return requestExact(session, candidateIndex, ExactRole.SMALL);
            }

            if (session.hasLarge) {
                List<Integer> largeToEvaluate = new ArrayList<>();
                for (int candidateIndex : selection.selected()) {
                    if (session.candidates.get(candidateIndex).virtualLarge == null) largeToEvaluate.add(candidateIndex);
                }
                if (!largeToEvaluate.isEmpty()) evaluateLargeCandidates(session, selection.selected(), largeToEvaluate);

                AssignmentScore largeAssignment = bestLargeAssignment(selection.selected(), session.candidates, session.targetDistance);
                if (largeAssignment.largeCandidateIndex() < 0) {
                    AfterTheEnd.LOGGER.debug(
                            "Altar planner {}: selected candidates failed Large exact validation; reselecting.",
                            session.cityId
                    );
                    session.selection = null;
                    continue;
                }
                selection = new SelectionResult(
                        selection.selected(), largeAssignment.largeCandidateIndex(), largeAssignment.objective()
                );
                session.selection = selection;
                Candidate largeCandidate = session.candidates.get(selection.largeCandidateIndex());
                if (!largeCandidate.largeExactEvaluated) return requestExact(session, selection.largeCandidateIndex(), ExactRole.LARGE);
                if (largeCandidate.usableLarge() == null) {
                    session.selection = new SelectionResult(
                            selection.selected(), -1, evaluateSmallObjective(
                                    selection.selected(), session.candidates, session.targetDistance, session.hasLarge
                            )
                    );
                    continue;
                }
            }

            List<Plan> plans = buildPlans(session.requests, selection, session.candidates);
            session.completedPlans = List.copyOf(plans);
            AfterTheEnd.LOGGER.info(
                    "Altar planning completed: city={}, structures={}, exactEvaluations={}, degraded={}",
                    session.cityId, session.count, session.exactEvaluations, session.degradedMode
            );
            logAccuracy(session, "small", session.accuracy.small, DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT);
            if (session.hasLarge || session.accuracy.large.fastAttempts > 0) {
                logAccuracy(session, "large", session.accuracy.large, LARGE_VIRTUAL_SAMPLE_AXIS_COUNT);
            }
            for (Plan plan : session.completedPlans) {
                AfterTheEnd.LOGGER.debug(
                        "Altar plan city={} spec={} {} chunk=({}, {}) center=({}, {}, {}) terrain={} buried={} floating={} water={}",
                        session.cityId, plan.specIndex(), plan.large() ? "large" : "small", plan.chunkX(), plan.chunkZ(),
                        plan.centerX(), plan.targetSurfaceY(), plan.centerZ(), format(plan.terrainScore()),
                        percent(plan.buriedFraction()), percent(plan.floatingFraction()), percent(plan.submergedFraction())
                );
            }
            return PreparationStep.complete(session.completedPlans);
        }
    }

    private static void refineSmallCandidates(PreparationSession session, List<Integer> candidateIndices, String logName) {
        List<CompletableFuture<VirtualSiteEvaluation>> futures = new ArrayList<>(candidateIndices.size());
        for (int candidateIndex : candidateIndices) {
            Candidate candidate = session.candidates.get(candidateIndex);
            futures.add(CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                Site site = evaluateVirtualSite(
                        session.level, session.generator, session.randomState, session.virtualSurfaceCache,
                        candidate.chunk, SMALL, session.smallBounds, session.virtualSurfaceMode,
                        DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT
                );
                return new VirtualSiteEvaluation(candidateIndex, site, elapsedSeconds(started));
            }, virtualExecutor()));
        }
        for (int position = 0; position < candidateIndices.size(); position++) {
            int candidateIndex = candidateIndices.get(position);
            Candidate candidate = session.candidates.get(candidateIndex);
            VirtualSiteEvaluation evaluation = joinVirtual(futures.get(position), session.cityId, "Small on-demand refine");
            candidate.virtualSmall = evaluation.site();
            candidate.smallRefined = true;
            AfterTheEnd.LOGGER.debug(
                    "{} {}sec chunk=({}, {}) small={} city={}",
                    logName, formatSeconds(evaluation.seconds()), candidate.chunk.chunkX(), candidate.chunk.chunkZ(),
                    format(candidate.virtualSmall.score()), session.cityId
            );
        }
    }

    private static void evaluateLargeCandidates(PreparationSession session, int[] selected, List<Integer> candidateIndices) {
        List<CompletableFuture<VirtualSiteEvaluation>> futures = new ArrayList<>(candidateIndices.size());
        for (int candidateIndex : candidateIndices) {
            Candidate candidate = session.candidates.get(candidateIndex);
            futures.add(CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                Site site = evaluateVirtualSite(
                        session.level, session.generator, session.randomState, session.virtualSurfaceCache,
                        candidate.chunk, LARGE, session.largeBounds, session.virtualSurfaceMode,
                        LARGE_VIRTUAL_SAMPLE_AXIS_COUNT
                );
                return new VirtualSiteEvaluation(candidateIndex, site, elapsedSeconds(started));
            }, virtualExecutor()));
        }
        for (int i = 0; i < candidateIndices.size(); i++) {
            int candidateIndex = candidateIndices.get(i);
            Candidate candidate = session.candidates.get(candidateIndex);
            VirtualSiteEvaluation evaluation = joinVirtual(futures.get(i), session.cityId, "Large virtual evaluation");
            candidate.virtualLarge = evaluation.site();
            int position = 0;
            while (position < selected.length && selected[position] != candidateIndex) position++;
            AfterTheEnd.LOGGER.debug(
                    "[{}/{}] {}sec chunk=({}, {}) large-virtual={} grid={}x{} city={}",
                    position + 1, selected.length, formatSeconds(evaluation.seconds()), candidate.chunk.chunkX(),
                    candidate.chunk.chunkZ(), format(candidate.virtualLarge.score()), LARGE_VIRTUAL_SAMPLE_AXIS_COUNT,
                    LARGE_VIRTUAL_SAMPLE_AXIS_COUNT, session.cityId
            );
        }
    }

    private static VirtualSiteEvaluation joinVirtual(
            CompletableFuture<VirtualSiteEvaluation> future, UUID cityId, String phase
    ) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            throw new IllegalStateException(phase + " failed for city " + cityId, exception.getCause());
        }
    }

    private static boolean expandReserveCandidates(PreparationSession session) {
        if (session.reserveChunks.isEmpty()) return false;
        List<ChunkSeed> reserve = List.copyOf(session.reserveChunks);
        session.reserveChunks.clear();
        int startIndex = session.candidates.size();
        AfterTheEnd.LOGGER.warn(
                "Altar planner {} exhausted the strict {}-candidate pool; expanding by {} reserve candidate(s).",
                session.cityId, startIndex, reserve.size()
        );
        List<CompletableFuture<VirtualSiteEvaluation>> coarseFutures = new ArrayList<>(reserve.size());
        for (int i = 0; i < reserve.size(); i++) {
            final int candidateIndex = startIndex + i;
            ChunkSeed chunk = reserve.get(i);
            coarseFutures.add(CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                Site site = evaluateVirtualSiteCoarse(
                        session.level, session.generator, session.randomState, session.virtualSurfaceCache,
                        chunk, SMALL, session.smallBounds, session.virtualSurfaceMode
                );
                return new VirtualSiteEvaluation(candidateIndex, site, elapsedSeconds(started));
            }, virtualExecutor()));
        }
        for (int i = 0; i < reserve.size(); i++) {
            VirtualSiteEvaluation evaluation = joinVirtual(coarseFutures.get(i), session.cityId, "Reserve coarse evaluation");
            session.candidates.add(new Candidate(reserve.get(i), evaluation.site()));
        }
        List<Integer> refineIndices = new ArrayList<>(reserve.size());
        for (int index = startIndex; index < session.candidates.size(); index++) refineIndices.add(index);
        refineSmallCandidates(session, refineIndices, "small-refine-reserve");
        session.selection = null;
        return true;
    }

    private static void enableDegradedMode(PreparationSession session) {
        session.degradedMode = true;
        int revivedSmall = 0;
        int revivedLarge = 0;
        for (Candidate candidate : session.candidates) {
            if (candidate.smallExactEvaluated && candidate.exactSmall != null && !candidate.smallReject.isEmpty()) {
                candidate.smallReject = "";
                revivedSmall++;
            }
            if (candidate.largeExactEvaluated && candidate.exactLarge != null && !candidate.largeReject.isEmpty()) {
                candidate.largeReject = "";
                revivedLarge++;
            }
        }
        session.selection = null;
        AfterTheEnd.LOGGER.warn(
                "Altar planner {} entering degraded terrain fallback: revived small={}, large={}. Structure and block-entity collisions remain hard rejects.",
                session.cityId, revivedSmall, revivedLarge
        );
    }

    private static PreparationStep requestExact(PreparationSession session, int candidateIndex, ExactRole role) {
        Candidate candidate = session.candidates.get(candidateIndex);
        ExactMode mode = switch (role) {
            case SMALL -> candidate.smallFallbackRequired ? ExactMode.FULL_SEARCH : ExactMode.FAST_CENTER;
            case LARGE -> candidate.largeFallbackRequired ? ExactMode.FULL_SEARCH : ExactMode.FAST_CENTER;
        };
        recordExactAttempt(session, candidate, role, mode);
        session.pendingExactCandidateIndex = candidateIndex;
        session.pendingExactRole = role;
        session.pendingExactMode = mode;
        session.pendingChunkStatus = structurePrechecked(candidate, role, mode)
                ? ChunkStatus.FULL
                : ChunkStatus.STRUCTURE_REFERENCES;
        return PreparationStep.candidate(candidateIndex, session.pendingChunkStatus, requiredChunks(session, candidateIndex));
    }

    private static void recordExactAttempt(PreparationSession session, Candidate candidate, ExactRole role, ExactMode mode) {
        RoleAccuracy accuracy = session.accuracy.forRole(role);
        if (role == ExactRole.SMALL) {
            if (mode == ExactMode.FAST_CENTER) {
                if (candidate.smallFastAttemptCounted) return;
                candidate.smallFastAttemptCounted = true;
                accuracy.fastAttempts++;
            } else {
                if (candidate.smallFullSearchAttemptCounted) return;
                candidate.smallFullSearchAttemptCounted = true;
                accuracy.fullSearchAttempts++;
            }
        } else if (mode == ExactMode.FAST_CENTER) {
            if (candidate.largeFastAttemptCounted) return;
            candidate.largeFastAttemptCounted = true;
            accuracy.fastAttempts++;
        } else {
            if (candidate.largeFullSearchAttemptCounted) return;
            candidate.largeFullSearchAttemptCounted = true;
            accuracy.fullSearchAttempts++;
        }
    }

    private static boolean structurePrechecked(Candidate candidate, ExactRole role, ExactMode mode) {
        return switch (role) {
            case SMALL -> mode == ExactMode.FAST_CENTER ? candidate.smallFastStructurePrechecked : candidate.smallFullStructurePrechecked;
            case LARGE -> mode == ExactMode.FAST_CENTER ? candidate.largeFastStructurePrechecked : candidate.largeFullStructurePrechecked;
        };
    }

    private static void markStructurePrechecked(Candidate candidate, ExactRole role, ExactMode mode) {
        if (role == ExactRole.SMALL) {
            if (mode == ExactMode.FAST_CENTER) candidate.smallFastStructurePrechecked = true;
            else candidate.smallFullStructurePrechecked = true;
        } else {
            if (mode == ExactMode.FAST_CENTER) candidate.largeFastStructurePrechecked = true;
            else candidate.largeFullStructurePrechecked = true;
        }
    }

    private static int firstRejectedSmall(SelectionResult selection, List<Candidate> candidates) {
        for (int index : selection.selected()) {
            Candidate candidate = candidates.get(index);
            if (candidate.smallExactEvaluated && candidate.usableSmall() == null) return index;
        }
        return -1;
    }

    static List<ChunkPos> requiredChunks(PreparationSession session, int candidateIndex) {
        if (session.pendingExactCandidateIndex != candidateIndex || session.pendingExactRole == null
                || session.pendingExactMode == null || session.pendingChunkStatus == null) {
            throw new IllegalStateException("No exact Altar evaluation is pending for candidate " + candidateIndex);
        }
        Candidate candidate = session.candidates.get(candidateIndex);
        if (session.pendingExactMode == ExactMode.FULL_SEARCH) return fullSearchChunks(candidate);
        Site virtualSite = session.pendingExactRole == ExactRole.SMALL ? candidate.virtualSmall : candidate.virtualLarge;
        TemplateSize size = session.pendingExactRole == ExactRole.SMALL ? SMALL : LARGE;
        if (virtualSite == null) throw new IllegalStateException("Fast exact Altar evaluation requires a virtual site.");
        return footprintChunks(virtualSite.centerX(), virtualSite.centerZ(), size);
    }

    private static List<ChunkPos> fullSearchChunks(Candidate candidate) {
        List<ChunkPos> chunks = new ArrayList<>(9);
        for (int radius = 0; radius <= 1; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    chunks.add(new ChunkPos(candidate.chunk.chunkX() + dx, candidate.chunk.chunkZ() + dz));
                }
            }
        }
        return List.copyOf(chunks);
    }

    private static List<ChunkPos> footprintChunks(int centerX, int centerZ, TemplateSize size) {
        int half = size.width() / 2;
        int minX = centerX - half;
        int maxX = minX + size.width() - 1;
        int minZ = centerZ - half;
        int maxZ = minZ + size.width() - 1;
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;
        List<ChunkPos> chunks = new ArrayList<>((maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1));
        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;
        for (int radius = 0; radius <= 2; radius++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    if (Math.max(Math.abs(chunkX - centerChunkX), Math.abs(chunkZ - centerChunkZ)) != radius) continue;
                    chunks.add(new ChunkPos(chunkX, chunkZ));
                }
            }
        }
        return List.copyOf(chunks);
    }

    static List<ChunkPos> missingRequiredChunks(PreparationSession session, int candidateIndex) {
        if (session.pendingChunkStatus == null) throw new IllegalStateException("No chunk status is pending.");
        List<ChunkPos> missing = new ArrayList<>();
        for (ChunkPos chunk : requiredChunks(session, candidateIndex)) {
            if (session.pendingChunkStatus == ChunkStatus.FULL) {
                if (session.level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) missing.add(chunk);
            } else if (session.level.getChunkSource().getChunk(
                    chunk.x(), chunk.z(), session.pendingChunkStatus, false
            ) == null) {
                missing.add(chunk);
            }
        }
        return List.copyOf(missing);
    }

    static void exactEvaluateLoaded(PreparationSession session, int candidateIndex) {
        List<ChunkPos> missing = missingRequiredChunks(session, candidateIndex);
        if (!missing.isEmpty()) throw new IllegalStateException("Exact Altar evaluation requires prepared chunks: " + missing);
        if (session.pendingExactRole == null || session.pendingExactMode == null || session.pendingChunkStatus == null
                || session.pendingExactCandidateIndex != candidateIndex) {
            throw new IllegalStateException("Unexpected exact Altar evaluation request for candidate " + candidateIndex);
        }
        Candidate candidate = session.candidates.get(candidateIndex);
        ExactRole role = session.pendingExactRole;
        ExactMode mode = session.pendingExactMode;
        RoleAccuracy accuracy = session.accuracy.forRole(role);
        TemplateSize size = role == ExactRole.SMALL ? SMALL : LARGE;
        SearchBounds bounds = role == ExactRole.SMALL ? session.smallBounds : session.largeBounds;
        Site virtualSite = role == ExactRole.SMALL ? candidate.virtualSmall : candidate.virtualLarge;

        if (session.pendingChunkStatus == ChunkStatus.STRUCTURE_REFERENCES) {
            long started = System.nanoTime();
            boolean clear = structurePrecheckPasses(session.level, candidate, virtualSite, size, bounds, mode);
            if (clear) {
                markStructurePrechecked(candidate, role, mode);
                AfterTheEnd.LOGGER.debug(
                        "Altar structure precheck sample={} chunk=({}, {}) {}sec {} {}=pass city={}",
                        candidate.chunk.sampleIndex(), candidate.chunk.chunkX(), candidate.chunk.chunkZ(),
                        formatSeconds(elapsedSeconds(started)), mode == ExactMode.FAST_CENTER ? "fast" : "fallback",
                        role == ExactRole.SMALL ? "small" : "large", session.cityId
                );
            } else if (mode == ExactMode.FAST_CENTER) {
                accuracy.structureFallbacks++;
                if (role == ExactRole.SMALL) candidate.smallFallbackRequired = true;
                else candidate.largeFallbackRequired = true;
                AfterTheEnd.LOGGER.debug(
                        "Altar structure precheck sample={} chunk=({}, {}) {}sec {}=fallback reason=structure overlap city={}",
                        candidate.chunk.sampleIndex(), candidate.chunk.chunkX(), candidate.chunk.chunkZ(),
                        formatSeconds(elapsedSeconds(started)), role == ExactRole.SMALL ? "small" : "large", session.cityId
                );
            } else {
                accuracy.fullSearchRejects++;
                if (role == ExactRole.SMALL) {
                    candidate.smallExactEvaluated = true;
                    candidate.smallReject = "structure overlap";
                    candidate.exactSmall = null;
                } else {
                    candidate.largeExactEvaluated = true;
                    candidate.largeReject = "structure overlap";
                    candidate.exactLarge = null;
                }
                session.exactEvaluations++;
                AfterTheEnd.LOGGER.debug(
                        "Altar structure precheck sample={} chunk=({}, {}) {}sec {}=reject reason=structure overlap city={}",
                        candidate.chunk.sampleIndex(), candidate.chunk.chunkX(), candidate.chunk.chunkZ(),
                        formatSeconds(elapsedSeconds(started)), role == ExactRole.SMALL ? "small" : "large", session.cityId
                );
            }
            clearPendingExact(session);
            return;
        }

        long started = System.nanoTime();
        ActualSiteResult result = mode == ExactMode.FAST_CENTER
                ? evaluateActualSiteAtCenter(session.level, virtualSite, size, bounds)
                : evaluateActualSite(session.level, candidate.chunk, size, bounds);
        String reject = exactRejectReason(result, role == ExactRole.SMALL ? "small" : "large", session.degradedMode);
        session.exactEvaluations++;

        if (mode == ExactMode.FAST_CENTER) recordFastComparison(session, role, virtualSite, result, reject);
        else if (!reject.isEmpty()) accuracy.fullSearchRejects++;

        if (mode == ExactMode.FAST_CENTER && !reject.isEmpty()) {
            if (role == ExactRole.SMALL) candidate.smallFallbackRequired = true;
            else candidate.largeFallbackRequired = true;
            AfterTheEnd.LOGGER.debug(
                    "Altar exact fast sample={} chunk=({}, {}) {}sec {}=fallback reason={} city={}",
                    candidate.chunk.sampleIndex(), candidate.chunk.chunkX(), candidate.chunk.chunkZ(),
                    formatSeconds(elapsedSeconds(started)), role == ExactRole.SMALL ? "small" : "large", reject, session.cityId
            );
        } else {
            if (role == ExactRole.SMALL) {
                candidate.exactSmall = result.site();
                candidate.smallReject = reject;
                candidate.smallExactEvaluated = true;
                candidate.smallFallbackRequired = false;
            } else {
                candidate.exactLarge = result.site();
                candidate.largeReject = reject;
                candidate.largeExactEvaluated = true;
                candidate.largeFallbackRequired = false;
            }
            Site exactSite = result.site();
            AfterTheEnd.LOGGER.debug(
                    "Altar exact {} sample={} chunk=({}, {}) {}sec {}={}{} city={}",
                    mode == ExactMode.FAST_CENTER ? "fast" : "fallback", candidate.chunk.sampleIndex(),
                    candidate.chunk.chunkX(), candidate.chunk.chunkZ(), formatSeconds(elapsedSeconds(started)),
                    role == ExactRole.SMALL ? "small" : "large", exactSite == null ? "null" : format(exactSite.score()),
                    reject.isEmpty() ? "" : " reject=" + reject, session.cityId
            );
        }
        clearPendingExact(session);
    }

    private static void recordFastComparison(
            PreparationSession session,
            ExactRole role,
            Site virtualSite,
            ActualSiteResult result,
            String reject
    ) {
        RoleAccuracy accuracy = session.accuracy.forRole(role);
        if (reject.isEmpty()) accuracy.fastSuccesses++;
        else if (result.site() != null) accuracy.terrainFallbacks++;
        else accuracy.otherFallbacks++;
        Site actualSite = result.site();
        if (virtualSite == null || actualSite == null) return;

        accuracy.comparisons++;
        double scoreError = Math.abs(actualSite.score() - virtualSite.score());
        accuracy.scoreAbsErrorSum += scoreError;
        accuracy.scoreAbsErrorMax = Math.max(accuracy.scoreAbsErrorMax, scoreError);
        TerrainAssessment virtualTerrain = virtualSite.terrain();
        TerrainAssessment actualTerrain = actualSite.terrain();
        double buriedError = Math.abs(actualTerrain.buriedFraction() - virtualTerrain.buriedFraction());
        double floatingError = Math.abs(actualTerrain.floatingFraction() - virtualTerrain.floatingFraction());
        double submergedError = Math.abs(actualTerrain.submergedFraction() - virtualTerrain.submergedFraction());
        accuracy.fractionAbsErrorSum += buriedError + floatingError + submergedError;
        accuracy.fractionAbsErrorMax = Math.max(
                accuracy.fractionAbsErrorMax, Math.max(buriedError, Math.max(floatingError, submergedError))
        );
        double virtualMaxFraction = maxRejectFraction(virtualTerrain);
        int bucket = accuracyBucket(virtualMaxFraction);
        accuracy.strictBucketComparisons[bucket]++;
        if (strictTerrainValid(actualSite)) accuracy.strictBucketPasses[bucket]++;

        AfterTheEnd.LOGGER.debug(
                "Altar virtual/exact accuracy city={} role={} virtualScore={} actualScore={} scoreError={} "
                        + "virtualFractions=({},{},{}) actualFractions=({},{},{}) strictPass={} productionReject={}",
                session.cityId, role == ExactRole.SMALL ? "small" : "large",
                format(virtualSite.score()), format(actualSite.score()), format(scoreError),
                percent(virtualTerrain.buriedFraction()), percent(virtualTerrain.floatingFraction()),
                percent(virtualTerrain.submergedFraction()), percent(actualTerrain.buriedFraction()),
                percent(actualTerrain.floatingFraction()), percent(actualTerrain.submergedFraction()),
                strictTerrainValid(actualSite), reject.isEmpty() ? "none" : reject
        );
    }

    private static boolean strictTerrainValid(Site site) {
        TerrainAssessment terrain = site.terrain();
        return terrain.buriedFraction() < EFFECTIVE_REJECT_LIMIT
                && terrain.floatingFraction() < EFFECTIVE_REJECT_LIMIT
                && terrain.submergedFraction() < EFFECTIVE_REJECT_LIMIT
                && site.score() <= ACTUAL_ABSOLUTE_SCORE_LIMIT;
    }

    private static double maxRejectFraction(TerrainAssessment terrain) {
        return Math.max(terrain.buriedFraction(), Math.max(terrain.floatingFraction(), terrain.submergedFraction()));
    }

    private static int accuracyBucket(double fraction) {
        if (fraction < 0.10) return 0;
        if (fraction < 0.15) return 1;
        if (fraction < 0.20) return 2;
        if (fraction < 0.25) return 3;
        if (fraction < 0.30) return 4;
        return 5;
    }

    private static void logAccuracy(PreparationSession session, String role, RoleAccuracy accuracy, int virtualAxisCount) {
        AfterTheEnd.LOGGER.info(
                "Altar virtual accuracy: city={}, role={}, virtualGrid={}x{}, exactGrid={}x{}, fastSuccess={}/{}, "
                        + "structureFallbacks={}, terrainFallbacks={}, otherFallbacks={}, fullSearchRejects={}/{}, "
                        + "comparisons={}, scoreMAE={}, scoreMax={}, fractionMAE={}, fractionMax={}, strictBuckets={}",
                session.cityId, role, virtualAxisCount, virtualAxisCount,
                DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT, DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT,
                accuracy.fastSuccesses, accuracy.fastAttempts, accuracy.structureFallbacks, accuracy.terrainFallbacks,
                accuracy.otherFallbacks, accuracy.fullSearchRejects, accuracy.fullSearchAttempts, accuracy.comparisons,
                meanOrNa(accuracy.scoreAbsErrorSum, accuracy.comparisons),
                accuracy.comparisons == 0 ? "n/a" : format(accuracy.scoreAbsErrorMax),
                meanOrNa(accuracy.fractionAbsErrorSum, accuracy.comparisons * 3),
                accuracy.comparisons == 0 ? "n/a" : percent(accuracy.fractionAbsErrorMax),
                strictBucketSummary(accuracy)
        );
    }

    private static String meanOrNa(double sum, int count) {
        return count == 0 ? "n/a" : format(sum / count);
    }

    private static String strictBucketSummary(RoleAccuracy accuracy) {
        String[] labels = {"<10", "10-15", "15-20", "20-25", "25-30", ">=30"};
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < labels.length; index++) {
            if (index > 0) builder.append(',');
            builder.append(labels[index]).append(':')
                    .append(accuracy.strictBucketPasses[index]).append('/')
                    .append(accuracy.strictBucketComparisons[index]);
        }
        return builder.append(']').toString();
    }

    private static void clearPendingExact(PreparationSession session) {
        session.pendingExactRole = null;
        session.pendingExactMode = null;
        session.pendingChunkStatus = null;
        session.pendingExactCandidateIndex = -1;
    }

    private static boolean structurePrecheckPasses(
            ServerLevel level,
            Candidate candidate,
            Site virtualSite,
            TemplateSize size,
            SearchBounds bounds,
            ExactMode mode
    ) {
        if (mode == ExactMode.FAST_CENTER) {
            return virtualSite != null && structureFreeAtCenter(level, virtualSite.centerX(), virtualSite.centerZ(), size);
        }
        int chunkMinX = candidate.chunk.chunkX() << 4;
        int chunkMinZ = candidate.chunk.chunkZ() << 4;
        int minX = Math.max(chunkMinX, bounds.minCenterX());
        int maxX = Math.min(chunkMinX + 15, bounds.maxCenterX());
        int minZ = Math.max(chunkMinZ, bounds.minCenterZ());
        int maxZ = Math.min(chunkMinZ + 15, bounds.maxCenterZ());
        if (minX > maxX || minZ > maxZ) return false;
        for (int centerX : sampledAxis(minX, maxX)) {
            for (int centerZ : sampledAxis(minZ, maxZ)) {
                if (structureFreeAtCenter(level, centerX, centerZ, size)) return true;
            }
        }
        return false;
    }

    private static boolean structureFreeAtCenter(ServerLevel level, int centerX, int centerZ, TemplateSize size) {
        int half = size.width() / 2;
        int minX = centerX - half;
        int maxX = minX + size.width() - 1;
        int minZ = centerZ - half;
        int maxZ = minZ + size.width() - 1;
        Set<StructureStart> seen = new HashSet<>();
        for (ChunkPos pos : footprintChunks(centerX, centerZ, size)) {
            ChunkAccess chunk = level.getChunkSource().getChunk(
                    pos.x(), pos.z(), ChunkStatus.STRUCTURE_REFERENCES, false
            );
            if (chunk == null) return false;
            for (StructureStart start : level.structureManager().startsForStructure(pos, structure -> true)) {
                if (start == null || !start.isValid() || !seen.add(start)) continue;
                BoundingBox box = start.getBoundingBox();
                if (box.maxX() >= minX && box.minX() <= maxX && box.maxZ() >= minZ && box.minZ() <= maxZ) return false;
            }
        }
        return true;
    }

    private static List<Plan> buildPlans(List<Request> requests, SelectionResult selection, List<Candidate> candidates) {
        List<Request> smallRequests = requests.stream().filter(request -> !request.large())
                .sorted(Comparator.comparingInt(Request::specIndex)).toList();
        Request largeRequest = requests.stream().filter(Request::large).findFirst().orElse(null);
        List<Integer> smallCandidates = Arrays.stream(selection.selected())
                .filter(index -> index != selection.largeCandidateIndex()).boxed()
                .sorted(Comparator.comparingInt(index -> candidates.get(index).chunk.sampleIndex())).toList();
        List<Plan> result = new ArrayList<>(requests.size());
        if (largeRequest != null) {
            if (selection.largeCandidateIndex() < 0) throw new IllegalStateException("Altar optimizer did not assign the large altar.");
            result.add(toPlan(largeRequest.specIndex(), candidates.get(selection.largeCandidateIndex()), true));
        }
        for (int i = 0; i < smallRequests.size(); i++) {
            result.add(toPlan(smallRequests.get(i).specIndex(), candidates.get(smallCandidates.get(i)), false));
        }
        result.sort(Comparator.comparingInt(Plan::specIndex));
        return result;
    }

    private static Plan toPlan(int specIndex, Candidate candidate, boolean large) {
        Site site;
        if (large) {
            if (!candidate.largeExactEvaluated || !candidate.largeReject.isEmpty()) {
                throw new IllegalStateException("Final Large Altar candidate was not exact-validated.");
            }
            site = candidate.exactLarge;
        } else {
            if (!candidate.smallExactEvaluated || !candidate.smallReject.isEmpty()) {
                throw new IllegalStateException("Final Small Altar candidate was not exact-validated.");
            }
            site = candidate.exactSmall;
        }
        if (site == null) throw new IllegalStateException("Final Altar candidate has no exact site.");
        TerrainAssessment terrain = site.terrain();
        return new Plan(
                specIndex, candidate.chunk.sampleIndex(), candidate.chunk.chunkX(), candidate.chunk.chunkZ(),
                site.centerX(), site.centerZ(), terrain.targetSurfaceY(), site.score(), terrain.buriedFraction(),
                terrain.floatingFraction(), terrain.submergedFraction(), large
        );
    }

    private static String exactRejectReason(ActualSiteResult actualResult, String role, boolean degradedMode) {
        if (actualResult.site() == null) return actualResult.rejectReason().isEmpty() ? role + " has no exact-valid center" : actualResult.rejectReason();
        if (degradedMode) return "";
        Site actual = actualResult.site();
        TerrainAssessment terrain = actual.terrain();
        if (terrain.buriedFraction() >= EFFECTIVE_REJECT_LIMIT
                || terrain.floatingFraction() >= EFFECTIVE_REJECT_LIMIT
                || terrain.submergedFraction() >= EFFECTIVE_REJECT_LIMIT) {
            return role + " exceeds 30% terrain rejection fraction";
        }
        if (actual.score() > ACTUAL_ABSOLUTE_SCORE_LIMIT) {
            return role + " actual score " + format(actual.score()) + " > " + format(ACTUAL_ABSOLUTE_SCORE_LIMIT);
        }
        return "";
    }

    private static Site evaluateVirtualSiteCoarse(
            ServerLevel level,
            ChunkGenerator generator,
            RandomState randomState,
            Map<Long, SurfaceSample> cache,
            ChunkSeed chunk,
            TemplateSize size,
            SearchBounds bounds,
            VirtualSurfaceMode virtualSurfaceMode
    ) {
        int chunkMinX = chunk.chunkX() << 4;
        int chunkMinZ = chunk.chunkZ() << 4;
        int minX = Math.max(chunkMinX, bounds.minCenterX());
        int maxX = Math.min(chunkMinX + 15, bounds.maxCenterX());
        int minZ = Math.max(chunkMinZ, bounds.minCenterZ());
        int maxZ = Math.min(chunkMinZ + 15, bounds.maxCenterZ());
        if (minX > maxX || minZ > maxZ) return virtualPenaltySite(level, chunk, bounds);
        int[] xs = sampledAxis(minX, maxX);
        int[] zs = sampledAxis(minZ, maxZ);
        int centerX = xs[xs.length / 2];
        int centerZ = zs[zs.length / 2];
        int half = size.width() / 2;
        int originX = centerX - half;
        int originZ = centerZ - half;
        int[] supportYs = new int[FOOTPRINT_SAMPLE_COUNT];
        int[] fluidTopYs = new int[FOOTPRINT_SAMPLE_COUNT];
        int sampleIndex = 0;
        for (int dxIndex = 0; dxIndex < DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT; dxIndex++) {
            int x = originX + footprintSampleOffset(size.width(), dxIndex, DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT);
            for (int dzIndex = 0; dzIndex < DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT; dzIndex++) {
                int z = originZ + footprintSampleOffset(size.width(), dzIndex, DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT);
                SurfaceSample sample = cache.computeIfAbsent(
                        packXZ(x, z), ignored -> readVirtualSurfaceSample(level, generator, randomState, x, z, virtualSurfaceMode)
                );
                if (sample == null) return virtualPenaltySite(level, chunk, bounds);
                supportYs[sampleIndex] = sample.supportY();
                fluidTopYs[sampleIndex++] = sample.fluidTopY();
            }
        }
        TerrainAssessment terrain = optimizeSurface(supportYs, fluidTopYs);
        return new Site(centerX, centerZ, terrain.score() + edgePenalty(bounds, centerX, centerZ), terrain);
    }

    private static Site evaluateVirtualSite(
            ServerLevel level,
            ChunkGenerator generator,
            RandomState randomState,
            Map<Long, SurfaceSample> cache,
            ChunkSeed chunk,
            TemplateSize size,
            SearchBounds bounds,
            VirtualSurfaceMode virtualSurfaceMode,
            int sampleAxisCount
    ) {
        List<Site> sites = evaluateTerrainSites(
                level, chunk, size, bounds,
                (x, z) -> cache.computeIfAbsent(
                        packXZ(x, z), ignored -> readVirtualSurfaceSample(level, generator, randomState, x, z, virtualSurfaceMode)
                ),
                sampleAxisCount
        );
        return sites.isEmpty() ? virtualPenaltySite(level, chunk, bounds) : sites.getFirst();
    }

    private static Site virtualPenaltySite(ServerLevel level, ChunkSeed chunk, SearchBounds bounds) {
        int x = clamp((chunk.chunkX() << 4) + 8, bounds.minCenterX(), bounds.maxCenterX());
        int z = clamp((chunk.chunkZ() << 4) + 8, bounds.minCenterZ(), bounds.maxCenterZ());
        TerrainAssessment terrain = new TerrainAssessment(
                level.getSeaLevel(), EFFECTIVE_REJECT_PENALTY * 10.0, 0.0, 0.0, 1.0, 0.0
        );
        return new Site(x, z, terrain.score(), terrain);
    }

    private static ActualSiteResult evaluateActualSiteAtCenter(
            ServerLevel level,
            Site virtualSite,
            TemplateSize size,
            SearchBounds bounds
    ) {
        if (virtualSite == null) return new ActualSiteResult(null, "missing virtual site");
        int centerX = virtualSite.centerX();
        int centerZ = virtualSite.centerZ();
        if (centerX < bounds.minCenterX() || centerX > bounds.maxCenterX()
                || centerZ < bounds.minCenterZ() || centerZ > bounds.maxCenterZ()) {
            return new ActualSiteResult(null, "virtual center outside search bounds");
        }
        int half = size.width() / 2;
        int originX = centerX - half;
        int originZ = centerZ - half;
        int[] supportYs = new int[FOOTPRINT_SAMPLE_COUNT];
        int[] fluidTopYs = new int[FOOTPRINT_SAMPLE_COUNT];
        int sampleIndex = 0;
        for (int dxIndex = 0; dxIndex < DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT; dxIndex++) {
            int x = originX + footprintSampleOffset(size.width(), dxIndex, DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT);
            for (int dzIndex = 0; dzIndex < DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT; dzIndex++) {
                int z = originZ + footprintSampleOffset(size.width(), dzIndex, DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT);
                SurfaceSample sample = readActualSurfaceSample(level, x, z);
                if (sample == null) return new ActualSiteResult(null, "no exact terrain sample at virtual center");
                supportYs[sampleIndex] = sample.supportY();
                fluidTopYs[sampleIndex++] = sample.fluidTopY();
            }
        }
        TerrainAssessment terrain = optimizeSurface(supportYs, fluidTopYs);
        Site site = new Site(centerX, centerZ, terrain.score() + edgePenalty(bounds, centerX, centerZ), terrain);
        if (hasStructureCollision(level, site, size)) return new ActualSiteResult(null, "structure overlap at virtual center");
        if (hasBlockEntityCollision(level, site, size)) return new ActualSiteResult(null, "block-entity overlap at virtual center");
        return new ActualSiteResult(site, "");
    }

    private static ActualSiteResult evaluateActualSite(ServerLevel level, ChunkSeed chunk, TemplateSize size, SearchBounds bounds) {
        List<Site> sites = evaluateTerrainSites(
                level, chunk, size, bounds, (x, z) -> readActualSurfaceSample(level, x, z),
                DEFAULT_FOOTPRINT_SAMPLE_AXIS_COUNT
        );
        if (sites.isEmpty()) return new ActualSiteResult(null, "no exact terrain samples");
        boolean structureCollision = false;
        boolean blockEntityCollision = false;
        for (Site site : sites) {
            if (hasStructureCollision(level, site, size)) {
                structureCollision = true;
                continue;
            }
            if (hasBlockEntityCollision(level, site, size)) {
                blockEntityCollision = true;
                continue;
            }
            return new ActualSiteResult(site, "");
        }
        if (structureCollision) return new ActualSiteResult(null, "structure overlap");
        if (blockEntityCollision) return new ActualSiteResult(null, "block-entity overlap");
        return new ActualSiteResult(null, "no exact-valid center");
    }

    private static List<Site> evaluateTerrainSites(
            ServerLevel level,
            ChunkSeed chunk,
            TemplateSize size,
            SearchBounds bounds,
            SurfaceReader reader,
            int sampleAxisCount
    ) {
        int chunkMinX = chunk.chunkX() << 4;
        int chunkMinZ = chunk.chunkZ() << 4;
        int minX = Math.max(chunkMinX, bounds.minCenterX());
        int maxX = Math.min(chunkMinX + 15, bounds.maxCenterX());
        int minZ = Math.max(chunkMinZ, bounds.minCenterZ());
        int maxZ = Math.min(chunkMinZ + 15, bounds.maxCenterZ());
        if (minX > maxX || minZ > maxZ) return List.of();
        FootprintStencil footprint = footprintStencil(
                minX - chunkMinX, maxX - chunkMinX, minZ - chunkMinZ, maxZ - chunkMinZ, size, sampleAxisCount
        );
        SurfaceSample[] surfaceSamples = new SurfaceSample[footprint.sampleXs().length];
        for (int sampleIndex = 0; sampleIndex < surfaceSamples.length; sampleIndex++) {
            surfaceSamples[sampleIndex] = reader.read(
                    chunkMinX + footprint.sampleXs()[sampleIndex],
                    chunkMinZ + footprint.sampleZs()[sampleIndex]
            );
        }
        int sampleCount = sampleAxisCount * sampleAxisCount;
        int[] supportYs = new int[sampleCount];
        int[] fluidTopYs = new int[sampleCount];
        List<Site> sites = new ArrayList<>(footprint.centerXs().length);
        for (int centerIndex = 0; centerIndex < footprint.centerXs().length; centerIndex++) {
            TerrainAssessment terrain = assessTerrain(footprint, surfaceSamples, centerIndex, supportYs, fluidTopYs);
            if (terrain == null) continue;
            int x = chunkMinX + footprint.centerXs()[centerIndex];
            int z = chunkMinZ + footprint.centerZs()[centerIndex];
            double score = terrain.score() + edgePenalty(bounds, x, z);
            sites.add(new Site(x, z, score, terrain));
        }
        sites.sort(Comparator.comparingDouble(Site::score));
        return sites;
    }

    private static VirtualSurfaceMode chooseVirtualSurfaceMode(
            ServerLevel level,
            ChunkGenerator generator,
            RandomState randomState,
            List<ChunkSeed> sampledChunks,
            UUID cityId
    ) {
        int count = Math.min(VIRTUAL_SURFACE_BENCHMARK_SAMPLES, sampledChunks.size());
        if (count == 0) return VirtualSurfaceMode.LEGACY_HEIGHTS;
        long legacyNanos = 0L;
        long columnNanos = 0L;
        boolean equivalent = true;
        for (int i = 0; i < count; i++) {
            ChunkSeed chunk = sampledChunks.get(i * sampledChunks.size() / count);
            int x = (chunk.chunkX() << 4) + 8;
            int z = (chunk.chunkZ() << 4) + 8;
            long started = System.nanoTime();
            SurfaceSample legacy = readVirtualSurfaceSampleLegacy(level, generator, randomState, x, z);
            legacyNanos += System.nanoTime() - started;
            started = System.nanoTime();
            SurfaceSample column = readVirtualSurfaceSampleColumn(level, generator, randomState, x, z);
            columnNanos += System.nanoTime() - started;
            if (legacy == null || column == null || !legacy.equals(column)) equivalent = false;
        }
        boolean useColumn = equivalent && columnNanos * 10L < legacyNanos * 9L;
        AfterTheEnd.LOGGER.debug(
                "Altar planner {}: virtual surface sampler={} benchmark samples={}, legacy={}ms, base-column={}ms, equivalent={}",
                cityId, useColumn ? VirtualSurfaceMode.BASE_COLUMN : VirtualSurfaceMode.LEGACY_HEIGHTS, count,
                String.format(Locale.ROOT, "%.2f", legacyNanos / 1_000_000.0),
                String.format(Locale.ROOT, "%.2f", columnNanos / 1_000_000.0), equivalent
        );
        return useColumn ? VirtualSurfaceMode.BASE_COLUMN : VirtualSurfaceMode.LEGACY_HEIGHTS;
    }

    private static SurfaceSample readVirtualSurfaceSample(
            ServerLevel level,
            ChunkGenerator generator,
            RandomState randomState,
            int x,
            int z,
            VirtualSurfaceMode mode
    ) {
        return mode == VirtualSurfaceMode.BASE_COLUMN
                ? readVirtualSurfaceSampleColumn(level, generator, randomState, x, z)
                : readVirtualSurfaceSampleLegacy(level, generator, randomState, x, z);
    }

    private static SurfaceSample readVirtualSurfaceSampleLegacy(
            ServerLevel level,
            ChunkGenerator generator,
            RandomState randomState,
            int x,
            int z
    ) {
        try {
            int worldSurface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
            int oceanFloor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
            return surfaceSampleFromHeights(level, worldSurface, oceanFloor);
        } catch (RuntimeException exception) {
            AfterTheEnd.LOGGER.debug("Virtual Altar terrain sample failed at ({}, {})", x, z, exception);
            return null;
        }
    }

    private static SurfaceSample readVirtualSurfaceSampleColumn(
            ServerLevel level,
            ChunkGenerator generator,
            RandomState randomState,
            int x,
            int z
    ) {
        try {
            NoiseColumn column = generator.getBaseColumn(x, z, level, randomState);
            Predicate<BlockState> worldSurfacePredicate = Heightmap.Types.WORLD_SURFACE_WG.isOpaque();
            Predicate<BlockState> oceanFloorPredicate = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
            int minY = generator.getMinY();
            int maxY = minY + generator.getGenDepth();
            int worldSurface = minY;
            int oceanFloor = minY;
            boolean foundWorldSurface = false;
            boolean foundOceanFloor = false;
            for (int y = maxY - 1; y >= minY && (!foundWorldSurface || !foundOceanFloor); y--) {
                BlockState state = column.getBlock(y);
                if (!foundWorldSurface && worldSurfacePredicate.test(state)) {
                    worldSurface = y + 1;
                    foundWorldSurface = true;
                }
                if (!foundOceanFloor && oceanFloorPredicate.test(state)) {
                    oceanFloor = y + 1;
                    foundOceanFloor = true;
                }
            }
            return surfaceSampleFromHeights(level, worldSurface, oceanFloor);
        } catch (RuntimeException exception) {
            AfterTheEnd.LOGGER.debug("Virtual Altar base-column sample failed at ({}, {})", x, z, exception);
            return null;
        }
    }

    private static SurfaceSample surfaceSampleFromHeights(ServerLevel level, int worldSurface, int oceanFloor) {
        int supportY = clamp(oceanFloor, level.getMinY() + 1, level.getMaxY());
        int fluidTopY = worldSurface > oceanFloor
                ? clamp(worldSurface - 1, level.getMinY(), level.getMaxY())
                : Integer.MIN_VALUE;
        return new SurfaceSample(supportY, fluidTopY);
    }

    private static SurfaceSample readActualSurfaceSample(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (top < level.getMinY()) return null;
        int fluidTopY = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, top, z);
        for (int y = top; y >= level.getMinY(); y--) {
            cursor.setY(y);
            BlockState state = level.getBlockState(cursor);
            if (!level.getFluidState(cursor).isEmpty()) {
                if (fluidTopY == Integer.MIN_VALUE) fluidTopY = y;
                continue;
            }
            if (state.isAir() || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.REPLACEABLE)) continue;
            return new SurfaceSample(y + 1, fluidTopY);
        }
        return null;
    }

    private static boolean hasStructureCollision(ServerLevel level, Site site, TemplateSize size) {
        BoundingBox altarBox = placementBox(level, site, size);
        int minChunkX = altarBox.minX() >> 4;
        int maxChunkX = altarBox.maxX() >> 4;
        int minChunkZ = altarBox.minZ() >> 4;
        int maxChunkZ = altarBox.maxZ() >> 4;
        Set<StructureStart> seen = new HashSet<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) return true;
                ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                for (StructureStart start : level.structureManager().startsForStructure(pos, structure -> true)) {
                    if (start == null || !start.isValid() || !seen.add(start)) continue;
                    if (start.getBoundingBox().intersects(altarBox)) return true;
                }
            }
        }
        return false;
    }

    private static boolean hasBlockEntityCollision(ServerLevel level, Site site, TemplateSize size) {
        BoundingBox altarBox = placementBox(level, site, size);
        int minChunkX = altarBox.minX() >> 4;
        int maxChunkX = altarBox.maxX() >> 4;
        int minChunkZ = altarBox.minZ() >> 4;
        int maxChunkZ = altarBox.maxZ() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) return true;
                for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                    if (pos.getX() >= altarBox.minX() && pos.getX() <= altarBox.maxX()
                            && pos.getY() >= altarBox.minY() && pos.getY() <= altarBox.maxY()
                            && pos.getZ() >= altarBox.minZ() && pos.getZ() <= altarBox.maxZ()) return true;
                }
            }
        }
        return false;
    }

    private static BoundingBox placementBox(ServerLevel level, Site site, TemplateSize size) {
        int half = size.width() / 2;
        int minX = site.centerX() - half;
        int minZ = site.centerZ() - half;
        int originY = site.terrain().targetSurfaceY() - 1;
        int maxY = Math.min(level.getMaxY(), originY + size.height() - 1);
        return new BoundingBox(
                minX, Math.max(level.getMinY(), originY), minZ,
                minX + size.width() - 1, maxY, minZ + size.width() - 1
        );
    }

    private static SelectionResult optimizeSmallSelection(
            List<Candidate> candidates,
            int count,
            boolean hasLarge,
            double targetDistance,
            RandomSource random
    ) {
        if (candidates.size() < count) throw new IllegalStateException("Not enough Altar candidates.");
        SelectionResult globalBest = null;
        int[] deterministic = deterministicInitialization(candidates, count);
        if (deterministic == null) return null;
        SelectionResult deterministicResult = improveBySmallSwaps(deterministic, candidates, hasLarge, targetDistance);
        if (Double.isFinite(deterministicResult.objective())) globalBest = deterministicResult;
        for (int restart = 1; restart < OPTIMIZER_RESTARTS; restart++) {
            int[] initial = randomFeasibleInitialization(candidates, count, random);
            if (initial == null) continue;
            SelectionResult local = improveBySmallSwaps(initial, candidates, hasLarge, targetDistance);
            if (Double.isFinite(local.objective()) && (globalBest == null || local.objective() < globalBest.objective())) globalBest = local;
        }
        if (globalBest == null) return null;
        return new SelectionResult(globalBest.selected(), -1, globalBest.objective());
    }

    private static int[] deterministicInitialization(List<Candidate> candidates, int count) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) if (candidates.get(i).usableSmall() != null) indices.add(i);
        indices.sort(Comparator.comparingDouble(index -> candidates.get(index).usableSmall().score()));
        if (indices.size() < count) return null;
        int[] selected = new int[count];
        for (int i = 0; i < count; i++) selected[i] = indices.get(i);
        return selected;
    }

    private static int[] randomFeasibleInitialization(List<Candidate> candidates, int count, RandomSource random) {
        int[] pool = new int[candidates.size()];
        int poolSize = 0;
        for (int i = 0; i < candidates.size(); i++) if (candidates.get(i).usableSmall() != null) pool[poolSize++] = i;
        if (poolSize < count) return null;
        for (int i = 0; i < count; i++) {
            int swap = i + random.nextInt(poolSize - i);
            int temp = pool[i];
            pool[i] = pool[swap];
            pool[swap] = temp;
        }
        return Arrays.copyOf(pool, count);
    }

    private static SelectionResult improveBySmallSwaps(
            int[] initial,
            List<Candidate> candidates,
            boolean hasLarge,
            double targetDistance
    ) {
        int[] current = Arrays.copyOf(initial, initial.length);
        double currentObjective = evaluateSmallObjective(current, candidates, targetDistance, hasLarge);
        for (int pass = 0; pass < OPTIMIZER_MAX_PASSES; pass++) {
            boolean[] chosen = new boolean[candidates.size()];
            for (int index : current) chosen[index] = true;
            double bestObjective = currentObjective;
            int bestSlot = -1;
            int bestCandidate = -1;
            for (int slot = 0; slot < current.length; slot++) {
                int old = current[slot];
                for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
                    if (chosen[candidateIndex] || candidates.get(candidateIndex).usableSmall() == null) continue;
                    current[slot] = candidateIndex;
                    double objective = evaluateSmallObjective(current, candidates, targetDistance, hasLarge);
                    if (objective + 1.0e-9 < bestObjective) {
                        bestObjective = objective;
                        bestSlot = slot;
                        bestCandidate = candidateIndex;
                    }
                }
                current[slot] = old;
            }
            if (bestSlot < 0) break;
            current[bestSlot] = bestCandidate;
            currentObjective = bestObjective;
        }
        return new SelectionResult(Arrays.copyOf(current, current.length), -1, currentObjective);
    }

    private static double evaluateSmallObjective(
            int[] selected,
            List<Candidate> candidates,
            double targetDistance,
            boolean hasLarge
    ) {
        if (hasLarge) {
            boolean allLargeFailed = true;
            for (int index : selected) {
                Candidate candidate = candidates.get(index);
                if (!candidate.largeExactEvaluated || candidate.largeReject.isEmpty()) {
                    allLargeFailed = false;
                    break;
                }
            }
            if (allLargeFailed) return Double.POSITIVE_INFINITY;
        }
        Site[] sites = new Site[selected.length];
        int[] widths = new int[selected.length];
        for (int i = 0; i < selected.length; i++) {
            Site site = candidates.get(selected[i]).usableSmall();
            if (site == null) return Double.POSITIVE_INFINITY;
            sites[i] = site;
            widths[i] = SMALL.width();
        }
        return evaluateLayoutObjective(sites, widths, targetDistance);
    }

    private static AssignmentScore bestLargeAssignment(int[] selected, List<Candidate> candidates, double targetDistance) {
        double best = Double.POSITIVE_INFINITY;
        int bestLarge = -1;
        for (int candidateIndex : selected) {
            Candidate largeCandidate = candidates.get(candidateIndex);
            Site largeSite = largeCandidate.usableLarge();
            if (largeSite == null) continue;
            Site[] sites = new Site[selected.length];
            int[] widths = new int[selected.length];
            boolean valid = true;
            for (int i = 0; i < selected.length; i++) {
                boolean large = selected[i] == candidateIndex;
                Site site = large ? largeSite : candidates.get(selected[i]).usableSmall();
                if (site == null) {
                    valid = false;
                    break;
                }
                sites[i] = site;
                widths[i] = large ? LARGE.width() : SMALL.width();
            }
            if (!valid) continue;
            double objective = evaluateLayoutObjective(sites, widths, targetDistance);
            if (objective < best) {
                best = objective;
                bestLarge = candidateIndex;
            }
        }
        return new AssignmentScore(best, bestLarge);
    }

    private static double evaluateLayoutObjective(Site[] sites, int[] widths, double targetDistance) {
        double terrain = 0.0;
        for (Site site : sites) terrain += site.score();
        double targetScale = Math.max(1.0, targetDistance);
        double pairPenalty = 0.0;
        double minDistance = Double.POSITIVE_INFINITY;
        double distanceSum = 0.0;
        int pairCount = 0;
        for (int i = 0; i < sites.length; i++) {
            for (int j = i + 1; j < sites.length; j++) {
                if (overlaps(sites[i], widths[i] / 2, sites[j], widths[j] / 2)) pairPenalty += OVERLAP_PENALTY;
                double pairDistance = distance(sites[i].centerX(), sites[i].centerZ(), sites[j].centerX(), sites[j].centerZ());
                double normalized = pairDistance / targetScale;
                pairPenalty += PAIR_DISTANCE_WEIGHT / (0.20 + normalized * normalized);
                minDistance = Math.min(minDistance, pairDistance);
                distanceSum += pairDistance;
                pairCount++;
            }
        }
        double minPenalty = 0.0;
        double averageReward = 0.0;
        if (pairCount > 0) {
            double shortfall = Math.max(0.0, 1.0 - minDistance / targetScale);
            minPenalty = MIN_DISTANCE_WEIGHT * shortfall * shortfall;
            averageReward = AVERAGE_DISTANCE_REWARD * (distanceSum / pairCount) / targetScale;
        }
        return terrain + pairPenalty + minPenalty - averageReward;
    }

    private static List<ChunkSeed> farthestPointSampleChunks(CityRegion region, long seed, int maximumCandidates) {
        int width = region.widthChunks();
        int height = region.heightChunks();
        int total = width * height;
        int wanted = Math.min(maximumCandidates, total);
        if (wanted <= 0) return List.of();
        int[] chunkX = new int[total];
        int[] chunkZ = new int[total];
        int cursor = 0;
        for (int x = region.minChunkX(); x <= region.maxChunkX(); x++) {
            for (int z = region.minChunkZ(); z <= region.maxChunkZ(); z++) {
                chunkX[cursor] = x;
                chunkZ[cursor] = z;
                cursor++;
            }
        }
        boolean[] selected = new boolean[total];
        int[] nearestDistanceSquared = new int[total];
        Arrays.fill(nearestDistanceSquared, Integer.MAX_VALUE);
        RandomSource random = RandomSource.create(mix(seed ^ 0xbb67ae8584caa73bL));
        int current = random.nextInt(total);
        List<ChunkSeed> result = new ArrayList<>(wanted);
        for (int sampleIndex = 0; sampleIndex < wanted; sampleIndex++) {
            selected[current] = true;
            result.add(new ChunkSeed(sampleIndex, chunkX[current], chunkZ[current]));
            int bestIndex = -1;
            int bestDistance = -1;
            int selectedX = chunkX[current];
            int selectedZ = chunkZ[current];
            for (int i = 0; i < total; i++) {
                if (selected[i]) continue;
                int dx = chunkX[i] - selectedX;
                int dz = chunkZ[i] - selectedZ;
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared < nearestDistanceSquared[i]) nearestDistanceSquared[i] = distanceSquared;
                if (nearestDistanceSquared[i] > bestDistance) {
                    bestDistance = nearestDistanceSquared[i];
                    bestIndex = i;
                }
            }
            if (bestIndex < 0) break;
            current = bestIndex;
        }
        return List.copyOf(result);
    }

    private static FootprintStencil footprintStencil(
            int minLocalX,
            int maxLocalX,
            int minLocalZ,
            int maxLocalZ,
            TemplateSize size,
            int sampleAxisCount
    ) {
        FootprintStencilKey key = new FootprintStencilKey(
                minLocalX, maxLocalX, minLocalZ, maxLocalZ, size.width(), sampleAxisCount
        );
        return FOOTPRINT_STENCILS.computeIfAbsent(key, AltarPlacementPlanner::buildFootprintStencil);
    }

    private static FootprintStencil buildFootprintStencil(FootprintStencilKey key) {
        int[] xs = sampledAxis(key.minLocalX(), key.maxLocalX());
        int[] zs = sampledAxis(key.minLocalZ(), key.maxLocalZ());
        int totalCenters = xs.length * zs.length;
        int samplesPerCenter = key.sampleAxisCount() * key.sampleAxisCount();
        int maximumSamples = totalCenters * samplesPerCenter;
        int[] centerXs = new int[totalCenters];
        int[] centerZs = new int[totalCenters];
        int[] sampleXs = new int[maximumSamples];
        int[] sampleZs = new int[maximumSamples];
        int[] sampleIndices = new int[maximumSamples];
        int uniqueSamples = 0;
        int centerIndex = 0;
        int half = key.width() / 2;
        for (int centerX : xs) {
            for (int centerZ : zs) {
                centerXs[centerIndex] = centerX;
                centerZs[centerIndex] = centerZ;
                int originX = centerX - half;
                int originZ = centerZ - half;
                int centerSampleBase = centerIndex * samplesPerCenter;
                int centerSampleOffset = 0;
                for (int dxIndex = 0; dxIndex < key.sampleAxisCount(); dxIndex++) {
                    int sampleX = originX + footprintSampleOffset(key.width(), dxIndex, key.sampleAxisCount());
                    for (int dzIndex = 0; dzIndex < key.sampleAxisCount(); dzIndex++) {
                        int sampleZ = originZ + footprintSampleOffset(key.width(), dzIndex, key.sampleAxisCount());
                        int sampleIndex = findSampleIndex(sampleXs, sampleZs, uniqueSamples, sampleX, sampleZ);
                        if (sampleIndex < 0) {
                            sampleIndex = uniqueSamples++;
                            sampleXs[sampleIndex] = sampleX;
                            sampleZs[sampleIndex] = sampleZ;
                        }
                        sampleIndices[centerSampleBase + centerSampleOffset++] = sampleIndex;
                    }
                }
                centerIndex++;
            }
        }
        return new FootprintStencil(
                centerXs, centerZs,
                Arrays.copyOf(sampleXs, uniqueSamples), Arrays.copyOf(sampleZs, uniqueSamples), sampleIndices
        );
    }

    private static int findSampleIndex(int[] sampleXs, int[] sampleZs, int count, int x, int z) {
        for (int index = 0; index < count; index++) {
            if (sampleXs[index] == x && sampleZs[index] == z) return index;
        }
        return -1;
    }

    private static int footprintSampleOffset(int width, int index, int sampleAxisCount) {
        if (sampleAxisCount < 2) return width / 2;
        double position = (width - 1.0) * index / (sampleAxisCount - 1.0);
        int midpoint = sampleAxisCount - 1;
        if (index * 2 < midpoint) return (int) Math.floor(position);
        if (index * 2 > midpoint) return (int) Math.ceil(position);
        return (int) Math.round(position);
    }

    private static TerrainAssessment assessTerrain(
            FootprintStencil footprint,
            SurfaceSample[] surfaceSamples,
            int centerIndex,
            int[] supportYs,
            int[] fluidTopYs
    ) {
        int sampleCount = supportYs.length;
        int sampleBase = centerIndex * sampleCount;
        for (int sampleOffset = 0; sampleOffset < sampleCount; sampleOffset++) {
            SurfaceSample sample = surfaceSamples[footprint.sampleIndices()[sampleBase + sampleOffset]];
            if (sample == null) return null;
            supportYs[sampleOffset] = sample.supportY();
            fluidTopYs[sampleOffset] = sample.fluidTopY();
        }
        return optimizeSurface(supportYs, fluidTopYs);
    }

    private static TerrainAssessment optimizeSurface(int[] supportYs, int[] fluidTopYs) {
        int minSurface = supportYs[0];
        int maxSurface = supportYs[0];
        for (int index = 1; index < supportYs.length; index++) {
            minSurface = Math.min(minSurface, supportYs[index]);
            maxSurface = Math.max(maxSurface, supportYs[index]);
        }
        int bestTarget = minSurface;
        double bestScore = Double.POSITIVE_INFINITY;
        double bestBuriedFraction = 0.0;
        double bestFloatingFraction = 0.0;
        double bestSubmergedFraction = 0.0;
        double roughness = maxSurface - minSurface;
        for (int target = minSurface; target <= maxSurface; target++) {
            double burialDepthCost = 0.0;
            double floatingDepthCost = 0.0;
            double waterDepthCost = 0.0;
            int buriedCells = 0;
            int floatingCells = 0;
            int submergedCells = 0;
            for (int index = 0; index < supportYs.length; index++) {
                int difference = supportYs[index] - target;
                if (difference > 0) {
                    buriedCells++;
                    burialDepthCost += burialDepthPenalty(difference);
                } else if (difference < 0) {
                    floatingCells++;
                    floatingDepthCost += floatingDepthPenalty(-difference);
                }
                if (fluidTopYs[index] >= target) {
                    submergedCells++;
                    waterDepthCost += fluidTopYs[index] - target + 1.0;
                }
            }
            double count = supportYs.length;
            double buriedFraction = buriedCells / count;
            double floatingFraction = floatingCells / count;
            double submergedFraction = submergedCells / count;
            double score = (burialDepthCost + floatingDepthCost) / count
                    + quarterFractionPenalty(buriedFraction)
                    + quarterFractionPenalty(floatingFraction)
                    + quarterFractionPenalty(submergedFraction)
                    + (waterDepthCost / count) * 20.0
                    + roughness * 0.30;
            if (score < bestScore) {
                bestTarget = target;
                bestScore = score;
                bestBuriedFraction = buriedFraction;
                bestFloatingFraction = floatingFraction;
                bestSubmergedFraction = submergedFraction;
            }
        }
        return new TerrainAssessment(
                bestTarget, bestScore, roughness, bestBuriedFraction, bestFloatingFraction, bestSubmergedFraction
        );
    }

    private static double burialDepthPenalty(int depth) {
        if (depth <= 1) return 0.08;
        if (depth == 2) return 0.20;
        if (depth == 3) return 0.55;
        double excess = depth - 3.0;
        return 1.0 + excess * excess * 2.5;
    }

    private static double floatingDepthPenalty(int depth) {
        if (depth <= 1) return 0.10;
        if (depth == 2) return 0.28;
        if (depth == 3) return 0.75;
        double excess = depth - 3.0;
        return 1.4 + excess * excess * 4.0;
    }

    private static double quarterFractionPenalty(double fraction) {
        if (fraction <= QUARTER_LIMIT) {
            double ratio = fraction / QUARTER_LIMIT;
            return ratio * ratio * 8.0;
        }
        if (fraction < EFFECTIVE_REJECT_LIMIT) {
            double transition = (fraction - QUARTER_LIMIT) / (EFFECTIVE_REJECT_LIMIT - QUARTER_LIMIT);
            return 8.0 + transition * transition * 1_992.0;
        }
        double excess = fraction - EFFECTIVE_REJECT_LIMIT;
        return EFFECTIVE_REJECT_PENALTY + excess * 2_000_000.0 + excess * excess * 5_000_000.0;
    }

    private static SearchBounds searchBounds(CityRegion region, TemplateSize size) {
        int half = size.width() / 2;
        int minCenterX = region.minBlockX() + half + CITY_EDGE_MARGIN;
        int maxCenterX = region.maxBlockX() - half - CITY_EDGE_MARGIN;
        int minCenterZ = region.minBlockZ() + half + CITY_EDGE_MARGIN;
        int maxCenterZ = region.maxBlockZ() - half - CITY_EDGE_MARGIN;
        if (minCenterX > maxCenterX || minCenterZ > maxCenterZ) {
            int centerX = (region.minBlockX() + region.maxBlockX()) / 2;
            int centerZ = (region.minBlockZ() + region.maxBlockZ()) / 2;
            return new SearchBounds(centerX, centerX, centerZ, centerZ);
        }
        return new SearchBounds(minCenterX, maxCenterX, minCenterZ, maxCenterZ);
    }

    private static double edgePenalty(SearchBounds bounds, int centerX, int centerZ) {
        int edgeDistance = Math.min(
                Math.min(centerX - bounds.minCenterX(), bounds.maxCenterX() - centerX),
                Math.min(centerZ - bounds.minCenterZ(), bounds.maxCenterZ() - centerZ)
        );
        if (edgeDistance >= 32) return 0.0;
        double ratio = (32 - Math.max(0, edgeDistance)) / 32.0;
        return ratio * ratio * 4.0;
    }

    private static boolean overlaps(Site a, int halfA, Site b, int halfB) {
        int required = halfA + halfB + MIN_STRUCTURE_GAP;
        return Math.abs(a.centerX() - b.centerX()) <= required
                && Math.abs(a.centerZ() - b.centerZ()) <= required;
    }

    private static double preferredDistance(CityRegion region, int count) {
        double shorterSide = Math.min(region.widthChunks(), region.heightChunks()) * 16.0;
        return 0.80 * shorterSide / Math.sqrt(Math.max(1, count));
    }

    private static int[] sampledAxis(int min, int max) {
        if (min >= max) return new int[]{min};
        int[] values = new int[CENTER_AXIS_SAMPLES];
        int count = 0;
        for (int i = 0; i < CENTER_AXIS_SAMPLES; i++) {
            int value = min + (int) Math.round((max - (double) min) * i / (CENTER_AXIS_SAMPLES - 1.0));
            if (count == 0 || values[count - 1] != value) values[count++] = value;
        }
        return count == values.length ? values : Arrays.copyOf(values, count);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double distance(int x1, int z1, int x2, int z2) {
        double dx = x1 - (double) x2;
        double dz = z1 - (double) z2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static long packXZ(int x, int z) {
        return (long) x << 32 ^ z & 0xffffffffL;
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ value >>> 33;
    }

    private static double elapsedSeconds(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000.0;
    }

    static String formatSeconds(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static String statusName(ChunkStatus status) {
        if (status == ChunkStatus.FULL) return "FULL";
        if (status == ChunkStatus.STRUCTURE_REFERENCES) return "STRUCTURE_REFERENCES";
        return status.toString();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
    }

    record Request(int specIndex, boolean large) { }

    record Plan(
            int specIndex,
            int sampleIndex,
            int chunkX,
            int chunkZ,
            int centerX,
            int centerZ,
            int targetSurfaceY,
            double terrainScore,
            double buriedFraction,
            double floatingFraction,
            double submergedFraction,
            boolean large
    ) { }

    static final class PreparationSession {
        private final ServerLevel level;
        private final List<Request> requests;
        private final long seed;
        private final UUID cityId;
        private final int count;
        private final boolean hasLarge;
        private final SearchBounds smallBounds;
        private final SearchBounds largeBounds;
        private final ChunkGenerator generator;
        private final RandomState randomState;
        private final Map<Long, SurfaceSample> virtualSurfaceCache;
        private final List<Candidate> candidates;
        private final List<ChunkSeed> reserveChunks;
        private final VirtualSurfaceMode virtualSurfaceMode;
        private final double targetDistance;
        private final AccuracyMetrics accuracy = new AccuracyMetrics();
        private int optimizationRound;
        private int exactEvaluations;
        private boolean degradedMode;
        private int pendingExactCandidateIndex = -1;
        private ExactRole pendingExactRole;
        private ExactMode pendingExactMode;
        private ChunkStatus pendingChunkStatus;
        private SelectionResult selection;
        private List<Plan> completedPlans;

        private PreparationSession(
                ServerLevel level,
                List<Request> requests,
                long seed,
                UUID cityId,
                int count,
                boolean hasLarge,
                SearchBounds smallBounds,
                SearchBounds largeBounds,
                ChunkGenerator generator,
                RandomState randomState,
                Map<Long, SurfaceSample> virtualSurfaceCache,
                List<Candidate> candidates,
                List<ChunkSeed> reserveChunks,
                VirtualSurfaceMode virtualSurfaceMode,
                double targetDistance
        ) {
            this.level = level;
            this.requests = requests;
            this.seed = seed;
            this.cityId = cityId;
            this.count = count;
            this.hasLarge = hasLarge;
            this.smallBounds = smallBounds;
            this.largeBounds = largeBounds;
            this.generator = generator;
            this.randomState = randomState;
            this.virtualSurfaceCache = virtualSurfaceCache;
            this.candidates = candidates;
            this.reserveChunks = reserveChunks;
            this.virtualSurfaceMode = virtualSurfaceMode;
            this.targetDistance = targetDistance;
        }
    }

    record PreparationStep(boolean complete, int candidateIndex, ChunkStatus chunkStatus, List<ChunkPos> requiredChunks, List<Plan> plans) {
        private static PreparationStep candidate(int candidateIndex, ChunkStatus chunkStatus, List<ChunkPos> requiredChunks) {
            return new PreparationStep(false, candidateIndex, chunkStatus, requiredChunks, List.of());
        }

        private static PreparationStep complete(List<Plan> plans) {
            return new PreparationStep(true, -1, ChunkStatus.FULL, List.of(), plans);
        }
    }

    private static final class Candidate {
        private final ChunkSeed chunk;
        private final Site coarseSmall;
        private Site virtualSmall;
        private Site virtualLarge;
        private boolean smallRefined;
        private boolean smallExactEvaluated;
        private boolean largeExactEvaluated;
        private boolean smallFallbackRequired;
        private boolean largeFallbackRequired;
        private boolean smallFastStructurePrechecked;
        private boolean smallFullStructurePrechecked;
        private boolean largeFastStructurePrechecked;
        private boolean largeFullStructurePrechecked;
        private boolean smallFastAttemptCounted;
        private boolean smallFullSearchAttemptCounted;
        private boolean largeFastAttemptCounted;
        private boolean largeFullSearchAttemptCounted;
        private Site exactSmall;
        private Site exactLarge;
        private String smallReject = "";
        private String largeReject = "";

        private Candidate(ChunkSeed chunk, Site coarseSmall) {
            this.chunk = chunk;
            this.coarseSmall = coarseSmall;
            this.virtualSmall = coarseSmall;
        }

        private Site usableSmall() {
            return smallExactEvaluated ? smallReject.isEmpty() ? exactSmall : null : virtualSmall;
        }

        private Site usableLarge() {
            if (virtualLarge == null) return null;
            return largeExactEvaluated ? largeReject.isEmpty() ? exactLarge : null : virtualLarge;
        }
    }

    private static final class AccuracyMetrics {
        private final RoleAccuracy small = new RoleAccuracy();
        private final RoleAccuracy large = new RoleAccuracy();

        private RoleAccuracy forRole(ExactRole role) {
            return role == ExactRole.SMALL ? small : large;
        }
    }

    private static final class RoleAccuracy {
        private int fastAttempts;
        private int fastSuccesses;
        private int structureFallbacks;
        private int terrainFallbacks;
        private int otherFallbacks;
        private int fullSearchAttempts;
        private int fullSearchRejects;
        private int comparisons;
        private double scoreAbsErrorSum;
        private double scoreAbsErrorMax;
        private double fractionAbsErrorSum;
        private double fractionAbsErrorMax;
        private final int[] strictBucketComparisons = new int[6];
        private final int[] strictBucketPasses = new int[6];
    }

    private enum ExactRole { SMALL, LARGE }
    private enum ExactMode { FAST_CENTER, FULL_SEARCH }
    private enum VirtualSurfaceMode { LEGACY_HEIGHTS, BASE_COLUMN }

    @FunctionalInterface
    private interface SurfaceReader {
        SurfaceSample read(int x, int z);
    }

    private record TemplateSize(int width, int height) { }
    private record ChunkSeed(int sampleIndex, int chunkX, int chunkZ) { }
    private record SurfaceSample(int supportY, int fluidTopY) { }
    private record VirtualSiteEvaluation(int candidateIndex, Site site, double seconds) { }
    private record FootprintStencilKey(int minLocalX, int maxLocalX, int minLocalZ, int maxLocalZ, int width,
                                       int sampleAxisCount) { }
    private record FootprintStencil(int[] centerXs, int[] centerZs, int[] sampleXs, int[] sampleZs, int[] sampleIndices) { }
    private record TerrainAssessment(int targetSurfaceY, double score, double roughness, double buriedFraction,
                                     double floatingFraction, double submergedFraction) { }
    private record Site(int centerX, int centerZ, double score, TerrainAssessment terrain) { }
    private record ActualSiteResult(Site site, String rejectReason) { }
    private record SearchBounds(int minCenterX, int maxCenterX, int minCenterZ, int maxCenterZ) { }
    private record AssignmentScore(double objective, int largeCandidateIndex) { }
    private record SelectionResult(int[] selected, int largeCandidateIndex, double objective) { }
}
