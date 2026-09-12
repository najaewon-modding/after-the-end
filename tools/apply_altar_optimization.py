from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
PLANNER = ROOT / "src/main/java/net/njw/aftertheend/city/altar/AltarPlacementPlanner.java"
SERVICE = ROOT / "src/main/java/net/njw/aftertheend/city/altar/AltarPlacementService.java"
HIDDEN = ROOT / "src/main/java/net/njw/aftertheend/city/altar/HiddenCityPreparationService.java"


def replace_once(text, old, new, name):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{name}: expected exactly one literal match, found {count}")
    return text.replace(old, new, 1)


def replace_between(text, start, end, new, name):
    a = text.find(start)
    if a < 0:
        raise RuntimeError(f"{name}: start marker not found")
    b = text.find(end, a)
    if b < 0:
        raise RuntimeError(f"{name}: end marker not found")
    return text[:a] + new + text[b:]


p = PLANNER.read_text(encoding="utf-8")
p = replace_once(p,
'''import java.util.UUID;\nimport java.util.concurrent.ConcurrentHashMap;''',
'''import java.util.UUID;\nimport java.util.concurrent.CompletableFuture;\nimport java.util.concurrent.CompletionException;\nimport java.util.concurrent.ConcurrentHashMap;\nimport java.util.concurrent.ExecutorService;\nimport java.util.concurrent.Executors;\nimport java.util.concurrent.atomic.AtomicInteger;\nimport java.util.function.Predicate;''',
"planner concurrency imports")
p = replace_once(p,
'''import net.minecraft.world.level.ChunkPos;''',
'''import net.minecraft.world.level.ChunkPos;\nimport net.minecraft.world.level.NoiseColumn;''',
"planner NoiseColumn import")
p = replace_once(p,
'''    private static final int FPS_CANDIDATE_COUNT = 64;\n    private static final int SMALL_REFINE_MIN_CANDIDATES = 24;''',
'''    private static final int FPS_CANDIDATE_COUNT = 64;\n    private static final int FPS_RESERVE_CANDIDATE_COUNT = 32;\n    private static final int VIRTUAL_SURFACE_BENCHMARK_SAMPLES = 6;\n    private static final int VIRTUAL_WORKER_COUNT = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));\n    private static final AtomicInteger VIRTUAL_THREAD_SEQUENCE = new AtomicInteger();\n    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newFixedThreadPool(\n            VIRTUAL_WORKER_COUNT,\n            runnable -> {\n                Thread thread = new Thread(runnable, "after-the-end-altar-virtual-" + VIRTUAL_THREAD_SEQUENCE.incrementAndGet());\n                thread.setDaemon(true);\n                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));\n                return thread;\n            }\n    );\n    private static final int SMALL_REFINE_MIN_CANDIDATES = 24;''',
"planner constants")

