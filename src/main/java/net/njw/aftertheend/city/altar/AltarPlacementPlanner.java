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
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
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
    private static final double ACTUAL_DEGRADATION_MIN_LIMIT = 12.0;
    private static final double ACTUAL_DEGRADATION_MULTIPLIER = 4.0;
    private static final double OUTLIER_MIN_DELTA = 8.0;
    private static final double OUTLIER_MULTIPLIER = 3.0;
    private static final long OPTIMIZER_SEED_SALT = 0x6a09e667f3bcc909L;

    private static final TemplateSize SMALL = new TemplateSize(11, 7);
    private static final TemplateSize LARGE = new TemplateSize(27, 10);
    private static final int FOOTPRINT_SAMPLE_COUNT = 9;
    private static final Map<FootprintStencilKey, FootprintStencil> FOOTPRINT_STENCILS = new ConcurrentHashMap<>();

    private AltarPlacementPlanner() { }

    static List<Plan> plan(ServerLevel level, CityRegion region, List<Request> requests, long seed, UUID cityId) {
        PreparationSession session = beginPreparation(level, region, requests, seed, cityId);
        while (true) {
            PreparationStep step = advancePreparation(session);
            if (step.complete()) return step.plans();
            for (ChunkPos chunk : step.requiredChunks()) level.getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL, true);
            exactEvaluateLoaded(session, step.candidateIndex());
        }
    }

    static PreparationSession beginPreparation(ServerLevel level, CityRegion region, List<Request> requests, long seed, UUID cityId) {
        if (requests.isEmpty()) throw new IllegalArgumentException("Altar preparation requires at least one request.");
        int count = requests.size();
        boolean hasLarge = requests.stream().anyMatch(Request::large);
        List<ChunkSeed> sampledChunks = farthestPointSampleChunks(region, seed);
        SearchBounds smallBounds = searchBounds(region, SMALL);
        SearchBounds largeBounds = searchBounds(region, LARGE);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        Map<Long, SurfaceSample> virtualSurfaceCache = new ConcurrentHashMap<>();
        List<Candidate> candidates = new ArrayList<>(sampledChunks.size());
        long started = System.nanoTime();
        for (ChunkSeed chunk : sampledChunks) {
            long candidateStarted = System.nanoTime();
            Site virtualSmall = evaluateVirtualSite(level, generator, randomState, virtualSurfaceCache, chunk, SMALL, smallBounds);
            candidates.add(new Candidate(chunk, virtualSmall));
            AfterTheEnd.LOGGER.info(
                    "[{}/{}] {}sec chunk=({}, {}) small={} city={}",
                    chunk.sampleIndex() + 1, sampledChunks.size(), formatSeconds(elapsedSeconds(candidateStarted)),
                    chunk.chunkX(), chunk.chunkZ(), format(virtualSmall.score()), cityId
            );
        }
        double targetDistance = preferredDistance(region, count);
        AfterTheEnd.LOGGER.info(
                "Altar planner {}: virtual-evaluated {} Small FPS candidates in {}sec without loading candidate chunks.",
                cityId, candidates.size(), formatSeconds(elapsedSeconds(started))
        );
        return new PreparationSession(
                level, List.copyOf(requests), seed, cityId, count, hasLarge, smallBounds, largeBounds,
                generator, randomState, virtualSurfaceCache, candidates, targetDistance
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
            }
            SelectionResult selection = session.selection;

            boolean invalidSmall = false;
            for (int candidateIndex : selection.selected()) {
                Candidate candidate = session.candidates.get(candidateIndex);
                if (!candidate.smallExactEvaluated) {
                    session.pendingExactCandidateIndex = candidateIndex;
                    session.pendingExactRole = ExactRole.SMALL;
                    return PreparationStep.candidate(candidateIndex, requiredChunks(session, candidateIndex));
                }
                if (candidate.usableSmall() == null) invalidSmall = true;
            }
            if (invalidSmall) {
                session.selection = null;
                continue;
            }

            int outlier = findSmallExactOutlier(selection, session.candidates);
            if (outlier >= 0) {
                Candidate candidate = session.candidates.get(outlier);
                candidate.smallReject = "exact terrain outlier";
                AfterTheEnd.LOGGER.info(
                        "Altar planner {}: rejected Small exact candidate sample={} chunk=({}, {}) as terrain-score outlier; re-optimizing.",
                        session.cityId, candidate.chunk.sampleIndex(), candidate.chunk.chunkX(), candidate.chunk.chunkZ()
                );
                session.selection = null;
                continue;
            }

            if (session.hasLarge) {
                for (int position = 0; position < selection.selected().length; position++) {
                    int candidateIndex = selection.selected()[position];
                    Candidate candidate = session.candidates.get(candidateIndex);
                    if (candidate.virtualLarge != null) continue;
                    long candidateStarted = System.nanoTime();
                    candidate.virtualLarge = evaluateVirtualSite(
                            session.level, session.generator, session.randomState, session.virtualSurfaceCache,
                            candidate.chunk, LARGE, session.largeBounds
                    );
                    AfterTheEnd.LOGGER.info(
                            "[{}/{}] {}sec chunk=({}, {}) large={} city={}",
                            position + 1, selection.selected().length, formatSeconds(elapsedSeconds(candidateStarted)),
                            candidate.chunk.chunkX(), candidate.chunk.chunkZ(), format(candidate.virtualLarge.score()), session.cityId
                    );
                }

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
                if (!largeCandidate.largeExactEvaluated) {
                    session.pendingExactCandidateIndex = selection.largeCandidateIndex();
                    session.pendingExactRole = ExactRole.LARGE;
                    return PreparationStep.candidate(selection.largeCandidateIndex(), requiredChunks(session, selection.largeCandidateIndex()));
                }
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
                    "Altar planner {}: FPS candidates={}, exact evaluations={}, structures={}, targetDistance={} blocks, objective={}",
                    session.cityId, session.candidates.size(), session.exactEvaluations, session.count,
                    format(session.targetDistance), format(selection.objective())
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

    static List<ChunkPos> requiredChunks(PreparationSession session, int candidateIndex) {
        Candidate candidate = session.candidates.get(candidateIndex);
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

    static List<ChunkPos> missingRequiredChunks(PreparationSession session, int candidateIndex) {
        List<ChunkPos> missing = new ArrayList<>();
        for (ChunkPos chunk : requiredChunks(session, candidateIndex)) {
            if (session.level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) missing.add(chunk);
        }
        return List.copyOf(missing);
    }

    static void exactEvaluateLoaded(PreparationSession session, int candidateIndex) {
        List<ChunkPos> missing = missingRequiredChunks(session, candidateIndex);
        if (!missing.isEmpty()) throw new IllegalStateException("Exact Altar evaluation requires loaded chunks: " + missing);
        if (session.pendingExactRole == null || session.pendingExactCandidateIndex != candidateIndex) {
            throw new IllegalStateException("Unexpected exact Altar evaluation request for candidate " + candidateIndex);
        }
        Candidate candidate = session.candidates.get(candidateIndex);
        long started = System.nanoTime();
        if (session.pendingExactRole == ExactRole.SMALL) {
            if (!candidate.smallExactEvaluated) {
                ActualSiteResult result = evaluateActualSite(session.level, candidate.chunk, SMALL, session.smallBounds);
                candidate.exactSmall = result.site();
                candidate.smallReject = exactRejectReason(candidate.virtualSmall, result, "small");
                candidate.smallExactEvaluated = true;
                session.exactEvaluations++;
                AfterTheEnd.LOGGER.info(
                        "Altar exact eval sample={} chunk=({}, {}) {}sec small={}{} city={}",
                        candidate.chunk.sampleIndex(), candidate.chunk.chunkX(), candidate.chunk.chunkZ(),
                        formatSeconds(elapsedSeconds(started)), candidate.exactSmall == null ? "null" : format(candidate.exactSmall.score()),
                        candidate.smallReject.isEmpty() ? "" : " reject=" + candidate.smallReject, session.cityId
                );
            }
        } else if (!candidate.largeExactEvaluated) {
            ActualSiteResult result = evaluateActualSite(session.level, candidate.chunk, LARGE, session.largeBounds);
            candidate.exactLarge = result.site();
            candidate.largeReject = exactRejectReason(candidate.virtualLarge, result, "large");
            candidate.largeExactEvaluated = true;
            session.exactEvaluations++;
            AfterTheEnd.LOGGER.info(
                    "Altar exact eval sample={} chunk=({}, {}) {}sec large={}{} city={}",
                    candidate.chunk.sampleIndex(), candidate.chunk.chunkX(), candidate.chunk.chunkZ(),
                    formatSeconds(elapsedSeconds(started)), candidate.exactLarge == null ? "null" : format(candidate.exactLarge.score()),
                    candidate.largeReject.isEmpty() ? "" : " reject=" + candidate.largeReject, session.cityId
            );
        }
        session.pendingExactRole = null;
        session.pendingExactCandidateIndex = -1;
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

    private static String exactRejectReason(Site virtualSite, ActualSiteResult actualResult, String role) {
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
        if (virtualSite != null) {
            double degradation = actual.score() - virtualSite.score();
            double limit = Math.max(
                    ACTUAL_DEGRADATION_MIN_LIMIT,
                    Math.max(0.0, virtualSite.score()) * ACTUAL_DEGRADATION_MULTIPLIER
            );
            if (degradation > limit) return role + " degraded by " + format(degradation) + " > " + format(limit);
        }
        return "";
    }

    private static int findSmallExactOutlier(SelectionResult selection, List<Candidate> candidates) {
        List<IndexedScore> scores = new ArrayList<>();
        for (int index : selection.selected()) {
            Candidate candidate = candidates.get(index);
            if (!candidate.smallExactEvaluated || candidate.exactSmall == null || !candidate.smallReject.isEmpty()) continue;
            scores.add(new IndexedScore(index, candidate.exactSmall.score()));
        }
        if (scores.size() < 3) return -1;
        double[] sorted = scores.stream().mapToDouble(IndexedScore::score).sorted().toArray();
        double median = sorted.length % 2 == 1
                ? sorted[sorted.length / 2]
                : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) * 0.5;
        double threshold = Math.max(median + OUTLIER_MIN_DELTA, median * OUTLIER_MULTIPLIER);
        IndexedScore worst = scores.stream().max(Comparator.comparingDouble(IndexedScore::score)).orElseThrow();
        return worst.score() > threshold ? worst.index() : -1;
    }

    private static Site evaluateVirtualSite(
            ServerLevel level,
            ChunkGenerator generator,
            RandomState randomState,
            Map<Long, SurfaceSample> cache,
            ChunkSeed chunk,
            TemplateSize size,
            SearchBounds bounds
    ) {
        List<Site> sites = evaluateTerrainSites(
                level, chunk, size, bounds,
                (x, z) -> cache.computeIfAbsent(
                        packXZ(x, z), ignored -> readVirtualSurfaceSample(level, generator, randomState, x, z)
                )
        );
        if (!sites.isEmpty()) return sites.getFirst();
        int x = clamp((chunk.chunkX() << 4) + 8, bounds.minCenterX(), bounds.maxCenterX());
        int z = clamp((chunk.chunkZ() << 4) + 8, bounds.minCenterZ(), bounds.maxCenterZ());
        SurfaceSample sample = readVirtualSurfaceSample(level, generator, randomState, x, z);
        int target = sample == null ? level.getSeaLevel() : sample.supportY();
        TerrainAssessment terrain = new TerrainAssessment(target, EFFECTIVE_REJECT_PENALTY * 10.0, 0.0, 0.0, 1.0, 0.0);
        return new Site(x, z, terrain.score(), terrain);
    }

    private static ActualSiteResult evaluateActualSite(ServerLevel level, ChunkSeed chunk, TemplateSize size, SearchBounds bounds) {
        List<Site> sites = evaluateTerrainSites(level, chunk, size, bounds, (x, z) -> readActualSurfaceSample(level, x, z));
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
            SurfaceReader reader
    ) {
        int chunkMinX = chunk.chunkX() << 4;
        int chunkMinZ = chunk.chunkZ() << 4;
        int minX = Math.max(chunkMinX, bounds.minCenterX());
        int maxX = Math.min(chunkMinX + 15, bounds.maxCenterX());
        int minZ = Math.max(chunkMinZ, bounds.minCenterZ());
        int maxZ = Math.min(chunkMinZ + 15, bounds.maxCenterZ());
        if (minX > maxX || minZ > maxZ) return List.of();
        FootprintStencil footprint = footprintStencil(
                minX - chunkMinX, maxX - chunkMinX, minZ - chunkMinZ, maxZ - chunkMinZ, size
        );
        SurfaceSample[] surfaceSamples = new SurfaceSample[footprint.sampleXs().length];
        for (int sampleIndex = 0; sampleIndex < surfaceSamples.length; sampleIndex++) {
            surfaceSamples[sampleIndex] = reader.read(
                    chunkMinX + footprint.sampleXs()[sampleIndex],
                    chunkMinZ + footprint.sampleZs()[sampleIndex]
            );
        }
        int[] supportYs = new int[FOOTPRINT_SAMPLE_COUNT];
        int[] fluidTopYs = new int[FOOTPRINT_SAMPLE_COUNT];
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

    private static SurfaceSample readVirtualSurfaceSample(
            ServerLevel level,
            ChunkGenerator generator,
            RandomState randomState,
            int x,
            int z
    ) {
        try {
            int worldSurface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
            int oceanFloor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
            int supportY = clamp(oceanFloor, level.getMinY() + 1, level.getMaxY());
            int fluidTopY = worldSurface > oceanFloor
                    ? clamp(worldSurface - 1, level.getMinY(), level.getMaxY())
                    : Integer.MIN_VALUE;
            return new SurfaceSample(supportY, fluidTopY);
        } catch (RuntimeException exception) {
            AfterTheEnd.LOGGER.debug("Virtual Altar terrain sample failed at ({}, {})", x, z, exception);
            return null;
        }
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
                if (!chunk.getAllReferences().isEmpty()) return true;
                for (StructureStart start : chunk.getAllStarts().values()) {
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
        List<Double> restartObjectives = new ArrayList<>();
        int[] deterministic = deterministicInitialization(candidates, count);
        SelectionResult deterministicResult = improveBySmallSwaps(deterministic, candidates, hasLarge, targetDistance);
        restartObjectives.add(deterministicResult.objective());
        if (Double.isFinite(deterministicResult.objective())) globalBest = deterministicResult;
        for (int restart = 1; restart < OPTIMIZER_RESTARTS; restart++) {
            int[] initial = randomFeasibleInitialization(candidates, count, random);
            if (initial == null) continue;
            SelectionResult local = improveBySmallSwaps(initial, candidates, hasLarge, targetDistance);
            restartObjectives.add(local.objective());
            if (Double.isFinite(local.objective()) && (globalBest == null || local.objective() < globalBest.objective())) globalBest = local;
        }
        if (globalBest == null) throw new IllegalStateException("No feasible Small Altar candidate combination remains after exact validation.");
        return new SelectionResult(globalBest.selected(), -1, globalBest.objective(), List.copyOf(restartObjectives));
    }

    private static int[] deterministicInitialization(List<Candidate> candidates, int count) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) if (candidates.get(i).usableSmall() != null) indices.add(i);
        indices.sort(Comparator.comparingDouble(index -> candidates.get(index).usableSmall().score()));
        if (indices.size() < count) throw new IllegalStateException("Not enough usable Small Altar candidates remain.");
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
        return new SelectionResult(Arrays.copyOf(current, current.length), -1, currentObjective, List.of());
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

    private static List<ChunkSeed> farthestPointSampleChunks(CityRegion region, long seed) {
        int width = region.widthChunks();
        int height = region.heightChunks();
        int total = width * height;
        int wanted = Math.min(FPS_CANDIDATE_COUNT, total);
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
            TemplateSize size
    ) {
        FootprintStencilKey key = new FootprintStencilKey(
                minLocalX, maxLocalX, minLocalZ, maxLocalZ, size.width()
        );
        return FOOTPRINT_STENCILS.computeIfAbsent(key, AltarPlacementPlanner::buildFootprintStencil);
    }

    private static FootprintStencil buildFootprintStencil(FootprintStencilKey key) {
        int[] xs = sampledAxis(key.minLocalX(), key.maxLocalX());
        int[] zs = sampledAxis(key.minLocalZ(), key.maxLocalZ());
        int totalCenters = xs.length * zs.length;
        int maximumSamples = totalCenters * FOOTPRINT_SAMPLE_COUNT;
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
                int centerSampleBase = centerIndex * FOOTPRINT_SAMPLE_COUNT;
                int centerSampleOffset = 0;
                for (int dxIndex = 0; dxIndex < 3; dxIndex++) {
                    int sampleX = originX + footprintSampleOffset(key.width(), dxIndex);
                    for (int dzIndex = 0; dzIndex < 3; dzIndex++) {
                        int sampleZ = originZ + footprintSampleOffset(key.width(), dzIndex);
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

    private static int footprintSampleOffset(int width, int index) {
        if (index == 0) return 0;
        if (index == 1) return width / 2;
        return width - 1;
    }

    private static TerrainAssessment assessTerrain(
            FootprintStencil footprint,
            SurfaceSample[] surfaceSamples,
            int centerIndex,
            int[] supportYs,
            int[] fluidTopYs
    ) {
        int sampleBase = centerIndex * FOOTPRINT_SAMPLE_COUNT;
        for (int sampleOffset = 0; sampleOffset < FOOTPRINT_SAMPLE_COUNT; sampleOffset++) {
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

    private static String formatSeconds(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
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
        private final double targetDistance;
        private int optimizationRound;
        private int exactEvaluations;
        private int pendingExactCandidateIndex = -1;
        private ExactRole pendingExactRole;
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
            this.targetDistance = targetDistance;
        }
    }

    record PreparationStep(boolean complete, int candidateIndex, List<ChunkPos> requiredChunks, List<Plan> plans) {
        private static PreparationStep candidate(int candidateIndex, List<ChunkPos> requiredChunks) {
            return new PreparationStep(false, candidateIndex, requiredChunks, List.of());
        }

        private static PreparationStep complete(List<Plan> plans) {
            return new PreparationStep(true, -1, List.of(), plans);
        }
    }

    private static final class Candidate {
        private final ChunkSeed chunk;
        private final Site virtualSmall;
        private Site virtualLarge;
        private boolean smallExactEvaluated;
        private boolean largeExactEvaluated;
        private Site exactSmall;
        private Site exactLarge;
        private String smallReject = "";
        private String largeReject = "";

        private Candidate(ChunkSeed chunk, Site virtualSmall) {
            this.chunk = chunk;
            this.virtualSmall = virtualSmall;
        }

        private Site usableSmall() {
            return smallExactEvaluated ? smallReject.isEmpty() ? exactSmall : null : virtualSmall;
        }

        private Site usableLarge() {
            if (virtualLarge == null) return null;
            return largeExactEvaluated ? largeReject.isEmpty() ? exactLarge : null : virtualLarge;
        }
    }

    private enum ExactRole { SMALL, LARGE }

    @FunctionalInterface
    private interface SurfaceReader {
        SurfaceSample read(int x, int z);
    }

    private record TemplateSize(int width, int height) { }
    private record ChunkSeed(int sampleIndex, int chunkX, int chunkZ) { }
    private record SurfaceSample(int supportY, int fluidTopY) { }
    private record FootprintStencilKey(int minLocalX, int maxLocalX, int minLocalZ, int maxLocalZ, int width) { }
    private record FootprintStencil(int[] centerXs, int[] centerZs, int[] sampleXs, int[] sampleZs, int[] sampleIndices) { }
    private record TerrainAssessment(int targetSurfaceY, double score, double roughness, double buriedFraction,
                                     double floatingFraction, double submergedFraction) { }
    private record Site(int centerX, int centerZ, double score, TerrainAssessment terrain) { }
    private record ActualSiteResult(Site site, String rejectReason) { }
    private record SearchBounds(int minCenterX, int maxCenterX, int minCenterZ, int maxCenterZ) { }
    private record AssignmentScore(double objective, int largeCandidateIndex) { }
    private record IndexedScore(int index, double score) { }
    private record SelectionResult(int[] selected, int largeCandidateIndex, double objective, List<Double> restartObjectives) { }
}