begin = '''    static PreparationSession beginPreparation(ServerLevel level, CityRegion region, List<Request> requests, long seed, UUID cityId) {
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
            }, VIRTUAL_EXECUTOR));
        }
        for (int index = 0; index < sampledChunks.size(); index++) {
            ChunkSeed chunk = sampledChunks.get(index);
            VirtualSiteEvaluation evaluation = joinVirtual(coarseFutures.get(index), cityId, "Small coarse");
            candidates.add(new Candidate(chunk, evaluation.site()));
            AfterTheEnd.LOGGER.info(
                    "[{}/{}] {}sec chunk=({}, {}) small-coarse={} city={}",
                    index + 1, sampledChunks.size(), formatSeconds(evaluation.seconds()),
                    chunk.chunkX(), chunk.chunkZ(), format(evaluation.site().score()), cityId
            );
        }
        double targetDistance = preferredDistance(region, count);
        AfterTheEnd.LOGGER.info(
                "Altar planner {}: coarse-evaluated {} Small FPS candidates in {}sec using {} virtual worker(s) without loading candidate chunks.",
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
                        level, generator, randomState, virtualSurfaceCache, candidate.chunk, SMALL, smallBounds, virtualSurfaceMode
                );
                return new VirtualSiteEvaluation(candidateIndex, site, elapsedSeconds(started));
            }, VIRTUAL_EXECUTOR));
        }
        for (int position = 0; position < refineIndices.size(); position++) {
            int candidateIndex = refineIndices.get(position);
            Candidate candidate = candidates.get(candidateIndex);
            VirtualSiteEvaluation evaluation = joinVirtual(refineFutures.get(position), cityId, "Small refine");
            candidate.virtualSmall = evaluation.site();
            candidate.smallRefined = true;
            AfterTheEnd.LOGGER.info(
                    "[{}/{}] {}sec chunk=({}, {}) small-refine={} city={}",
                    position + 1, refineTarget, formatSeconds(evaluation.seconds()), candidate.chunk.chunkX(),
                    candidate.chunk.chunkZ(), format(candidate.virtualSmall.score()), cityId
            );
        }
        AfterTheEnd.LOGGER.info(
                "Altar planner {}: refined {} of {} Small candidates in {}sec with unchanged 24-candidate quality target; remaining candidates refine on demand.",
                cityId, refineIndices.size(), candidates.size(), formatSeconds(elapsedSeconds(refineStarted))
        );
        return new PreparationSession(
                level, List.copyOf(requests), seed, cityId, count, hasLarge, smallBounds, largeBounds,
                generator, randomState, virtualSurfaceCache, candidates, new ArrayList<>(reserveChunks),
                virtualSurfaceMode, targetDistance
        );
    }

'''
p = replace_between(p,
"    static PreparationSession beginPreparation(ServerLevel level, CityRegion region, List<Request> requests, long seed, UUID cityId) {",
"    static PreparationStep advancePreparation(PreparationSession session) {",
begin,
"beginPreparation")

advance = '''    static PreparationStep advancePreparation(PreparationSession session) {
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
                AfterTheEnd.LOGGER.info(
                        "Altar planner {}: Small exact candidate sample={} chunk=({}, {}) rejected; re-optimizing immediately.",
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
                    AfterTheEnd.LOGGER.info(
                            "Altar planner {}: all {} Small-selected candidates failed Large exact validation; selecting a new Small candidate set.",
                            session.cityId, selection.selected().length
                    );
                    session.selection = null;
                    continue;
                }
                selection = new SelectionResult(
                        selection.selected(), largeAssignment.largeCandidateIndex(), largeAssignment.objective(), selection.restartObjectives()
                );
                session.selection = selection;
                Candidate largeCandidate = session.candidates.get(selection.largeCandidateIndex());
                if (!largeCandidate.largeExactEvaluated) return requestExact(session, selection.largeCandidateIndex(), ExactRole.LARGE);
                if (largeCandidate.usableLarge() == null) {
                    session.selection = new SelectionResult(
                            selection.selected(), -1, evaluateSmallObjective(
                                    selection.selected(), session.candidates, session.targetDistance, session.hasLarge
                            ), selection.restartObjectives()
                    );
                    continue;
                }
            }

            List<Plan> plans = buildPlans(session.requests, selection, session.candidates);
            session.completedPlans = List.copyOf(plans);
            AfterTheEnd.LOGGER.info(
                    "Altar planner {}: FPS candidates={}, exact evaluations={}, structures={}, targetDistance={} blocks, objective={}, sampler={}, degraded={}",
                    session.cityId, session.candidates.size(), session.exactEvaluations, session.count,
                    format(session.targetDistance), format(selection.objective()), session.virtualSurfaceMode, session.degradedMode
            );
            for (Plan plan : session.completedPlans) {
                AfterTheEnd.LOGGER.info(
                        "Altar planner {} spec={} {} chunk=({}, {}) center=({}, {}, {}) terrain={} buried={} floating={} water={}",
                        session.cityId, plan.specIndex(), plan.large() ? "large" : "small", plan.chunkX(), plan.chunkZ(),
                        plan.centerX(), plan.targetSurfaceY(), plan.centerZ(), format(plan.terrainScore()),
                        percent(plan.buriedFraction()), percent(plan.floatingFraction()), percent(plan.submergedFraction())
                );
            }
            return PreparationStep.complete(session.completedPlans);
        }
    }

'''
p = replace_between(p,
"    static PreparationStep advancePreparation(PreparationSession session) {",
"    private static void refineSmallCandidate(PreparationSession session, Candidate candidate) {",
advance,
"advancePreparation")

helpers = '''    private static void refineSmallCandidate(PreparationSession session, Candidate candidate) {
        refineSmallCandidates(session, List.of(session.candidates.indexOf(candidate)), "small-refine-selected");
    }

    private static void refineSmallCandidates(PreparationSession session, List<Integer> candidateIndices, String logName) {
        List<CompletableFuture<VirtualSiteEvaluation>> futures = new ArrayList<>(candidateIndices.size());
        for (int candidateIndex : candidateIndices) {
            Candidate candidate = session.candidates.get(candidateIndex);
            futures.add(CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                Site site = evaluateVirtualSite(
                        session.level, session.generator, session.randomState, session.virtualSurfaceCache,
                        candidate.chunk, SMALL, session.smallBounds, session.virtualSurfaceMode
                );
                return new VirtualSiteEvaluation(candidateIndex, site, elapsedSeconds(started));
            }, VIRTUAL_EXECUTOR));
        }
        for (int position = 0; position < candidateIndices.size(); position++) {
            int candidateIndex = candidateIndices.get(position);
            Candidate candidate = session.candidates.get(candidateIndex);
            VirtualSiteEvaluation evaluation = joinVirtual(futures.get(position), session.cityId, "Small on-demand refine");
            candidate.virtualSmall = evaluation.site();
            candidate.smallRefined = true;
            AfterTheEnd.LOGGER.info(
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
                        candidate.chunk, LARGE, session.largeBounds, session.virtualSurfaceMode
                );
                return new VirtualSiteEvaluation(candidateIndex, site, elapsedSeconds(started));
            }, VIRTUAL_EXECUTOR));
        }
        for (int i = 0; i < candidateIndices.size(); i++) {
            int candidateIndex = candidateIndices.get(i);
            Candidate candidate = session.candidates.get(candidateIndex);
            VirtualSiteEvaluation evaluation = joinVirtual(futures.get(i), session.cityId, "Large virtual evaluation");
            candidate.virtualLarge = evaluation.site();
            int position = 0;
            while (position < selected.length && selected[position] != candidateIndex) position++;
            AfterTheEnd.LOGGER.info(
                    "[{}/{}] {}sec chunk=({}, {}) large={} city={}",
                    position + 1, selected.length, formatSeconds(evaluation.seconds()), candidate.chunk.chunkX(),
                    candidate.chunk.chunkZ(), format(candidate.virtualLarge.score()), session.cityId
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
                "Altar planner {} exhausted the strict {}-candidate pool; expanding by {} reserve candidate(s) before considering degraded terrain fallback.",
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
            }, VIRTUAL_EXECUTOR));
        }
        for (int i = 0; i < reserve.size(); i++) {
            VirtualSiteEvaluation evaluation = joinVirtual(coarseFutures.get(i), session.cityId, "Reserve coarse evaluation");
            session.candidates.add(new Candidate(reserve.get(i), evaluation.site()));
        }
        List<Integer> refineIndices = new ArrayList<>(reserve.size());
        for (int index = startIndex; index < session.candidates.size(); index++) refineIndices.add(index);
        refineSmallCandidates(session, refineIndices, "small-refine-reserve");
        AfterTheEnd.LOGGER.warn(
                "Altar planner {} reserve expansion complete: candidates={} (+{}), all reserve candidates fully refined.",
                session.cityId, session.candidates.size(), reserve.size()
        );
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
                "Altar planner {} entering DEGRADED TERRAIN fallback after strict search exhaustion: revived small={}, large={}. Structure and block-entity collisions remain hard rejects; only terrain fraction/score limits are relaxed.",
                session.cityId, revivedSmall, revivedLarge
        );
    }

'''
p = replace_between(p,
"    private static void refineSmallCandidate(PreparationSession session, Candidate candidate) {",
"    private static PreparationStep requestExact(PreparationSession session, int candidateIndex, ExactRole role) {",
helpers,
"planner virtual helper block")

p = replace_once(p,
'''        String reject = exactRejectReason(result, role == ExactRole.SMALL ? "small" : "large");''',
'''        String reject = exactRejectReason(result, role == ExactRole.SMALL ? "small" : "large", session.degradedMode);''',
"degraded exact reject call")

old_structure_free = '''    private static boolean structureFreeAtCenter(ServerLevel level, int centerX, int centerZ, TemplateSize size) {
        int half = size.width() / 2;
        int minX = centerX - half;
        int maxX = minX + size.width() - 1;
        int minZ = centerZ - half;
        int maxZ = minZ + size.width() - 1;
        for (ChunkPos pos : footprintChunks(centerX, centerZ, size)) {
            ChunkAccess chunk = level.getChunkSource().getChunk(
                    pos.x(), pos.z(), ChunkStatus.STRUCTURE_REFERENCES, false
            );
            if (chunk == null) return false;
            if (!chunk.getAllReferences().isEmpty()) return false;
            for (StructureStart start : chunk.getAllStarts().values()) {
                if (start == null || !start.isValid()) continue;
                BoundingBox box = start.getBoundingBox();
                if (box.maxX() >= minX && box.minX() <= maxX && box.maxZ() >= minZ && box.minZ() <= maxZ) return false;
            }
        }
        return true;
    }
'''
new_structure_free = '''    private static boolean structureFreeAtCenter(ServerLevel level, int centerX, int centerZ, TemplateSize size) {
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
'''
p = replace_once(p, old_structure_free, new_structure_free, "precise structure precheck")

p = replace_once(p,
'''    private static String exactRejectReason(ActualSiteResult actualResult, String role) {
        if (actualResult.site() == null) return actualResult.rejectReason().isEmpty() ? role + " has no exact-valid center" : actualResult.rejectReason();
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
''',
'''    private static String exactRejectReason(ActualSiteResult actualResult, String role, boolean degradedMode) {
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
''',
"degraded reject method")

p = p.replace(
'''            SearchBounds bounds
    ) {''',
'''            SearchBounds bounds,
            VirtualSurfaceMode virtualSurfaceMode
    ) {''',
2)
p = p.replace(
'''packXZ(x, z), ignored -> readVirtualSurfaceSample(level, generator, randomState, x, z)''',
'''packXZ(x, z), ignored -> readVirtualSurfaceSample(level, generator, randomState, x, z, virtualSurfaceMode)''')

read_virtual_start = "    private static SurfaceSample readVirtualSurfaceSample(\n"
read_virtual_end = "    private static SurfaceSample readActualSurfaceSample(ServerLevel level, int x, int z) {"
read_virtual = '''    private static VirtualSurfaceMode chooseVirtualSurfaceMode(
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
        AfterTheEnd.LOGGER.info(
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

'''
p = replace_between(p, read_virtual_start, read_virtual_end, read_virtual, "virtual surface sampler")

old_collision_start = "    private static boolean hasStructureCollision(ServerLevel level, Site site, TemplateSize size) {"
old_collision_end = "    private static boolean hasBlockEntityCollision(ServerLevel level, Site site, TemplateSize size) {"
new_collision = '''    private static boolean hasStructureCollision(ServerLevel level, Site site, TemplateSize size) {
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

'''
p = replace_between(p, old_collision_start, old_collision_end, new_collision, "precise actual structure collision")

old_opt = '''        int[] deterministic = deterministicInitialization(candidates, count);
        SelectionResult deterministicResult = improveBySmallSwaps(deterministic, candidates, hasLarge, targetDistance);
        restartObjectives.add(deterministicResult.objective());
        if (Double.isFinite(deterministicResult.objective())) globalBest = deterministicResult;'''
new_opt = '''        int[] deterministic = deterministicInitialization(candidates, count);
        if (deterministic == null) return null;
        SelectionResult deterministicResult = improveBySmallSwaps(deterministic, candidates, hasLarge, targetDistance);
        restartObjectives.add(deterministicResult.objective());
        if (Double.isFinite(deterministicResult.objective())) globalBest = deterministicResult;'''
p = replace_once(p, old_opt, new_opt, "nullable optimizer init")
p = replace_once(p,
'''        if (globalBest == null) throw new IllegalStateException("No feasible Small Altar candidate combination remains after exact validation.");
        return new SelectionResult(globalBest.selected(), -1, globalBest.objective(), List.copyOf(restartObjectives));''',
'''        if (globalBest == null) return null;
        return new SelectionResult(globalBest.selected(), -1, globalBest.objective(), List.copyOf(restartObjectives));''',
"nullable optimizer result")
p = replace_once(p,
'''        if (indices.size() < count) throw new IllegalStateException("Not enough usable Small Altar candidates remain.");''',
'''        if (indices.size() < count) return null;''',
"nullable deterministic init")

p = replace_once(p,
'''    private static List<ChunkSeed> farthestPointSampleChunks(CityRegion region, long seed) {
        int width = region.widthChunks();
        int height = region.heightChunks();
        int total = width * height;
        int wanted = Math.min(FPS_CANDIDATE_COUNT, total);''',
'''    private static List<ChunkSeed> farthestPointSampleChunks(CityRegion region, long seed, int maximumCandidates) {
        int width = region.widthChunks();
        int height = region.heightChunks();
        int total = width * height;
        int wanted = Math.min(maximumCandidates, total);''',
"FPS maximum")

session_old = '''        private final Map<Long, SurfaceSample> virtualSurfaceCache;
        private final List<Candidate> candidates;
        private final double targetDistance;
        private int optimizationRound;
        private int exactEvaluations;'''
session_new = '''        private final Map<Long, SurfaceSample> virtualSurfaceCache;
        private final List<Candidate> candidates;
        private final List<ChunkSeed> reserveChunks;
        private final VirtualSurfaceMode virtualSurfaceMode;
        private final double targetDistance;
        private int optimizationRound;
        private int exactEvaluations;
        private boolean degradedMode;'''
p = replace_once(p, session_old, session_new, "session fields")
p = replace_once(p,
'''                Map<Long, SurfaceSample> virtualSurfaceCache,
                List<Candidate> candidates,
                double targetDistance''',
'''                Map<Long, SurfaceSample> virtualSurfaceCache,
                List<Candidate> candidates,
                List<ChunkSeed> reserveChunks,
                VirtualSurfaceMode virtualSurfaceMode,
                double targetDistance''',
"session constructor args")
p = replace_once(p,
'''            this.virtualSurfaceCache = virtualSurfaceCache;
            this.candidates = candidates;
            this.targetDistance = targetDistance;''',
'''            this.virtualSurfaceCache = virtualSurfaceCache;
            this.candidates = candidates;
            this.reserveChunks = reserveChunks;
            this.virtualSurfaceMode = virtualSurfaceMode;
            this.targetDistance = targetDistance;''',
"session constructor fields")

p = replace_once(p,
'''    private enum ExactRole { SMALL, LARGE }
    private enum ExactMode { FAST_CENTER, FULL_SEARCH }''',
'''    private enum ExactRole { SMALL, LARGE }
    private enum ExactMode { FAST_CENTER, FULL_SEARCH }
    private enum VirtualSurfaceMode { LEGACY_HEIGHTS, BASE_COLUMN }''',
"surface mode enum")
p = replace_once(p,
'''    private record SurfaceSample(int supportY, int fluidTopY) { }''',
'''    private record SurfaceSample(int supportY, int fluidTopY) { }
    private record VirtualSiteEvaluation(int candidateIndex, Site site, double seconds) { }''',
"virtual evaluation record")

PLANNER.write_text(p, encoding="utf-8")

s = SERVICE.read_text(encoding="utf-8")
s = replace_once(s,
'''import java.util.List;\nimport java.util.UUID;''',
'''import java.util.List;\nimport java.util.UUID;\nimport java.util.concurrent.CompletableFuture;''',
"service CompletableFuture import")
old_generate = '''    private static List<AltarPlacement> generate(MinecraftServer server, City city) {
        Preparation preparation = beginPreparation(server, city);
        if (preparation == null) return AltarManager.getPlacements(server, city.id());
        while (true) {
            AltarPlacementPlanner.PreparationStep step = advancePreparation(preparation);
            if (step.complete()) return completePreparation(server, city, preparation, step.plans());
            for (ChunkPos chunk : missingRequiredChunks(preparation, step.candidateIndex())) {
                long started = System.nanoTime();
                preparation.level().getChunk(chunk.x(), chunk.z(), step.chunkStatus(), true);
                AfterTheEnd.LOGGER.info(
                        "chunk ({}, {}) {} {}sec city={}", chunk.x(), chunk.z(),
                        AltarPlacementPlanner.statusName(step.chunkStatus()),
                        AltarPlacementPlanner.formatSeconds((System.nanoTime() - started) / 1_000_000_000.0), city.id()
                );
            }
            exactEvaluateLoaded(preparation, step.candidateIndex());
        }
    }
'''
new_generate = '''    private static List<AltarPlacement> generate(MinecraftServer server, City city) {
        Preparation preparation = beginPreparation(server, city);
        if (preparation == null) return AltarManager.getPlacements(server, city.id());
        while (true) {
            AltarPlacementPlanner.PreparationStep step = advancePreparation(preparation);
            if (step.complete()) return completePreparation(server, city, preparation, step.plans());
            ensureRequiredChunks(server, preparation, step);
            exactEvaluateLoaded(preparation, step.candidateIndex());
        }
    }

    private static void ensureRequiredChunks(
            MinecraftServer server,
            Preparation preparation,
            AltarPlacementPlanner.PreparationStep step
    ) {
        List<ChunkPos> missing = missingRequiredChunks(preparation, step.candidateIndex());
        if (missing.isEmpty()) return;
        long started = System.nanoTime();
        List<CompletableFuture<?>> futures = new ArrayList<>(missing.size());
        for (ChunkPos chunk : missing) {
            futures.add(preparation.level().getChunkSource().getChunkFuture(
                    chunk.x(), chunk.z(), step.chunkStatus(), true
            ));
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        server.managedBlock(all::isDone);
        for (CompletableFuture<?> future : futures) future.join();
        List<ChunkPos> remaining = missingRequiredChunks(preparation, step.candidateIndex());
        if (!remaining.isEmpty()) throw new IllegalStateException("Batch Altar chunk generation did not prepare: " + remaining);
        AfterTheEnd.LOGGER.info(
                "chunk batch {} {} chunk(s) {}sec city={}",
                AltarPlacementPlanner.statusName(step.chunkStatus()), missing.size(),
                AltarPlacementPlanner.formatSeconds((System.nanoTime() - started) / 1_000_000_000.0), city.id()
        );
    }
'''
s = replace_once(s, old_generate, new_generate, "service batch chunk generation")
SERVICE.write_text(s, encoding="utf-8")

h = HIDDEN.read_text(encoding="utf-8")
h = replace_once(h,
'''    private static final ArrayDeque<ChunkPos> CHUNKS_TO_ENSURE = new ArrayDeque<>();\n''',
'''    private static List<ChunkPos> pendingChunks = List.of();\n    private static List<CompletableFuture<?>> pendingChunkFutures = List.of();\n''',
"hidden chunk fields")
h = replace_once(h,
'''    private static ChunkPos pendingChunk;
    private static ChunkStatus pendingChunkStatus;''',
'''    private static ChunkStatus pendingChunkStatus;''',
"hidden pending chunk field")
h = h.replace('''                CHUNKS_TO_ENSURE.clear();\n''', '')
old_tick_chunk = '''            if (pendingChunkFuture != null) {
                if (!pendingChunkFuture.isDone()) return;
                finishPendingChunk();
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
            activeStep = null;'''
new_tick_chunk = '''            if (pendingChunkFuture != null) {
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
            activeStep = null;'''
h = replace_once(h, old_tick_chunk, new_tick_chunk, "hidden tick batch")
chunk_methods_start = "    private static boolean isPrepared(ServerLevel level, ChunkPos chunk, ChunkStatus status) {"
chunk_methods_end = "    private static void resetActive(boolean cancelFuture) {"
chunk_methods = '''    private static void startChunkBatch(ServerLevel level, List<ChunkPos> chunks, ChunkStatus status) {
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

'''
h = replace_between(h, chunk_methods_start, chunk_methods_end, chunk_methods, "hidden chunk batch helpers")
h = replace_once(h,
'''            if (pendingChunkFuture != null) pendingChunkFuture.cancel(false);''',
'''            if (pendingChunkFuture != null) pendingChunkFuture.cancel(false);
            for (CompletableFuture<?> future : pendingChunkFutures) future.cancel(false);''',
"hidden cancel batch")
h = replace_once(h,
'''        pendingChunkFuture = null;
        pendingChunk = null;
        pendingChunkStatus = null;''',
'''        pendingChunkFuture = null;
        pendingChunkFutures = List.of();
        pendingChunks = List.of();
        pendingChunkStatus = null;''',
"hidden reset batch")
HIDDEN.write_text(h, encoding="utf-8")

print("Applied robust altar performance patch")
